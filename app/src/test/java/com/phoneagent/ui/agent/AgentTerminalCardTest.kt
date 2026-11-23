package com.phoneagent.ui.agent

import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.TaskOutcome
import com.phoneagent.domain.model.TaskStatus
import com.phoneagent.engine.PlanPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `配置缺失的终态卡同样给出查看原始消息入口`() {
        // HAPP-73 在 Pad 7 上量到的就是这张卡：原因行本身就是引擎原句（cause == detail），
        // 旧判据 `detail != cause` 因此把展开入口整块摘掉，dump 里「查看原始消息」出现 0 次。
        // HAPP-52 验收表 #3 要求「原因 + 可执行动作 + 原始消息可展开」三条每一档都齐
        val raw = "还没有填写 API Key。请到「设置 → 模型与接入」填写后再下达任务。"
        val outcome = TaskOutcome(
            status = TaskStatus.FAILED,
            summary = raw,
            failureReason = raw,
            failureKind = AgentState.FailureKind.CONFIG,
            atMillis = 500L,
        )
        val card = timeline(outcome, configProblem = raw).card()
        assertEquals("配置这一档的原因行就是引擎原句", card.detail, card.cause)
        assertTrue("原因与原始消息相同也要能展开核对", AgentTerminal.showsRawMessage(card.detail))
    }

    @Test
    fun `每种结束方式都给出查看原始消息入口`() {
        val raw = "AI 决策链路持续异常，已停止任务"
        listOf(TaskStatus.FAILED, TaskStatus.ABORTED, TaskStatus.NOT_ACHIEVED).forEach { status ->
            val outcome = TaskOutcome(
                status = status,
                summary = raw,
                failureReason = raw,
                failureKind = AgentState.FailureKind.UNKNOWN,
                atMillis = 500L,
            )
            val card = timeline(outcome).card()
            assertTrue("$status 的终态卡缺原始消息入口", AgentTerminal.showsRawMessage(card.detail))
        }
    }

    @Test
    fun `引擎没给原始消息时不摆一个展开后空白的入口`() {
        assertFalse(AgentTerminal.showsRawMessage(""))
        assertFalse(AgentTerminal.showsRawMessage("   "))
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

    // ==================== 规划失败卡（HAPP-79） ====================

    /** 铺一次规划失败：走到这张卡的入口就是 [PlanPhase.Error]（外部入口经 startPlanning 走到） */
    private fun planFailed(message: String, configProblem: Boolean = false): AgentTimelineItem.PlanFailed =
        AgentTimelineMapper.build(
            submittedTask = "打开相册",
            state = AgentState(isRunning = false, task = "打开相册", phase = AgentState.Phase.IDLE),
            planPhase = PlanPhase.Error(message, configProblem = configProblem),
            planText = "",
            traces = emptyList(),
            history = emptyList(),
            queue = emptyList(),
            needsUser = false,
            needsUserReason = "",
            a11yEnabled = true,
        ).filterIsInstance<AgentTimelineItem.PlanFailed>().single()

    @Test
    fun `规划失败卡必须给出出口与可展开的原始消息`() {
        // 引擎原句（解析失败）是排障用的，正文说人话，原文留在「查看原始消息」里
        val item = planFailed("规划解析失败：Unexpected token at offset 12")
        assertEquals("引擎原始消息要带进卡片", "规划解析失败：Unexpected token at offset 12", item.message)
        assertEquals("规划本身失败时重试就是出路", listOf(TerminalAction.RETRY), AgentTerminal.planFailedActions(item.configProblem))
        assertTrue("原始消息必须可展开", AgentTerminal.planFailedShowsRawMessage(item.message))
        val cause = AgentTerminal.planFailedCause(item.message, item.configProblem)
        assertTrue("原因行要说人话，而不是把解析报错原样摆出来", !cause.contains("Unexpected token"))
        assertTrue("原因行仍要指出去路", cause.contains("再试一次"))
    }

    @Test
    fun `配置缺失导致的规划失败，出口是去配置而不是重试`() {
        // PlanningFlow.startPlanning 会在起跑前拦下并写成 PlanPhase.Error(configProblem = true)：
        // 这种情况下重试还是同一个结果
        val item = planFailed("API Key 未填写", configProblem = true)
        assertTrue("配置缺失的结构化标记要一路传到卡片", item.configProblem)
        assertEquals(
            listOf(TerminalAction.OPEN_SETTINGS),
            AgentTerminal.planFailedActions(item.configProblem),
        )
        val cause = AgentTerminal.planFailedCause(item.message, item.configProblem)
        assertTrue("原因行要复用引擎那句配置说明", cause.contains("API Key"))
    }

    @Test
    fun `规划失败卡在引擎没给原文时不留空展开区`() {
        val cause = AgentTerminal.planFailedCause("", configProblem = false)
        assertTrue("没有原文也要有一句能照做的原因", cause.isNotBlank())
        assertTrue("没有原文就不出「查看原始消息」", !AgentTerminal.planFailedShowsRawMessage(""))
    }
}
