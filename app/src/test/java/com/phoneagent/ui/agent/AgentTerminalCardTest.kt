package com.phoneagent.ui.agent

import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.TaskOutcome
import com.phoneagent.domain.model.TaskStatus
import com.phoneagent.engine.PlanPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终态卡单测：任务结束后主界面那张卡说哪句话、给哪个出口（UX-02、HAPP-58）。
 *
 * 只关心卡片的措辞与出口，不关心任务流怎么拼装，所以从 [AgentTimelineMapperTest] 拆出来：
 * 拼装（工具链、气泡、记忆卡片）与结论（一张终态卡）本来就该各自长大。
 */
class AgentTerminalCardTest {

    /** 铺一次终态，返回整条任务流：个别用例要同时看终态卡与标题 chip */
    private fun timeline(
        lastOutcome: TaskOutcome,
        a11yEnabled: Boolean = true,
        configProblem: String? = null,
    ): List<AgentTimelineItem> = AgentTimelineMapper.build(
        submittedTask = "",
        state = AgentState(isRunning = false, task = "打开相册", phase = AgentState.Phase.IDLE),
        planPhase = PlanPhase.Idle,
        planText = "",
        traces = emptyList(),
        history = emptyList(),
        queue = emptyList(),
        needsUser = false,
        needsUserReason = "",
        a11yEnabled = a11yEnabled,
        lastOutcome = lastOutcome,
        configProblem = configProblem,
    )

    private fun List<AgentTimelineItem>.card(): AgentTimelineItem.Terminal =
        filterIsInstance<AgentTimelineItem.Terminal>().single()

    @Test
    fun `任务失败后终态卡留在主界面而不是退回空态`() {
        // 失败于决策阶段：一条执行痕迹都没有——正是空态成立的条件，历史上界面就此变成「还没有任务」
        val outcome = TaskOutcome(
            status = TaskStatus.FAILED,
            summary = "决策链路连续异常",
            failureReason = "AI 决策链路持续异常，已停止任务",
            failureKind = AgentState.FailureKind.DECISION,
            atMillis = 500L,
        )
        val items = timeline(outcome)
        val card = items.card()
        assertEquals(TaskStatus.FAILED, card.status)
        assertTrue("失败卡必须给至少一个可执行出口", card.actions.isNotEmpty())
        assertTrue("原因要透出引擎给的具体说法", card.cause.contains("异常"))
        // 标题 chip 必须自己带状态，否则它与终态卡同屏时会被读成两个任务
        assertEquals(TaskStatus.FAILED, items.filterIsInstance<AgentTimelineItem.UserTask>().single().status)
    }

    @Test
    fun `无障碍没开导致的失败，卡片标题与出口都指向权限`() {
        val outcome = TaskOutcome(
            status = TaskStatus.FAILED,
            summary = "决策链路连续异常",
            failureKind = AgentState.FailureKind.DECISION,
            atMillis = 500L,
        )
        val card = timeline(outcome, a11yEnabled = false).card()
        assertEquals("无障碍没开，AI 看不见屏幕", card.headline)
        assertTrue("主出口必须能直接去开授权", card.actions.contains(TerminalAction.OPEN_PERMISSIONS))
    }

    @Test
    fun `用户停止的终态是已停止且仍可重试`() {
        val outcome = TaskOutcome(
            status = TaskStatus.ABORTED,
            summary = "已按你的要求停止任务",
            failureKind = AgentState.FailureKind.USER_STOPPED,
            atMillis = 500L,
        )
        val card = timeline(outcome).card()
        assertEquals(TaskStatus.ABORTED, card.status)
        assertEquals("已停止", card.headline)
        assertEquals(listOf(TerminalAction.RETRY), card.actions)
    }

    @Test
    fun `模型没配置时终态卡的出口是去配置`() {
        val outcome = TaskOutcome(
            status = TaskStatus.FAILED,
            summary = "任务异常终止：IllegalStateException",
            atMillis = 500L,
        )
        val card = timeline(outcome, configProblem = "API Key 未填写").card()
        assertEquals(listOf(TerminalAction.OPEN_SETTINGS), card.actions)
        assertEquals("还没配置模型", card.headline)
    }

    @Test
    fun `配置也没配好又不便时，终态卡先说是配置，别把无障碍写成主因`() {
        // HAPP-58 在模拟器上撞到的状态：无障碍没开 + 上次连接测试失败（configProblem 非空）。
        // 那张卡的标题写「无障碍没开，AI 看不见屏幕」，唯一的出口却是「去配置」——
        // 一句话指了两个方向。配置缺失是硬前置，标题必须跟着出口走
        val outcome = TaskOutcome(
            status = TaskStatus.FAILED,
            summary = "上次连接测试失败：HTTP 401",
            failureKind = AgentState.FailureKind.CONFIG,
            atMillis = 500L,
        )
        val card = timeline(outcome, a11yEnabled = false, configProblem = "上次连接测试失败：HTTP 401").card()
        assertEquals("还没配置模型", card.headline)
        assertEquals(listOf(TerminalAction.OPEN_SETTINGS), card.actions)
        assertTrue("原因要指向配置", card.cause.contains("401"))
    }
}
