package com.phoneagent.env

import android.content.Context
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 内置 Linux 环境：PRoot 引导集 + Alpine 根文件系统。
 *
 * ## 为什么必须待在 targetSdk 28 的组件里
 * Android 10 起内核/SELinux 对 targetSdk ≥ 29 的应用禁止 execve 私有目录里的文件（W^X）。
 * proot 引导集只能解压到 filesDir（系统不给第三方应用别的可写可执行位置），
 * 因此它只能在 targetSdk 28 的进程里跑。主应用 targetSdk 36，这个环境只能由
 * 独立组件（com.phoneagent.env）承载，主应用通过 AIDL 远程驱动。
 *
 * ## 装配流程
 * 引导集随 APK 携带（4 个文件约 300 KB）；根文件系统在首次安装时从 Alpine 官方镜像下载
 * （约 3.8 MB），解归档到 `files/linux/rootfs`。装配完成后做一次真实 proot 启动探测，
 * 探测出「要不要 --link2symlink、绑定集用完整还是最小」并记在标记文件里。
 */
class LinuxEnv(private val ctx: Context) {

    companion object {
        private const val ASSET_ROOT = "linux"
        private const val VERSION_ASSET = "$ASSET_ROOT/version.txt"

        /** 引导集：资产路径到运行目录相对路径。总共四个文件，约 300 KB。 */
        private val BOOTSTRAP = listOf(
            "$ASSET_ROOT/bin/proot" to "bin/proot",
            "$ASSET_ROOT/bin/loader" to "bin/loader",
            "$ASSET_ROOT/lib/libtalloc.so.2" to "lib/libtalloc.so.2",
            "$ASSET_ROOT/lib/libandroid-shmem.so" to "lib/libandroid-shmem.so",
        )

        /**
         * 根文件系统候选资产名。
         *
         * 允许把 rootfs 直接放进 APK 资产（离线可用），但默认走网络下载：
         * 27 MB 的归档塞进包里会让组件 APK 变成"只为装一次"的巨型包。
         */
        private val ROOTFS_ASSETS = listOf("$ASSET_ROOT/rootfs.tar", "$ASSET_ROOT/rootfs.tar.gz")

        /**
         * Alpine minirootfs（aarch64）下载源。
         *
         * 两个镜像轮流试：官方 CDN 在国内常被限速，阿里云镜像缺文件时再回落官方。
         * 版本与 [version.txt] 里记录的 alpine-3.24 对齐。
         */
        private val ROOTFS_URLS = listOf(
            "https://mirrors.aliyun.com/alpine/v3.24/releases/aarch64/" +
                "alpine-minirootfs-3.24.2-aarch64.tar.gz",
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/" +
                "alpine-minirootfs-3.24.2-aarch64.tar.gz",
        )

        private const val MARKER = ".installed"
        private const val LOCK = ".installing"
        private const val LINK2SYMLINK_MARK = ".link2symlink"
        private const val MINIMAL_BINDS_MARK = ".minimalbinds"

        /** 容器内的 PATH，固定值，与宿主无关。 */
        private const val GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        /** 绑进容器的宿主路径；实际绑定时逐个检查存在性，缺哪个跳过哪个。 */
        private val BIND_CANDIDATES = listOf(
            "/dev" to null,
            "/proc" to null,
            "/sys" to null,
            "/dev/pts" to null,
            "/data/local/tmp" to null,
            "/storage/emulated/0" to "/sdcard",
            "/storage/emulated/0" to "/storage/emulated/0",
            "/proc/self/fd" to "/dev/fd",
        )

        /**
         * 最小绑定集：只保留设备节点与进程文件系统。
         * 完整集里任一可选绑定失败 proot 会直接起不来，这里牺牲便利性换取可用。
         */
        private val MINIMAL_BIND_CANDIDATES = listOf(
            "/dev" to null,
            "/dev/pts" to null,
            "/proc" to null,
        )

        private const val LOCK_TIMEOUT_MS = 180_000L
        private const val PROBE_TIMEOUT_SECONDS = 20L

        /** 权限常量：Kotlin 没有八进制字面量，这里写十进制并在注释里标出八进制。 */
        const val MODE_EXEC = 493 // 0755
        const val MODE_FILE = 420 // 0644
        const val MODE_PRIVATE = 384 // 0600

        /** 下载缓冲区：256 KB 兼顾吞吐与取消响应速度 */
        private const val COPY_BUFFER = 256 * 1024

        /** 根归档最小可信体积：真实 minirootfs 约 3.8 MB，明显低于此值说明下到了错误页 */
        private const val MIN_ROOTFS_BYTES = 1_000_000L
    }

