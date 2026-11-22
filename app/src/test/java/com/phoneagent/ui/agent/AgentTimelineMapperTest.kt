package com.phoneagent.ui.agent

import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.Clarification
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.device.vision.StepTrace
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.domain.model.TaskStatus
import com.phoneagent.domain.model.TaskStep
import com.phoneagent.domain.model.TaskOutcome
import com.phoneagent.engine.ClarifyAnswered
import com.phoneagent.engine.MemoryEvent
import com.phoneagent.engine.PlanPhase
import com.phoneagent.engine.SayEvent
import com.phoneagent.engine.TaskSession
import com.phoneagent.engine.execution.VerifyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务流映射层单测。
 *
 * 覆盖的都是"历史上真出过问题"的点：同一步被拆成两条、key 冲突导致列表闪退、
 * 中间状态刷屏、结束态与实时状态同时出现。
 * 现在任务流一次只铺开一个任务（默认最新，可由侧边栏指定回看某一次），
 * 因此"旧任务不再无限铺开"改为由「聚焦」相关用例覆盖。
 */
class AgentTimelineMapperTest {

    private fun trace(
        taskId: Long,
        step: Int,
        taskName: String = "任务$taskId",
        received: String = """{"type":"tap","confidence":0.9}""",
        tokens: Int = 10,
        latencyMs: Long = 120,
    ) = StepTrace(
        taskId = taskId,
        taskName = taskName,
        step = step,
        receivedText = received,
        totalTokens = tokens,
        latencyMs = latencyMs,
    )

    private fun record(
        step: Int,
        verified: Boolean,
        type: String = ActionType.TAP,
        taskId: Long = -1,
        result: String = if (verified) VerifyRules.STATUS_VERIFIED else VerifyRules.STATUS_UNVERIFIED,
    ) = StepRecord(
        step = step,
        taskId = taskId,
        action = AgentAction(
            type = type,
            target = ActionTarget(method = "id", value = "search_box"),
            confidence = 0.9,
        ),
        verificationResult = result,
        isConfirmed = verified,
        durationMs = 200,
    )

    private fun build(
        submittedTask: String = "",
        state: AgentState = AgentState(),
        planPhase: PlanPhase = PlanPhase.Idle,
        planText: String = "",
        traces: List<StepTrace> = emptyList(),
        history: List<StepRecord> = emptyList(),
        queue: List<String> = emptyList(),
        needsUser: Boolean = false,
        needsUserReason: String = "",
        a11yEnabled: Boolean = true,
        fold: LiveStatusFold = LiveStatusFold(),
        memoryEvents: List<MemoryEvent> = emptyList(),
        sayEvents: List<SayEvent> = emptyList(),
        clarifyEvents: List<ClarifyAnswered> = emptyList(),
        focusTaskId: Long? = null,
        archived: TaskSession? = null,
        conversationStart: Long = 0L,
        lastOutcome: TaskOutcome? = null,
        configProblem: String? = null,
    ) = AgentTimelineMapper.build(
        submittedTask = submittedTask,
        state = state,
        planPhase = planPhase,
        planText = planText,
        traces = traces,
        history = history,
        queue = queue,
        needsUser = needsUser,
        needsUserReason = needsUserReason,
        a11yEnabled = a11yEnabled,
        fold = fold,
        memoryEvents = memoryEvents,
        sayEvents = sayEvents,
        clarifyEvents = clarifyEvents,
        focusTaskId = focusTaskId,
        archived = archived,
        conversationStart = conversationStart,
        lastOutcome = lastOutcome,
        configProblem = configProblem,
    )

    private fun running(message: String, stepCount: Int = 1) = AgentState(
        isRunning = true,
        task = "把字体调大",
        phase = AgentState.Phase.ACTING,
        message = message,
        stepCount = stepCount,
    )

    private fun memoryEvent(
        id: Long,
        step: Int,
        runKey: String = "r1",
        content: String = "用户喜欢简洁界面",
    ) = MemoryEvent(
        id = id,
        content = content,
        category = "preference",
        updated = false,
        runKey = runKey,
        step = step,
    )

