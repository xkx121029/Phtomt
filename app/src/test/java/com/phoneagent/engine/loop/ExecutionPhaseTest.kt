package com.phoneagent.engine.loop

import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.domain.model.TaskStep
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.IntentTranslator
import com.phoneagent.engine.execution.VerifyResult
import com.phoneagent.engine.perception.AnnotatedPage
import com.phoneagent.engine.runtime.EngineLimits.MAX_SAY_STREAK
import com.phoneagent.engine.runtime.LoopGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行阶段回归（阶段 B4）：浏览器分流 / 提前完成拦截 / 完成与放弃收尾 / 纯端侧动作 /
 * 确认门 / 结构错误 / 连续失败引导 / 用户离开失败收尾。
 *
 * 每一条原先都是主循环里的 `continue` / `return`，迁移后必须验证 StepOutcome 语义不变，
 * 且"哪些副作用发生了、哪些没有"与原文一致（例如放弃不学模板、结构错误才计数）。
 */
class ExecutionPhaseTest {

    private class Fake {
        val logs = mutableListOf<String>()
        val floating = mutableListOf<String>()
        val interactions = mutableListOf<String>()
        val conversations = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val progresses = mutableListOf<Int>()
        val shots = mutableListOf<String>()
        var checkpoints = 0
        var histories = 0
        var needsUser = false
        var userHint = ""
        val thinkings = mutableListOf<String>()
        val done = mutableListOf<String>()
        val finished = mutableListOf<Pair<String, String?>>()
        var learnTemplate = 0
        var clearCheckpoint = 0
        var distill = 0
        var stops = 0
        var completedWith = ""
        var remembered = 0
        var deviceQueried = 0
        var said = 0
        var shownAgent = 0
        var browses = 0
        var translates = 0
        var executes = 0
        var anomalyUses = 0
        var learnedSolutions = 0
        var state = AgentState()
    }

    // 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
    @Suppress("LongParameterList")
    private class Fixture(
        fake: Fake = Fake(),
        val translation: IntentTranslator.TranslationResult =
            IntentTranslator.TranslationResult.Command(AgentAction(type = ActionType.CLICK)),
        val verify: VerifyResult = VerifyResult(true, "", "", ""),
        val plan: TaskPlan? = null,
        val mode: ActionMode = ActionMode.BALANCED,
        val shellApproved: Boolean = true,
        val confirmation: Boolean = true,
        val idempotentDone: Boolean = false,
        val browser: Set<String> = emptySet(),
        val guided: AgentIntent? = null,
    ) {
        val f = fake
        var earlyDone = 0
        var sayStreak = 0
        var failures = 0
        var invalidCommands = 0

        val phase = ExecutionPhase(
            loopGuard = LoopGuard(),
            currentPlan = { plan },
            ui = ExecutionUiHooks(
                log = { level: AgentLog.Level, message: String -> f.logs += "$level:$message" },
                pushFloating = { status: String, _: String -> f.floating += status },
                state = { f.state },
                setState = { f.state = it },
                showFloatingInteraction = { type: String, title: String, _: String ->
                    f.interactions += "$type:$title"
                },
                setNeedsUser = { f.needsUser = it },
                emitHintRequest = { },
                awaitUserHint = { f.userHint },
                pushThinking = { sent: String?, _: String? -> f.thinkings += sent.orEmpty() },
                showDone = { f.done += it },
                setAgentRunning = { },
                addConversation = { role: String, content: String -> f.conversations += "$role:$content" },
            ),
            record = ExecutionRecordHooks(
                recordStep = { _, _, status, _, _, reason -> f.steps += "$status:$reason" },
                recordsIntoHistory = { _, _, _ -> f.histories++ },
                stepShotCapture = { _, action, _ -> f.shots += action.type },
                recordProgress = { step, _ -> f.progresses += step },
                saveCheckpoint = { f.checkpoints++ },
                takeShellOutput = { "" },
                recordAnomalyUse = { f.anomalyUses++ },
                learnSolution = { _, _, _, _ -> f.learnedSolutions++ },
                pageMemoryOnIntent = { },
                pageMemoryOnStepResult = { },
            ),
            device = ExecutionDeviceHooks(
                takeVisualCoordinate = { null },
                translate = { _, _, _ ->
                    f.translates++
                    translation
                },
                fillMissingParam = { tr, _, _, _ -> tr },
                execute = { _, _ ->
                    f.executes++
                    verify
                },
                reobserve = { ScreenSnapshot() },
                ensureShellApproval = { shellApproved },
                awaitActionConfirmation = { confirmation },
                handleRemember = { _, _, _ -> f.remembered++ },
                handleDeviceQuery = { _, _, _ -> f.deviceQueried++ },
                handleSay = { _, _ -> f.said++ },
                handleShowAgent = { _, _ -> f.shownAgent++ },
                browserHandles = { op: String -> op in browser },
                handleBrowse = { _, _, _ ->
                    f.browses++
                    AgentAction(type = IntentType.BROWSE_READ) to VerifyResult(true, "", "", "")
                },
                decideWithUserHint = { _, _ -> guided },
                actionMode = { mode },
                idempotencyDone = { idempotentDone },
                a11yDisconnectedNote = { "" },
            ),
            finish = ExecutionFinishHooks(
                finishTaskMemory = { status, note -> f.finished += status to note },
                learnTemplate = { f.learnTemplate++ },
                clearCheckpoint = { f.clearCheckpoint++ },
                onTaskCompleted = { summary -> f.completedWith = summary },
                distillMemories = { f.distill++ },
                stopEngine = { f.stops++ },
            ),
            counters = ExecutionCounters(
                earlyDoneRejections = { earlyDone },
                setEarlyDoneRejections = { earlyDone = it },
                sayStreak = { sayStreak },
                setSayStreak = { sayStreak = it },
                consecutiveFailures = { failures },
                setConsecutiveFailures = { failures = it },
                invalidCommandStreak = { invalidCommands },
                setInvalidCommandStreak = { invalidCommands = it },
            ),
        )
    }

