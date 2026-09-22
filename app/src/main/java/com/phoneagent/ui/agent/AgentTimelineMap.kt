package com.phoneagent.ui.agent

import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.domain.model.StepTrace
import com.phoneagent.engine.MemoryEvent
import com.phoneagent.engine.PlanPhase
import com.phoneagent.engine.TaskSession
import com.phoneagent.feature.document.DocResult

/**
 * 引擎状态 → 任务流列表项的**纯函数**映射层（无 Compose 依赖，可离线单测）。
 *
 * 数据来源只用引擎已有状态：traces（每步决策追踪，带 taskId）、executionHistory（每步执行结果）、
 * AgentState（相位/消息/步数）、PlanPhase + planStream（规划流程）、taskQueue。
 * **刻意不用 AgentEngine.conversation**：那里 user 角色存的是发给模型的完整决策 prompt，
 * 渲染成气泡等于把内部提示词泄露到界面，且每次任务开始都会被清空。
 *
 * 一次只铺开**一个任务**：默认跟随实时任务（最新一次执行 + 正在进行的规划），
 * 也可用 [focusTaskId] 指定回看某次历史任务（配 [archived] 取标题/计划/摘要/记忆）。
 * 更早的任务不再混进当前任务流，改由侧边栏按任务列出、点谁看谁。
 */
internal object AgentTimelineMapper {

    /** 人话摘要的最大长度，防止超长文本把一条列表项撑爆 */
    private const val HUMAN_MAX = 200

    /** 视觉描述（OCR/模型输出）单条最大长度 */
    private const val VISION_MAX = 400

    /** 实时流式回显保留的最大字符数（只展示尾部，避免长文把列表项撑爆） */
    private const val STREAM_MAX = 800

    /** 引擎在端侧决策留档时写入 StepTrace.visionSource 的标注值（对应 AgentEngine.LOCAL_DECISION_SOURCE） */
    private const val SOURCE_LOCAL_DECISION = "端侧决策"

    private val typeRegex = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")

