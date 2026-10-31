package com.phoneagent.coding.agent

/**
 * JSON-lines 事件 → 消息列表变更的纯逻辑（Superagent 融合 Phase 7 可测性抽取）。
 *
 * 从 [AgentClient.handleEvent] 原样抽出：输入是**已解析的基本类型字段**（不用 org.json，
 * android.jar stub 在 JVM 单测下会抛 "Stub!"），输出是应用完单个事件后的新状态切片。
 * AgentClient 负责解析 JSONObject 与重连调度，Reducer 只做"事件 → 状态"的映射，
 * 两者的行为契约（text 增量拼接、tool 卡匹配规则、审批置灰、done 置闲、host_exited 标记）逐分支一致。
 *
 * 无人值守增强（与并发工具/并行子 agent 配套）：
 * - tool 卡按 [ChatMessage.toolCallId] 匹配，同轮两个同名工具不会串卡；
 * - tool_end 回填耗时，子 agent 事件生成独立的进度卡；
 * - 输出 [phase]/[iteration]/[elapsedMs] 供界面显示"跑到哪一步、第几轮、多久"。
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
        /** 当前阶段文案；null = 不改，空串 = 清空（工具/本轮结束） */
        val phase: String? = null,
        /** 当前工具循环轮次；null = 不改 */
        val iteration: Int? = null,
    )

    /**
     * 把一个 agent 事件应用到当前消息列表。
     *
     * @param type          事件类型（text / thinking / run_start / iteration / provider_* / tool_start /
     *                      tool_approval / tool_end / approval_request / subagent_* / heartbeat / done / cancelled / host_exited）
     * @param messages      当前消息列表（调用方在锁内传入最新值）
     * @param assistantKey  当前「正在流式输出的 assistant 消息」key；null 表示无在写消息
     * @param nextId        生成新消息 key 的回调（AgentClient 传自增序列）
     * @param delta         text / subagent_text 事件的增量文本
     * @param name          tool_start / tool_approval / tool_end 的工具名
     * @param ok            tool_end / subagent_end 的成败（org.json optBoolean 的缺省 false 原样传入）
     * @param approved      tool_approval 的审批结论（缺省 true，与 optBoolean("approved", true) 一致）
     * @param why           tool_approval 被拒时的原因
     * @param callId        approval_request / tool_* 的调用 id
     * @param tool          approval_request 的工具名（作为卡片正文）
     * @param code          host_exited 的退出码
     * @param argsPreview   tool_start 的参数摘要（AgentClient 侧由 org.json 渲染后传入）
     * @param durationMs    tool_end / subagent_end / done 的耗时
     * @param subagentId    子 agent 事件的子任务 id
     * @param subagentLabel subagent_start 的任务名
     * @param promptPreview subagent_start 的任务摘要（卡片正文）
     * @param iteration     iteration 事件的轮次序号
     * @param errorDetail   subagent_end 的失败原因
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
        durationMs: Long = 0L,
        subagentId: String = "",
        subagentLabel: String = "",
        subagentPersona: String = "",
        promptPreview: String = "",
        iteration: Int = 0,
        errorDetail: String = "",
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

        // 推理模型的思考过程不进正文，也不新起气泡：无人值守时它只是噪声，
        // 但阶段要切到"接收输出"，否则界面看起来像卡在等首包。
        "thinking" -> Result(messages = messages, assistantKey = assistantKey, phase = "接收输出")

        "run_start" -> Result(messages = messages, assistantKey = null, busy = true, phase = "启动中")

        "iteration" -> Result(
            messages = messages,
            assistantKey = assistantKey,
            iteration = iteration,
            phase = "第 $iteration 轮",
        )

        "provider_request" -> Result(messages = messages, assistantKey = assistantKey, phase = "等待模型响应")

        "provider_first_token" -> Result(messages = messages, assistantKey = assistantKey, phase = "接收输出")

        "provider_stream_end" -> Result(messages = messages, assistantKey = assistantKey, phase = "")

        "tool_start" -> Result(
            messages = messages + ChatMessage(
                key = "t_${nextId()}",
                role = "tool",
                toolName = name,
                text = argsPreview,
                // 并行同名工具靠 id 分账：tool_end 不来名字也会来 id。
                toolCallId = callId.ifEmpty { null },
            ),
            assistantKey = null,
            phase = "执行 ${name.ifEmpty { "工具" }}",
        )

        // agent 对每次工具调用都广播一次审批结论。approved=false 时把原因写进卡片，
        // 否则用户点了「允许」只看到卡片变灰，不知道到底发生了什么。
        "tool_approval" -> {
            if (!approved) {
                val why2 = why.ifEmpty { "被拒绝" }
                val idx = toolCardIndex(messages, name, callId)
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
            val idx = toolCardIndex(messages, name, callId)
            val msgs = if (idx >= 0) {
                messages.toMutableList().also {
                    it[idx] = it[idx].copy(
                        toolOk = ok,
                        durationMs = durationMs.takeIf { d -> d > 0 },
                    )
                }
            } else {
                messages
            }
            Result(messages = msgs, assistantKey = assistantKey, phase = "")
        }

        "approval_request" -> Result(
            messages = messages + ChatMessage(
                key = "p_$callId", role = "approval", approvalCallId = callId, text = tool,
            ),
            assistantKey = assistantKey,
        )

        // —— 并行子 agent ——
        // 子 agent 的进度单独一张卡：主界面能同时看到几个子任务各自跑到哪、谁在干。
        "subagent_start" -> {
            val who = listOfNotNull(subagentPersona.ifEmpty { null }, subagentLabel.ifEmpty { null }).joinToString(" · ")
            Result(
                messages = messages + ChatMessage(
                    key = "s_${subagentId}_${nextId()}",
                    role = "subagent",
                    toolName = subagentLabel.ifEmpty { subagentId },
                    text = promptPreview,
                    subagentId = subagentId,
                    subagentLabel = subagentLabel,
                    subagentPersona = subagentPersona.ifEmpty { null },
                ),
                assistantKey = assistantKey,
                phase = if (who.isEmpty()) "子 agent" else "子 agent $who",
            )
        }

        "subagent_text" -> {
            val idx = subagentIndex(messages, subagentId)
            val msgs = if (idx >= 0) {
                messages.toMutableList().also { list ->
                    list[idx] = list[idx].copy(text = (list[idx].text + delta).takeLast(600))
                }
            } else {
                messages
            }
            // 子 agent 的正文不要接进主 assistant 气泡：两条流会串成一句话。
            Result(messages = msgs, assistantKey = assistantKey)
        }

        "subagent_end" -> {
            val idx = subagentIndex(messages, subagentId)
            val msgs = if (idx >= 0) {
                messages.toMutableList().also { list ->
                    list[idx] = list[idx].copy(
                        toolOk = ok,
                        durationMs = durationMs.takeIf { d -> d > 0 },
                        text = if (ok) {
                            "完成 · ${formatDuration(durationMs)}"
                        } else {
                            "失败：${errorDetail.ifEmpty { "未知原因" }}"
                        },
                    )
                }
            } else {
                messages
            }
            Result(messages = msgs, assistantKey = assistantKey, phase = "")
        }

        // 心跳：界面不需要它做任何事（计时器本地跑），保留分支是为了明示
        //「心跳不改状态」——否则以后有人顺手把阶段清掉，长工具执行时就再也看不到「执行 xxx」。
        "heartbeat" -> Result(messages = messages, assistantKey = assistantKey)

        "done", "cancelled" -> Result(
            messages = messages,
            assistantKey = null,
            busy = false,
            phase = "",
        )

        "host_exited" -> Result(
            messages = messages,
            assistantKey = null,
            busy = false,
            conn = ConnState.DISCONNECTED,
            error = "agent host exited (code $code)，正在重连…",
            hostExited = true,
            phase = "",
        )

        else -> Result(messages = messages, assistantKey = assistantKey)
    }

    /** 工具卡定位：优先按 callId（并行安全），没有 id 时退回「最后一张同名未结卡」。 */
    private fun toolCardIndex(messages: List<ChatMessage>, name: String, callId: String): Int {
        if (callId.isNotEmpty()) {
            val byId = messages.indexOfLast { it.role == "tool" && it.toolCallId == callId }
            if (byId >= 0) return byId
        }
        return messages.indexOfLast { it.role == "tool" && it.toolName == name && it.toolOk == null }
    }

    private fun subagentIndex(messages: List<ChatMessage>, subagentId: String): Int =
        if (subagentId.isEmpty()) -1 else messages.indexOfLast { it.role == "subagent" && it.subagentId == subagentId }

    private fun formatDuration(ms: Long): String = when {
        ms <= 0 -> "—"
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
}
