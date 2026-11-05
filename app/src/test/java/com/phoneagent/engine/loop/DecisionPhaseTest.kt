package com.phoneagent.engine.loop

import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.perception.AnnotatedPage
import com.phoneagent.engine.runtime.DecisionTimeoutExhaustedException
import com.phoneagent.engine.runtime.EngineLimits.MAX_DECISION_FAILURE_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_DECISION_TIMEOUT_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_MODE_DENY_STREAK
import com.phoneagent.engine.runtime.LoopGuard
import com.phoneagent.engine.skill.SkillRunner
import com.phoneagent.domain.rules.LocalDecisionEngine
import com.phoneagent.feature.skill.SkillCompat
import com.phoneagent.feature.skill.SkillExecutionGateway
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策阶段回归（阶段 B3）：端侧优先 / 云端异常重试与熔断 / 空决策 / 技能归一化 / 动作模式门控 / 宏注入。
 *
 * 这些分支原先在主循环里各自 `continue` / `return`，迁到 DecisionPhase 后必须验证
 * 返回的 [StepOutcome] 与原文控制流等价，且会话产物（intent / fromLocal）正确交接给执行阶段。
 */
class DecisionPhaseTest {

    private class FakeHooks {
        val logs = mutableListOf<String>()
        val floating = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var decisionFatal = 0
        var modeFatal = 0
        var skillFatal = 0
        var localLoop = 0
        var noProgress = 0

        val hooks = DecisionHooks(
            log = { level: AgentLog.Level, message: String -> logs += "$level:$message" },
            pushFloating = { status: String, _: String -> floating += status },
            markDecisionFatal = { _: String -> decisionFatal++ },
            markModeFatal = { _: String -> modeFatal++ },
            markSkillFatal = { _: String -> skillFatal++ },
            notifyLocalLoop = { localLoop++ },
            notifyNoProgress = { noProgress++ },
            pageMemoryOnIntent = { _: AgentIntent -> },
        )
    }

    private class Counters {
        var decisionFailure = 0
        var skillError = 0
        var modeDeny = 0
    }

    private class Fixture(
        val localIntent: AgentIntent? = null,
        val cloud: suspend () -> AgentIntent? = { null },
        val normalized: SkillCompat.Normalized? = null,
        val mode: ActionMode = ActionMode.BALANCED,
        val macroIntent: AgentIntent? = null,
    ) {
        val hooks = FakeHooks()
        val counters = Counters()
        var cloudCalls = 0
        var visionCalls = 0
        var mcpCalls = 0
        val localTraces = mutableListOf<String>()
        var session: StepSession? = null

        val phase: DecisionPhase = run {
            val local = mockk<LocalDecisionEngine>(relaxed = true).also { l ->
                every { l.decide(any()) } returns localIntent
            }
            val runner = mockk<SkillRunner>(relaxed = true)
            coEvery { runner.invokeMcpSkill(any(), any(), any()) } answers { mcpCalls++ }
            val gateway = if (normalized == null) null else mockk<SkillExecutionGateway>(relaxed = true).also { g ->
                every { g.normalize(any()) } returns normalized
            }
            DecisionPhase(
                localDecision = local,
                loopGuard = LoopGuard(),
                skillGateway = gateway,
                skillRunner = runner,
                macro = MacroRelay(
                    active = { macroIntent != null },
                    next = { _ -> macroIntent },
                    name = { if (macroIntent != null) "测试宏" else "" },
                    dispatched = { 1 },
                    start = { },
                    abort = { },
                ),
                hooks = hooks.hooks,
                cloudDecide = { _, _, _, _, _, _, _ ->
                    cloudCalls++
                    cloud()
                },
                clearImageCache = { },
                invokeVisionAsk = { _, _, _, _, _ -> visionCalls++ },
                recordLocalTrace = { _, intent -> localTraces += intent.intent },
                decisionFailureStreak = { counters.decisionFailure },
                setDecisionFailureStreak = { counters.decisionFailure = it },
                skillErrorStreak = { counters.skillError },
                setSkillErrorStreak = { counters.skillError = it },
                modeDenyStreak = { counters.modeDeny },
                setModeDenyStreak = { counters.modeDeny = it },
                actionMode = { mode },
                completedSteps = { 1 },
            )
        }
    }

    private fun session(messages: MutableList<ChatMessageDto> = mutableListOf()): StepSession {
        val snapshot = ScreenSnapshot(packageName = "com.example", screenWidth = 1080, screenHeight = 1920)
        return StepSession(
            task = "测试任务",
            plan = null,
            step = 1,
            settingsVal = AppSettings.Settings(),
            lang = PromptLang.CN,
            messages = messages,
        ).also {
            it.snapshot = snapshot
            it.semanticSnapshot = snapshot
            it.annotated = AnnotatedPage(snapshot, "generic", "", emptyList(), "fp")
        }
    }