    fun build(
        submittedTask: String,
        state: AgentState,
        planPhase: PlanPhase,
        planText: String,
        traces: List<StepTrace>,
        history: List<StepRecord>,
        queue: List<String>,
        needsUser: Boolean,
        needsUserReason: String,
        a11yEnabled: Boolean,
        fold: LiveStatusFold = LiveStatusFold(),
        doc: DocResult? = null,
        decisionStream: String = "",
        /** 本次任务内 AI 写入的记忆事件（引擎内存态），实时插卡 */
        memoryEvents: List<MemoryEvent> = emptyList(),
        /** 只看这一次任务；null = 跟随实时（最新一次执行 + 尚未产生执行的规划流程） */
        focusTaskId: Long? = null,
        /** 焦点任务是历史会话时，引擎归档的标题 / 计划 / 摘要 / 记忆 */
        archived: TaskSession? = null,
    ): List<AgentTimelineItem> {
        val items = ArrayList<AgentTimelineItem>()
        // 已挂到具体步骤上的记忆事件 id，避免末尾兜底时重复插入
        val consumedMemoryIds = HashSet<Long>()
        val runs = buildRuns(traces, history)
        val newestId = runs.lastOrNull()?.taskId
        // 焦点就是「正在跑的那一次」：这时才渲染实时状态、结束态、待批准的计划等活的流程数据
        val isLive = focusTaskId == null || focusTaskId == newestId
        val focusRun = if (focusTaskId == null) newestId?.let { id -> runs.first { it.taskId == id } }
        else runs.firstOrNull { it.taskId == focusTaskId }
        val events = if (isLive) memoryEvents else archived?.memoryEvents.orEmpty()

        // 1) 无障碍是硬前置：未开启时 AI 读不到控件，必须在任务流顶部说明（历史回看不提示）
        if (isLive && !a11yEnabled) {
            items += AgentTimelineItem.Notice(
                AgentTimelineItem.NoticeKind.ACCESSIBILITY,
                "无障碍服务未开启，AI 无法读取页面控件",
            )
        }

        // 2) 焦点任务的标题 —— 实时看引擎状态里的任务名，历史回看用归档标题
        val focusTitle = focusRun?.taskName?.takeIf { it.isNotBlank() }
            ?: archived?.title.orEmpty()
            ?: if (isLive) "" else "历史任务"
        if (focusTitle.isNotBlank()) {
            items += AgentTimelineItem.UserTask(
                text = focusTitle,
                queued = false,
                ownerKey = focusRun?.runKey ?: "r$focusTaskId",
            )
        }

        // 3) 已批准的计划：实时的取当前 PlanPhase，历史的取归档里存的那一份
        val focusPlan = if (isLive) (planPhase as? PlanPhase.Approved)?.plan else archived?.plan
        focusPlan?.let { items += AgentTimelineItem.PlanApproved(it) }

        // 4) 本次任务的每一步（决策 + 执行合并），以及挂在步骤上的记忆卡片
        focusRun?.steps?.forEach { step ->
            items += stepItem(focusRun.runKey, step)
            events.filter { it.runKey == focusRun.runKey && it.step == step.step }.forEach { ev ->
                consumedMemoryIds += ev.id
                items += memoryItem(ev, focusRun.runKey, step.step)
            }
            step.trace?.visionDescription?.takeIf { it.isNotBlank() }?.let { desc ->
                items += AgentTimelineItem.AssistantNote(
                    text = desc.take(VISION_MAX),
                    source = AgentTimelineItem.NoteSource.VISION,
                    runKey = focusRun.runKey,
                    step = step.step,
                )
            }
        }

        // 5) 没挂上具体步骤的记忆（如任务结束后的提炼）：统一跟在步骤之后；历史回看时给出归档标题兜底
        events.filterNot { it.id in consumedMemoryIds }.forEach { ev ->
            items += memoryItem(ev, ev.runKey, ev.step)
        }

        if (!isLive) {
            // 6) 历史任务的终态：归档只留了结论，不回放当时的实时状态
            when (archived?.status) {
                TaskSession.Status.DONE -> items += AgentTimelineItem.Done(
                    okSteps = focusRun?.steps?.count { it.verified } ?: archived.okSteps,
                    totalSteps = focusPlan?.steps?.size ?: archived.steps,
                    tokens = focusRun?.steps?.sumOf { it.tokens } ?: 0,
                    avgLatencyMs = focusRun?.steps?.takeIf { it.isNotEmpty() }
                        ?.let { list -> list.sumOf { it.durationMs } / list.size } ?: 0L,
                    note = archived.summary.take(80),
                )

                TaskSession.Status.RUNNING -> Unit
                else -> items += AgentTimelineItem.Failed(
                    archived?.summary?.takeIf { it.isNotBlank() } ?: "任务未完成",
                )
            }
            return items.distinctBy { it.key }
        }

        // 7) 需要协助：动作连续未生效 / 敏感页只读保护
        if (needsUser) {
            items += AgentTimelineItem.NeedsUser(
                reason = needsUserReason.takeIf { it.isNotBlank() } ?: "Agent 已暂停，等待你接管或指示",
                step = focusRun?.steps?.lastOrNull()?.step ?: state.stepCount,
            )
        }

        // 8) 实时中间状态：恒定一条，只展示最新（ERROR 交给 Failed 项）
        if (state.isRunning && state.phase != AgentState.Phase.ERROR) {
            items += AgentTimelineItem.LiveStatus(
                phase = state.phase,
                message = state.message.ifBlank { phaseLabel(state.phase) },
                foldedCount = fold.observe(focusRun?.runKey ?: "", state.message),
                // AI 正在生成的正文（限长，尾部滚动展示即可）
                streaming = decisionStream.takeLast(STREAM_MAX),
                startedAtMillis = state.startedAtMillis,
            )
        }

        // 9) 结束态：完成摘要 / 失败原因
        when (state.phase) {
            AgentState.Phase.DONE -> {
                val planned = (planPhase as? PlanPhase.Approved)?.plan?.steps?.size ?: 0
                items += AgentTimelineItem.Done(
                    okSteps = focusRun?.steps?.count { it.verified } ?: 0,
                    totalSteps = if (planned > 0) planned else (focusRun?.steps?.size ?: state.stepCount),
                    tokens = focusRun?.steps?.sumOf { it.tokens } ?: 0,
                    avgLatencyMs = focusRun?.steps?.takeIf { it.isNotEmpty() }
                        ?.let { list -> list.sumOf { it.durationMs } / list.size } ?: 0L,
                    note = state.message.take(80),
                )
                runLevelNote(focusRun, state.message)?.let { items += it }
            }

            AgentState.Phase.ERROR -> {
                items += AgentTimelineItem.Failed(state.message.ifBlank { "任务执行出错，已停止" })
            }

            else -> Unit
        }

        // 10) 待批准的规划（新任务尚未产生 traces）
        if (planPhase is PlanPhase.Planning || planPhase is PlanPhase.Clarifying ||
            planPhase is PlanPhase.AwaitingApproval || planPhase is PlanPhase.Error
        ) {
            if (submittedTask.isNotBlank()) {
                items += AgentTimelineItem.UserTask(submittedTask, queued = false, ownerKey = "pending")
            }
            when (planPhase) {
                is PlanPhase.Planning -> if (planText.isNotBlank()) {
                    items += AgentTimelineItem.PlanStreaming(planText)
                }

                is PlanPhase.Clarifying -> items += AgentTimelineItem.PlanClarify(planPhase.clarification)
                is PlanPhase.AwaitingApproval -> items += AgentTimelineItem.PlanApproval(planPhase.plan)
                is PlanPhase.Error -> items += AgentTimelineItem.PlanFailed(planPhase.message)
                else -> Unit
            }
        }

        // 11) 排队等待执行的任务（属于实时任务流，回看历史时不混入）
        queue.forEachIndexed { index, text ->
            items += AgentTimelineItem.UserTask(text, queued = true, ownerKey = "q$index")
        }

        // 12) AI 生成的文档结果：直接在任务流里预览（原工作区页面已移除）
        doc?.takeIf { it.content.isNotBlank() }?.let {
            items += AgentTimelineItem.DocPreview(fileName = it.fileName, content = it.content)
        }

        // 稳定 key 兜底：历史上 ChatPanel 出现过 key 冲突导致闪退
        return items.distinctBy { it.key }
    }

