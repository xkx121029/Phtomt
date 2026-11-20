package com.phoneagent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证码类脱敏回归。
 *
 * 修的是同一个病根：脱敏只在「关键词和数字出现在同一串」时才生效。
 * - 引导词原来只认「验证码」，银行短信模板普遍写「校验码 / 动态码」→ 扩表
 * - 验证码常单独成一个控件，元素自身既无引导词也无 11 位数字 → 整页上下文判定
 * - 短信/银行推送把引导词折进标题，正文只剩数字 → 整条遮蔽
 */
class DataSanitizerPageCodeTest {

    @Test
    fun `校验码与动态码后也掩码`() {
        assertEquals("校验码 ******，请勿转发", DataSanitizer.sanitize("校验码 884213，请勿转发"))
        assertEquals("动态密码：******", DataSanitizer.sanitize("动态密码：663912"))
        assertEquals("Your code: ******", DataSanitizer.sanitize("Your code: 778812"))
    }

    @Test
    fun `英文 code 只在整词时当引导词`() {
        // barcode / decode 里也含 code，把它们当引导词会把无关数字一起掩码
        assertEquals("barcode 1234567", DataSanitizer.sanitize("barcode 1234567"))
        assertEquals("decode 1234567", DataSanitizer.sanitize("decode 1234567"))
    }

    @Test
    fun `整页上下文里单独成元素的验证码被掩码`() {
        assertEquals(
            listOf("请输入验证码", "******"),
            DataSanitizer.sanitizePageLabels(listOf("请输入验证码", "123456")),
        )
        // 上下文也可以来自密码框
        assertEquals(
            listOf("请输入密码", "******"),
            DataSanitizer.sanitizePageLabels(listOf("请输入密码", "123456"), hasPasswordField = true),
        )
    }

    @Test
    fun `没有验证码上下文时不把普通数字当验证码`() {
        assertEquals(
            listOf("订单号", "4829"),
            DataSanitizer.sanitizePageLabels(listOf("订单号", "4829")),
        )
    }

    @Test
    fun `整页文本脱敏覆盖跨元素的验证码`() {
        val page = """
            当前前台应用: com.cmbchina
              [#0] android.widget.TextView(Text) label="请输入验证码" center=(540,900)
              [#1] android.widget.TextView(EditText) label="123456" center=(540,1000)
        """.trimIndent()
        val out = DataSanitizer.sanitizePage(page)
        assertFalse("整页文本仍含明文验证码：\n$out", out.contains("123456"))
        assertTrue(out.contains("******"))
    }

    @Test
    fun `短信银行类来源的纯数字正文整条遮蔽`() {
        assertEquals("******", DataSanitizer.sanitizeNotificationBody("com.android.mms", "884213"))
        assertEquals("******", DataSanitizer.sanitizeNotificationBody("com.cmbchina", " 884213 "))
        // 非短信/银行来源不按验证码处理：6 位数字可能是订单号、金额
        assertEquals("884213", DataSanitizer.sanitizeNotificationBody("com.taobao.taobao", "884213"))
        // 正文自带引导词时由 sanitize 负责
        assertEquals(
            "校验码 ******，请勿转发",
            DataSanitizer.sanitizeNotificationBody("com.cmbchina", "校验码 884213，请勿转发"),
        )
    }
}
