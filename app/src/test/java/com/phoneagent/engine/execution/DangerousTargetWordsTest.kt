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
}
