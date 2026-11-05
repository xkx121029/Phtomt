package com.phoneagent.env

import android.content.Context
import android.util.Log
import com.phoneagent.env.EnvApi.STEP_FAIL
import com.phoneagent.env.EnvApi.STEP_OK
import com.phoneagent.env.EnvApi.STEP_START
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 工具链安装与构建编排。
 *
 * 分工：**重活都在 Kotlin 侧**（下载、解包），容器里只跑真正需要 Linux 的动作
 * （sdkmanager、Gradle 构建、apk 装 qemu）。这样即使容器起不来，下载/解包阶段的
 * 失败原因也能精确落到某一步，而不是一整段 shell 输出里猜。
 *
 * 安装产物全部落在 rootfs 的 /opt 下（跟着 Linux 环境走，卸载环境即全部清掉）：
 *   /opt/jdk17         JDK 17（Adoptium 官方 aarch64 构建）
 *   /opt/gradle        Gradle 发行版（版本按工程 gradle-wrapper.properties 自动识别）
 *   /opt/android-sdk   Android SDK（cmdline-tools + platform + build-tools）
 *   /opt/glibc         x86_64 glibc 运行库（Ubuntu base rootfs，给 aapt2 用）
 *   /opt/aapt2         aapt2 包装脚本（qemu-x86_64 + glibc，绕开 aapt2 只有 x86_64 的限制）
 *
 * aapt2 是设备端构建的硬伤：Google 只发 x86_64 版二进制，arm64 设备必须经 qemu 转译。
 * 这一步失败不影响其它步骤，构建阶段的自检会把缺的东西逐条列出来。
 */
class ToolchainInstaller(private val ctx: Context, private val env: LinuxEnv) {

    private companion object {
        const val OPT = "/opt"
        const val JDK = "$OPT/jdk17"
        const val GRADLE = "$OPT/gradle"
        const val SDK = "$OPT/android-sdk"
        const val GLIBC = "$OPT/glibc"
        const val AAPT2 = "$OPT/aapt2"

        /** 下载归档的本地缓存目录（宿主侧，不进 rootfs） */
        const val DOWNLOAD_DIR = "downloads"

        private const val MIN_ARCHIVE_BYTES = 1_000_000L
        private const val DOWNLOAD_CHUNK = 256 * 1024
        private const val LOG_LIMIT = 200_000

        /** Adoptium 官方 JDK 17（aarch64），返回 tar.gz */
        private val JDK_URLS = listOf(
            "https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/" +
                "hotspot/normal/eclipse",
        )

        /** Gradle 发行版（版本号占位替换） */
        private fun gradleUrl(version: String) =
            "https://services.gradle.org/distributions/gradle-$version-bin.zip"

        /** Android cmdline-tools（sdkmanager 是 Java 程序，跨架构可用） */
        private val CMDLINE_TOOLS_URLS = listOf(
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip",
            "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip",
        )

        /**
         * x86_64 glibc 运行库：直接用 Ubuntu base 的 amd64 rootfs。
         * 只取 lib 目录当 qemu 的 -L sysroot，比逐个挑 deb 包稳。
         */
        private val GLIBC_URLS = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
                "ubuntu-base-24.04.2-base-amd64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
                "ubuntu-base-24.04.1-base-amd64.tar.gz",
        )