    /** 摊平工具链：断言"某一步"时不必关心它被收进了哪条链 */
    private fun List<AgentTimelineItem>.steps(): List<StepCall> =
        filterIsInstance<AgentTimelineItem.ToolChain>().flatMap { it.steps }

    private fun sayEvent(
        id: Long,
        step: Int,
        text: String = "我先打开设置，再定位到显示项",
        runKey: String = "r1",
    ) = SayEvent(id = id, text = text, runKey = runKey, step = step)

    /** 找到承载某一步的那条工具链在列表中的下标 */
    private fun List<AgentTimelineItem>.chainIndexOf(step: Int): Int =
        indexOfFirst { it is AgentTimelineItem.ToolChain && it.steps.any { s -> s.step == step } }

    @Test
    fun `连续步骤收成一条工具链且能摊平回原有步序`() {
        val items = build(
            traces = (1..3).map { trace(1, it) },
            history = (1..3).map { record(it, verified = true) },
        )
        val chains = items.filterIsInstance<AgentTimelineItem.ToolChain>()
        assertEquals("连续三步应只出一条工具链", 1, chains.size)
        assertEquals(listOf(1, 2, 3), chains.single().steps.map { it.step })
        assertTrue("工具链要能报出调用了什么工具", chains.single().steps.all { it.toolType.isNotBlank() })
    }

    @Test
    fun `中间夹了记忆卡片时工具链自然断开`() {
        val items = build(
            traces = (1..3).map { trace(1, it) },
            history = (1..3).map { record(it, verified = true) },
            memoryEvents = listOf(memoryEvent(id = 1, step = 2)),
        )
        val chains = items.filterIsInstance<AgentTimelineItem.ToolChain>()
        assertEquals("第 2 步后夹了记忆卡，应断成两条链", 2, chains.size)
        assertEquals(listOf(1, 2), chains[0].steps.map { it.step })
        assertEquals(listOf(3), chains[1].steps.map { it.step })
    }

    @Test
    fun `记忆卡片紧跟产生它的那一步`() {
        val items = build(
            traces = listOf(trace(1, 1), trace(1, 2)),
            history = listOf(record(1, verified = true), record(2, verified = true)),
            memoryEvents = listOf(memoryEvent(id = 7, step = 1)),
        )
        val stepIdx = items.chainIndexOf(1)
        val memIdx = items.indexOfFirst { it is AgentTimelineItem.MemoryAdded }
        assertTrue("应产出记忆卡片", memIdx >= 0)
        assertEquals("记忆卡片应紧跟第 1 步", stepIdx + 1, memIdx)
    }

    @Test
    fun `步号对不上的记忆追加且只出现一次`() {
        val items = build(
            traces = listOf(trace(1, 1)),
            history = listOf(record(1, verified = true)),
            // 任务结束提炼用的是 completedSteps，可能不对应任何一步
            memoryEvents = listOf(memoryEvent(id = 9, step = 99)),
        )
        assertEquals(1, items.count { it is AgentTimelineItem.MemoryAdded })
    }

    @Test
    fun `多条记忆卡片的 key 互不冲突`() {
        val items = build(
            traces = listOf(trace(1, 1)),
            history = listOf(record(1, verified = true)),
            memoryEvents = listOf(
                memoryEvent(id = 1, step = 1, content = "第一条记忆"),
                memoryEvent(id = 2, step = 1, content = "第二条记忆"),
            ),
        )
        val memKeys = items.filterIsInstance<AgentTimelineItem.MemoryAdded>().map { it.key }
        assertEquals("两条记忆都应产出", 2, memKeys.size)
        assertEquals("key 不应重复（重复会导致 LazyColumn 闪退）", memKeys.size, memKeys.toSet().size)
    }

    @Test
    fun `澄清进行中任务流出一条AI问题气泡不产步骤`() {
        val items = build(
            submittedTask = "把字体调大",
            planPhase = PlanPhase.Clarifying(
                Clarification(
                    question = "要调系统字体还是应用内字体？",
                    options = listOf(ClarificationOption(id = "sys", label = "系统字体")),
                ),
            ),
        )
        assertEquals(listOf("把字体调大"), items.filterIsInstance<AgentTimelineItem.UserTask>().map { it.text })
        // 提问现在留在任务流里（左对齐 AI 气泡），选项仍由输入栏承载
        val question = items.filterIsInstance<AgentTimelineItem.ClarifyQuestion>().single()
        assertEquals("要调系统字体还是应用内字体？", question.question)
        assertTrue(items.none { it is AgentTimelineItem.PlanStreaming })
        assertTrue(items.none { it is AgentTimelineItem.ClarifyAnswer })
    }

