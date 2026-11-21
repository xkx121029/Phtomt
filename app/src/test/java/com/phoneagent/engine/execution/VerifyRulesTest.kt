package com.phoneagent.engine.execution

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

    // ---- 交付状态 ----

    @Test
    fun delivery_由结构化字段推导_与文案无关() {
        // 文案改成什么都不会影响交付判定：只看 success / notDelivered
        assertEquals(
            Delivery.EFFECTIVE,
            VerifyResult(true, "随便写的成功理由", "", "").delivery,
        )
        assertEquals(
            Delivery.NOT_DELIVERED,
            VerifyResult(false, "随便写的失败理由", "", "", notDelivered = true).delivery,
        )
        assertEquals(
            Delivery.DELIVERED_NO_EFFECT,
            VerifyResult(false, "随便写的失败理由", "", "").delivery,
        )
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
            VerifyRules.shouldRetry(
                ActionType.SCROLL, Delivery.NOT_DELIVERED, structural = false,
                attempt = 1, active = true,
            ),
        )
    }

    @Test
    fun shouldRetry_已交付但未确认_绝不重跑() {
        // 闹钟降级链整条重跑两次 = 闹钟列表里两条一样的条目，属于重复副作用
        assertFalse(
            VerifyRules.shouldRetry(
                ActionType.SCROLL, Delivery.DELIVERED_NO_EFFECT, structural = false,
                attempt = 1, active = true,
            ),
        )
    }

    @Test
    fun shouldRetry_已达上限_不再重试() {
        // 上限是"含首次执行的总尝试次数"，attempt 到了上限就不再重试
        val last = VerifyRules.MAX_ATTEMPTS - 1
        assertTrue(
            VerifyRules.shouldRetry(
                ActionType.SCROLL, Delivery.NOT_DELIVERED, structural = false,
                attempt = last, active = true,
            ),
        )
        assertFalse(
            VerifyRules.shouldRetry(
                ActionType.SCROLL, Delivery.NOT_DELIVERED, structural = false,
                attempt = VerifyRules.MAX_ATTEMPTS, active = true,
            ),
        )
    }

    @Test
    fun shouldRetry_结构性错误_即使未达上限也不重试() {
        assertFalse(
            VerifyRules.shouldRetry(
                ActionType.SHELL, Delivery.NOT_DELIVERED, structural = true,
                attempt = 1, active = true,
            ),
        )
        assertFalse(
            VerifyRules.shouldRetry(
                ActionType.A11Y_CALL, Delivery.NOT_DELIVERED, structural = true,
                attempt = 1, active = true,
            ),
        )
    }

    @Test
    fun shouldRetry_点击类_交给执行器内部重试而不是外层() {
        // ClickRunner 内部已逐级穷尽定位方式，外层重跑只会重复同一串动作（还可能重复副作用）
        assertFalse(
            VerifyRules.shouldRetry(
                ActionType.CLICK, Delivery.NOT_DELIVERED, structural = false,
                attempt = 1, active = true,
            ),
        )
        assertFalse(
            VerifyRules.shouldRetry(
                ActionType.LONG_PRESS, Delivery.NOT_DELIVERED, structural = false,
                attempt = 1, active = true,
            ),
        )
    }

    @Test
    fun shouldRetry_任务已取消_不再重试() {
        assertFalse(
            VerifyRules.shouldRetry(
                ActionType.SCROLL, Delivery.NOT_DELIVERED, structural = false,
                attempt = 1, active = false,
            ),
        )
    }

    // ---- 失败回注 ----

    @Test
    fun failureHint_未送达_允许换通道或落点再试() {
        val hint = VerifyRules.failureHint(Delivery.NOT_DELIVERED, "手势被系统拒绝", structural = false)

        assertTrue(hint.contains("没有送达设备"))
        assertTrue(hint.contains("换一条执行通道"))
    }

    @Test
    fun failureHint_已送达未生效_要求换入口而不是重放() {
        val hint = VerifyRules.failureHint(Delivery.DELIVERED_NO_EFFECT, "页面与控件状态均无变化", structural = false)

        assertTrue(hint.contains("已经送达"))
        assertTrue(hint.contains("不要原地重复"))
    }

    @Test
    fun failureHint_结构性错误_不再叠加提示() {
        // 结构性错误已有 ExecutionFeedback 的纠正文案，这里再叠一层只会稀释重点
        assertEquals("", VerifyRules.failureHint(Delivery.NOT_DELIVERED, "未知 shell 命令", structural = true))
        assertEquals("", VerifyRules.failureHint(Delivery.EFFECTIVE, "页面已变化", structural = false))
    }

    // ---- 步后等待 ----

    @Test
    fun stepSettleMs_不碰界面的动作_只等极短时间() {
        // 说一句 / 记一条 / 查设备信息都不会让页面变化，陪着空等 400ms 是纯浪费
        assertEquals(VerifyRules.NO_SETTLE_MS, VerifyRules.stepSettleMs(ActionType.SAY))
        assertEquals(VerifyRules.NO_SETTLE_MS, VerifyRules.stepSettleMs(ActionType.REMEMBER))
        assertEquals(VerifyRules.NO_SETTLE_MS, VerifyRules.stepSettleMs(ActionType.DEVICE_QUERY))
    }

    @Test
    fun stepSettleMs_改变界面的动作_保留完整等待() {
        // 动画/转场没走完就去观察，会读到"画了一半"的页面，宁可多等一会
        assertEquals(VerifyRules.SETTLE_MS, VerifyRules.stepSettleMs(ActionType.CLICK))
        assertEquals(VerifyRules.SETTLE_MS, VerifyRules.stepSettleMs(ActionType.SWIPE))
        assertEquals(VerifyRules.SETTLE_MS, VerifyRules.stepSettleMs(ActionType.TYPE_TEXT))
        assertEquals(VerifyRules.SETTLE_MS, VerifyRules.stepSettleMs(ActionType.SHELL))
        // 未知类型保守处理：走完整等待，不能因为不认识就抢跑
        assertEquals(VerifyRules.SETTLE_MS, VerifyRules.stepSettleMs("unknown_type"))
    }

    // ---- 留档 ----

    @Test
    fun historyStatus_按交付结论给出三态() {
        assertEquals(VerifyRules.STATUS_VERIFIED, VerifyRules.historyStatus(Delivery.EFFECTIVE))
        // 读不到页面 = 未确认（⚠️），不能与"看到了没变化"混为一谈
        assertEquals(VerifyRules.STATUS_UNVERIFIED, VerifyRules.historyStatus(Delivery.DELIVERED_UNCONFIRMED))
        // 读到了页面、就是没变化 = 未见生效（❌ 已交付但未见生效）
        assertEquals(VerifyRules.STATUS_NO_EFFECT, VerifyRules.historyStatus(Delivery.DELIVERED_NO_EFFECT))
        // 压根没交付：说"已交付"才是错话，落回未生效
        assertEquals(VerifyRules.STATUS_FAILED, VerifyRules.historyStatus(Delivery.NOT_DELIVERED))
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

    @Test
    fun deviceOpsStatus_成功为已验证() {
        assertEquals(VerifyRules.STATUS_VERIFIED, VerifyRules.deviceOpsStatus(ok = true, dispatched = false))
    }

    @Test
    fun deviceOpsStatus_已下发未回读为未确认() {
        // 命令交给系统了，只是读不到回执：不能吓唬用户说"没执行"
        assertEquals(VerifyRules.STATUS_UNVERIFIED, VerifyRules.deviceOpsStatus(ok = false, dispatched = true))
    }

    @Test
    fun deviceOpsStatus_根本没执行为failed() {
        // StepTexts 用 STATUS_FAILED 渲染 ❌（通道不可用/参数非法这类用户要改配置的情况）
        assertEquals(VerifyRules.STATUS_FAILED, VerifyRules.deviceOpsStatus(ok = false, dispatched = false))
    }
}
