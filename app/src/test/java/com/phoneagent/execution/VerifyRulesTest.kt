package com.phoneagent.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.engine.execution.VerifyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行结果后处理判据测试。
 *
 * 这些用例锁住的是"失败之后怎么办"——重试还是交给 AI 重新决策。拆分前它们内联在 600 行的
 * 主循环里，判据依赖 `verify.reason` 的文案，改一句提示词就可能悄悄改变重试行为且无人察觉。
 */
class VerifyRulesTest {

    // ---- 结构性错误 ----

    @Test
    fun isStructuralError_端侧校验类错误_全部判为不可重试() {
        // 这六条文案分别来自 shell 命令表与自由模式的端点白名单，都是"重跑一遍结果不会变"的错误
        assertTrue(VerifyRules.isStructuralError("未知 shell 命令: xxx"))
        assertTrue(VerifyRules.isStructuralError("shell 命令为空"))
        assertTrue(VerifyRules.isStructuralError("参数无效"))
        assertTrue(VerifyRules.isStructuralError("未知无障碍端点: click_node"))
        assertTrue(VerifyRules.isStructuralError("缺少参数: target"))
        assertTrue(VerifyRules.isStructuralError("缺少 endpoint"))
    }

    @Test
    fun isStructuralError_语义类失败_判为可重试() {
        // 页面未变化属于"这次没成，可能页面卡了一下"，重试有意义，不能归到结构性错误
        assertFalse(VerifyRules.isStructuralError("页面未变化，动作可能未生效"))
        assertFalse(VerifyRules.isStructuralError("动作执行返回失败"))
        assertFalse(VerifyRules.isStructuralError(""))
    }

    // ---- 点击类 ----

    @Test
    fun isClickLike_四种点击_判为点击类() {
        assertTrue(VerifyRules.isClickLike(ActionType.CLICK))
        assertTrue(VerifyRules.isClickLike(ActionType.TAP))
        assertTrue(VerifyRules.isClickLike(ActionType.LONG_CLICK))
        assertTrue(VerifyRules.isClickLike(ActionType.LONG_PRESS))
    }

    @Test
    fun isClickLike_非点击动作_判为非点击类() {
        assertFalse(VerifyRules.isClickLike(ActionType.SWIPE))
        assertFalse(VerifyRules.isClickLike(ActionType.SCROLL))
        assertFalse(VerifyRules.isClickLike(ActionType.TYPE_TEXT))
    }

    // ---- 重试判据 ----

    @Test
    fun shouldRetry_首次失败且可重试_语义类动作值得重试() {
        assertTrue(
            VerifyRules.shouldRetry(ActionType.SCROLL, "页面未变化，动作可能未生效", attempt = 1, active = true),
        )
    }

    @Test
    fun shouldRetry_已达上限_不再重试() {
        // 上限是"含首次执行的总尝试次数"，attempt 到了上限就不再重试
        val last = VerifyRules.MAX_ATTEMPTS - 1
        assertTrue(VerifyRules.shouldRetry(ActionType.SCROLL, "页面未变化", attempt = last, active = true))
        assertFalse(
            VerifyRules.shouldRetry(ActionType.SCROLL, "页面未变化", attempt = VerifyRules.MAX_ATTEMPTS, active = true),
        )
    }

    @Test
    fun shouldRetry_结构性错误_即使未达上限也不重试() {
        assertFalse(
            VerifyRules.shouldRetry(ActionType.SHELL, "未知 shell 命令: xxx", attempt = 1, active = true),
        )
        assertFalse(
            VerifyRules.shouldRetry(ActionType.A11Y_CALL, "缺少参数: target", attempt = 1, active = true),
        )
    }

    @Test
    fun shouldRetry_点击类_交给执行器内部重试而不是外层() {
        // ClickRunner 内部已逐级穷尽定位方式，外层重跑只会重复同一串动作（还可能重复副作用）
        assertFalse(VerifyRules.shouldRetry(ActionType.CLICK, "页面未变化", attempt = 1, active = true))
        assertFalse(VerifyRules.shouldRetry(ActionType.LONG_PRESS, "页面未变化", attempt = 1, active = true))
    }

    @Test
    fun shouldRetry_任务已取消_不再重试() {
        assertFalse(VerifyRules.shouldRetry(ActionType.SCROLL, "页面未变化", attempt = 1, active = false))
    }

    // ---- 留档 ----

    @Test
    fun historyStatus_按验证结果给出确认状态() {
        assertEquals(VerifyRules.STATUS_VERIFIED, VerifyRules.historyStatus(true))
        assertEquals(VerifyRules.STATUS_UNVERIFIED, VerifyRules.historyStatus(false))
    }

    @Test
    fun actionDescription_已验证_优先用AI的reasoning() {
        val action = AgentAction(type = ActionType.TAP, reasoning = "打开设置入口", reason = "端侧理由")

        val text = VerifyRules.actionDescription(action, verified = true, label = "点按")

        assertEquals("✅ 已生效 · 点按\n打开设置入口", text)
    }

    @Test
    fun actionDescription_未验证且无reasoning_退回端侧reason() {
        val action = AgentAction(type = ActionType.SCROLL, reason = "页面未变化")

        val text = VerifyRules.actionDescription(action, verified = false, label = "滚动查找")

        assertEquals("⚠️ 待确认 · 滚动查找\n页面未变化", text)
    }

    @Test
    fun actionDescription_空白理由_只输出状态与动作名() {
        // 空白串留着会让说明多出一个空行，看着像"理由丢了"
        val action = AgentAction(type = ActionType.BACK, reasoning = "   ", reason = "  ")

        val text = VerifyRules.actionDescription(action, verified = true, label = "返回")

        assertEquals("✅ 已生效 · 返回", text)
    }
}