    @Test
    fun `选定澄清选项后：问题与选择成组保留在任务流里`() {
        val items = build(
            submittedTask = "把字体调大",
            planPhase = PlanPhase.AwaitingApproval(TaskPlan(steps = emptyList(), confidence = 0.8)),
            clarifyEvents = listOf(
                ClarifyAnswered(id = 1, question = "要调系统字体还是应用内字体？", answer = "系统字体"),
            ),
        )
        val question = items.filterIsInstance<AgentTimelineItem.ClarifyQuestion>().single()
        assertEquals("要调系统字体还是应用内字体？", question.question)
        // 用户点的选择收成用户侧气泡，紧跟在同一组问题后面
        val answer = items.filterIsInstance<AgentTimelineItem.ClarifyAnswer>().single()
        assertEquals("系统字体", answer.answer)
        assertTrue(items.indexOfFirst { it is AgentTimelineItem.ClarifyQuestion } <
            items.indexOfFirst { it is AgentTimelineItem.ClarifyAnswer })
    }

    @Test
    fun `待批准的步骤清单不再进任务流（改由输入栏承载）`() {
        val items = build(
            submittedTask = "把字体调大",
            planPhase = PlanPhase.AwaitingApproval(TaskPlan(steps = emptyList(), confidence = 0.8)),
        )
        // 步骤清单与「批准并开始 / 取消」都搬进输入栏，任务流只留任务标题
        assertEquals(listOf("把字体调大"), items.filterIsInstance<AgentTimelineItem.UserTask>().map { it.text })
        assertTrue(items.none { it is AgentTimelineItem.PlanApproved })
        assertTrue(items.none { it is AgentTimelineItem.PlanFailed })
    }

    @Test
    fun `纯对话阶段：任务标题留在任务流，回答以 say 气泡呈现`() {
        val items = build(
            submittedTask = "你好",
            planPhase = PlanPhase.Reply("你好！有什么可以帮你的？"),
            sayEvents = listOf(sayEvent(id = 1, step = 1)),
        )
        assertEquals(listOf("你好"), items.filterIsInstance<AgentTimelineItem.UserTask>().map { it.text })
        // AI 回答必须排在用户气泡下面（用户问 → AI 答），不能抢到上面
        assertTrue(items.indexOfFirst { it is AgentTimelineItem.UserTask } < items.indexOfFirst { it is AgentTimelineItem.Say })
        // 规划流的原始 JSON 不该漏进任务流
        assertTrue(items.none { it is AgentTimelineItem.PlanStreaming })
        assertTrue(items.none { it is AgentTimelineItem.PlanFailed })
    }

    @Test
    fun `AI 说的话挂在最近的前序步骤后面`() {
        val items = build(
            traces = (1..3).map { trace(1, it) },
            history = (1..3).map { record(it, verified = true) },
            sayEvents = listOf(sayEvent(id = 1, step = 2), sayEvent(id = 2, step = 3)),
        )
        val says = items.filterIsInstance<AgentTimelineItem.Say>()
        assertEquals("两条话都应产出，且按 id 升序", listOf(1L, 2L), says.map { it.id })
        assertEquals("第 2 步说的话跟在第 2 步后面", items.chainIndexOf(2) + 1, items.indexOfFirst { it is AgentTimelineItem.Say })
        assertEquals(
            "第 3 步说的话跟在第 3 步后面",
            items.chainIndexOf(3) + 1,
            items.indexOfLast { it is AgentTimelineItem.Say },
        )
    }

