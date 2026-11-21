package com.phoneagent.domain.rules

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

    /**
     * 英文边界：左词边界 + "右邻不是小写字母"。
     *
     * 这两条各有出处：左边界挡 `resend`→`send`、`border`→`order`；右下界挡 `sender`→`send`、
     * `postcode`→`post`、`confirmed`→`confirm`（英文界面上这些是纯导航标签，误拦等于每次点击都弹门）。
     * 大写开新词不算延伸，所以驼峰命名 `deleteComment` / `DeleteAccountButton` 仍然照拦——
     * 那是 `by=id` 场景里唯一还能看见危险的证据。
     */
    @Test
    fun `英文边界_同词延伸不误伤_驼峰命名照拦`() {
        assertNull(DangerousTargetWords.hit("sender"))
        assertNull(DangerousTargetWords.hit("resend"))
        assertNull(DangerousTargetWords.hit("postcode"))
        assertNull(DangerousTargetWords.hit("confirmed"))
        assertEquals("delete", DangerousTargetWords.hit("deleteComment"))
        assertEquals("delete", DangerousTargetWords.hit("DeleteAccountButton"))
        assertEquals("send", DangerousTargetWords.hit("SendMessage"))
    }

    @Test
    fun `英文导航语境与中文提交档同口径豁免`() {
        assertNull(DangerousTargetWords.hit("Post settings"))
        assertNull(DangerousTargetWords.hit("Send history"))
        assertNull(DangerousTargetWords.hit("Checkout detail"))
    }

    /**
     * `payment` 是刻意收进 [DangerousTargetWords.IRREVERSIBLE_EN] 的：英文没有中文那种
     * 「不收笼统的支付以避开『支付方式』」的收窄手段（`Payment method` 确实以 payment 打头），
     * 安全方向上宁可多问一句。回报的词是 `payment` 而不是 `pay`：右下界拦下了「pay + 小写后缀」，
     * 所以 `pay` 那条规则不会抢在 `payment` 前面命中。
     */
    @Test
    fun `payment开头照拦_回报更具体的词`() {
        assertEquals("payment", DangerousTargetWords.hit("payment"))
        assertEquals("payment", DangerousTargetWords.hit("Payment method"))
        assertEquals("pay", DangerousTargetWords.hit("Pay now"))
    }
}
