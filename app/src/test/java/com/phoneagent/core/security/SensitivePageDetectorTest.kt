package com.phoneagent.core.security

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感页判定回归。
 *
 * 原实现只用包名白名单做前置闸门，而白名单里写的是 SDK 包名 "com.alipay"，
 * 支付宝正式包名 com.eg.android.AlipayGphone 里没有该子串 → 支付页从未进过只读。
 * 现在改成「白名单 ∪ 关键词黑名单」，包名只是其中一条入口。
 */
class SensitivePageDetectorTest {

    private fun el(
        index: Int,
        text: String? = null,
        editable: Boolean = false,
        isPassword: Boolean = false,
    ) = UiElement(
        index = index,
        className = if (editable) "android.widget.EditText" else "android.widget.TextView",
        type = if (editable) "EditText" else "Text",
        text = text,
        x = 0,
        y = 0,
        left = 0,
        top = 0,
        right = 10,
        bottom = 10,
        editable = editable,
        isPassword = isPassword,
    )

    private fun snapshot(pkg: String, vararg elements: UiElement) = ScreenSnapshot(
        packageName = pkg,
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
    )

    @Test
    fun `支付宝正式包名的收银台判定敏感`() {
        val page = snapshot(
            "com.eg.android.AlipayGphone",
            el(0, text = "确认支付"),
            el(1, text = "输入密码", editable = true, isPassword = true),
        )
        assertTrue(SensitivePageDetector.isSensitive(page))
    }

    @Test
    fun `金融类应用里的密码框是强信号`() {
        // 卡号/密码页往往没有「确认支付」这类词，只有密码框
        val page = snapshot("com.cmbchina", el(0, text = "请输入一卡通密码", editable = true, isPassword = true))
        assertTrue(SensitivePageDetector.isSensitive(page))
    }

    @Test
    fun `支付关键词不看包名`() {
        assertTrue(
            SensitivePageDetector.isSensitive(snapshot("com.example.shop", el(0, text = "确认支付"))),
        )
        assertTrue(
            SensitivePageDetector.isSensitive(snapshot("com.example.webview", el(0, text = "收银台"))),
        )
    }

    @Test
    fun `普通应用的普通页面不误判`() {
        assertFalse(
            SensitivePageDetector.isSensitive(snapshot("com.example.app", el(0, text = "确定"), el(1, text = "取消"))),
        )
        // 非金融类应用的登录密码框不进只读，否则任何应用的登录页都会把任务锁死
        assertFalse(
            SensitivePageDetector.isSensitive(
                snapshot("com.example.app", el(0, text = "登录", editable = true, isPassword = true)),
            ),
        )
    }

    @Test
    fun `微信聊天里的银行卡余额不误判`() {
        val page = snapshot(
            "com.tencent.mm",
            el(0, text = "我在看银行卡余额"),
            el(1, text = "转账给你了"),
        )
        assertFalse(SensitivePageDetector.isSensitive(page))
    }

    @Test
    fun `微信支付页仍判定敏感`() {
        assertTrue(
            SensitivePageDetector.isSensitive(snapshot("com.tencent.mm", el(0, text = "确认付款"))),
        )
    }
}