        private const val SDK_TIMEOUT_MS = 30 * 60_000L
        private const val BUILD_TIMEOUT_MS = 45 * 60_000L
    }

    /** 进度与日志出口，由 [EnvService] 转发给 AIDL 回调 */
    interface Listener {
        fun onStep(index: Int, total: Int, title: String, state: String)
        fun onLog(line: String)
    }

    data class Request(
        val op: String,
        val project: String,
        val gradleVersion: String,
        val agpVersion: String,
        val compileSdk: Int,
        val buildTools: String,
        val task: String,
    ) {
        companion object {
            fun fromJson(raw: String): Request {
                val json = JSONObject(raw)
                return Request(
                    op = json.optString(EnvApi.KEY_OP, EnvApi.OP_INSTALL),
                    project = json.optString(EnvApi.KEY_PROJECT),
                    gradleVersion = json.optString(EnvApi.KEY_GRADLE),
                    agpVersion = json.optString(EnvApi.KEY_AGP),
                    compileSdk = json.optInt(EnvApi.KEY_COMPILE_SDK, 36),
                    buildTools = json.optString(EnvApi.KEY_BUILD_TOOLS, "36.0.0"),
                    task = json.optString(EnvApi.KEY_TASK, "assembleDebug"),
                )
            }
        }
    }

    data class Result(val ok: Boolean, val message: String)

    private var listener: Listener = object : Listener {
        override fun onStep(index: Int, total: Int, title: String, state: String) = Unit
        override fun onLog(line: String) = Unit
    }

    private var cancelled: () -> Boolean = { false }

    /** 工具链现状（stateJson 的内容来源） */
    fun state(): JSONObject = JSONObject().apply {
        put(EnvApi.STATE_LINUX, if (env.isReady()) "就绪" else "未装配")
        put(EnvApi.STATE_JDK, if (env.guestExists("$JDK/bin/java")) "JDK 17" else "未安装")
        put(EnvApi.STATE_GRADLE, gradleVersionInstalled() ?: "未安装")
        put(EnvApi.STATE_SDK, platformsInstalled())
        put(EnvApi.STATE_AAPT2, if (env.guestExists(AAPT2)) "已配置" else "未配置")
        put(EnvApi.STATE_INSTALLED_AT, installedAt())
    }

    /** 执行一次请求；[isCancelled] 每步之间与下载循环里都会检查。 */
    fun execute(request: Request, listener: Listener, isCancelled: () -> Boolean): Result {
        this.listener = listener
        this.cancelled = isCancelled
        val steps = if (request.op == EnvApi.OP_BUILD) buildSteps(request) else installSteps(request)
        steps.forEachIndexed { index, step ->
            if (cancelled.invoke()) return Result(false, "已取消")
            listener.onStep(index, steps.size, step.title, STEP_START)
            val failure = runCatching { step.block() }
                .getOrElse { t -> "异常：${t.javaClass.simpleName} ${t.message}" }
            if (failure != null) {
                listener.onStep(index, steps.size, step.title, STEP_FAIL)
                return Result(false, "${step.title}失败：$failure")
            }
            listener.onStep(index, steps.size, step.title, STEP_OK)
        }
        if (request.op != EnvApi.OP_BUILD) {
            env.writeGuestFile("$OPT/.installed-at", System.currentTimeMillis().toString(), LinuxEnv.MODE_FILE)
        }
        return Result(true, if (request.op == EnvApi.OP_BUILD) "构建完成" else "环境安装完成")
    }

    // ------------------------------------------------------------ 步骤定义

    private data class Step(val title: String, val block: () -> String?)

    private fun installSteps(req: Request): List<Step> = listOf(
        Step("Linux 根文件系统") { if (ensureLinux()) null else "Linux 环境装配失败（详见日志）" },
        Step("JDK 17") { installJdk() },
        Step("Gradle ${req.gradleVersion.ifBlank { "（未识别）" }}") { installGradle(req.gradleVersion) },
        Step("Android SDK 工具") { installCmdlineTools() },
        Step("Android 平台与 Build Tools") { installSdkPackages(req) },
        Step("aapt2 兼容层") { installAapt2(req) },
        Step("工程构建配置") { writeProjectConfig(req) },
    )

    private fun buildSteps(req: Request): List<Step> = listOf(
        Step("环境自检") { checkToolchains() },
        Step("构建 ${req.task}") { runBuild(req) },
    )

    // ------------------------------------------------------------ Linux 环境

    private fun ensureLinux(): Boolean = env.ensureReady(
        onProgress = { log(it) },
        onLog = { log(it) },
        isCancelled = { cancelled.invoke() },
    )

    // ------------------------------------------------------------ JDK

    private fun installJdk(): String? {
        if (env.guestExists("$JDK/bin/java")) {
            log("JDK 17 已安装，跳过")
            return null
        }
        val archive = download(JDK_URLS, "jdk17-aarch64.tar.gz", "JDK 17") ?: return "下载失败（网络不可达？）"
        log("解压 JDK 17…")
        return extractFlatten(archive, JDK) { source, dest -> env.extractArchive(source, dest) }
    }

    // ------------------------------------------------------------ Gradle

    private fun installGradle(version: String): String? {
        val wanted = version.trim().ifBlank { return "工程未声明 Gradle 版本" }
        if (gradleVersionInstalled() == wanted && env.guestExists("$GRADLE/bin/gradle")) {
            log("Gradle $wanted 已安装，跳过")
            return null
        }
        val archive = download(listOf(gradleUrl(wanted)), "gradle-$wanted-bin.zip", "Gradle $wanted")
            ?: return "下载失败（版本号 $wanted 是否正确？）"
        log("解压 Gradle $wanted…")
        val failure = extractFlatten(archive, GRADLE) { source, dest -> unzipOrThrow(source, dest) }
        if (failure == null) env.writeGuestFile("$OPT/.gradle-version", wanted, LinuxEnv.MODE_FILE)
        return failure
    }

    /** 读安装记录里记下的 Gradle 版本；没装过返回 null。 */
    private fun gradleVersionInstalled(): String? =
        runCatching { env.guestFile("$OPT/.gradle-version").readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------ Android SDK

    private fun installCmdlineTools(): String? {
        if (env.guestExists("$SDK/cmdline-tools/latest/bin/sdkmanager")) {
            log("cmdline-tools 已安装，跳过")
            return null
        }
        val archive = download(CMDLINE_TOOLS_URLS, "commandlinetools-linux.zip", "Android cmdline-tools")
            ?: return "下载失败（dl.google.com 不可达？）"
        log("解压 cmdline-tools…")
        return extractFlatten(archive, "$SDK/cmdline-tools/latest") { source, dest ->
            unzipOrThrow(source, dest)
        }
    }

    private fun installSdkPackages(req: Request): String? {
        val platform = "$SDK/platforms/android-${req.compileSdk}"
        val buildTools = "$SDK/build-tools/${req.buildTools}"
        if (env.guestExists(platform) && env.guestExists(buildTools)) {
            log("platform android-${req.compileSdk} 与 build-tools ${req.buildTools} 已安装，跳过")
            return null
        }
        val packages = listOf(
            "platform-tools",
            "platforms;android-${req.compileSdk}",
            "build-tools;${req.buildTools}",
        ).joinToString(" ") { "\"$it\"" }
        val script = "yes | $SDK/cmdline-tools/latest/bin/sdkmanager " +
            "--sdk_root=$SDK --install $packages"
        log("sdkmanager 安装：$packages")
        val result = env.exec(
            guestArgv = listOf("/bin/sh", "-c", script),
            timeoutMs = SDK_TIMEOUT_MS,
            extraEnv = javaEnv(),
            onLine = { if (it.isNotBlank()) log(it) },
        )
        return if (result.ok) null else "sdkmanager 退出码 ${result.code}（日志末行见上）"
    }

    // ------------------------------------------------------------ aapt2

    private fun installAapt2(req: Request): String? {
        if (env.guestExists(AAPT2)) {
            log("aapt2 兼容层已配置，跳过")
            return null
        }
        log("安装 qemu-x86_64（arm64 设备跑 x86_64 版 aapt2 的唯一途径）…")
        val add = env.exec(
            guestArgv = listOf("/bin/sh", "-c", "apk add --no-cache qemu-x86_64"),
            timeoutMs = 15 * 60_000,
            onLine = { if (it.isNotBlank()) log(it) },
        )
        if (!add.ok) return "qemu-x86_64 安装失败：${add.output.trim().takeLast(500)}"

        if (!env.guestExists("$GLIBC/lib/x86_64-linux-gnu")) {
            val archive = download(GLIBC_URLS, "ubuntu-base-amd64.tar.gz", "x86_64 glibc 运行库")
                ?: return "glibc 运行库下载失败"
            log("解压 x86_64 glibc 运行库…")
            val failure = runCatching { env.extractArchive(archive, env.guestFile(GLIBC)) }
                .exceptionOrNull()
            if (failure != null) return "glibc 解压失败：${failure.message}"
        }

        // 包装脚本：固定指向本次安装的 build-tools 里那份 aapt2
        val script = buildString {
            append("#!/bin/sh\n")
            append("exec /usr/bin/qemu-x86_64 -L $GLIBC ")
            append("$SDK/build-tools/${req.buildTools}/aapt2 \"$@\"\n")
        }
        env.writeGuestFile(AAPT2, script, LinuxEnv.MODE_EXEC)
        log("aapt2 已包装为 $AAPT2（qemu-x86_64 + glibc sysroot）")
        return null
    }

    // ------------------------------------------------------------ 工程配置

    /**
     * 写 local.properties / gradle.properties。
     *
     * 只补缺失的键，不覆盖用户已有配置：工程里自定义的 jvmargs / 签名配置必须留着。
     */
    private fun writeProjectConfig(req: Request): String? {
        val project = File(req.project)
        if (!project.isDirectory) return "工程目录不存在：${req.project}"
        if (!project.canRead() || !project.canWrite()) {
            return "环境组件无权读写该目录（请把工作区放到共享存储）"
        }

        mergeProperties(
            File(project, "local.properties"),
            listOf("sdk.dir" to SDK),
        )
        mergeProperties(
            File(project, "gradle.properties"),
            listOf(
                "org.gradle.jvmargs" to "-Xmx1536m -Dfile.encoding=UTF-8",
                "org.gradle.daemon" to "false",
                // 告诉 AGP 别用 Maven 里那份 x86_64 aapt2，换成 qemu 包装脚本
                "android.aapt2FromMavenOverride" to AAPT2,
            ),
        )
        log("已写入 local.properties（sdk.dir）与 gradle.properties（aapt2FromMavenOverride）")
        return null
    }

    private fun mergeProperties(file: File, entries: List<Pair<String, String>>) {
        val existing = if (file.isFile) file.readText() else ""
        val lines = existing.lines().toMutableList()
        for ((key, value) in entries) {
            val present = lines.any { it.trimStart().startsWith("$key=") || it.trim() == key }
            if (!present) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
                lines += "$key=$value"
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n").trimEnd() + "\n")
    }

    // ------------------------------------------------------------ 构建

    private fun checkToolchains(): String? {
        val missing = buildList {
            if (!env.isReady()) add("Linux 环境")
            if (!env.guestExists("$JDK/bin/java")) add("JDK 17")
            if (!env.guestExists("$GRADLE/bin/gradle")) add("Gradle")
            if (!env.guestExists("$SDK/platforms")) add("Android SDK")
        }
        return if (missing.isEmpty()) null else "缺少 ${missing.joinToString("、")}，请先一键安装"
    }

    private fun runBuild(req: Request): String? {
        val project = File(req.project)
        if (!project.isDirectory) return "工程目录不存在：${req.project}"
        val hasWrapper = File(project, "gradlew").isFile
        val argv = if (hasWrapper) {
            listOf("/bin/sh", "gradlew", req.task)
        } else {
            listOf("/bin/sh", "$GRADLE/bin/gradle", "-p", req.project, req.task)
        }
        log("执行：${req.task}（${if (hasWrapper) "工程自带 gradlew" else "使用内置 Gradle"}）")
        log("工程目录：${req.project}")
        val result = env.exec(
            guestArgv = argv,
            timeoutMs = BUILD_TIMEOUT_MS,
            extraEnv = javaEnv() + mapOf(
                "ANDROID_HOME" to SDK,
                "ANDROID_SDK_ROOT" to SDK,
                "GRADLE_USER_HOME" to "/root/.gradle",
            ),
            workDir = req.project,
            onLine = { if (it.isNotBlank()) log(it) },
        )
        if (!result.ok) {
            return "构建失败（退出码 ${result.code}）。APK 产物通常位于 " +
                "build/outputs/apk/<variant>/，失败原因见上方 Gradle 日志"
        }
        log("构建成功，产物在 ${req.project}/build/outputs/apk/")
        return null
    }

    private fun javaEnv(): Map<String, String> = mapOf(
        "JAVA_HOME" to JDK,
        "PATH" to "$JDK/bin:$GRADLE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    // ------------------------------------------------------------ 下载 / 解包

    /**
     * 依次尝试 [urls]，成功返回落盘文件；全部失败返回 null。
     * 已有同名缓存（体积可信）时直接复用，避免重复下 100 MB 级归档。
     */
    private fun download(urls: List<String>, fileName: String, label: String): File? {
        val dir = File(ctx.filesDir, DOWNLOAD_DIR).apply { mkdirs() }
        val target = File(dir, fileName)
        if (target.isFile && target.length() >= MIN_ARCHIVE_BYTES) {
            log("$label：复用本地缓存（${target.length() / 1024 / 1024} MB）")
            return target
        }
        for (url in urls) {
            if (cancelled.invoke()) return null
            log("$label：下载 $url")
            val temp = File(dir, "$fileName.part").apply { delete() }
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "happy-phone-env/1.0")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(temp).use { out ->
                        val buffer = ByteArray(DOWNLOAD_CHUNK)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            if (cancelled.invoke()) {
                                temp.delete()
                                return null
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            if (copied - lastReport >= (1 shl 20)) {
                                lastReport = copied
                                val text = if (total > 0) {
                                    "${copied / 1024 / 1024} / ${total / 1024 / 1024} MB"
                                } else {
                                    "${copied / 1024 / 1024} MB"
                                }
                                log("$label 已下载 $text")
                            }
                        }
                    }
                }
                conn.disconnect()
                if (temp.length() < MIN_ARCHIVE_BYTES) throw IOException("下载内容不完整")
                target.delete()
                if (!temp.renameTo(target)) throw IOException("归档无法就位")
                return target
            } catch (t: Throwable) {
                log("$label 下载失败（${t.javaClass.simpleName}）：${t.message}")
                temp.delete()
            }
        }
        return null
    }

    /** 把归档解开并抹掉顶层单目录（JDK / Gradle / cmdline-tools 都是这种布局）。 */
    private fun extractFlatten(
        source: File,
        guestDest: String,
        extract: (File, File) -> Unit,
    ): String? {
        val dest = env.guestFile(guestDest)
        val staging = env.guestFile("$guestDest.staging")
        env.deleteRecursive(staging)
        env.deleteRecursive(dest)
        staging.mkdirs()
        val failed = runCatching { extract(source, staging) }.exceptionOrNull()
        if (failed != null) {
            env.deleteRecursive(staging)
            return "解压失败：${failed.message}"
        }
        val children = staging.listFiles()?.sortedBy { it.name }.orEmpty()
        val onlyDir = children.singleOrNull()?.takeIf { it.isDirectory }
        if (onlyDir != null) {
            if (!onlyDir.renameTo(dest)) {
                env.deleteRecursive(staging)
                return "目录无法就位"
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                if (!child.renameTo(File(dest, child.name))) {
                    env.deleteRecursive(staging)
                    return "目录无法就位"
                }
            }
        }
        env.deleteRecursive(staging)
        return null
    }

    private fun unzipOrThrow(zip: File, dest: File) {
        if (!unzip(zip, dest)) throw IOException("zip 解包失败：${zip.name}")
    }

    private fun unzip(zip: File, dest: File): Boolean = try {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/').replace('\\', '/')
                if (name.isNotEmpty() && !name.contains("..")) {
                    val out = File(dest, name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { stream -> input.copyTo(stream, DOWNLOAD_CHUNK) }
                    }
                }
                entry = input.nextEntry
            }
        }
        true
    } catch (t: Throwable) {
        Log.w("ToolchainInstaller", "解压 ${zip.name} 失败", t)
        false
    }

    private fun platformsInstalled(): String {
        val dir = env.guestFile("$SDK/platforms")
        val names = dir.list()?.filter { it.startsWith("android-") }.orEmpty()
        return if (names.isEmpty()) "未安装" else names.sorted().joinToString("、")
    }

    private fun installedAt(): String =
        runCatching { env.guestFile("$OPT/.installed-at").readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "从未安装"

    private fun log(line: String) {
        if (line.length > LOG_LIMIT) return
        listener.onLog(line)
    }
}
