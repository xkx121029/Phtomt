package com.phoneagent.env

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 下载产物的完整性校验。
 *
 * 环境装配要下四类官方产物（Alpine 根文件系统、Adoptium JDK、Gradle、cmdline-tools）
 * 加一份 x86_64 glibc sysroot，全都是 4 MB–190 MB 级归档。以前只看「体积大于 1 MB」就算
 * 下载成功，于是 CDN 的错误页、被中断的续传、被镜像替换过的包都能一路装进 rootfs，
 * 失败点被推到几十步之后的解包或构建，用户看到的是「构建失败」而不是「下载坏了」。
 *
 * 这里的原则：**凡是官方公布了摘要的，一律先校验再用；拿不到官方摘要的一律不装。**
 * 校验失败的文件当场删除，绝不留在缓存里冒充「下次复用」。
 */

/** 十六进制摘要算法名；打印进日志与错误信息，不要写成枚举，方便直接传给 MessageDigest */
internal const val SHA256 = "SHA-256"
internal const val SHA1 = "SHA-1"

internal object Digest {

    /**
     * 官方发布的摘要一律是小写十六进制，这里也按小写比较。
     * 期望值为空、长度与算法不符、或含非十六进制字符，一律判不匹配——
     * 「没有摘要」不是「不用校验」，调用方必须先拿到摘要才允许走到这一步。
     */
    fun matches(expected: String?, actual: String): Boolean {
        val want = expected?.trim()?.lowercase() ?: return false
        if (want.isEmpty() || want.length != actual.length) return false
        if (!want.all { it in '0'..'9' || it in 'a'..'f' }) return false
        return want == actual.lowercase()
    }

    fun hexOf(bytes: ByteArray, algorithm: String = SHA256): String =
        MessageDigest.getInstance(algorithm).digest(bytes).toHex()

    /** 流式摘要：JDK 归档 190 MB，读进内存算摘要会把低端机直接压死 */
    fun hexOf(file: File, algorithm: String = SHA256): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * 官方 sidecar 文件的解析（Gradle 的 `<url>.sha256`、Alpine 的 `.sha256`）。
 *
 * 格式是 coreutils 的两种变体：`<hash>  <filename>` 或 `<hash> *<filename>`，
 * 也有只放一行 hash 的。只取第一行第一个字段，并要求它确实是 40/64 位十六进制——
 * 镜像站被劫持时返回的 HTML 错误页必须在这里就被判成「拿不到摘要」，而不是当成摘要。
 */
internal fun parseSidecar(raw: String?): String? {
    val firstLine = raw?.trim()?.lineSequence()?.firstOrNull()?.trim() ?: return null
    val token = firstLine.split(' ', '\t').firstOrNull()?.trim() ?: return null
    if (token.length != 40 && token.length != 64) return null
    if (!token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return token.lowercase()
}

/** 一个下载条目的摘要从哪来 */
internal sealed class Expected {
    /** 代码里钉住的官方摘要：URL 与摘要写在一起，改 URL 而不改摘要会立刻校验失败 */
    data class Fixed(val algorithm: String, val hex: String) : Expected()

    /** 官方 sidecar：Gradle 这类版本在运行时才确定的产物只能这样 */
    data class Sidecar(val algorithm: String, val url: String) : Expected()
}

/** 官方摘要的获取结果：拿不到就是拿不到，调用方据此拒绝安装而不是跳过校验 */
internal fun resolveExpected(expected: Expected): String? = when (expected) {
    is Expected.Fixed -> expected.hex.trim().lowercase().takeIf { it.isNotEmpty() }
    is Expected.Sidecar -> parseSidecar(Fetch.text(expected.url))
}

/**
 * 校验并决定去留：不匹配时**删掉文件**。
 *
 * 删是这里的关键语义——缓存里的坏归档如果不删，下一次安装会「复用本地缓存」直接跳过
 * 下载，坏文件就永久驻留，用户只能清应用数据才能重来。
 */
internal fun verifyOrDiscard(file: File, algorithm: String, expectedHex: String): Boolean {
    if (!file.isFile) return false
    val actual = runCatching { Digest.hexOf(file, algorithm) }.getOrNull() ?: return false
    if (Digest.matches(expectedHex, actual)) return true
    runCatching { file.delete() }
    return false
}

/** 网络读取的唯一出口：超时、UA、重定向、取消检查都在这里，避免两处各写一套 */
internal object Fetch {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    private fun open(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "happy-phone-env/1.0")
        }

    /**
     * 下载到 [target]；成功返回 null，失败返回一条能直接给人看的原因。
     * [onProgress] 每满 1 MB 回调一次（已下载字节，总字节；总字节未知时为 0）。
     */
    fun toFile(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): String? {
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url)
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) return "已取消"
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= (1L shl 20)) {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                }
            }
            if (temp.length() <= 0L) return "响应为空"
            if (!temp.renameTo(target)) return "归档无法就位"
            null
        } catch (t: Throwable) {
            temp.delete()
            "${t.javaClass.simpleName}：${t.message ?: "无详情"}"
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 读一小段文本（摘要 sidecar、元数据 JSON）；失败返回 null，调用方负责报「拿不到」 */
    fun text(url: String, limitBytes: Int = 64 * 1024, timeoutMs: Int = 30_000): String? = try {
        open(url, timeoutMs).run {
            inputStream.use { stream ->
                val buffer = ByteArray(limitBytes)
                var filled = 0
                while (filled < limitBytes) {
                    val read = stream.read(buffer, filled, limitBytes - filled)
                    if (read < 0) break
                    filled += read
                }
                String(buffer, 0, filled, Charsets.UTF_8)
            }
        }
    } catch (_: Throwable) {
        null
    }

    private const val COPY_BUFFER = 256 * 1024
}
