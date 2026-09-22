package com.phoneagent.ui.agent

import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.Clarification
import com.phoneagent.domain.model.TaskPlan

/**
 * Agent 页任务流的列表项模型（纯数据，无 Compose 依赖）。
 *
 * 只承载"已发生的事实"：全部字段都来自引擎已有状态（traces / executionHistory /
 * AgentState / PlanPhase / planStream），**不含任何由 AI 现编的台词**。
 *
 * [key] 一律由数据派生（runKey + step + 语义 id），不得使用列表下标，
 * 供 LazyColumn 稳定复用（历史上出现过 key 冲突闪退）。
 */
internal sealed interface AgentTimelineItem {

    val key: String

    /** 用户下达的任务（含排队待执行的）。[ownerKey] 用于生成稳定 key（runKey / pending / qN） */
    data class UserTask(
        val text: String,
        val queued: Boolean,
        val ownerKey: String = "",
    ) : AgentTimelineItem {
        override val key: String get() = "ut#$ownerKey"
    }

    /** 规划阶段的流式思考文本（恒定 key，始终是最新一条） */
    data class PlanStreaming(val text: String) : AgentTimelineItem {
        override val key: String get() = "plan#live"
    }

    /** 规划请求澄清（歧义） */
    data class PlanClarify(val clarification: Clarification) : AgentTimelineItem {
        override val key: String get() = "plan#clarify#${clarification.question.hashCode()}"
    }

    /** 计划已生成，等待用户批准 */
    data class PlanApproval(val plan: TaskPlan) : AgentTimelineItem {
        override val key: String get() = "plan#approval"
    }

    /** 计划已批准，正在执行 */
    data class PlanApproved(val plan: TaskPlan) : AgentTimelineItem {
        override val key: String get() = "plan#approved"
    }

    /** 规划失败 */
    data class PlanFailed(val message: String) : AgentTimelineItem {
        override val key: String get() = "plan#failed"
    }

    /** 助手真实文本（完成/放弃摘要、视觉描述），默认折叠，不编造 */
    data class AssistantNote(
        val text: String,
        val source: NoteSource,
        val runKey: String,
        val step: Int,
    ) : AgentTimelineItem {
        override val key: String get() = "an#${source.id}#$runKey#$step"
    }

    /** 单步工具调用（trace 与 record 按 step 合并后的一条） */
    data class StepCall(
        val runKey: String,
        val step: Int,
        val actionVerb: String,
        val human: String,
        val confidence: Double?,
        val verified: Boolean,
        val failed: Boolean,
        val durationMs: Long,
        val tokens: Int,
        val thinking: String?,
        val rawReceived: String,
        val rawSent: String,
        val hasScreenshot: Boolean,
        /** 该步实际执行的命令（仅 shell 类动作有值） */
        val shellCommand: String = "",
        /** 命令输出：成功为 stdout/stderr，失败为可读原因 */
        val shellOutput: String = "",
        /** 执行/转译结果说明：让"未生效"带上原因，而不是一句没有解释的结论 */
        val detail: String = "",
        /** 该步是端侧（本地）决策产出的（云端决策为 false） */
        val fromLocalDecision: Boolean = false,
    ) : AgentTimelineItem {
        override val key: String get() = "sc#$runKey#$step"
    }

    /** 连续中间状态折叠成的恒定一条 */
    data class LiveStatus(
        val phase: AgentState.Phase,
        val message: String,
        val foldedCount: Int,
        /** AI 正在生成的流式内容（空串 = 当前没有流式输出） */
        val streaming: String = "",
        /** 本次任务开始时间戳（用于显示"已工作 N 秒"；0 = 未知） */
        val startedAtMillis: Long = 0,
    ) : AgentTimelineItem {
        override val key: String get() = "live#status"
    }

    /** 需要用户协助（敏感页保护 / 动作连续未生效） */
    data class NeedsUser(val reason: String, val step: Int) : AgentTimelineItem {
        override val key: String get() = "live#needs"
    }

    /** 任务完成摘要 */
    data class Done(
        val okSteps: Int,
        val totalSteps: Int,
        val tokens: Int,
        val avgLatencyMs: Long,
        val note: String,
    ) : AgentTimelineItem {
        override val key: String get() = "live#done"
    }

    /** 任务失败/放弃 */
    data class Failed(val message: String) : AgentTimelineItem {
        override val key: String get() = "live#failed"
    }

    /** 内嵌提示（缺权限等） */
    data class Notice(val kind: NoticeKind, val text: String) : AgentTimelineItem {
        override val key: String get() = "notice#${kind.name.lowercase()}"
    }

    /** AI 生成的文档结果（write_doc 产出），直接嵌在任务流里预览 */
    data class DocPreview(
        val fileName: String,
        val content: String,
    ) : AgentTimelineItem {
        override val key: String get() = "doc#$fileName"
    }

    /**
     * AI 在本次任务中写入/更新了一条记忆（remember 意图或任务结束提炼）。
     * [updated] 为 true 表示与已有记忆合并更新，而不是新增。
     * 只承载"本轮刚发生"的写入，供用户当场看到并撤销。
     */
    data class MemoryAdded(
        val id: Long,
        val content: String,
        val category: String,
        val updated: Boolean,
        val runKey: String,
        val step: Int,
    ) : AgentTimelineItem {
        override val key: String get() = "mem#$runKey#$id#$step"
    }

    /**
     * 提示类型。
     * 只有「无障碍未开启」会阻断 AI 读页面，属于真问题；
     * 悬浮窗是可选能力，不在此提示（改由设置页开关控制）。
     */
    enum class NoticeKind { ACCESSIBILITY }

    /**
     * 助手文本的真实来源。
     * [THINKING] 预留给"思考模型流式输出"（当前引擎只把思考文本推给悬浮窗，未落成状态流），暂未产生列表项。
     */
    enum class NoteSource(val id: String) {
        FINISH("fin"),
        GIVE_UP("giveup"),
        THINKING("think"),
        VISION("vision"),
    }
}

/**
 * 中间状态折叠计数：把"连着变了好几条、但只展示最新一条"的差额记下来。
 *
 * 纯函数映射层没有记忆，故由调用方持有一个实例（每个任务流一个），
 * 以 runKey 为界：换任务自动归零。
 */
internal class LiveStatusFold {

    private var ownerRunKey: String = ""
    private var lastMessage: String? = null
    private var count: Int = 0

    /** 观察一条实时状态，返回累计被折叠掉的条数 */
    fun observe(runKey: String, message: String): Int {
        if (runKey != ownerRunKey) {
            ownerRunKey = runKey
            lastMessage = null
            count = 0
        }
        if (message.isBlank() || message == lastMessage) return count
        if (lastMessage != null) count++
        lastMessage = message
        return count
    }

    fun reset() {
        ownerRunKey = ""
        lastMessage = null
        count = 0
    }
}