    @Test
    fun `回看历史任务时不回放 AI 说过的话`() {
        val items = build(
            traces = (1..3).map { trace(taskId = 1, step = it) } + trace(taskId = 2, step = 1),
            history = (1..3).map { record(step = it, verified = true, taskId = 1) } +
                record(step = 1, verified = true, taskId = 2),
            sayEvents = listOf(sayEvent(id = 1, step = 1, runKey = "r1")),
            focusTaskId = 1L,
        )
        assertTrue("归档不存这些话，历史回看不该出现", items.none { it is AgentTimelineItem.Say })
    }

    @Test
    fun `同一步的追踪与执行记录只产出一条步骤项`() {
        val items = build(
            traces = listOf(trace(1, 1), trace(1, 2)),
            history = listOf(record(1, verified = true), record(2, verified = false)),
        )
        val calls = items.steps()
        assertEquals(2, calls.size)
        assertEquals(listOf(1, 2), calls.map { it.step })
        assertTrue(calls.first { it.step == 1 }.verified)
        assertTrue(!calls.first { it.step == 2 }.verified)
        // 执行记录里的结构化动作优先于原文本，人能直接看懂
        assertTrue(calls.first().human.isNotBlank())
    }

    @Test
    fun `所有列表项 key 唯一`() {
        val items = build(
            submittedTask = "把字体调大",
            state = running("正在点击搜索框"),
            planPhase = PlanPhase.Approved(TaskPlan(steps = listOf(TaskStep("打开设置")))),
            traces = listOf(trace(1, 1), trace(2, 1), trace(2, 2)),
            history = listOf(record(1, true), record(2, true), record(2, false)),
            queue = listOf("给张三发消息", "清理截图"),
            needsUser = true,
            needsUserReason = "敏感页需要你确认",
        )
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `实时状态恒定一条且折叠计数递增`() {
        val fold = LiveStatusFold()
        val first = build(
            state = running("正在读取页面"),
            traces = listOf(trace(1, 1)),
            fold = fold,
        )
        val second = build(
            state = running("正在点击搜索框"),
            traces = listOf(trace(1, 1)),
            fold = fold,
        )
        val firstLive = first.filterIsInstance<AgentTimelineItem.LiveStatus>().single()
        val secondLive = second.filterIsInstance<AgentTimelineItem.LiveStatus>().single()
        assertEquals(0, firstLive.foldedCount)
        assertEquals(1, secondLive.foldedCount)
        assertEquals("正在点击搜索框", secondLive.message)
    }

    @Test
    fun `任务完成后只出完成摘要不再出实时状态`() {
        val plan = TaskPlan(
            steps = listOf(TaskStep("打开设置"), TaskStep("把字体调大")),
            estimatedTimeSeconds = 20,
            confidence = 0.8,
        )
        val items = build(
            state = AgentState(
                isRunning = false,
                task = "把字体调大",
                phase = AgentState.Phase.DONE,
                message = "字体已调大",
                stepCount = 2,
            ),
            planPhase = PlanPhase.Approved(plan),
            traces = listOf(trace(1, 1), trace(1, 2)),
            history = listOf(record(1, true), record(2, true)),
        )
        assertTrue(items.none { it is AgentTimelineItem.LiveStatus })
        assertTrue(items.any { it is AgentTimelineItem.PlanApproved })
        val done = items.last() as AgentTimelineItem.Done
        assertEquals(2, done.okSteps)
        assertEquals(2, done.totalSteps)
    }

    @Test
    fun `默认只铺开最新一次任务`() {
        val items = build(
            traces = (1..9).map { trace(taskId = 1, step = it) } + trace(taskId = 2, step = 1),
            history = (1..9).map { record(step = it, verified = it <= 5, taskId = 1) } +
                record(step = 1, verified = true, taskId = 2),
        )
        val calls = items.steps()
        assertEquals("只应铺开最新一次", listOf("r2"), calls.map { it.runKey }.distinct())
        assertEquals("任务2", items.filterIsInstance<AgentTimelineItem.UserTask>().single().text)
    }

    @Test
    fun `指定任务时只看那一次且不摆实时状态`() {
        val items = build(
            state = running("正在点击搜索框"),
            traces = (1..3).map { trace(taskId = 1, step = it) } + trace(taskId = 2, step = 1),
            history = (1..3).map { record(step = it, verified = true, taskId = 1) } +
                record(step = 1, verified = true, taskId = 2),
            focusTaskId = 1L,
        )
        val calls = items.steps()
        assertEquals(listOf(1, 2, 3), calls.map { it.step })
        assertEquals(listOf("r1"), calls.map { it.runKey }.distinct())
        assertEquals("任务1", items.filterIsInstance<AgentTimelineItem.UserTask>().single().text)
        // 回看历史时不回放当时的实时状态，也不混入排队任务
        assertTrue(items.none { it is AgentTimelineItem.LiveStatus })
        // 没有归档就不硬造终态（超出归档上限的老任务容易被误判成失败）
        assertTrue(items.none { it is AgentTimelineItem.Terminal })
    }

    @Test
    fun `选中的就是最新任务时仍按实时渲染`() {
        val items = build(
            state = running("正在点击搜索框"),
            traces = listOf(trace(taskId = 2, step = 1)),
            history = listOf(record(step = 1, verified = true, taskId = 2)),
            focusTaskId = 2L,
        )
        assertTrue(items.any { it is AgentTimelineItem.LiveStatus })
    }

    @Test
    fun `回看历史任务用归档的标题与终态`() {
        val archived = TaskSession(
            taskId = 7L,
            title = "把字体调大",
            status = TaskSession.Status.DONE,
            summary = "字体已调到最大",
            steps = 3,
            okSteps = 2,
        )
        val items = build(focusTaskId = 7L, archived = archived)
        assertEquals("把字体调大", items.filterIsInstance<AgentTimelineItem.UserTask>().single().text)
        val done = items.filterIsInstance<AgentTimelineItem.Done>().single()
        assertEquals(2, done.okSteps)
        assertEquals(3, done.totalSteps)
        assertEquals("字体已调到最大", done.note)
    }

    @Test
    fun `归档为已停止时回看显示失败原因`() {
        val archived = TaskSession(
            taskId = 7L,
            title = "把字体调大",
            status = TaskSession.Status.ABORTED,
            summary = "用户手动停止",
        )
        val items = build(focusTaskId = 7L, archived = archived)
        val card = items.filterIsInstance<AgentTimelineItem.Terminal>().single()
        assertEquals("用户手动停止", card.cause)
        assertEquals("停止不是失败：终态必须是已停止", TaskStatus.ABORTED, card.status)
        assertTrue(items.none { it is AgentTimelineItem.Done })
    }

    // ---- 终态卡：任务结束必须在主界面留下结论（UX-02）----

    @Test
    fun `任务失败后终态卡留在主界面而不是退回空态`() {
        // 失败于决策阶段：一条执行痕迹都没有——正是空态成立的条件，历史上界面就此变成「还没有任务」
        val items = build(
            state = AgentState(
                isRunning = false,
                task = "打开相册，看看最近拍的照片",
                phase = AgentState.Phase.IDLE,
                startedAtMillis = 100L,
            ),
            lastOutcome = TaskOutcome(
                status = TaskStatus.FAILED,
                summary = "决策链路连续异常",
                failureReason = "AI 决策链路持续异常，已停止任务",
                failureKind = AgentState.FailureKind.DECISION,
                atMillis = 500L,
            ),
        )
        val card = items.filterIsInstance<AgentTimelineItem.Terminal>().single()
        assertEquals(TaskStatus.FAILED, card.status)
        assertTrue("失败卡必须给至少一个可执行出口", card.actions.isNotEmpty())
        assertTrue("原因要透出引擎给的具体说法", card.cause.contains("异常"))
        // 标题 chip 必须自己带状态，否则它与终态卡同屏时会被读成两个任务
        assertEquals(
            TaskStatus.FAILED,
            items.filterIsInstance<AgentTimelineItem.UserTask>().single().status,
        )
    }

    @Test
    fun `无障碍没开导致的失败，卡片标题与出口都指向权限`() {
        val items = build(
            state = AgentState(
                isRunning = false,
                task = "打开相册",
                phase = AgentState.Phase.IDLE,
                startedAtMillis = 100L,
            ),
            a11yEnabled = false,
            lastOutcome = TaskOutcome(
                status = TaskStatus.FAILED,
                summary = "决策链路连续异常",
                failureKind = AgentState.FailureKind.DECISION,
                atMillis = 500L,
            ),
        )
        val card = items.filterIsInstance<AgentTimelineItem.Terminal>().single()
        assertEquals("无障碍没开，AI 看不见屏幕", card.headline)
        assertTrue("主出口必须能直接去开授权", card.actions.contains(TerminalAction.OPEN_PERMISSIONS))
    }

    @Test
    fun `用户停止的终态是已停止且仍可重试`() {
        val items = build(
            state = AgentState(isRunning = false, task = "打开相册", phase = AgentState.Phase.IDLE),
            lastOutcome = TaskOutcome(
                status = TaskStatus.ABORTED,
                summary = "已按你的要求停止任务",
                failureKind = AgentState.FailureKind.USER_STOPPED,
                atMillis = 500L,
            ),
        )
        val card = items.filterIsInstance<AgentTimelineItem.Terminal>().single()
        assertEquals(TaskStatus.ABORTED, card.status)
        assertEquals("已停止", card.headline)
        assertEquals(listOf(TerminalAction.RETRY), card.actions)
    }

    @Test
    fun `终态卡按三态报步数：生效、未确认、未生效分开算`() {
        val items = build(
            state = AgentState(isRunning = false, task = "把字体调大", phase = AgentState.Phase.IDLE),
            traces = (1..3).map { trace(9, it) },
            history = listOf(
                record(1, verified = true, taskId = 9),
                record(2, verified = false, taskId = 9, result = VerifyRules.STATUS_UNVERIFIED),
                record(3, verified = false, taskId = 9, result = VerifyRules.STATUS_NO_EFFECT),
            ),
            lastOutcome = TaskOutcome(
                status = TaskStatus.FAILED,
                summary = "动作连续未生效",
                failureKind = AgentState.FailureKind.UNKNOWN,
                atMillis = 500L,
            ),
        )
        val card = items.filterIsInstance<AgentTimelineItem.Terminal>().single()
        assertEquals(1, card.okSteps)
        assertEquals(1, card.unconfirmedSteps)
        assertEquals(1, card.failedSteps)
        val line = AgentTerminal.stepLine(card.okSteps, card.unconfirmedSteps, card.failedSteps)
        assertEquals("已确认生效 1 步 · 已交付未确认 1 步 · 未生效 1 步", line)
        assertTrue(
            "未确认的步骤要提示用户去核对，而不是折进失败数里",
            AgentTerminal.unconfirmedNote(card.unconfirmedSteps).contains("核对"),
        )
    }

    @Test
    fun `模型没配置时终态卡的出口是去配置`() {
        val items = build(
            state = AgentState(isRunning = false, task = "打开相册", phase = AgentState.Phase.IDLE),
            configProblem = "API Key 未填写",
            lastOutcome = TaskOutcome(
                status = TaskStatus.FAILED,
                summary = "任务异常终止：IllegalStateException",
                atMillis = 500L,
            ),
        )
        val card = items.filterIsInstance<AgentTimelineItem.Terminal>().single()
        assertEquals(listOf(TerminalAction.OPEN_SETTINGS), card.actions)
        assertEquals("还没配置模型", card.headline)
    }

    @Test
    fun `配置也没配好又不便时，终态卡先说是配置，别把无障碍写成主因`() {
        // HAPP-58 在模拟器上撞到的状态：无障碍没开 + 上次连接测试失败（configProblem 非空）。
        // 那张卡的标题写「无障碍没开，AI 看不见屏幕」，唯一的出口却是「去配置」——
        // 一句话指了两个方向。配置缺失是硬前置，标题必须跟着出口走
        val items = build(
            state = AgentState(isRunning = false, task = "打开相册", phase = AgentState.Phase.IDLE),
            a11yEnabled = false,
            configProblem = "上次连接测试失败：HTTP 401",
            lastOutcome = TaskOutcome(
                status = TaskStatus.FAILED,
                summary = "上次连接测试失败：HTTP 401",
                failureKind = AgentState.FailureKind.CONFIG,
                atMillis = 500L,
            ),
        )
        val card = items.filterIsInstance<AgentTimelineItem.Terminal>().single()
        assertEquals("还没配置模型", card.headline)
        assertEquals(listOf(TerminalAction.OPEN_SETTINGS), card.actions)
        assertTrue("原因要指向配置", card.cause.contains("401"))
    }

    @Test
    fun `上一段对话的失败终态不铺到新对话里`() {
        val items = build(
            state = AgentState(isRunning = false, task = "打开相册", phase = AgentState.Phase.IDLE),
            conversationStart = 600L,
            lastOutcome = TaskOutcome(
                status = TaskStatus.FAILED,
                summary = "决策链路连续异常",
                atMillis = 500L,
            ),
        )
        assertTrue("上一段对话的失败不该出现在新对话", items.isEmpty())
    }

    @Test
    fun `成功时只出完成摘要，不重复出终态卡`() {
        val items = build(
            state = AgentState(
                isRunning = false,
                task = "把字体调大",
                phase = AgentState.Phase.DONE,
                message = "字体已调大",
                stepCount = 1,
            ),
            traces = listOf(trace(9, 1)),
            history = listOf(record(1, verified = true, taskId = 9)),
            lastOutcome = TaskOutcome(
                status = TaskStatus.SUCCESS,
                summary = "字体已调大",
                atMillis = 500L,
            ),
        )
        assertEquals(1, items.count { it is AgentTimelineItem.Done })
        assertTrue(items.none { it is AgentTimelineItem.Terminal })
        assertEquals(
            TaskStatus.SUCCESS,
            items.filterIsInstance<AgentTimelineItem.UserTask>().single().status,
        )
    }

    @Test
    fun `完成动作没有摘要但有理由时归为放弃`() {
        val items = build(
            state = AgentState(
                isRunning = false,
                task = "关闭系统更新",
                phase = AgentState.Phase.DONE,
                message = "",
                stepCount = 1,
            ),
            traces = listOf(trace(1, 1)),
            history = listOf(
                record(step = 1, verified = false, type = ActionType.TASK_DONE).let {
                    it.copy(action = it.action?.copy(summary = null, reason = "系统页面不允许修改"))
                },
            ),
        )
        val note = items.filterIsInstance<AgentTimelineItem.AssistantNote>().single()
        assertEquals(AgentTimelineItem.NoteSource.GIVE_UP, note.source)
        assertTrue(note.text.isNotBlank())
    }

    // ---- 对话边界：新建对话后主区域必须是干净的 ----

    @Test
    fun `新建对话后不铺开上一段对话的痕迹与终态`() {
        val items = build(
            state = AgentState(
                isRunning = false,
                task = "把字体调大",
                phase = AgentState.Phase.DONE,
                message = "字体已调大",
                stepCount = 2,
                startedAtMillis = 100L,
            ),
            traces = listOf(trace(100, 1), trace(100, 2)),
            history = listOf(record(1, true, taskId = 100), record(2, true, taskId = 100)),
            conversationStart = 200L,
        )
        assertTrue("上一段对话的标题/步骤/完成摘要都不该出现在新对话里", items.isEmpty())
    }

    @Test
    fun `本对话内的任务照常铺开`() {
        val items = build(
            state = AgentState(
                isRunning = false,
                task = "把字体调大",
                phase = AgentState.Phase.DONE,
                message = "字体已调大",
                stepCount = 2,
                startedAtMillis = 300L,
            ),
            traces = listOf(trace(300, 1), trace(300, 2)),
            history = listOf(record(1, true, taskId = 300), record(2, true, taskId = 300)),
            conversationStart = 200L,
        )
        assertEquals(listOf(1, 2), items.steps().map { it.step })
        assertTrue(items.any { it is AgentTimelineItem.Done })
    }

    @Test
    fun `回看上一段对话的历史任务仍能铺开它的步骤`() {
        val items = build(
            traces = listOf(trace(100, 1), trace(100, 2)),
            history = listOf(record(1, true, taskId = 100), record(2, true, taskId = 100)),
            focusTaskId = 100L,
            conversationStart = 200L,
        )
        assertEquals(listOf(1, 2), items.steps().map { it.step })
    }
}