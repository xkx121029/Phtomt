package com.phoneagent.engine.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 危险词表的行为契约：命中要问、可逆的不要问。
 * 这张表是确认门在「没走语义意图」那条路上的唯一防线，误伤会卡住正常操作、漏判等于门形同虚设，
 * 所以正反两面都钉住。
 */
class DangerousTargetWordsTest {

    @Test
    fun `不可逆标签命中`() {
        assertEquals("删除", DangerousTargetWords.hit("删除"))
        assertEquals("删除", DangerousTargetWords.hit("删除并退出"))
        assertEquals("删除", DangerousTargetWords.hit("永久删除"))
        assertEquals("卸载", DangerousTargetWords.hit("卸载应用"))
        assertEquals("付款", DangerousTargetWords.hit("立即付款"))
        assertEquals("确认支付", DangerousTargetWords.hit("确认支付"))
        assertEquals("转账", DangerousTargetWords.hit("转账 100 元"))
        assertEquals("提交订单", DangerousTargetWords.hit("提交订单"))
        assertEquals("注销", DangerousTargetWords.hit("注销账号"))
        assertEquals("格式化", DangerousTargetWords.hit("格式化存储卡"))
    }

    @Test
    fun `可逆与导航标签不命中`() {
        assertNull(DangerousTargetWords.hit("取消"))
        assertNull(DangerousTargetWords.hit("关闭"))
        assertNull(DangerousTargetWords.hit("返回"))
        assertNull(DangerousTargetWords.hit("退出登录"))
        assertNull(DangerousTargetWords.hit("设置"))
        // 「支付方式」是设置页的导航入口，不是付款；笼统的「支付」不入表就是为了不误伤它
        assertNull(DangerousTargetWords.hit("支付方式"))
        assertNull(DangerousTargetWords.hit(""))
        assertNull(DangerousTargetWords.hit("   "))
        assertNull(DangerousTargetWords.hit(null))
    }

    @Test
    fun `批量抹除默认命中`() {
        assertEquals("清空", DangerousTargetWords.hit("清空"))
        assertEquals("清空", DangerousTargetWords.hit("清空聊天记录"))
        assertEquals("清空", DangerousTargetWords.hit("清空回收站"))
        assertEquals("清除", DangerousTargetWords.hit("清除全部数据"))
    }

    @Test
    fun `批量抹除同现可逆语境词时放行`() {
        assertNull(DangerousTargetWords.hit("清空输入框"))
        assertNull(DangerousTargetWords.hit("清除筛选条件"))
        assertNull(DangerousTargetWords.hit("清除草稿"))
    }

    /**
     * 提交类中文词：发出去 / 订单成立就收不回，和删除同级。
     * 审计缺口：「发送 / 发布 / 结算」原先一个都不在表里，模型用 tap 点「发送」不会被拦。
     */
    @Test
    fun `提交类中文标签命中`() {
        assertEquals("发送", DangerousTargetWords.hit("发送"))
        assertEquals("发送", DangerousTargetWords.hit("发送消息"))
        assertEquals("发布", DangerousTargetWords.hit("发布"))
        assertEquals("结算", DangerousTargetWords.hit("去结算"))
        // 「下单」子串已覆盖「确认下单 / 立即下单」，不重复登记
        assertEquals("下单", DangerousTargetWords.hit("确认下单"))
        assertEquals("下单", DangerousTargetWords.hit("立即下单"))
        assertEquals("购买", DangerousTargetWords.hit("购买"))
        // 「发送草稿箱」不豁免：草稿发出去就进了对话，和别人手滑发一样收不回
        assertEquals("发送", DangerousTargetWords.hit("发送草稿箱"))
    }

    @Test
    fun `提交类标签同现导航语境词时放行`() {
        assertNull(DangerousTargetWords.hit("发送记录"))
        assertNull(DangerousTargetWords.hit("发送状态"))
        assertNull(DangerousTargetWords.hit("结算中心"))
        assertNull(DangerousTargetWords.hit("结算明细"))
        assertNull(DangerousTargetWords.hit("购买记录"))
        assertNull(DangerousTargetWords.hit("发布设置"))
        assertNull(DangerousTargetWords.hit("发送管理"))
    }

    /**
     * 英文控件名：`by=id` 时 AI 给的是英文 id（send / checkout），中文表看不见。
     * 按词边界匹配，所以 sender / postcode / payment 这类不含独立词的标签仍放行。
     */
    @Test
    fun `英文控件名命中且按词边界`() {
        assertEquals("send", DangerousTargetWords.hit("send"))
        assertEquals("send", DangerousTargetWords.hit("Send message"))
        assertEquals("send", DangerousTargetWords.hit("SEND"))
        assertEquals("post", DangerousTargetWords.hit("Post"))
        assertEquals("publish", DangerousTargetWords.hit("publish now"))
        assertEquals("pay", DangerousTargetWords.hit("Pay now"))
        assertEquals("checkout", DangerousTargetWords.hit("Checkout"))
        assertEquals("confirm", DangerousTargetWords.hit("Confirm order"))
        assertEquals("delete", DangerousTargetWords.hit("Delete"))
        assertEquals("purchase", DangerousTargetWords.hit("Purchase"))
    }

    @Test
    fun `英文词边界与导航语境不误伤`() {
        assertNull(DangerousTargetWords.hit("sender"))
        assertNull(DangerousTargetWords.hit("resend"))
        assertNull(DangerousTargetWords.hit("postcode"))
        assertNull(DangerousTargetWords.hit("payment"))
        assertNull(DangerousTargetWords.hit("confirmed"))
        assertNull(DangerousTargetWords.hit("Post settings"))
        assertNull(DangerousTargetWords.hit("Send history"))
        assertNull(DangerousTargetWords.hit("Checkout detail"))
    }
}