    val home: File = File(ctx.filesDir, "linux")

    /** 根文件系统目录，装配完成后存在于 [home] 下。 */
    val rootfs: File = File(home, "rootfs")

    /** 容器内的 /dev/shm 与 proot 临时目录，都在宿主私有目录里。 */
    private val tmpDir: File = File(ctx.filesDir, "tmp")

    private val binDir: File = File(home, "bin")
    private val marker: File = File(home, MARKER)
    private val lockDir: File = File(home, LOCK)
    private val link2symlinkMark: File = File(home, LINK2SYMLINK_MARK)
    private val minimalBindsMark: File = File(home, MINIMAL_BINDS_MARK)
    private val archive: File = File(home, "rootfs.tar.gz")

    val prootPath: String get() = File(binDir, "proot").absolutePath

    private val loaderPath: String get() = File(binDir, "loader").absolutePath

    /** 环境是否已装配就绪（标记、目录、proot 三者齐全）。 */
    fun isReady(): Boolean = try {
        marker.isFile && rootfs.isDirectory && File(prootPath).isFile
    } catch (_: Throwable) {
        false
    }

    /**
     * 装配环境；已装配且资产版本一致时直接返回。
     *
     * [onProgress] 给人看的阶段说明，[onLog] 是原始输出（下载进度、proot 探测失败原因）。
     */
    fun ensureReady(
        onProgress: (String) -> Unit = {},
        onLog: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val wanted = assetVersion() ?: return false
        if (isReady() && runCatching { marker.readText().trim() }.getOrNull() == wanted) return true

        home.mkdirs()
        if (!lockDir.mkdirs()) {
            onProgress("Linux 环境正在装配，等待完成…")
            val deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MS
            while (lockDir.exists() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return isReady()
        }

        return try {
            tmpDir.mkdirs()
            onProgress("释放 Linux 引导程序…")
            releaseBootstrap()
            onProgress("准备 Linux 根文件系统…")
            if (!ensureArchive(onProgress, onLog, isCancelled)) return false
            onProgress("装配 Linux 环境（首次约需数十秒）…")
            extractRootfs()
            // 分级探测：功能最全的档位先试，起不来就换更保守的组合。
            val plan = listOf(
                LaunchPlan(link2symlink = true, minimalBinds = false),
                LaunchPlan(link2symlink = true, minimalBinds = true),
                LaunchPlan(link2symlink = false, minimalBinds = false),
                LaunchPlan(link2symlink = false, minimalBinds = true),
            ).firstOrNull { probe(it) }
            if (plan == null) {
                onProgress("Linux 环境自检未通过（proot 无法启动）")
                return false
            }
            link2symlinkMark.writeText(if (plan.link2symlink) "1" else "0")
            minimalBindsMark.writeText(if (plan.minimalBinds) "1" else "0")
            marker.writeText(wanted)
            archive.delete()
            onProgress("Linux 环境就绪")
            true
        } catch (t: Throwable) {
            onProgress("Linux 环境装配失败：${t.message}")
            onLog("装配异常：${t.javaClass.simpleName} ${t.message}")
            false
        } finally {
            lockDir.delete()
        }
    }

    /** 容器内执行一条命令，逐行回调输出；调用方负责放到后台线程。 */
    fun exec(
        guestArgv: List<String>,
        timeoutMs: Long = 60_000L,
        extraEnv: Map<String, String> = emptyMap(),
        workDir: String? = null,
        onLine: (String) -> Unit = {},
    ): ShellResult = try {
        val process = builder(guestArgv, extraEnv, workDir).redirectErrorStream(true).start()
        val sink = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { buffered ->
                    var line = buffered.readLine()
                    while (line != null) {
                        // 只保留前 100 KB：构建日志可能有几万行，全存下来毫无意义
                        if (sink.length < 100_000) sink.append(line).append('\n')
                        onLine(line)
                        line = buffered.readLine()
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000)
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        ShellResult(finished && code == 0, code, sink.toString())
    } catch (t: Throwable) {
        ShellResult(false, -1, t.message ?: "执行失败")
    }

    /** 一次容器内命令的执行结果。 */
    data class ShellResult(val ok: Boolean, val code: Int, val output: String)

    /** 容器内的绝对路径 → 宿主上的真实文件（用于直接写文件，不经过 shell）。 */
    fun guestFile(guestAbsolute: String): File = File(rootfs, guestAbsolute.removePrefix("/"))

    /** 写一个容器内文件；属主是应用自身，chmod 一定生效。 */
    fun writeGuestFile(guestAbsolute: String, text: String, mode: Int = MODE_FILE) {
        val file = guestFile(guestAbsolute)
        file.parentFile?.mkdirs()
        file.writeText(text)
        runCatching { Os.chmod(file.absolutePath, mode) }
    }

    /** 容器内路径是否存在（文件或目录）。 */
    fun guestExists(guestAbsolute: String): Boolean = guestFile(guestAbsolute).exists()

    // ---------------------------------------------------------------- 装配内部

    private fun assetVersion(): String? = try {
        ctx.assets.open(VERSION_ASSET).use { it.readBytes().decodeToString().trim() }
    } catch (_: Throwable) {
        null
    }

    private fun releaseBootstrap() {
        for ((asset, relative) in BOOTSTRAP) {
            val target = File(home, relative)
            target.parentFile?.mkdirs()
            ctx.assets.open(asset).use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, COPY_BUFFER) }
            }
            Os.chmod(target.absolutePath, MODE_EXEC)
        }
    }

    /**
     * 准备根文件系统归档：优先用随包资产，否则从镜像下载。
     * 已有可用归档（上次下载完成但装配失败）时直接复用，不重复下载。
     */
    private fun ensureArchive(
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (archive.isFile && archive.length() >= MIN_ROOTFS_BYTES) {
            onLog("复用已下载的根文件系统归档（${archive.length() / 1024 / 1024} MB）")
            return true
        }
        for (asset in ROOTFS_ASSETS) {
            val available = runCatching { ctx.assets.open(asset).close() }.isSuccess
            if (!available) continue
            onProgress("释放随包根文件系统…")
            ctx.assets.open(asset).use { input ->
                FileOutputStream(archive).use { out -> input.copyTo(out, COPY_BUFFER) }
            }
            return true
        }
        return download(onLog, isCancelled)
    }

    private fun download(onLog: (String) -> Unit, isCancelled: () -> Boolean): Boolean {
        for (url in ROOTFS_URLS) {
            if (isCancelled()) return false
            onLog("下载 Alpine 根文件系统：$url")
            val temp = File(home, "rootfs.tar.gz.part")
            temp.delete()
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
                        val buffer = ByteArray(COPY_BUFFER)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            if (isCancelled()) {
                                temp.delete()
                                return false
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            if (copied - lastReport >= 1 shl 20) {
                                lastReport = copied
                                val mb = copied / 1024 / 1024
                                val text = if (total > 0) "$mb / ${total / 1024 / 1024} MB" else "$mb MB"
                                onLog("根文件系统已下载 $text")
                            }
                        }
                    }
                }
                conn.disconnect()
                if (temp.length() < MIN_ROOTFS_BYTES) throw IllegalStateException("下载内容不完整")
                archive.delete()
                if (!temp.renameTo(archive)) throw IllegalStateException("归档无法就位")
                return true
            } catch (t: Throwable) {
                onLog("镜像不可用（${t.javaClass.simpleName}）：${t.message}")
                temp.delete()
            }
        }
        return false
    }

    /** 解归档到临时目录再整体就位，中途失败不会破坏上一次可用的环境。 */
    fun extractRootfs() {
        val staging = File(home, "rootfs.staging")
        deleteRecursive(staging)
        staging.mkdirs()
        openArchive(archive).use { input -> untar(input, staging) }
        deleteRecursive(rootfs)
        if (!staging.renameTo(rootfs)) {
            deleteRecursive(staging)
            throw IllegalStateException("根文件系统无法就位")
        }
    }

    /** 解一个 tar(.gz) 归档到 [dest]；JDK、glibc sysroot 这类归档都走这里。 */
    fun extractArchive(source: File, dest: File) {
        dest.mkdirs()
        openArchive(source).use { input -> untar(input, dest) }
    }

    /** 按魔数判断要不要解压：gzip 头就套一层 GZIPInputStream，否则当裸 tar 读。 */
    private fun openArchive(file: File): InputStream {
        val buffered = BufferedInputStream(file.inputStream(), 1 shl 16)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
    }

    /**
     * 精简 ustar 解包器：支持常规文件 / 目录 / 符号链接，跳过 pax 扩展头。
     * JDK、glibc sysroot 这类归档也用它，所以放在这里而不是安装器里。
     */
    fun untar(input: InputStream, dest: File) {
        val header = ByteArray(512)
        val buffer = ByteArray(1 shl 16)
        while (true) {
            if (!readBlock(input, header)) return
            if (header[0] == 0.toByte()) return
            val name = readString(header, 0, 100)
            if (name.isEmpty()) return
            val mode = readOctal(header, 100, 8).toInt()
            val size = readOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = readString(header, 157, 100)
            val prefix = readString(header, 345, 155)
            val full = if (prefix.isEmpty()) name else "$prefix/$name"
            val relative = full.removePrefix("./").trimStart('/')
            val isRegular = type == '0' || type == '\u0000' || type == '7'

            val target = if (relative.isEmpty() || relative.split('/').contains("..")) {
                null
            } else {
                File(dest, relative)
            }

            var consumed = false
            if (target != null) {
                when {
                    type == '5' -> target.mkdirs()
                    type == '2' -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        runCatching { Os.symlink(linkName, target.absolutePath) }
                    }
                    isRegular -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> copyExactly(input, out, size, buffer) }
                        consumed = true
                        runCatching { Os.chmod(target.absolutePath, mode) }
                    }
                }
            }

            val payload = if (isRegular) size else 0L
            if (!consumed && payload > 0) skipExactly(input, payload, buffer)
            val padding = (512L - (payload % 512L)) % 512L
            if (padding > 0) skipExactly(input, padding, buffer)
        }
    }

    // ---------------------------------------------------------------- proot 参数

    /** 一类启动组合：是否带 --link2symlink、绑定集用完整还是最小。 */
    private data class LaunchPlan(val link2symlink: Boolean, val minimalBinds: Boolean)

    /** proot 前缀参数：进入 rootfs、伪造 root、工作目录 + 绑定宿主目录。 */
    fun launchPrefix(workDir: String? = null): List<String> {
        val out = mutableListOf("-0", "-r", rootfs.absolutePath)
        if (runCatching { link2symlinkMark.readText().trim() }.getOrNull() == "1") {
            out += "--link2symlink"
        }
        out += listOf("-w", workDir ?: "/root")
        for ((source, guest) in activeBinds()) {
            out += "-b"
            out += if (guest == null || guest == source) source else "$source:$guest"
        }
        return out
    }

    /**
     * 构造一条容器内命令。
     *
     * 宿主环境变量一条都不带进去：内核给组件进程的 LD_LIBRARY_PATH 指向它自己的库目录，
     * 继承下去会让容器内的动态加载器先去那里找，可能撞库。引导集 RUNPATH 是 $ORIGIN 形式，
     * 清空 LD_LIBRARY_PATH 后照样能找到 libtalloc 与 libandroid-shmem。
     */
    fun builder(
        guestArgv: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        workDir: String? = null,
    ): ProcessBuilder {
        val command = mutableListOf<String>()
        command += prootPath
        command += launchPrefix(workDir)
        command += "/usr/bin/env"
        command += "-i"
        command += "HOME=/root"
        command += "SHELL=/bin/sh"
        command += "TERM=xterm-256color"
        command += "LANG=C.UTF-8"
        command += "PATH=$GUEST_PATH"
        for ((key, value) in extraEnv) command += "$key=$value"
        command += guestArgv

        val builder = ProcessBuilder(command)
        builder.directory(home)
        val environment = builder.environment()
        environment.remove("LD_LIBRARY_PATH")
        environment["PROOT_LOADER"] = loaderPath
        environment["PROOT_TMP_DIR"] = tmpDir.absolutePath
        return builder
    }

    /**
     * 用真实 proot 启动一次容器做自检；能起就采纳这套组合，起不来换下一档。
     */
    private fun probe(plan: LaunchPlan): Boolean {
        val binds = if (plan.minimalBinds) minimalBinds() else bindPairs()
        val command = mutableListOf<String>()
        command += prootPath
        command += "-0"
        command += "-r"
        command += rootfs.absolutePath
        if (plan.link2symlink) command += "--link2symlink"
        command += "-w"
        command += "/root"
        for ((source, guest) in binds) {
            command += "-b"
            command += if (guest == null || guest == source) source else "$source:$guest"
        }
        command += "/usr/bin/env"
        command += "-i"
        command += "HOME=/root"
        command += "/bin/true"

        return try {
            val builder = ProcessBuilder(command)
            builder.directory(home)
            builder.redirectErrorStream(true)
            val environment = builder.environment()
            environment.remove("LD_LIBRARY_PATH")
            environment["PROOT_LOADER"] = loaderPath
            environment["PROOT_TMP_DIR"] = tmpDir.absolutePath
            val process = builder.start()
            process.inputStream.readBytes()
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun activeBinds(): List<Pair<String, String?>> {
        val minimal = runCatching { minimalBindsMark.readText().trim() }.getOrNull() == "1"
        return if (minimal) minimalBinds() else bindPairs()
    }

    private fun bindPairs(): List<Pair<String, String?>> = collectBinds(BIND_CANDIDATES)

    private fun minimalBinds(): List<Pair<String, String?>> = collectBinds(MINIMAL_BIND_CANDIDATES)

    private fun collectBinds(candidates: List<Pair<String, String?>>): List<Pair<String, String?>> {
        val pairs = mutableListOf<Pair<String, String?>>()
        for ((source, guest) in candidates) {
            if (runCatching { File(source).exists() }.getOrDefault(false)) pairs += source to guest
        }
        // 宿主应用沙箱自身：主应用的工作区常落在 filesDir 下，容器里要能按同一路径看到
        val files = ctx.filesDir.absolutePath
        pairs += files to files
        if (tmpDir.isDirectory) pairs += tmpDir.absolutePath to "/dev/shm"
        return pairs
    }

    // ---------------------------------------------------------------- 工具

    private fun readBlock(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var started = false
        var index = offset
        val limit = offset + length
        while (index < limit) {
            val c = header[index].toInt().toChar()
            if (!started && (c == ' ' || c == '\u0000')) {
                index++
                continue
            }
            if (c < '0' || c > '7') break
            started = true
            value = value * 8 + (c - '0')
            index++
        }
        return value
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long, buffer: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, chunk)
            if (read < 0) throw IllegalStateException("归档数据不完整")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: InputStream, size: Long, buffer: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, chunk)
            if (read < 0) return
            remaining -= read
        }
    }

    fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }
}
