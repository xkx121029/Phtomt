package com.phoneagent.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 下载完整性校验的判据测试。
 *
 * 所有期望值都取自公开标准向量（"abc" 的 SHA-256 / SHA-1），不是从实现里抄回来的——
 * 把 `Digest.hexOf` 改错一个字节，这里立刻红。
 */
class ArtifactVerifyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val abcSha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"

    @Test
    fun `sha256 与 sha1 命中标准向量`() {
        val bytes = "abc".toByteArray()
        assertEquals(abcSha256, Digest.hexOf(bytes, SHA256))
        assertEquals(abcSha1, Digest.hexOf(bytes, SHA1))
    }

    @Test
    fun `文件摘要与内存摘要一致`() {
        val file = temp.newFile("payload.bin").apply { writeBytes("abc".toByteArray()) }
        assertEquals(abcSha256, Digest.hexOf(file, SHA256))
    }

    @Test
    fun `大文件摘要走流式且结果正确`() {
        // 覆盖 read 循环跨多个 64 KB 缓冲区的分支：内容超过一个缓冲区才走得到第二次 read
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val file = temp.newFile("big.bin").apply { writeBytes(payload) }
        assertEquals(Digest.hexOf(payload, SHA256), Digest.hexOf(file, SHA256))
    }

    @Test
    fun `摘要比对只认真正相等`() {
        assertTrue(Digest.matches(abcSha256, abcSha256))
        assertTrue("大写期望值也要能匹配", Digest.matches(abcSha256.uppercase(), abcSha256))
        assertFalse("期望值缺失不能当成通过", Digest.matches(null, abcSha256))
        assertFalse(Digest.matches("", abcSha256))
        assertFalse("长度不符直接判否", Digest.matches(abcSha1, abcSha256))
        assertFalse("非十六进制不能通过", Digest.matches(abcSha256.dropLast(1) + "z", abcSha256))
    }

    @Test
    fun `校验失败要删掉坏文件，成功要留着`() {
        val bad = temp.newFile("bad.zip").apply { writeBytes("corrupted".toByteArray()) }
        assertFalse(verifyOrDiscard(bad, SHA256, abcSha256))
        assertFalse("坏归档必须被删掉，否则会被当成缓存复用", bad.exists())

        val good = temp.newFile("good.zip").apply { writeBytes("abc".toByteArray()) }
        assertTrue(verifyOrDiscard(good, SHA256, abcSha256))
        assertTrue(good.exists())
    }

    @Test
    fun `sidecar 支持 coreutils 两种格式与裸摘要`() {
        val name = "gradle-8.13-bin.zip"
        assertEquals(abcSha256, parseSidecar("$abcSha256  $name\n"))
        assertEquals(abcSha256, parseSidecar("$abcSha256 *$name"))
        assertEquals(abcSha256, parseSidecar(abcSha256))
        assertEquals("大写摘要归一成小写", abcSha256, parseSidecar("${abcSha256.uppercase()}  $name"))
        assertEquals(abcSha1, parseSidecar("$abcSha1  $name\r\n"))
    }

    @Test
    fun `sidecar 不是摘要时必须判成拿不到`() {
        assertNull("镜像返回 HTML 错误页", parseSidecar("<html><body>301 Moved</body></html>"))
        assertNull("空内容", parseSidecar(""))
        assertNull(parseSidecar(null))
        assertNull("长度像但含非 hex 字符", parseSidecar(abcSha256.dropLast(1) + "z"))
        assertNull("短摘要不能当 64 位用", parseSidecar("deadbeef"))
    }

    @Test
    fun `目录不会被当成可校验文件`() {
        val dir: File = temp.newFolder("dir")
        assertFalse(verifyOrDiscard(dir, SHA256, abcSha256))
    }
}