    /** 记忆事件 → 列表项 */
    private fun memoryItem(ev: MemoryEvent, runKey: String, step: Int): AgentTimelineItem.MemoryAdded =
        AgentTimelineItem.MemoryAdded(
            id = ev.id,
            content = ev.content,
            category = ev.category,
            updated = ev.updated,
            runKey = runKey,
            step = step,
        )

    /** 相位中文名（消息为空时的兜底文案，不编造内容） */
    private fun phaseLabel(phase: AgentState.Phase): String = when (phase) {
        AgentState.Phase.IDLE -> "空闲"
        AgentState.Phase.OBSERVING -> "观察页面"
        AgentState.Phase.THINKING -> "思考下一步"
        AgentState.Phase.ACTING -> "执行动作"
        AgentState.Phase.DONE -> "已完成"
        AgentState.Phase.ERROR -> "出错"
    }

    private fun stepItem(runKey: String, step: MergedStep): AgentTimelineItem.StepCall {
        val action: AgentAction? = step.record?.action
        val raw = step.trace?.receivedText.orEmpty()
        val type = action?.type ?: typeRegex.find(raw)?.groupValues?.getOrNull(1)
        val human = action?.let { summarizeAction(it) }
            ?: HumanTranslator.summarizeDecision(raw).take(HUMAN_MAX)
        return AgentTimelineItem.StepCall(
            runKey = runKey,
            step = step.step,
            actionVerb = type?.let { HumanTranslator.actionVerb(it) } ?: "",
            human = human,
            confidence = action?.confidence ?: HumanTranslator.extractConfidence(raw),
            verified = step.verified,
            failed = step.record?.verificationResult == "failed",
            durationMs = step.durationMs,
            tokens = step.trace?.totalTokens ?: 0,
            thinking = HumanTranslator.extractReasoning(raw),
            rawReceived = raw,
            rawSent = step.trace?.sentText.orEmpty(),
            hasScreenshot = step.trace?.screenshot != null,
            // shell 类动作：命令来自 action，输出来自本步执行记录（用户可见的执行证据）
            shellCommand = action?.command.orEmpty(),
            shellOutput = step.record?.shellOutput.orEmpty(),
            detail = step.record?.detail.orEmpty(),
            fromLocalDecision = step.trace?.visionSource == SOURCE_LOCAL_DECISION,
        )
    }

