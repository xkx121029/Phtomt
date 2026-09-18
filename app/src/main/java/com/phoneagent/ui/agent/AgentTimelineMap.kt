package com.phoneagent.ui.agent

import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.domain.model.StepTrace
import com.phoneagent.engine.PlanPhase
import com.phoneagent.feature.document.DocResult

/**
 * 引擎状态 → 任务流列表项的**纯函数**映射层（无 Compose 依赖，可离线单测）。
 *
 * 数据来源只用引擎已有状态：traces（每步决策追踪，带 taskId）、executionHistory（每步执行结果）、
 * AgentState（相位/消息/步数）、PlanPhase + planStream（规划流程）、taskQueue。
 * **刻意不用 AgentEngine.conversation**：那里 user 角色存的是发给模型的完整决策 prompt，
 * 渲染成气泡等于把内部提示词泄露到界面，且每次任务开始都会被清空。
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
        expandedRuns: Set<Long> = emptySet(),
        foldRunThreshold: Int = 8,
        fold: LiveStatusFold = LiveStatusFold(),
        doc: DocResult? = null,
        decisionStream: String = "",
    ): List<AgentTimelineItem> {
        val items = ArrayList<AgentTimelineItem>()

        // 1) 无障碍是硬前置：未开启时 AI 读不到控件，必须在任务流顶部说明
        if (!a11yEnabled) {
            items += AgentTimelineItem.Notice(
                AgentTimelineItem.NoticeKind.ACCESSIBILITY,
                "无障碍服务未开启，AI 无法读取页面控件",
            )
        }

        // 2) 历史任务分区（按 taskId 升序，最新一个永远展开）
        val runs = buildRuns(traces, history)
        val newest = runs.lastOrNull()
        runs.forEach { run ->
            val isNewest = newest != null && run.taskId == newest.taskId
            val folded = !isNewest && run.steps.size > foldRunThreshold && run.taskId !in expandedRuns
            if (folded) {
                items += AgentTimelineItem.RunDigest(
                    runKey = run.runKey,
                    task = run.taskName,
                    steps = run.steps.size,
                    okSteps = run.steps.count { it.verified },
                )
                return@forEach
            }
            items += AgentTimelineItem.UserTask(run.taskName, queued = false, ownerKey = run.runKey)
            // 已批准的计划只挂在最新任务上（批准后 planPhase 会一直停在 Approved）
            if (isNewest && planPhase is PlanPhase.Approved) {
                planPhase.plan?.let { items += AgentTimelineItem.PlanApproved(it) }
            }
            run.steps.forEach { step ->
                items += stepItem(run.runKey, step)
                step.trace?.visionDescription?.takeIf { it.isNotBlank() }?.let { desc ->
                    items += AgentTimelineItem.AssistantNote(
                        text = desc.take(VISION_MAX),
                        source = AgentTimelineItem.NoteSource.VISION,
                        runKey = run.runKey,
                        step = step.step,
                    )
                }
            }
        }

        // 3) 需要协助：动作连续未生效 / 敏感页只读保护
        if (needsUser) {
            items += AgentTimelineItem.NeedsUser(
                reason = needsUserReason.takeIf { it.isNotBlank() } ?: "Agent 已暂停，等待你接管或指示",
                step = newest?.steps?.lastOrNull()?.step ?: state.stepCount,
            )
        }

        // 4) 实时中间状态：恒定一条，只展示最新（ERROR 交给 Failed 项）
        if (state.isRunning && state.phase != AgentState.Phase.ERROR) {
            items += AgentTimelineItem.LiveStatus(
                phase = state.phase,
                message = state.message.ifBlank { phaseLabel(state.phase) },
                foldedCount = fold.observe(newest?.runKey ?: "", state.message),
                // AI 正在生成的正文（限长，尾部滚动展示即可）
                streaming = decisionStream.takeLast(STREAM_MAX),
                startedAtMillis = state.startedAtMillis,
            )
        }

        // 5) 结束态：完成摘要 / 失败原因
        when (state.phase) {
            AgentState.Phase.DONE -> {
                val run = newest
                val planned = (planPhase as? PlanPhase.Approved)?.plan?.steps?.size ?: 0
                items += AgentTimelineItem.Done(
                    okSteps = run?.steps?.count { it.verified } ?: 0,
                    totalSteps = if (planned > 0) planned else (run?.steps?.size ?: state.stepCount),
                    tokens = run?.steps?.sumOf { it.tokens } ?: 0,
                    avgLatencyMs = run?.steps?.takeIf { it.isNotEmpty() }
                        ?.let { list -> list.sumOf { it.durationMs } / list.size } ?: 0L,
                    note = state.message.take(80),
                )
                runLevelNote(run, state.message)?.let { items += it }
            }

            AgentState.Phase.ERROR -> {
                items += AgentTimelineItem.Failed(state.message.ifBlank { "任务执行出错，已停止" })
            }

            else -> Unit
        }

        // 6) 待批准的规划（新任务尚未产生 traces）
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

        // 7) 排队等待执行的任务
        queue.forEachIndexed { index, text ->
            items += AgentTimelineItem.UserTask(text, queued = true, ownerKey = "q$index")
        }

        // 8) AI 生成的文档结果：直接在任务流里预览（原工作区页面已移除）
        doc?.takeIf { it.content.isNotBlank() }?.let {
            items += AgentTimelineItem.DocPreview(fileName = it.fileName, content = it.content)
        }

        // 稳定 key 兜底：历史上 ChatPanel 出现过 key 冲突导致闪退
        return items.distinctBy { it.key }
    }

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
     * 按 taskId 分组，并把执行记录按 step 并入（一一对应，缺一方时用另一方）。
     *
     * StepRecord 没有 taskId，故按 step 建立先进先出队列逐个消费：
     * 第 1 个任务的第 3 步取走第 1 条 step=3 的记录，第 2 个任务的第 3 步取走第 2 条，
     * 不依赖下标、不会把旧任务的记录错挂到新任务上。
     */
    private fun buildRuns(traces: List<StepTrace>, history: List<StepRecord>): List<RunData> {
        if (traces.isEmpty()) return emptyList()
        val recordQueue = HashMap<Int, ArrayDeque<StepRecord>>()
        history.forEach { recordQueue.getOrPut(it.step) { ArrayDeque() }.addLast(it) }

        val grouped = LinkedHashMap<Long, MutableList<StepTrace>>()
        traces.sortedBy { it.taskId }.forEach { grouped.getOrPut(it.taskId) { mutableListOf() }.add(it) }

        return grouped.map { (taskId, list) ->
            val ordered = list.sortedBy { it.step }
            val steps = ordered.map { trace ->
                MergedStep(
                    step = trace.step,
                    trace = trace,
                    record = recordQueue[trace.step]?.removeFirstOrNull(),
                )
            }
            RunData(
                taskId = taskId,
                runKey = "r$taskId",
                taskName = ordered.firstNotNullOfOrNull { it.taskName?.takeIf { n -> n.isNotBlank() } }.orEmpty(),
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