    @Test
    fun `端侧决策命中时不再调用云端`() = runTest {
        val tap = AgentIntent(intent = IntentType.TAP)
        val f = Fixture(localIntent = tap)
        val s = session()
        val outcome = f.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(tap, s.intent)
        assertTrue("端侧决策应标记 fromLocal", s.fromLocal)
        assertEquals(0, f.cloudCalls)
        assertEquals(listOf(IntentType.TAP), f.localTraces)
    }

    @Test
    fun `云端异常时回退本步并等待重试`() = runTest {
        val f = Fixture(cloud = { throw IllegalStateException("网络抖动") })
        val s = session()
        val outcome = f.phase.run(s)
        assertTrue(outcome is StepOutcome.Retry)
        assertEquals(1_500L, (outcome as StepOutcome.Retry).delayMs)
        assertEquals(1, f.counters.decisionFailure)
        assertTrue(f.hooks.logs.any { it.contains("云端决策异常") })
    }

    @Test
    fun `云端连续异常达到阈值时失败收尾`() = runTest {
        val f = Fixture(cloud = { throw IllegalStateException("接口不可达") })
        f.counters.decisionFailure = MAX_DECISION_FAILURE_STREAK - 1
        val s = session()
        val outcome = f.phase.run(s)
        assertTrue(outcome is StepOutcome.Fatal)
        assertEquals(1, f.hooks.decisionFatal)
        assertEquals(MAX_DECISION_FAILURE_STREAK, f.counters.decisionFailure)
    }

    @Test
    fun `云端连续超时直接失败收尾并提示检查网络`() = runTest {
        // maxSteps=0（不设上限）时，超时若继续降级为 wait 会无限空转 —— 必须直接收尾
        val f = Fixture(cloud = { throw DecisionTimeoutExhaustedException(MAX_DECISION_TIMEOUT_STREAK) })
        val s = session()
        val outcome = f.phase.run(s)
        assertTrue(outcome is StepOutcome.Fatal)
        assertEquals(1, f.hooks.decisionFatal)
        assertTrue((outcome as StepOutcome.Fatal).reason.contains("检查网络"))
        assertEquals(0, f.counters.decisionFailure)
        assertTrue(f.hooks.logs.any { it.contains("网络可能不通") })
    }

    @Test
    fun `决策为空时失败收尾`() = runTest {
        val f = Fixture(cloud = { null })
        val s = session()
        val outcome = f.phase.run(s)
        assertTrue(outcome is StepOutcome.Fatal)
        assertTrue(f.hooks.logs.any { it.contains("决策为空") })
    }

    @Test
    fun `技能归一化为标准意图时以归一化结果为准`() = runTest {
        val cloudIntent = AgentIntent(intent = "my_skill")
        val normalizedIntent = AgentIntent(intent = IntentType.TAP)
        val f = Fixture(
            cloud = { cloudIntent },
            normalized = SkillCompat.Normalized.Intent(normalizedIntent),
        )
        val s = session()
        val outcome = f.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(IntentType.TAP, s.intent?.intent)
        assertEquals(1, f.cloudCalls)
    }

    @Test
    fun `动作模式拒绝时注入提示并继续`() = runTest {
        val f = Fixture(
            cloud = { AgentIntent(intent = IntentType.SHELL) },
            mode = ActionMode.CONSERVATIVE,
        )
        val s = session()
        val outcome = f.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, f.counters.modeDeny)
        assertTrue("拒绝原因应提示给 AI", s.messages.any { m ->
            m.content.any { part -> part.text.orEmpty().contains("⚠️") }
        })
        assertTrue(f.hooks.logs.any { it.contains("动作模式拒绝") })
    }

    @Test
    fun `动作模式连续拒绝达到阈值时失败收尾`() = runTest {
        val f = Fixture(
            cloud = { AgentIntent(intent = IntentType.SHELL) },
            mode = ActionMode.CONSERVATIVE,
        )
        f.counters.modeDeny = MAX_MODE_DENY_STREAK - 1
        val s = session()
        val outcome = f.phase.run(s)
        assertTrue(outcome is StepOutcome.Fatal)
        assertEquals(1, f.hooks.modeFatal)
    }

    @Test
    fun `宏接力注入时跳过端侧与云端决策`() = runTest {
        val macro = AgentIntent(intent = IntentType.SCROLL_TO)
        val f = Fixture(localIntent = AgentIntent(intent = IntentType.TAP), macroIntent = macro)
        val s = session()
        val outcome = f.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(IntentType.SCROLL_TO, s.intent?.intent)
        assertEquals(0, f.cloudCalls)
        assertTrue(f.hooks.logs.any { it.contains("注入") })
    }
}