    /** 用执行记录里的结构化动作拼人话；reason 走端侧映射，避免把技术术语抛给用户 */
    private fun summarizeAction(action: AgentAction): String {
        val verb = HumanTranslator.actionVerb(action.type)
        val target = action.target?.value?.takeIf { it.isNotBlank() }
            ?: action.text?.takeIf { it.isNotBlank() }?.take(20)
            ?: action.summary?.takeIf { it.isNotBlank() }?.take(20)
            ?: action.packageName?.takeIf { it.isNotBlank() }
        val sb = StringBuilder(verb)
        if (target != null) sb.append("「$target」")
        action.confidence?.let { sb.append(" · ").append(HumanTranslator.confidenceWord(it)) }
        action.reasoning?.takeIf { it.isNotBlank() }?.let { sb.append(" · 因为：").append(it) }
        return sb.toString().take(HUMAN_MAX)
    }

    /**
     * 任务级助手文本（只在最新任务结束时产出）：
     * - 完成摘要过长时另出一张折叠 note（短摘要已由 Done.note 承载，不重复）；
     * - 记录里的 TASK_DONE 没有 summary 却带 reason → 实为 give_up，明确告知用户"AI 放弃了"。
     */
    private fun runLevelNote(run: RunData?, summary: String): AgentTimelineItem.AssistantNote? {
        val last = run?.steps?.lastOrNull() ?: return null
        val action = last.record?.action
        if (action?.type == ActionType.TASK_DONE && action.summary.isNullOrBlank()) {
            return AgentTimelineItem.AssistantNote(
                text = HumanTranslator.translateError(action.reason ?: "AI 无法自行完成该任务"),
                source = AgentTimelineItem.NoteSource.GIVE_UP,
                runKey = run.runKey,
                step = last.step,
            )
        }
        if (summary.isNotBlank() && summary.length > 80) {
            return AgentTimelineItem.AssistantNote(
                text = summary,
                source = AgentTimelineItem.NoteSource.FINISH,
                runKey = run.runKey,
                step = last.step,
            )
        }
        return null
    }

    /**
     * 按 taskId 分组，把决策追踪与执行记录按 (taskId, step) 并成一次次执行。
     *
     * 两侧都带 taskId（执行记录早期版本没有，恒为 -1），因此一一对应不再依赖消费顺序；
     * 只有那些无归属的旧记录按 step 建先进先出队列，补给缺执行结果的任务，
     * 避免这些步骤只剩「怎么决定的」而没有「执行成没成」。
     */
    private fun buildRuns(traces: List<StepTrace>, history: List<StepRecord>): List<RunData> {
        val traceGroups = traces.groupBy { it.taskId }
        val recordGroups = history.groupBy { it.taskId }
        val legacy = HashMap<Int, ArrayDeque<StepRecord>>()
        recordGroups[-1]?.forEach { legacy.getOrPut(it.step) { ArrayDeque() }.addLast(it) }

        return (traceGroups.keys + recordGroups.keys.filter { it >= 0 }).sorted().map { taskId ->
            val ts = traceGroups[taskId].orEmpty().sortedBy { it.step }
            val rs = recordGroups[taskId].orEmpty().associateBy { it.step }
            val steps = (ts.map { it.step } + rs.keys).distinct().sorted().map { s ->
                MergedStep(
                    step = s,
                    trace = ts.firstOrNull { it.step == s },
                    record = rs[s] ?: legacy[s]?.removeFirstOrNull(),
                )
            }
            RunData(
                taskId = taskId,
                runKey = "r$taskId",
                taskName = ts.firstNotNullOfOrNull { it.taskName?.takeIf { n -> n.isNotBlank() } }
                    ?: rs.values.firstNotNullOfOrNull { it.taskName?.takeIf { n -> n.isNotBlank() } }
                    ?: if (taskId < 0) "无归属步骤" else "",
                steps = steps,
            )
        }
    }

    /** 一次任务的合并视图 */
    private data class RunData(
        val taskId: Long,
        val runKey: String,
        val taskName: String,
        val steps: List<MergedStep>,
    )

    /** 同一步的 trace + record 合并结果 */
    private data class MergedStep(
        val step: Int,
        val trace: StepTrace?,
        val record: StepRecord?,
    ) {
        /** 执行耗时：record 未填充时退回该步决策耗时 */
        val durationMs: Long
            get() = record?.durationMs?.takeIf { it > 0 } ?: trace?.latencyMs ?: 0L

        /** 该步是否已验证生效 */
        val verified: Boolean
            get() = record?.isConfirmed == true || record?.verificationResult == "verified_success"

        /** Token 消耗 */
        val tokens: Int
            get() = trace?.totalTokens ?: 0
    }
}