    private fun session(
        intent: AgentIntent,
        step: Int = 1,
        plan: TaskPlan? = null,
        messages: MutableList<ChatMessageDto> = mutableListOf(),
    ): StepSession {
        val snapshot = ScreenSnapshot(packageName = "com.example", screenWidth = 1080, screenHeight = 1920)
        return StepSession(
            task = "测试任务",
            plan = plan,
            step = step,
            settingsVal = AppSettings.Settings(),
            lang = PromptLang.CN,
            messages = messages,
        ).also {
            it.snapshot = snapshot
            it.semanticSnapshot = snapshot
            it.annotated = AnnotatedPage(snapshot, "generic", "", emptyList(), "fp")
            it.intent = intent
        }
    }

    @Test
    fun `浏览器通道先于转译层分流且不调用转译`() = runTest {
        val fixture = Fixture(browser = setOf(IntentType.BROWSE_READ))
        val s = session(AgentIntent(intent = IntentType.BROWSE_READ))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.f.browses)
        assertEquals(0, fixture.f.translates)
        assertEquals(1, fixture.f.histories)
    }

    @Test
    fun `提前完成未达门槛时要求重新决策`() = runTest {
        val plan = TaskPlan(steps = List(5) { TaskStep(description = "步骤$it") })
        val fixture = Fixture(
            plan = plan,
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TASK_DONE, summary = "完成了"),
            ),
        )
        val s = session(AgentIntent(intent = IntentType.FINISH), step = 2, plan = plan)
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.earlyDone)
        assertEquals(0, fixture.f.finished.size)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("提前结束") } })
    }

    @Test
    fun `完成达标时学习模板并提炼记忆`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TASK_DONE, summary = "全部完成"),
            ),
        )
        val s = session(AgentIntent(intent = IntentType.FINISH), step = 3)
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Return, outcome)
        assertEquals(1, fixture.f.learnTemplate)
        assertEquals(1, fixture.f.clearCheckpoint)
        assertEquals(1, fixture.f.distill)
        assertEquals("全部完成", fixture.f.completedWith)
        assertEquals(AgentState.Phase.DONE, fixture.f.state.phase)
        assertTrue(fixture.f.finished.any { it.first == TaskMemoryEntry.STATUS_SUCCESS })
    }

    @Test
    fun `放弃按失败口径收尾且不学模板`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TASK_DONE, summary = "做不了"),
            ),
        )
        val s = session(AgentIntent(intent = IntentType.GIVE_UP), step = 3)
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Return, outcome)
        assertEquals(0, fixture.f.learnTemplate)
        assertEquals(AgentState.Phase.IDLE, fixture.f.state.phase)
        assertTrue(fixture.f.done.any { it.contains("已放弃") })
        assertTrue(fixture.f.finished.any { it.first == TaskMemoryEntry.STATUS_FAILED && it.second == "已放弃：做不了" })
    }

    @Test
    fun `记忆写入走纯端侧代办后进入下一轮`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(AgentAction(type = ActionType.REMEMBER)),
        )
        val s = session(AgentIntent(intent = IntentType.REMEMBER))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.f.remembered)
        assertEquals(0, fixture.f.histories)
    }

    @Test
    fun `连续说话超限时不再呈现并注入纠偏提示`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(AgentAction(type = ActionType.SAY)),
        )
        fixture.sayStreak = MAX_SAY_STREAK
        val s = session(AgentIntent(intent = IntentType.SAY))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(0, fixture.f.said)
        assertEquals(0, fixture.sayStreak)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("没有执行任何动作") } })
    }

    @Test
    fun `自写命令被拒绝时留档并跳过执行`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(AgentAction(type = ActionType.SHELL)),
            shellApproved = false,
        )
        val s = session(AgentIntent(intent = IntentType.SHELL))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(0, fixture.f.executes)
        assertEquals(1, fixture.f.histories)
        assertEquals(1, fixture.f.shots.size)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("用户拒绝执行这条命令") } })
    }

    @Test
    fun `不可逆操作被取消时跳过执行`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TAP, needsUserConfirmation = true),
            ),
            confirmation = false,
        )
        val s = session(AgentIntent(intent = IntentType.DELETE))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(0, fixture.f.executes)
        assertEquals(1, fixture.f.histories)
    }

    @Test
    fun `执行成功时记进度并存检查点`() = runTest {
        val fixture = Fixture()
        val s = session(AgentIntent(intent = IntentType.TAP))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.f.executes)
        assertEquals(1, fixture.f.histories)
        assertEquals(1, fixture.f.progresses.size)
        assertEquals(1, fixture.f.checkpoints)
        assertEquals(0, fixture.failures)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("执行了") } })
    }

    @Test
    fun `结构性错误不重试并升级无效命令计数`() = runTest {
        val fixture = Fixture(verify = VerifyResult(false, "未知 shell 命令", "", ""))
        val s = session(AgentIntent(intent = IntentType.SHELL))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.invalidCommands)
        // 结构错误只执行一次（不参与外层重试），但历史与截图照常落档
        assertEquals(1, fixture.f.executes)
        assertEquals(1, fixture.f.histories)
    }

    @Test
    fun `连续失败且用户离开时按失败收尾`() = runTest {
        val fixture = Fixture(verify = VerifyResult(false, "页面未按预期变化", "", ""))
        fixture.failures = 2
        fixture.f.userHint = ""
        val s = session(AgentIntent(intent = IntentType.SWIPE))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Return, outcome)
        assertEquals(1, fixture.f.steps.size)
        assertEquals(1, fixture.f.stops)
        assertTrue(fixture.f.finished.any { it.first == TaskMemoryEntry.STATUS_FAILED })
        assertEquals(1, fixture.f.interactions.size)
        assertTrue(fixture.f.needsUser)
    }

    @Test
    fun `连续失败后用户给出指导时执行引导意图`() = runTest {
        val fixture = Fixture(
            verify = VerifyResult(false, "页面未按预期变化", "", ""),
            browser = setOf(IntentType.BROWSE_READ),
            guided = AgentIntent(intent = IntentType.BROWSE_READ),
        )
        fixture.failures = 2
        fixture.f.userHint = "试试浏览器"
        val s = session(AgentIntent(intent = IntentType.SWIPE))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.f.learnedSolutions)
        assertEquals(1, fixture.f.browses)
        // 引导动作接进既有链路后由末尾统一留档一次（浏览器分支本身不重复落档）
        assertEquals(1, fixture.f.histories)
    }

    @Test
    fun `转译失败按结构错误回注原因`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Failed("未知意图：tapx"),
        )
        val s = session(AgentIntent(intent = "tapx"))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.invalidCommands)
        assertTrue(fixture.f.logs.any { it.contains("意图转译失败") })
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("tapx") } })
    }
}
