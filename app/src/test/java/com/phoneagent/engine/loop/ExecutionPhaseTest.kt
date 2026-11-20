package com.phoneagent.engine.loop

import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.domain.model.TaskStep
import com.phoneagent.domain.model.UiElement
import com.phoneagent.domain.rules.EngineRules
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.IntentTranslator
import com.phoneagent.engine.execution.PendingInput
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
        var confirmations = 0
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
        /** 本任务内已下发并确认过的副作用动作（幂等判据的唯一来源） */
        val committedEffects: List<EngineRules.CommittedEffect> = emptyList(),
        val browser: Set<String> = emptySet(),
        val guided: AgentIntent? = null,
        /** 完成前复核时再观察到的页面（默认空页面 = 没有持久状态） */
        val reobserved: ScreenSnapshot = ScreenSnapshot(),
    ) {
        val f = fake
        var earlyDone = 0
        var sayStreak = 0
        var failures = 0
        var invalidCommands = 0
        var pendingInput: PendingInput? = null
        var inputCommitRejections = 0
        var completionRecheckRejections = 0
        val committed = committedEffects.toMutableList()

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
                showDone = { text, _, _ -> f.done += text },
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
                reobserve = { reobserved },
                ensureShellApproval = { shellApproved },
                awaitActionConfirmation = { f.confirmations++; confirmation },
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
                committedEffects = { committed.toList() },
                recordEffect = { action, step ->
                    committed += EngineRules.CommittedEffect(EngineRules.idempotencyKey(action, action.type), step)
                },
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
                pendingInput = { pendingInput },
                setPendingInput = { pendingInput = it },
                inputCommitRejections = { inputCommitRejections },
                setInputCommitRejections = { inputCommitRejections = it },
                completionRecheckRejections = { completionRecheckRejections },
                setCompletionRecheckRejections = { completionRecheckRejections = it },
            ),
        )
    }

    private fun session(
        intent: AgentIntent,
        step: Int = 1,
        plan: TaskPlan? = null,
        messages: MutableList<ChatMessageDto> = mutableListOf(),
        snapshot: ScreenSnapshot = ScreenSnapshot(
            packageName = "com.example",
            screenWidth = 1080,
            screenHeight = 1920,
        ),
    ): StepSession {
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
    fun `输入未提交时拒绝完成`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TASK_DONE, summary = "答完了"),
            ),
        )
        fixture.pendingInput = PendingInput(label = "答题框", preview = "42", step = 2)
        val s = session(AgentIntent(intent = IntentType.FINISH), step = 3)
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.inputCommitRejections)
        assertEquals(0, fixture.f.finished.size)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("并没有提交") } })
    }

    @Test
    fun `依据瞬时提示报完成时要求重新核对`() = runTest {
        val toastPage = ScreenSnapshot(
            packageName = "com.example",
            screenWidth = 1080,
            screenHeight = 1920,
            elements = listOf(
                UiElement(
                    index = 0,
                    className = "android.widget.TextView",
                    type = "Text",
                    text = "已加入播放队列",
                    x = 540,
                    y = 1800,
                    left = 0,
                    top = 1760,
                    right = 1080,
                    bottom = 1840,
                ),
            ),
        )
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TASK_DONE, summary = "已加入播放队列"),
            ),
            reobserved = ScreenSnapshot(packageName = "com.example"),
        )
        val s = session(AgentIntent(intent = IntentType.FINISH), step = 3, snapshot = toastPage)
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.completionRecheckRejections)
        assertEquals(0, fixture.f.finished.size)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("瞬时提示") } })
    }

    @Test
    fun `未提交输入拒绝耗尽后放行并在任务记忆里标注`() = runTest {
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TASK_DONE, summary = "答完了"),
            ),
        )
        fixture.pendingInput = PendingInput(label = "答题框", preview = "42", step = 2)
        fixture.inputCommitRejections = 2
        val s = session(AgentIntent(intent = IntentType.FINISH), step = 3)
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Return, outcome)
        assertTrue(
            fixture.f.finished.any {
                it.first == TaskMemoryEntry.STATUS_SUCCESS && it.second.orEmpty().contains("未确认提交")
            },
        )
        assertEquals(null, fixture.pendingInput)
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
        val fixture = Fixture(
            verify = VerifyResult(false, "未知 shell 命令", "", "", notDelivered = true, structural = true),
        )
        val s = session(AgentIntent(intent = IntentType.SHELL))
        val outcome = fixture.phase.run(s)
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals(1, fixture.invalidCommands)
        // 结构错误只执行一次（不参与外层重试），但历史与截图照常落档
        assertEquals(1, fixture.f.executes)
        assertEquals(1, fixture.f.histories)
    }

    @Test
    fun `已送达但未生效时回注换入口提示而不是重放`() = runTest {
        // 交付状态由结构化字段决定，不解析文案：这条理由里没有任何关键词
        val fixture = Fixture(
            verify = VerifyResult(false, "页面与控件状态均无变化", "", ""),
        )
        val s = session(AgentIntent(intent = IntentType.TAP))
        fixture.phase.run(s)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("已经送达") } })
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("不要原地重复") } })
    }

    @Test
    fun `没送达时回注换通道提示`() = runTest {
        val fixture = Fixture(
            verify = VerifyResult(false, "手势被系统拒绝", "", "", notDelivered = true),
        )
        val s = session(AgentIntent(intent = IntentType.SWIPE))
        fixture.phase.run(s)
        assertTrue(s.messages.any { m -> m.content.any { it.text.orEmpty().contains("没有送达设备") } })
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

    // ---- 幂等保护（HAPP-5 P0-1）：判据绑本任务执行历史，页面文字不算"已经做过" ----

    /** 待办详情页：标题 + 状态标签 + 返回/编辑/删除 三个按钮（5 个元素） */
    private fun todoDetailPage(status: String) = ScreenSnapshot(
        packageName = "com.example.todo",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = listOf(
            todoElement(0, "买牛奶", 40, 40, 900, 160),
            todoElement(1, status, 40, 220, 300, 320),
            todoElement(2, "返回", 40, 80, 160, 180),
            todoElement(3, "编辑", 40, 1600, 300, 1700),
            todoElement(4, "删除", 780, 1600, 1040, 1700),
        ),
    )

    private fun todoElement(index: Int, text: String, left: Int, top: Int, right: Int, bottom: Int) = UiElement(
        index = index, className = "android.widget.Button", type = "Button", text = text,
        x = (left + right) / 2, y = (top + bottom) / 2,
        left = left, top = top, right = right, bottom = bottom,
    )

    private fun deleteIntent() = AgentIntent(
        intent = IntentType.DELETE,
        target = AgentIntentTarget(by = "label", value = "删除"),
    )

    private fun deleteTranslation() = IntentTranslator.TranslationResult.Command(
        AgentAction(type = ActionType.TAP, target = ActionTarget(method = "label", value = "删除")),
    )

    @Test
    fun `待办详情页删除_页面已完成标签不得让动作被跳过`() = runTest {
        // 旧口径：元素 ≤6 + 页面含「已完成」→ 幂等命中 → ExecutionCore 直接返回 VerifyResult(true)，
        // 删除动作整条没有下发却记成 ✅。新口径必须真的下发，或走不可逆动作的强制二次确认。
        val page = todoDetailPage("已完成")
        val denied = Fixture(translation = deleteTranslation(), confirmation = false)
        val deniedOutcome = denied.phase.run(session(deleteIntent(), snapshot = page))
        assertEquals(StepOutcome.Continue, deniedOutcome)
        assertEquals("不可逆动作必须先问用户", 1, denied.f.confirmations)
        assertEquals("用户拒绝后不得静默下发", 0, denied.f.executes)
        assertEquals("拒绝的步骤不得记成幂等成功", 0, denied.committed.size)

        val allowed = Fixture(translation = deleteTranslation(), confirmation = true)
        val allowedOutcome = allowed.phase.run(session(deleteIntent(), snapshot = page))
        assertEquals(StepOutcome.Continue, allowedOutcome)
        assertEquals(1, allowed.f.confirmations)
        assertEquals("确认后必须真的下发删除动作，而不是跳过", 1, allowed.f.executes)
    }

    @Test
    fun `本任务内已下发过同一动作_幂等命中且不重复下发`() = runTest {
        // 第 3 步已经真的点过「删除」并验证成功：页面仍是完成态时重复的同一动作要跳过，防重复副作用。
        val key = EngineRules.idempotencyKey(
            AgentAction(type = ActionType.TAP, target = ActionTarget(method = "label", value = "删除")),
            ActionType.TAP,
        )
        val fixture = Fixture(
            translation = deleteTranslation(),
            committedEffects = listOf(EngineRules.CommittedEffect(key, 3)),
        )
        val outcome = fixture.phase.run(session(deleteIntent(), snapshot = todoDetailPage("删除成功")))
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals("已下发过，不得重复下发", 0, fixture.f.executes)
        assertEquals("跳过也要落一条执行历史", 1, fixture.f.histories)
        assertTrue(fixture.f.logs.any { it.contains("幂等跳过") })
    }

    @Test
    fun `本任务内没做过同一动作_页面出现提交成功也不跳过`() = runTest {
        // 「发送」不在不可逆词表：没有执行记录就直接执行。这是旧实现最危险的一类——
        // 页面上一句「提交成功」就能把真要发的消息跳过。这里刻意用不可逆词表外的动作。
        val fixture = Fixture(
            translation = IntentTranslator.TranslationResult.Command(
                AgentAction(type = ActionType.TAP, target = ActionTarget(method = "label", value = "发送")),
            ),
        )
        val outcome = fixture.phase.run(
            session(
                AgentIntent(intent = IntentType.SEND, target = AgentIntentTarget(by = "label", value = "发送")),
                snapshot = todoDetailPage("提交成功"),
            ),
        )
        assertEquals(StepOutcome.Continue, outcome)
        assertEquals("页面文字不构成跳过理由", 1, fixture.f.executes)
        assertEquals(0, fixture.f.confirmations)
    }

    @Test
    fun `下发成功后写入幂等历史`() = runTest {
        val fixture = Fixture(translation = deleteTranslation(), confirmation = true)
        fixture.phase.run(session(deleteIntent(), snapshot = todoDetailPage("待办")))
        assertEquals(1, fixture.f.executes)
        assertEquals("只有真的下发且验证成功才记账", 1, fixture.committed.size)
        assertEquals(1, fixture.committed.first().step)
    }

    @Test
    fun `下发失败不写幂等历史`() = runTest {
        val fixture = Fixture(
            translation = deleteTranslation(),
            confirmation = true,
            verify = VerifyResult(false, "没点到", "", ""),
        )
        fixture.phase.run(session(deleteIntent(), snapshot = todoDetailPage("待办")))
        assertEquals(0, fixture.committed.size)
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
