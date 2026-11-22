package com.phoneagent.env

import android.content.Context
import android.util.Log
import com.phoneagent.env.EnvApi.STEP_FAIL
import com.phoneagent.env.EnvApi.STEP_OK
import com.phoneagent.env.EnvApi.STEP_START
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 工具链安装与构建编排。
 *
 * 分工：**重活都在 Kotlin 侧**（下载、解包），容器里只跑真正需要 Linux 的动作
 * （sdkmanager、Gradle 构建、apk 装 qemu）。这样即使容器起不来，下载/解包阶段的
 * 失败原因也能精确落到某一步，而不是一整段 shell 输出里猜。
 *
 * 安装产物全部落在 rootfs 的 /opt 下（跟着 Linux 环境走，卸载环境即全部清掉）：
 *   /opt/jdkN          JDK（Adoptium 官方 aarch64 构建；N 由工程声明与 AGP 最低要求推导）
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
        const val GRADLE = "$OPT/gradle"
        const val SDK = "$OPT/android-sdk"
        const val GLIBC = "$OPT/glibc"
        const val AAPT2 = "$OPT/aapt2"

        private const val MIN_ARCHIVE_BYTES = 1_000_000L
        private const val DOWNLOAD_CHUNK = 256 * 1024
        private const val LOG_LIMIT = 200_000

        /**
         * 默认 JDK 大版本：工程文件推导不出来时的保守取值。
         * AGP 8/9 的最低要求就是 JDK 17，本仓库 app/build.gradle.kts 也显式声明 VERSION_17。
         */
        const val DEFAULT_JDK = 17

        /**
         * Adoptium 发布接口（aarch64），版本号按工程要求替换。
         *
         * 它在同一份 JSON 里给出真实下载链接与官方 sha256。JDK 的构建号每个月都在变，
         * 把文件名钉死再去猜 sidecar 并不可靠；而 `binary/latest/...` 那种重定向本身不带摘要，
         * 没法验证下到的到底是不是 Adoptium 发的那一份。
         */
        private fun adoptiumAssets(version: Int) =
            "https://api.adoptium.net/v3/assets/latest/$version/hotspot" +
                "?architecture=aarch64&os=linux&image_type=jdk&vendor=eclipse"

        /** 容器内的 JDK 安装目录 */
        fun jdkDir(version: Int) = "$OPT/jdk$version"

        /** /opt/jdk17 这类目录名 */
        private val JDK_DIR = Regex("""jdk(\d+)""")

        /** `openjdk version "17.0.16" 2025-xx-xx` */
        private val JDK_VERSION_LINE = Regex("""version "([^"]+)"""")

        /** `Gradle 8.13` */
        private val GRADLE_VERSION_LINE = Regex("""(?m)^Gradle (\S+)$""")

        /** `platforms;android-36` */
        private val PLATFORM_PKG = Regex("""platforms;android-(\d+)""")

        /** `build-tools;36.0.0` */
        private val BUILD_TOOLS_PKG = Regex("""build-tools;(\d+(?:\.\d+)+)""")

        /** `Android Asset Packaging Tool (aapt) 2.19-...` */
        private val AAPT2_VERSION_LINE = Regex("""aapt\)?\s+([\d][\w.\-]*)""")

        private const val PROBE_TIMEOUT_MS = 60_000L
        private const val SDK_PROBE_TIMEOUT_MS = 180_000L

        /** 就绪实测结果的缓存时长：探针要起几个 JVM，不能每次读状态都重跑 */
        private const val STATE_TTL_MS = 5 * 60_000L

        /** Gradle 发行版（版本号占位替换） */
        private fun gradleUrl(version: String) =
            "https://services.gradle.org/distributions/gradle-$version-bin.zip"

        /**
         * Android cmdline-tools（sdkmanager 是 Java 程序，跨架构可用）。
         *
         * Google 不为单个 zip 提供 sidecar，官方摘要写在 repository2-3.xml 的
         * `<archive><complete><checksum type="sha1">` 里，下面钉的就是那个值。
         *
         * **只能是 SHA-1**：这份清单本身就只有 SHA-1。它弱于 SHA-256 的地方是碰撞成本——
         * SHA-1 的实用碰撞攻击已有公开实例，能防传输损坏与镜像替换，防不住有算力的攻击者
         * 构造一份同摘要的恶意包。要升级得等 Google 在清单里给出 SHA-256。
         */
        private const val CMDLINE_TOOLS_SHA1 = "d313adb7aedccf6cf0cfca51ec180f0059f5f8f8"

        private val CMDLINE_TOOLS_URLS = listOf(
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip",
            "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip",
        )

        /**
         * x86_64 glibc 运行库：直接用 Ubuntu base 的 amd64 rootfs。
         * 只取 lib 目录当 qemu 的 -L sysroot，比逐个挑 deb 包稳。
         *
         * 2026-11-21 实测：原先钉的 24.04.2 与 24.04.1 在官方目录里**已全部 404**
         * （官方 SHA256SUMS 只剩 24.04.3/4/5），aapt2 兼容层这一步因此必然失败。
         * 现在钉版本号 + 官方公布的 sha256，并保留两个入口：版本化目录为主，浮动目录兜底。
         */
        private const val GLIBC_FILE = "ubuntu-base-24.04.5-base-amd64.tar.gz"

        /** 来自 https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS */
        private const val GLIBC_SHA256 =
            "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86"

        private val GLIBC_URLS = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.5/release/$GLIBC_FILE",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/$GLIBC_FILE",
        )

        private const val SDK_TIMEOUT_MS = 30 * 60_000L
        private const val BUILD_TIMEOUT_MS = 45 * 60_000L

        /** 占用统计的缓存时长：遍历几万个文件不该跟着页面每次刷新走 */
        private const val DISK_CACHE_MS = 30_000L
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
        /** 要装的 JDK 大版本，由主应用按工程声明 + AGP 最低要求推导后传进来 */
        val jdkVersion: Int,
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
                    jdkVersion = json.optInt(EnvApi.KEY_JDK, DEFAULT_JDK),
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

    /** 最近一次实测的快照；探针要起几个 JVM，不能每次读状态都重跑 */
    private data class Snapshot(val at: Long, val json: JSONObject)

    @Volatile
    private var snapshot: Snapshot? = null

    /** (统计时刻, 占用, 可清理)，见 [diskUsage] */
    private var diskUsageCache: Triple<Long, Long, Long>? = null

    /**
     * 工具链现状（stateJson 的内容来源）。
     *
     * 判据是"真跑一次"：`java -version` / `gradle --version` / `sdkmanager --list_installed`
     * / `aapt2 version` 全部落在这个方法背后。文件存在只能说明装过，装过的二进制能不能在本机
     * 跑起来是另一件事（proot 绑定、qemu 转译、glibc sysroot 任一环失效都会在构建时才炸）。
     * 结果缓存 [STATE_TTL_MS]；安装与构建结束时清掉缓存，下次读状态重新实测。
     *
     * [taskRunning] 为 true 时不做实测：此时容器和 Gradle 用户目录正被安装/构建占着，
     * 探针抢不到锁只会给出骗人的"跑不通"。任务结束后缓存被清，下一次读就是真实结果。
     */
    fun state(taskRunning: Boolean = false): JSONObject {
        val cached = snapshot
        if (cached != null && System.currentTimeMillis() - cached.at < STATE_TTL_MS) return cached.json
        if (taskRunning) {
            return cached?.json ?: JSONObject().apply {
                put(EnvApi.STATE_LINUX, if (env.isReady()) "就绪" else "未装配")
                put(EnvApi.STATE_PROBLEMS, "任务执行中：工具链状态会在任务结束后重新实测")
            }
        }
        val fresh = probeState()
        snapshot = Snapshot(System.currentTimeMillis(), fresh)
        return fresh
    }

    /**
     * 占用与可清理量。
     *
     * 统计要遍历 rootfs 与 /opt 下几万个文件，页面每次刷新都走一遍没有意义，
     * 30 秒内复用上一次结果；清理/重置之后立刻作废缓存。
     */
    private fun diskUsage(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        diskUsageCache?.let { cached ->
            if (now - cached.first < DISK_CACHE_MS) return cached.second to cached.third
        }
        val used = env.usedBytes()
        val reclaimable = env.reclaimableBytes()
        diskUsageCache = Triple(now, used, reclaimable)
        return used to reclaimable

    }

    /** 执行一次请求；[isCancelled] 每步之间与下载循环里都会检查。 */
    fun execute(request: Request, listener: Listener, isCancelled: () -> Boolean): Result {
        this.listener = listener
        this.cancelled = isCancelled
        val steps = when (request.op) {
            EnvApi.OP_BUILD -> buildSteps(request)
            EnvApi.OP_CLEAN -> cleanSteps()
            EnvApi.OP_RESET -> resetSteps()
            EnvApi.OP_INSTALL -> installSteps(request)
            // 不认识的操作必须拒绝：旧组件收到新 op 时若按 install 处理，会静默开装
            else -> return Result(false, "未知操作：${request.op}")
        }
        try {
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
            // 只有 install 会真正改变「装了没装」；清理与重置不刷新安装时间
            if (request.op == EnvApi.OP_INSTALL) {
                env.writeGuestFile("$OPT/.installed-at", System.currentTimeMillis().toString(), LinuxEnv.MODE_FILE)
            }
            return Result(
                ok = true,
                message = when (request.op) {
                    EnvApi.OP_BUILD -> "构建完成"
                    EnvApi.OP_CLEAN -> "已清理临时产物"
                    EnvApi.OP_RESET -> "编码环境已重置"
                    else -> "环境安装完成"
                },
            )
        } finally {
            // 任务改了工具链现状：下次读状态必须重新实测，不能拿旧快照
            snapshot = null
        }
    }

    // ------------------------------------------------------------ 步骤定义

    private data class Step(val title: String, val block: () -> String?)

    private fun installSteps(req: Request): List<Step> = listOf(
        Step("Linux 根文件系统") { ensureLinux() },
        Step("JDK ${req.jdkVersion}") { installJdk(req) },
        Step("Gradle ${req.gradleVersion.ifBlank { "（未识别）" }}") { installGradle(req.gradleVersion) },
        Step("Android SDK 工具") { installCmdlineTools() },
        Step("Android 平台与 Build Tools") { installSdkPackages(req) },
        Step("aapt2 兼容层") { installAapt2(req) },
        Step("工程构建配置") { writeProjectConfig(req) },
        Step("工具链自检") { verifyToolchain() },
    )

    private fun buildSteps(req: Request): List<Step> = listOf(
        Step("环境自检") { checkToolchains(req) },
        Step("构建 ${req.task}") { runBuild(req) },
    )

    private fun cleanSteps(): List<Step> = listOf(
        Step("清理临时产物") { cleanCaches() },
    )

    private fun resetSteps(): List<Step> = listOf(
        Step("重置编码环境") { resetEnvironment() },
    )

    // ------------------------------------------------------------ Linux 环境

    private fun ensureLinux(): String? = when (
        val result = env.ensureReady(
            onProgress = { log(it) },
            onLog = { log(it) },
            isCancelled = { cancelled.invoke() },
        )
    ) {
        LinuxEnv.Prepare.Ready -> null
        is LinuxEnv.Prepare.Busy -> result.message
        is LinuxEnv.Prepare.Failed -> "Linux 环境装配失败：${result.message}"
    }

    // ------------------------------------------------------------ JDK

    private fun installJdk(req: Request): String? {
        val dir = jdkDir(req.jdkVersion)
        if (env.guestExists("$dir/bin/java")) {
            log("JDK ${req.jdkVersion} 已安装，跳过")
            return null
        }
        val artifact = jdkArtifact(req.jdkVersion)
            ?: return "拿不到 Adoptium 的官方下载信息（api.adoptium.net 不可达或格式变了），拒绝安装未校验的 JDK"
        val archive = download(artifact) ?: return "JDK ${req.jdkVersion} 下载失败（见上一条日志）"
        log("解压 JDK ${req.jdkVersion}…")
        return extractFlatten(archive, dir) { source, dest -> env.extractArchive(source, dest) }
    }

    /** 从 Adoptium 发布 JSON 里取「真实链接 + 官方 sha256」；缺任何一项就返回 null */
    private fun jdkArtifact(version: Int): Artifact? {
        val raw = Fetch.text(adoptiumAssets(version)) ?: run {
            log("JDK $version：api.adoptium.net 取不到发布信息")
            return null
        }
        val pkg = runCatching {
            JSONArray(raw).getJSONObject(0).getJSONObject("binary").getJSONObject("package")
        }.getOrNull() ?: run {
            log("JDK $version：Adoptium 返回的 JSON 里没有 binary.package")
            return null
        }
        val link = pkg.optString("link")
        val checksum = pkg.optString("checksum")
        if (link.isEmpty() || checksum.isEmpty()) {
            log("JDK $version：Adoptium 发布信息缺少下载链接或摘要")
            return null
        }
        return Artifact(
            label = "JDK $version",
            fileName = "jdk$version-aarch64.tar.gz",
            urls = listOf(link),
            expected = Expected.Fixed(SHA256, checksum),
            // 真实产物约 190 MB，低于 50 MB 必然不是 JDK
            minBytes = 50L * 1024 * 1024,
        )
    }

    // ------------------------------------------------------------ Gradle

    private fun installGradle(version: String): String? {
        val wanted = version.trim().ifBlank { return "工程未声明 Gradle 版本" }
        if (gradleVersionInstalled() == wanted && env.guestExists("$GRADLE/bin/gradle")) {
            log("Gradle $wanted 已安装，跳过")
            return null
        }
        val url = gradleUrl(wanted)
        val archive = download(
            Artifact(
                label = "Gradle $wanted",
                fileName = "gradle-$wanted-bin.zip",
                urls = listOf(url),
                // Gradle 版本是运行时从工程读的，无法预钉摘要，只能读官方 sidecar
                expected = Expected.Sidecar(SHA256, "$url.sha256"),
            ),
        ) ?: return "Gradle $wanted 下载失败（版本号 $wanted 是否正确？见上一条日志）"
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
        val archive = download(
            Artifact(
                label = "Android cmdline-tools",
                fileName = "commandlinetools-linux.zip",
                urls = CMDLINE_TOOLS_URLS,
                expected = Expected.Fixed(SHA1, CMDLINE_TOOLS_SHA1),
                minBytes = 100L * 1024 * 1024,
            ),
        ) ?: return "cmdline-tools 下载失败（dl.google.com 不可达，或包已被 Google 替换，见日志）"
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
            extraEnv = javaEnv(req.jdkVersion),
            onLine = { if (it.isNotBlank()) log(it) },
        )
        if (!result.ok) return "sdkmanager 退出码 ${result.code}（日志末行见上）"

        // sdkmanager 由它自己按 repository2-3.xml 里的 checksum 校验归档，我们拿不到独立摘要，
        // 所以这里补一道结构断言：报成功但关键文件不在，等同于装了个坏包。
        val missing = buildList {
            if (!env.guestExists("$platform/android.jar")) add("platforms/android-${req.compileSdk}/android.jar")
            if (!env.guestExists("$buildTools/aapt2")) add("build-tools/${req.buildTools}/aapt2")
            if (!env.guestExists("$buildTools/d8")) add("build-tools/${req.buildTools}/d8")
        }
        return if (missing.isEmpty()) null else "sdkmanager 报成功但产物缺失：${missing.joinToString("、")}"
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
            val archive = download(
                Artifact(
                    label = "x86_64 glibc 运行库",
                    fileName = GLIBC_FILE,
                    urls = GLIBC_URLS,
                    expected = Expected.Fixed(SHA256, GLIBC_SHA256),
                ),
            ) ?: return "glibc 运行库下载失败（官方源可能已下架 $GLIBC_FILE，见上一条日志）"
            log("解压 x86_64 glibc 运行库…")
            val staging = env.guestFile("$GLIBC.staging")
            env.deleteRecursive(staging)
            staging.mkdirs()
            val unpacked = runCatching { env.extractArchive(archive, staging) }.exceptionOrNull()
            if (unpacked != null) {
                env.deleteRecursive(staging)
                return "glibc 解压失败：${unpacked.message}"
            }
            val moved = InstallSwap.replace(staging, env.guestFile(GLIBC))
            if (moved != null) {
                env.deleteRecursive(staging)
                return "glibc 无法就位：$moved"
            }
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
     * 写工程侧的构建配置。
     *
     * 只补 `local.properties` 的 `sdk.dir`，不覆盖用户已有的值：工程里自定义的签名与依赖
     * 配置必须留着。
     *
     * **`aapt2FromMavenOverride` 与 `org.gradle.jvmargs` 故意不写进任何 gradle.properties**：
     * 主应用每次请求（构建也是）都会把工作区整份镜像覆盖到共享存储，写在工程文件里的键在下一次
     * 构建前就被擦掉（这正是本仓库先前的实际症状）；写到用户级文件里也不能压过工程级——实测见
     * [BuildInvocation] 的类注释。它们只由 [BuildInvocation] 在调用时按命令行传，擦不到。
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
        log("已写入 local.properties（sdk.dir）；aapt2 覆盖与 jvmargs 由构建调用时传入，不依赖工程文件")
        return null
    }

    /** 合并 properties 文件：只补缺失的键，不覆盖用户已有配置 */
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

    /**
     * 构建前的自检：真的把每个工具跑一次，并与工程要求的 compileSdk / build-tools 对齐。
     * 这里失败必须是"人能照着做"的原因，而不是让 Gradle 抛一句 aapt2 相关的天书。
     */
    private fun checkToolchains(req: Request): String? {
        val report = ToolchainReadiness.evaluate(
            env = probeState().asStateMap(),
            compileSdk = req.compileSdk,
            buildTools = req.buildTools,
        )
        return if (report.ready) null else "${report.summary}。先执行一键安装（可重复执行，只补缺的部分）"
    }

    private fun runBuild(req: Request): String? {
        val project = File(req.project)
        if (!project.isDirectory) return "工程目录不存在：${req.project}"
        val hasWrapper = File(project, "gradlew").isFile
        val plan = BuildInvocation.plan(
            task = req.task,
            projectDir = req.project,
            hasWrapper = hasWrapper,
            jdkDir = jdkDir(req.jdkVersion),
            gradleDir = GRADLE,
            sdkDir = SDK,
        )
        log("执行：${req.task}（${if (hasWrapper) "工程自带 gradlew" else "使用内置 Gradle"}）")
        log("工程目录：${req.project}")
        log("调用时属性：${BuildInvocation.ownedProperties().joinToString("、") { it.first }}（不依赖工程文件，镜像覆盖擦不到）")
        val result = env.exec(
            guestArgv = plan.argv,
            timeoutMs = BUILD_TIMEOUT_MS,
            extraEnv = plan.extraEnv,
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

    private fun javaEnv(jdkVersion: Int): Map<String, String> =
        BuildInvocation.javaEnv(jdkDir(jdkVersion), GRADLE)

    // ------------------------------------------------------------ 就绪实测

    /** 单件工具的实测结果：就绪时 [reason] 为 null */
    private data class Probe(val label: String, val version: String?, val reason: String?) {
        companion object {
            fun failed(label: String, reason: String) = Probe(label, null, reason)
        }
    }

    /** SDK 实测结果：`sdkmanager --list_installed` 里真的存在的包 */
    private data class SdkProbe(
        val platforms: List<Int>,
        val buildTools: List<String>,
        val reason: String?,
    )

    /**
     * 把每个工具真的跑一次，产出 stateJson。
     *
     * 只看文件存在是不够的：装机目录可能在，可执行文件却跑不起来（proot 绑定丢了、
     * qemu-x86_64 没装上、glibc sysroot 缺失），那种情况只有真执行才暴露，而它恰好是
     * 构建阶段最难懂的那类报错。每条失败都给一句可操作的原因。
     */
    private fun probeState(): JSONObject {
        if (!env.isReady()) {
            return JSONObject().apply {
                put(EnvApi.STATE_LINUX, "未装配")
                put(EnvApi.STATE_JDK, "未安装")
                put(EnvApi.STATE_JDK_VERSION, "")
                put(EnvApi.STATE_GRADLE, "未安装")
                put(EnvApi.STATE_GRADLE_VERSION, "")
                put(EnvApi.STATE_SDK, "未安装")
                put(EnvApi.STATE_BUILD_TOOLS, "未安装")
                put(EnvApi.STATE_AAPT2, "未配置")
                put(EnvApi.STATE_PROBLEMS, "Linux 根文件系统未装配")
                put(EnvApi.STATE_INSTALLED_AT, installedAt())
                val (used, reclaimable) = diskUsage()
                put(EnvApi.STATE_DISK, formatBytes(used))
                put(EnvApi.STATE_DISK_RECLAIM, formatBytes(reclaimable))
            }
        }
        val jdk = probeJdk()
        val gradle = probeGradle()
        val sdk = probeSdk()
        val aapt2 = probeAapt2()
        val problems = listOfNotNull(jdk.reason, gradle.reason, sdk.reason, aapt2.reason)
        return JSONObject().apply {
            put(EnvApi.STATE_LINUX, "就绪")
            put(EnvApi.STATE_JDK, jdk.label)
            put(EnvApi.STATE_JDK_VERSION, jdk.version ?: "")
            put(EnvApi.STATE_GRADLE, gradle.label)
            put(EnvApi.STATE_GRADLE_VERSION, gradle.version ?: "")
            put(EnvApi.STATE_SDK, sdk.platforms.joinToString("、") { "android-$it" }.ifEmpty { "未安装" })
            put(EnvApi.STATE_BUILD_TOOLS, sdk.buildTools.joinToString("、").ifEmpty { "未安装" })
            put(EnvApi.STATE_AAPT2, aapt2.label)
            put(EnvApi.STATE_PROBLEMS, problems.joinToString("；"))
            put(EnvApi.STATE_INSTALLED_AT, installedAt())
            val (used, reclaimable) = diskUsage()
            put(EnvApi.STATE_DISK, formatBytes(used))
            put(EnvApi.STATE_DISK_RECLAIM, formatBytes(reclaimable))
        }
    }

    /** 安装的最后一步：实测跑不通就把原因当作安装失败返回，并把结果留给下一次读状态 */
    private fun verifyToolchain(): String? {
        val state = probeState()
        snapshot = Snapshot(System.currentTimeMillis(), state)
        val problems = state.optString(EnvApi.STATE_PROBLEMS)
        if (problems.isNotBlank()) return "工具链自检未通过：$problems"
        log(
            "工具链自检通过：${state.optString(EnvApi.STATE_JDK)} / " +
                "Gradle ${state.optString(EnvApi.STATE_GRADLE)} / " +
                "${state.optString(EnvApi.STATE_SDK)} / build-tools ${state.optString(EnvApi.STATE_BUILD_TOOLS)}",
        )
        return null
    }

    /** 已安装的 JDK 目录：/opt/jdkN 里真有 bin/java 的那个，大版本高的优先 */
    private fun installedJdkDir(): String? =
        env.guestFile(OPT).listFiles().orEmpty()
            .mapNotNull { dir -> JDK_DIR.matchEntire(dir.name)?.let { it.groupValues[1].toIntOrNull() } }
            .filter { env.guestExists("$OPT/jdk$it/bin/java") }
            .maxOrNull()
            ?.let { "$OPT/jdk$it" }

    private fun probeJdk(): Probe {
        val dir = installedJdkDir()
            ?: return Probe.failed("未安装", "JDK 未安装：/opt 下没有可用的 jdkN/bin/java")
        val r = probe("'$dir/bin/java' -version")
        val text = r.output.trim()
        val version = JDK_VERSION_LINE.find(text)?.groupValues?.get(1)
        return when {
            !r.ok -> Probe.failed(
                "无法启动",
                "java -version 执行失败（退出码 ${r.code}）：${text.takeLast(200).ifBlank { "无输出" }}",
            )
            version == null -> Probe.failed("无法识别版本", "java -version 输出无法解析：${text.take(200)}")
            else -> Probe("JDK $version", version, null)
        }
    }

    private fun probeGradle(): Probe {
        if (!env.guestExists("$GRADLE/bin/gradle")) {
            return Probe.failed("未安装", "Gradle 未安装：$GRADLE/bin/gradle 不存在")
        }
        val jdk = installedJdkDir()
            ?: return Probe.failed("无法执行", "gradle --version 无法执行：缺少 JDK（它需要 JAVA_HOME）")
        val r = probe("$GRADLE/bin/gradle --version", extraEnv = BuildInvocation.javaEnv(jdk, GRADLE))
        val text = r.output.trim()
        val version = GRADLE_VERSION_LINE.find(text)?.groupValues?.get(1)
        val wanted = gradleVersionInstalled()
        return when {
            !r.ok -> Probe.failed(
                "无法启动",
                "gradle --version 执行失败（退出码 ${r.code}）：${text.takeLast(200).ifBlank { "无输出" }}",
            )
            version == null -> Probe.failed("无法识别版本", "gradle --version 输出无法解析：${text.take(200)}")
            wanted != null && wanted != version -> Probe.failed(
                "版本不符",
                "工程要 Gradle $wanted，设备上能跑的是 $version（重新一键安装会补齐）",
            )
            else -> Probe(version, version, null)
        }
    }

    private fun probeSdk(): SdkProbe {
        val sdkmanager = "$SDK/cmdline-tools/latest/bin/sdkmanager"
        if (!env.guestExists(sdkmanager)) {
            return SdkProbe(emptyList(), emptyList(), "Android SDK 未安装：缺少 $sdkmanager")
        }
        val jdk = installedJdkDir()
            ?: return SdkProbe(emptyList(), emptyList(), "Android SDK 无法校验：缺少 JDK（sdkmanager 需要 JAVA_HOME）")
        val r = probe(
            "$sdkmanager --sdk_root=$SDK --list_installed",
            timeoutMs = SDK_PROBE_TIMEOUT_MS,
            extraEnv = BuildInvocation.javaEnv(jdk, GRADLE),
        )
        if (!r.ok) {
            return SdkProbe(
                emptyList(),
                emptyList(),
                "sdkmanager --list_installed 执行失败（退出码 ${r.code}）：" +
                    r.output.trim().takeLast(200).ifBlank { "无输出" },
            )
        }
        val platforms = PLATFORM_PKG.findAll(r.output).mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sorted().toList()
        val buildTools = BUILD_TOOLS_PKG.findAll(r.output).map { it.groupValues[1] }
            .distinct().sorted().toList()
        val reason = if (platforms.isEmpty()) {
            "Android SDK 平台未安装：sdkmanager 没列出任何 platforms;android-N"
        } else {
            null
        }
        return SdkProbe(platforms, buildTools, reason)
    }

    private fun probeAapt2(): Probe {
        if (!env.guestExists(AAPT2)) {
            return Probe.failed("未配置", "aapt2 兼容层未配置：$AAPT2 不存在")
        }
        val r = probe("$AAPT2 version")
        val text = r.output.trim()
        if (!r.ok) {
            return Probe.failed(
                "无法执行",
                "aapt2 兼容层无法执行（qemu-x86_64 或 x86_64 glibc 缺失）：${text.takeLast(200).ifBlank { "无输出" }}",
            )
        }
        // 能跑通就是就绪：包装脚本的版本行格式随 build-tools 变，认不出不影响结论
        return Probe("已配置", AAPT2_VERSION_LINE.find(text)?.groupValues?.get(1), null)
    }

    /** 在容器里跑一条探测命令；输出同时进日志，方便用户回看真实报错 */
    private fun probe(
        cmd: String,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
        extraEnv: Map<String, String> = emptyMap(),
    ): LinuxEnv.ShellResult = env.exec(
        guestArgv = listOf("/bin/sh", "-c", cmd),
        timeoutMs = timeoutMs,
        extraEnv = extraEnv,
        onLine = { if (it.isNotBlank()) log("[自检] $it") },
    )

    private fun JSONObject.asStateMap(): Map<String, String> =
        keys().asSequence().associateWith { optString(it) }

    // ------------------------------------------------------------ 下载 / 解包

    /** 一个待下载产物：来源列表 + 官方摘要 + 体积下限 */
    private data class Artifact(
        val label: String,
        val fileName: String,
        val urls: List<String>,
        val expected: Expected,
        val minBytes: Long = MIN_ARCHIVE_BYTES,
    )

    /**
     * 带官方摘要校验的下载：拿不到摘要就拒绝安装，摘要不符就删文件换下一个源。
     *
     * 缓存同样要重新校验——坏归档留在 `downloads/` 里的话，下一次安装会「复用本地缓存」
     * 直接跳过下载，坏文件就此永久驻留，只能清应用数据才能重来。
     */
    private fun download(artifact: Artifact): File? {
        val dir = File(ctx.filesDir, LinuxEnv.DOWNLOAD_DIR).apply { mkdirs() }
        val target = File(dir, artifact.fileName)
        val algorithm = algorithmOf(artifact.expected)
        val expectedHex = resolveExpected(artifact.expected)
        if (expectedHex == null) {
            log("${artifact.label}：拿不到官方摘要（${describe(artifact.expected)}），拒绝安装未校验的包")
            return null
        }

        if (target.isFile && target.length() >= artifact.minBytes) {
            if (verifyOrDiscard(target, algorithm, expectedHex)) {
                log("${artifact.label}：复用本地缓存（${target.length() / 1024 / 1024} MB，摘要校验通过）")
                return target
            }
            log("${artifact.label}：本地缓存摘要不符，已删除并重新下载")
        }

        for (url in artifact.urls) {
            if (cancelled.invoke()) return null
            log("${artifact.label}：下载 $url")
            val failure = Fetch.toFile(
                url = url,
                target = target,
                onProgress = { copied, total ->
                    val text = if (total > 0) {
                        "${copied / 1024 / 1024} / ${total / 1024 / 1024} MB"
                    } else {
                        "${copied / 1024 / 1024} MB"
                    }
                    log("${artifact.label} 已下载 $text")
                },
                isCancelled = { cancelled.invoke() },
            )
            if (failure != null) {
                log("${artifact.label}：$url 不可用（$failure）")
                continue
            }
            if (target.length() < artifact.minBytes) {
                log("${artifact.label}：$url 只拿到 ${target.length() / 1024} KB，明显不完整，丢弃")
                target.delete()
                continue
            }
            val actual = runCatching { Digest.hexOf(target, algorithm) }.getOrNull()
            if (actual == null || !Digest.matches(expectedHex, actual)) {
                log(
                    "${artifact.label}：$url 的摘要与官方值不符" +
                        "（期望 ${expectedHex.take(12)}…，实际 ${actual?.take(12) ?: "计算失败"}…），已删除该文件",
                )
                target.delete()
                continue
            }
            log("${artifact.label}：$algorithm 校验通过")
            return target
        }
        log("${artifact.label}：${artifact.urls.size} 个下载源全部失败——官方可能已下架该版本，请更新代码里的 URL 或换镜像")
        return null
    }

    private fun algorithmOf(expected: Expected): String = when (expected) {
        is Expected.Fixed -> expected.algorithm
        is Expected.Sidecar -> expected.algorithm
    }

    private fun describe(expected: Expected): String = when (expected) {
        is Expected.Fixed -> "代码里钉住的 ${expected.algorithm}"
        is Expected.Sidecar -> "官方 sidecar ${expected.url}"
    }

    /**
     * 把归档解开并抹掉顶层单目录（JDK / Gradle / cmdline-tools 都是这种布局）。
     *
     * 先解到 `*.staging`，再经 [InstallSwap] 换入：以前是先删旧目录再解包，
     * 解到一半被打断就把**已经装好的**工具链一起带走了。
     */
    private fun extractFlatten(
        source: File,
        guestDest: String,
        extract: (File, File) -> Unit,
    ): String? {
        val dest = env.guestFile(guestDest)
        val staging = env.guestFile("$guestDest.staging")
        env.deleteRecursive(staging)
        if (!staging.mkdirs()) return "无法创建临时目录：${staging.absolutePath}"

        val failed = runCatching { extract(source, staging) }.exceptionOrNull()
        if (failed != null) {
            env.deleteRecursive(staging)
            return "解压失败：${failed.message}"
        }
        val children = staging.listFiles()?.sortedBy { it.name }.orEmpty()
        if (children.isEmpty()) {
            env.deleteRecursive(staging)
            return "归档里没有任何文件（下到的可能是错误页）"
        }
        val content = children.singleOrNull()?.takeIf { it.isDirectory } ?: staging
        val failure = InstallSwap.replace(content, dest)
        env.deleteRecursive(staging)
        return failure
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

    private fun installedAt(): String =
        runCatching { env.guestFile("$OPT/.installed-at").readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "从未安装"

    // ------------------------------------------------------------ 维护

    /**
     * 清理：删掉下载缓存、临时目录与上次中断留下的半装目录。
     * 已装好的 rootfs 与 /opt 工具链原样保留——这是「清理缓存」，不是「重置」。
     */
    private fun cleanCaches(): String? {
        val freed = env.cleanTemporary()
        diskUsageCache = null
        log("清理完成：释放 ${formatBytes(freed)}，当前占用 ${formatBytes(env.usedBytes())}")
        return null
    }

    /** 重置：把环境组件自己的私有目录清空，回到未装配。用户的工程文件不在这些目录里 */
    private fun resetEnvironment(): String? {
        val freed = env.resetAll()
        diskUsageCache = null
        log("已重置编码环境：释放 ${formatBytes(freed)}；下次安装会重新下载并装配")
        return null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun log(line: String) {
        if (line.length > LOG_LIMIT) return
        listener.onLog(line)
    }
}
