package com.phoneagent.feature.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 通知正文脱敏回归。
 *
 * 通知正文是全应用最直接的验证码/到账信息来源（列表见 AgentNotificationListener
 * 全收 EXTRA_TITLE/EXTRA_TEXT），原样拼进决策上下文就等于明文上云。
 */
class NotificationFeedSanitizeTest {

    @Before
    fun clear() {
        // setConnected(false) 会清空缓存，避免用例之间互相看到对方的通知
        NotificationFeed.setConnected(false)
    }

    private fun seed(packageName: String, appName: String, title: String, text: String) {
        NotificationFeed.setConnected(true)
        NotificationFeed.upsert(
            NotificationFeed.Item(
                key = "$packageName/$text",
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                postedAt = 0L,
                ongoing = false,
            )
        )
    }

    @Test
    fun `决策上下文里的校验码被掩码`() {
        seed("com.cmbchina", "招商银行", "招商银行", "【招商银行】校验码 884213，请勿转发")
        val out = NotificationFeed.decisionText("com.phoneagent")
        assertTrue("输出未掩码：\n$out", out.contains("******"))
        assertFalse("输出仍含明文验证码：\n$out", out.contains("884213"))
    }

    @Test
    fun `短信类来源的纯数字正文整条遮蔽`() {
        seed("com.android.mms", "信息", "95533", "884213")
        val out = NotificationFeed.decisionText("com.phoneagent")
        assertFalse("输出仍含明文验证码：\n$out", out.contains("884213"))
    }

    @Test
    fun `notif_read 回传同样掩码`() {
        seed("com.cmbchina", "招商银行", "招商银行", "【招商银行】校验码 884213，请勿转发")
        val out = NotificationFeed.readText().orEmpty()
        assertTrue("输出未掩码：\n$out", out.contains("******"))
        assertFalse("输出仍含明文验证码：\n$out", out.contains("884213"))
    }

    @Test
    fun `本应用自己的通知不进决策上下文`() {
        seed("com.phoneagent", "手机助手", "任务进度", "已执行 3 步")
        assertEquals("", NotificationFeed.decisionText("com.phoneagent"))
    }

    @Test
    fun `监听未连接时 notif_read 返回空`() {
        NotificationFeed.setConnected(false)
        assertNull(NotificationFeed.readText())
    }
}
