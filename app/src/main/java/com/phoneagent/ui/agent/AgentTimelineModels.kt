package com.phoneagent.ui.agent

import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.domain.model.TaskStatus

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
        /**
         * 这次任务已经结束时的终态（null = 还在跑 / 还没开始）。
         * 「最近任务」这条 chip 只有标题时，用户分辨不出它是刚才成功那次还是失败那次。
         */
        val status: TaskStatus? = null,
    ) : AgentTimelineItem {
        override val key: String get() = "ut#$ownerKey"
    }

    /** 规划阶段的流式思考文本（恒定 key，始终是最新一条） */
    data class PlanStreaming(val text: String) : AgentTimelineItem {
        override val key: String get() = "plan#live"
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

    /**
     * 连续若干步工具调用：聊天流里收成一条「调用了 N 个工具」的回显行。
     *
     * 图标连排（几个图标连在一起，就是调用了几个工具），点开才铺开每一步的细节。
     * 单步也走这条，形态与多步一致。
     */
    data class ToolChain(
        val runKey: String,
        val steps: List<StepCall>,
    ) : AgentTimelineItem {
        /**
         * key 只锚在**链首步**上，不用首尾区间：链是边走边长的，用尾步号做 key
         * 会让每来一步就换一个 key —— LazyColumn 视作新项、展开状态被丢掉、
         * 入场动画重播。锚住链首，同一条链自始至终是同一项。
         */
        override val key: String get() = "tc#$runKey#${steps.firstOrNull()?.step}"
    }

    /** 连续中间状态折叠成的恒定一条 */
    data class LiveStatus(
        val phase: AgentState.Phase,
        val message: String,
        val foldedCount: Int,
        /** AI 正在生成的流式内容（空串 = 当前没有流式输出） */
        val streaming: String = "",
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

    /**
     * 任务终态卡：除「完成」以外的一切结束方式（失败 / 未达成 / 用户停止）都走这一条。
     *
     * 引擎收尾会把 [AgentState.phase] 落回 IDLE（见 AgentEngine.stop），因此成败只能来自
     * 引擎显式交出的 TaskOutcome。[cause] 是给用户看的可行动原因，[detail] 是引擎原始消息，
     * [actions] 决定卡片底部摆哪几个出口。
     */
    data class Terminal(
        val status: TaskStatus,
        val headline: String,
        /** 可行动的原因（人话）：优先说「哪里需要你动手」，而不是「AI 出了点问题」 */
        val cause: String,
        /** 引擎原始消息：默认折叠，需要时展开看细节 */
        val detail: String,
        val actions: List<TerminalAction>,
        /** 三态步数：已确认生效 / 已交付未确认 / 未生效 —— 「未确认」不是「失败」 */
        val okSteps: Int = 0,
        val unconfirmedSteps: Int = 0,
        val failedSteps: Int = 0,
        val elapsedMs: Long = 0L,
    ) : AgentTimelineItem {
        override val key: String get() = "live#terminal"
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
     * AI 主动对用户说的一句话（say 意图）。
     * 它不是操作、不占执行步：只在任务流里作为一条对话消息出现，正文支持 Markdown。
     */
    data class Say(
        val id: Long,
        val text: String,
        val runKey: String,
        val step: Int,
    ) : AgentTimelineItem {
        override val key: String get() = "say#$runKey#$id"
    }

    /**
     * AI 澄清歧义时提出的问题（左对齐 AI 气泡）。
     * [exchangeId] 锚定这一组往来：live 表示正在回答、还没定答案；cN 表示已完成的第 N 组
     *（与同组的 [ClarifyAnswer] 共用同一个交换 id，key 才各自唯一）。
     */
    data class ClarifyQuestion(
        val question: String,
        val options: List<ClarificationOption>,
        val exchangeId: String,
    ) : AgentTimelineItem {
        override val key: String get() = "cq#$exchangeId"
    }

    /**
     * 用户在澄清中选择的答案（右对齐用户气泡 + 主题色描边）。
     * [question] 只为完整承载这一组往来；展示时问题已由同组的 AI 气泡呈现，这里只渲染选择。
     */
    data class ClarifyAnswer(
        val question: String,
        val answer: String,
        val exchangeId: String,
    ) : AgentTimelineItem {
        override val key: String get() = "ca#$exchangeId"
    }

    /**
     * 合并形态下的一次纯对话问答：用户问题 + AI 回答成组出现。
     *
     * 分开形态里这两句在「对话」Tab 看着，合并后对话 Tab 收起，任务流必须自己把它们摆出来，
     * 否则用户问完一句就什么都看不到。
     */
    data class ChatTurn(
        val id: Long,
        val question: String,
        val answer: String,
    ) : AgentTimelineItem {
        override val key: String get() = "ct#$id"
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
 * 单步工具调用（trace 与 record 按 step 合并后的一条）。
 *
 * 它不是列表项：连续的若干步会被 [AgentTimelineItem.ToolChain] 收成一条回显行，
 * 界面从它身上取"调用了哪个工具"（[toolType] → 图标 + 工具名）与这步的细节。
 */
internal data class StepCall(
    val runKey: String,
    val step: Int,
    /** AI 输出的原始动作类型（click / type / shell …），用于选工具图标与工具名 */
    val toolType: String,
    val actionVerb: String,
    val human: String,
    val confidence: Double?,
    val verified: Boolean,
    val failed: Boolean,
    /**
     * 已交付、但读不到页面：既没确认生效，也没有证据说失败。
     *
     * 与 [failed]（未生效）分开：二者的下一步完全不同——一个该等页面可读，一个该换入口。
     * 这个状态过去在界面上没有位置，用户只看到「什么标签都没有」的一步。
     */
    val unconfirmed: Boolean = false,
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
) {
    /** 稳定 key：同一步在同一任务内唯一（供 rememberSaveable 记住展开状态） */
    val key: String get() = "sc#$runKey#$step"
}

/**
 * 失败卡片在「没有可读原因」时的兜底文案。
 *
 * 之前这三种情况各写各的（归档中断、归档失败、实时错误），改了其中一处，
 * 另外两处就会留成另一种说法，用户看到的口径也就不一致。集中在这里一处改全改。
 */
internal object AgentFallbackCopy {
    /** 归档会话：用户主动停止且没有摘要 */
    const val ABORTED = "任务已停止"
    /** 归档会话：失败且没有摘要 */
    const val FAILED = "任务未完成"
    /** 实时错误：状态里没带消息 */
    const val ERROR = "任务执行出错，已停止"
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
/** 终态卡上的可执行出口 */
internal enum class TerminalAction {
    /** 重跑上一次任务 */
    RETRY,

    /** 去模型配置（Key / 模型 / 接口地址） */
    OPEN_SETTINGS,

    /** 去开启无障碍（读屏能力） */
    OPEN_PERMISSIONS,
}
