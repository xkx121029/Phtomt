package com.phoneagent.device.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用系统应用打开链接/文件」目标归一化单测（[OpenTarget]）。
 *
 * 覆盖三件事：
 * 1. **本地路径 → 文档提供者 content:// 形式**：API 24+ 直接交 `file://` 会抛 `FileUriExposedException`，
 *    必须换成 `content://com.android.externalstorage.documents/document/<卷>%3A<路径>`；
 * 2. **非本地目标原样放行**：网址/私有 scheme/content:// 不能被改写；
 * 3. **扩展名 → MIME**：给不出准确类型时文档软件不会进候选，认不出则返回 null（绝不硬编错类型）。
 */
class OpenTargetTest {

    // ==================== 一、是否为本地文件 ====================

    @Test
    fun `本地判断_绝对路径与file协议为真`() {
        assertTrue(OpenTarget.isLocalFile("/sdcard/Download/季度汇报.ppt"))
        assertTrue(OpenTarget.isLocalFile("file:///sdcard/Download/x.doc"))
        assertTrue(OpenTarget.isLocalFile("  /storage/emulated/0/a.pdf  "))
    }

    @Test
    fun `本地判断_网址与私有scheme为假`() {
        assertFalse(OpenTarget.isLocalFile("https://www.example.com/a.ppt"))
        assertFalse(OpenTarget.isLocalFile("weixin://dl/chat"))
        assertFalse(OpenTarget.isLocalFile("content://com.android.externalstorage.documents/document/primary%3Aa.ppt"))
    }

    // ==================== 二、归一化 ====================

    @Test
    fun `归一化_sdcard路径映射到主卷`() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2F%E5%AD%A3%E5%BA%A6%E6%B1%87%E6%8A%A5.ppt",
            OpenTarget.normalize("/sdcard/Download/季度汇报.ppt"),
        )
    }

    @Test
    fun `归一化_emulated路径也映射到主卷`() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Freport.doc",
            OpenTarget.normalize("/storage/emulated/0/Download/report.doc"),
        )
    }

    @Test
    fun `归一化_外置卡保留卷名`() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/1A2B-3C4D%3Amovie.mp4",
            OpenTarget.normalize("/storage/1A2B-3C4D/movie.mp4"),
        )
    }

    @Test
    fun `归一化_file协议先还原路径再转换`() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.pdf",
            OpenTarget.normalize("file:///sdcard/Download/x.pdf"),
        )
    }

    @Test
    fun `归一化_网址原样放行`() {
        val url = "https://www.example.com/news?id=1"
        assertEquals(url, OpenTarget.normalize(url))
    }

    @Test
    fun `归一化_未知卷的绝对路径原样返回不硬凑`() {
        assertEquals("/proc/self/status", OpenTarget.normalize("/proc/self/status"))
    }

    @Test
    fun `归一化_空串返回空串`() {
        assertEquals("", OpenTarget.normalize(""))
    }

    // ==================== 三、扩展名 → MIME ====================

    @Test
    fun `类型推断_常见文档扩展名`() {
        assertEquals("application/pdf", OpenTarget.mimeOf("/sdcard/a.pdf"))
        assertEquals("application/vnd.ms-powerpoint", OpenTarget.mimeOf("/sdcard/季度汇报.ppt"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            OpenTarget.mimeOf("/sdcard/a.pptx"),
        )
        assertEquals("application/msword", OpenTarget.mimeOf("/sdcard/a.doc"))
        assertEquals("application/vnd.ms-excel", OpenTarget.mimeOf("/sdcard/a.xls"))
    }

    @Test
    fun `类型推断_音视频与图片`() {
        assertEquals("image/jpeg", OpenTarget.mimeOf("/sdcard/a.JPG"))
        assertEquals("video/mp4", OpenTarget.mimeOf("/sdcard/a.mp4"))
        assertEquals("audio/mpeg", OpenTarget.mimeOf("/sdcard/a.mp3"))
    }

    @Test
    fun `类型推断_忽略查询串与锚点`() {
        // 扩展名在路径里：查询串/锚点先剥掉，取最后一段路径的扩展名
        assertEquals("application/pdf", OpenTarget.mimeOf("https://x.com/download/a.pdf?v=2"))
        assertEquals("text/plain", OpenTarget.mimeOf("https://x.com/read/a.txt#L10"))
    }

    @Test
    fun `类型推断_扩展名只在查询串里时返回null`() {
        // 路径本身没有扩展名（?file=a.pdf 只是参数），不能拿参数当文件名
        assertNull(OpenTarget.mimeOf("https://x.com/get?file=a.pdf"))
    }

    @Test
    fun `类型推断_网址主机名里的点不算扩展名`() {
        // "example.com" 的 .com 不是扩展名——取的是最后一段 "com"，不在表内
        assertNull(OpenTarget.mimeOf("https://www.example.com"))
    }

    @Test
    fun `类型推断_未知扩展名返回null不硬编`() {
        assertNull(OpenTarget.mimeOf("/sdcard/a.xyz"))
        assertNull(OpenTarget.mimeOf("/sdcard/noext"))
    }
}