package com.phoneagent.coding.agent

/**
 * JSON-lines 事件 → 消息列表变更的纯逻辑（Superagent 融合 Phase 7 可测性抽取）。
 *
 * 从 [AgentClient.handleEvent] 原样抽出：输入是**已解析的基本类型字段**（不用 org.json，
 * android.jar stub 在 JVM 单测下会抛 "Stub!"），输出是应用完单个事件后的新状态切片。
 * AgentClient 负责解析 JSONObject 与重连调度，Reducer 只做"事件 → 状态"的映射，
 * 两者的行为契约（text 增量拼接、tool 卡匹配规则、审批置灰、done 置闲、host_exited 标记）逐分支一致。
 */
internal object CodingEventReducer {

    /** 单个事件应用后的状态切片。null 字段一律表示"该事件不改这一项"（调用方保留原值）。 */
    data class Result(
        /** 事件应用后的完整消息列表（不变时原样返回同一引用） */
        val messages: List<ChatMessage>,
        /** 新的「正在流式输出的 assistant 消息」key：text 事件延续旧 key 或分配新 key，tool_start/done/host_exited 置空 */
        val assistantKey: String?,
        /** done / cancelled / host_exited 置 false；其余事件不改（null） */
        val busy: Boolean? = null,
        /** 仅 host_exited 置 DISCONNECTED（重连调度由 AgentClient 负责） */
        val conn: ConnState? = null,
        /** 仅 host_exited 置错误文案 */
        val error: String? = null,
        /** host 进程退出标记：AgentClient 据此触发退避重连 */
        val hostExited: Boolean = false,
    )

    /**
     * 把一个 agent 事件应用到当前消息列表。
     *
     * @param type         事件类型（text / tool_start / tool_approval / tool_end / approval_request / done / cancelled / host_exited）
     * @param messages     当前消息列表（调用方在锁内传入最新值）
     * @param assistantKey 当前「正在流式输出的 assistant 消息」key；null 表示无在写消息
     * @param nextId       生成新消息 key 的回调（AgentClient 传自增序列）
     * @param delta        text 事件的增量文本
     * @param name         tool_start / tool_approval / tool_end 的工具名
     * @param ok           tool_end 的成败（org.json optBoolean 的缺省 false 原样传入）
     * @param approved     tool_approval 的审批结论（缺省 true，与 optBoolean("approved", true) 一致）
     * @param why          tool_approval 被拒时的原因
     * @param callId       approval_request 的调用 id
     * @param tool         approval_request 的工具名（作为卡片正文）
     * @param code         host_exited 的退出码
     * @param argsPreview  tool_start 的参数摘要（AgentClient 侧由 org.json 渲染后传入）
     */
    fun apply(
        type: String,
        messages: List<ChatMessage>,
        assistantKey: String?,
        nextId: () -> String,
        delta: String = "",
        name: String = "",
        ok: Boolean = false,
        approved: Boolean = true,
        why: String = "",
        callId: String = "",
        tool: String = "",
        code: Int = 0,
        argsPreview: String = "",
    ): Result = when (type) {
        "text" -> {
            val key = assistantKey ?: "a_${nextId()}"
            val idx = messages.indexOfFirst { it.key == key }
            val msgs = if (idx >= 0) {
                messages.toMutableList().also { list -> list[idx] = list[idx].copy(text = list[idx].text + delta) }
            } else {
                messages + ChatMessage(key = key, role = "assistant", text = delta)
            }
            Result(messages = msgs, assistantKey = key)
        }

        "tool_start" -> Result(
            messages = messages + ChatMessage(key = "t_${nextId()}", role = "tool", toolName = name, text = argsPreview),
            assistantKey = null,
        )

        // agent 对每次工具调用都广播一次审批结论。approved=false 时把原因写进卡片，
        // 否则用户点了「允许」只看到卡片变灰，不知道到底发生了什么。
        "tool_approval" -> {
            if (!approved) {
                val why2 = why.ifEmpty { "被拒绝" }
                val idx = messages.indexOfLast { it.role == "tool" && it.toolName == name && it.toolOk == null }
                val msgs = if (idx >= 0) {
                    messages.toMutableList().also { it[idx] = it[idx].copy(text = "⛔ $why2", toolOk = false) }
                } else {
                    messages
                }
                Result(messages = msgs, assistantKey = assistantKey)
            } else {
                Result(messages = messages, assistantKey = assistantKey)
            }
        }

        "tool_end" -> {
            val idx = messages.indexOfLast { it.role == "tool" && it.toolName == name && it.toolOk == null }
            val msgs = if (idx >= 0) messages.toMutableList().also { it[idx] = it[idx].copy(toolOk = ok) } else messages
            Result(messages = msgs, assistantKey = assistantKey)
        }

        "approval_request" -> Result(
            messages = messages + ChatMessage(
                key = "p_$callId", role = "approval", approvalCallId = callId, text = tool,
            ),
            assistantKey = assistantKey,
        )

        "done", "cancelled" -> Result(messages = messages, assistantKey = null, busy = false)

        "host_exited" -> Result(
            messages = messages,
            assistantKey = null,
            busy = false,
            conn = ConnState.DISCONNECTED,
            error = "agent host exited (code $code)，正在重连…",
            hostExited = true,
        )

        else -> Result(messages = messages, assistantKey = assistantKey)
    }
}
