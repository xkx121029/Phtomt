package com.phoneagent.engine.execution

import com.phoneagent.engine.decision.DecisionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级策略表测试：四类无人值守故障都要有「条件 → 兜底 → 失败判据」，
 * 且给用户的中文必须点名现在用的是什么方式。
 */
class DegradationPolicyTest {

    @Test
    fun 四类故障都有完整策略行() {
        assertEquals(
            DegradationTrigger.values().toSet(),
            DegradationPolicy.rules.map { it.trigger }.toSet(),
        )
        DegradationPolicy.rules.forEach { rule ->
            assertTrue("${rule.trigger} 缺少生效条件", rule.condition.isNotBlank())
            assertTrue("${rule.trigger} 缺少兜底动作", rule.fallback.isNotBlank())
            assertTrue("${rule.trigger} 缺少失败判据", rule.failureCriterion.isNotBlank())
        }
    }

    @Test
    fun 通道切换文案_点名原通道与新通道() {
        val text = DegradationPolicy.notice(
            DegradationTrigger.CHANNEL_UNAVAILABLE,
            detail = "无障碍",
            channel = "内置 Shizuku",
        )
        assertTrue(text.contains("无障碍"))
        assertTrue(text.contains("内置 Shizuku"))
        assertTrue(text.contains("改用"))
        assertTrue("不能只说失败", text.contains("继续执行"))
    }

    @Test
    fun 权限缺失_有替代通道与无替代通道都要说清楚() {
        val withFallback = DegradationPolicy.notice(
            DegradationTrigger.PERMISSION_MISSING, "无障碍服务未开启", "内置 Shizuku",
        )
        assertTrue(withFallback.contains("内置 Shizuku"))

        val noFallback = DegradationPolicy.notice(
            DegradationTrigger.PERMISSION_MISSING, "通知使用权未开启", null,
        )
        assertTrue(noFallback.contains("需要你授权"))
        assertTrue(noFallback.contains("没有可替代"))
    }

    @Test
    fun 模型超时与页面卡死_说明下一步而不是停在原地() {
        val timeout = DegradationPolicy.notice(DegradationTrigger.MODEL_TIMEOUT, "45s", "等待后重试")
        assertTrue(timeout.contains("超时"))
        assertTrue(timeout.contains("下一轮会自动重试"))

        val stuck = DegradationPolicy.notice(DegradationTrigger.PAGE_STUCK, "4 次重复", "换一种做法")
        assertTrue(stuck.contains("换一种做法"))
        assertTrue(stuck.contains("征求你的意见"))
    }

    @Test
    fun 通道阶梯降级后_能直接给出用户说明() {
        val result = LadderResult(
            delivered = true,
            confirmed = true,
            channel = ChannelKind.SHELL_SHIZUKU,
            attempts = listOf(
                ChannelAttempt(ChannelKind.ACCESSIBILITY, ChannelOutcome.NOT_DELIVERED, "启动被后台限制拦截", 1200),
                ChannelAttempt(ChannelKind.SHELL_SHIZUKU, ChannelOutcome.CONFIRMED, "", 300),
            ),
            detail = "已切换",
        )
        val notice = result.userNotice()!!
        assertTrue(notice.contains("内置 Shizuku"))
        assertTrue(notice.contains("启动被后台限制拦截"))
        assertTrue(notice.contains("改用"))
    }

    @Test
    fun 未发生降级_不额外打扰用户() {
        val result = LadderResult(
            delivered = true,
            confirmed = true,
            channel = ChannelKind.ACCESSIBILITY,
            attempts = listOf(ChannelAttempt(ChannelKind.ACCESSIBILITY, ChannelOutcome.CONFIRMED, "", 200)),
            detail = "",
        )
        assertNull(result.userNotice())
    }

    @Test
    fun 全部通道不可用_说明没有候选并保留现场() {
        val result = LadderResult(
            delivered = false,
            confirmed = false,
            channel = null,
            attempts = listOf(
                ChannelAttempt(ChannelKind.ACCESSIBILITY, ChannelOutcome.NOT_DELIVERED, "节点失效", 500),
                ChannelAttempt(ChannelKind.SHELL_ADB, ChannelOutcome.NOT_DELIVERED, "无线调试未连接", 500),
            ),
            detail = "未交付",
        )
        val notice = result.userNotice()!!
        assertTrue(notice.contains("所有候选通道都不可用"))
        assertTrue(notice.contains("停止"))
    }

    @Test
    fun 决策护栏复用同一张策略表() {
        val timeout = DecisionPolicy.timeoutNotice()
        assertTrue(timeout.contains("超时"))
        assertTrue(timeout.contains("等待后重试"))

        val stuck = DecisionPolicy.pageStuckNotice(6)
        assertTrue(stuck.contains("6 次重复"))
        assertTrue(stuck.contains("换一种做法"))
    }
}
