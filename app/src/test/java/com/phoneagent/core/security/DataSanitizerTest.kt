package com.phoneagent.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 脱敏回归：手机号 / 身份证 / 银行卡必须掩码，其余文本必须原样返回。
 *
 * 其中「数字不足 11 位直接返回」是本轮为感知热路径加的快速通道——
 * 三条脱敏规则最短也要 11 位数字才可能命中，这里用边界样本锁住该判定，
 * 防止以后有人把阈值改错导致漏脱敏。
 */
class DataSanitizerTest {

    @Test
    fun `手机号脱敏`() {
        assertEquals("联系 138****8000 尽快回电", DataSanitizer.sanitize("联系 13800138000 尽快回电"))
    }

    @Test
    fun `身份证脱敏`() {
        assertEquals("110101********123X", DataSanitizer.sanitize("11010119900307123X"))
    }

    @Test
    fun `银行卡脱敏`() {
        assertEquals("6222****2233", DataSanitizer.sanitize("6222020200112233"))
    }

    @Test
    fun `数字不足11位时原样返回`() {
        val text = "订单号 12345 已发货，共 6 件"
        assertEquals(text, DataSanitizer.sanitize(text))
    }

    @Test
    fun `恰好11位数字但不是手机号时不误伤`() {
        // 11 位但首位不是 1[3-9]，快速通道放行后也不应被三条规则改写
        val text = "流水号 00000000000"
        assertEquals(text, DataSanitizer.sanitize(text))
    }

    @Test
    fun `API Key 在导出前被掩码`() {
        // 实测泄漏路径：设置页停着 Key → 页面文本进提示词 → 诊断报告原样导出到「下载/」
        val raw = "页面提示：https://api.example.cn/v1、sk-test0000000000000000000000000000000000000000、agnes-2.5-flash"
        val out = DataSanitizer.sanitize(raw)
        assertEquals(false, out.contains("sk-test0000000000"))
        assertEquals(true, out.contains("sk-tes****00"))
    }

    @Test
    fun `验证码只在验证码字样后被掩码`() {
        assertEquals("验证码 ******，5 分钟内有效", DataSanitizer.sanitize("验证码 482913，5 分钟内有效"))
        // 没有「验证码」上下文时不动数字，避免误伤金额与时间
        assertEquals("余额 482913 元", DataSanitizer.sanitize("余额 482913 元"))
    }
}
