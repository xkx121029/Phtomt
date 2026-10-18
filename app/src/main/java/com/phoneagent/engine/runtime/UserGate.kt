package com.phoneagent.engine.runtime

import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ActionType
import com.phoneagent.engine.runtime.EngineLimits.ACTION_CONFIRM_TIMEOUT_MS
import com.phoneagent.engine.runtime.EngineLimits.SELF_DISMISS_HINT
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

/**
 * 引擎用户协作门（重构批次 7a）：三套互相独立的用户等待/确认通道——
 * ① 用户指导信箱（awaitUserHint / provideUserHint / dismissUser）
 * ② AI 自写命令确认信箱（ensureShellApproval / resolveShellApproval）
 * ③ 不可逆动作确认信箱（awaitActionConfirmation / respondActionConfirmation）
 * 以及任务搁置闸门（pauseTask / resumeTask / awaitResume）。
 * 从 AgentEngine 原样迁入，行为不变；信箱与标志存 [EngineStore]，UI 推送经 [UiBridge]。
 *
 * 三套等待互相独立的原因：确认等待若共用 needsUser 标志，一边收尾会把另一边的等待一起掐掉。
 */
internal class UserGate(
    private val store: EngineStore,
    private val ui: UiBridge,
    /** 引擎钩子：用户中途说的话记进任务记忆（AgentEngine.mutateTaskMemory） */
    private val mutateTaskMemory: ((com.phoneagent.data.store.TaskMemoryEntry) -> com.phoneagent.data.store.TaskMemoryEntry) -> Unit,
    /** 引擎钩子：动作类型中文名（AgentEngine.actionLabel） */
    private val actionLabel: (String) -> String,
) {
    /**
     * 搁置闸门：每轮开头调用，搁置期间只挂起、不干活。
     *
     * 用轮询而不是 CompletableDeferred：闸门会被反复经过，Deferred 需要额外的"谁来重建"约定，
     * 一旦漏建就会永久挂死；300ms 的轮询代价可以忽略，且协程取消（stop）能直接打断它。
     */
    suspend fun awaitResume() {
        if (!store.taskPaused) return
        while (store.taskPaused && coroutineContext.isActive) delay(300)
    }

    /** 用户协作者：提供下一步指导 */
    fun provideUserHint(hint: String) {
        if (hint.isBlank()) return
        // 只在「Agent 正在等待用户输入」时收：否则面板早已关闭，用户误触残留按钮送来的内容
        // 会滞留在信箱里，害得下一次等待被立刻满足而跳过等待
        if (!store.needsUser.value) return
        // 用户中途说的话就是新的任务要求，必须记进任务记忆并注入后续每一轮决策，
        // 否则长线任务跑到后面会把它忘掉（历史压缩只保留最近几轮对话）；
        // 「已手动处理」是语义信号不是要求，不记
        if (hint != SELF_DISMISS_HINT) mutateTaskMemory { it.withRequirement(hint) }
        ui.clearFloatingQuery()
        store.userHintMailbox.trySend(hint)
    }

    fun dismissUser() {
        if (!store.needsUser.value) return
        store.needsUser.value = false
        ui.clearFloatingQuery()
        // 「已手动处理」：不退出，发送语义信号让 Agent 继续观察页面并重新决策下一步
        store.userHintMailbox.trySend(SELF_DISMISS_HINT)
    }

    /** 等待用户输入（挂起点）：置起等待标志，从信箱取一条指导 */
    suspend fun awaitUserHint(): String {
        store.needsUser.value = true
        return try {
            store.userHintMailbox.receive()
        } finally {
            // 一次等待只消费一条：排掉可能重复投递的残留，免得下一轮等待被旧值立刻满足而跳过等待
            while (store.userHintMailbox.tryReceive().isSuccess) { /* drain */ }
            store.needsUser.value = false
        }
    }

    /**
     * 用户对「AI 自写命令」的答复（悬浮窗 / App 内协助面板的「批准执行 / 拒绝」按钮）。
     *
     * 与 provideUserHint 的关键区别：这条答复**不受 needsUser 约束**。
     * 协助面板是由 pendingShellCommand 驱动的独立交互，若也挂到 needsUser 上，
     * 两套等待就会互相覆盖（一边关掉标志，另一边永远等不到）。
     */
    fun resolveShellApproval(approve: Boolean) {
        if (store.pendingShellCommand.value == null) return
        ui.clearFloatingQuery()
        store.shellApprovalMailbox.trySend(approve)
    }

    /**
     * 自由模式：AI 自写命令的首次确认闸门。
     *
     * 只在「本任务第一次真要执行 AI 亲自写的命令」时问一次：批准 → 本任务内后续自写命令直接放行；
     * 拒绝 → 本任务内不再问，也不再执行任何自写命令（由调用方把拒绝结果回注给 AI 换路子）。
     *
     * 端侧自己编排的命令（如 fetch 落到 Termux 的 curl）不带 AgentAction.aiAuthored，不走这里。
     *
     * @return true = 可以执行；false = 用户拒绝，调用方**不得**执行
     */
    suspend fun ensureShellApproval(action: AgentAction): Boolean {
        if (action.type != ActionType.SHELL || !action.aiAuthored) return true
        store.shellApprovedForTask?.let { return it }

        val cmd = action.command.orEmpty()
        store.pendingShellCommand.value = cmd
        ui.log(AgentLog.Level.WARN, "自由模式：等待用户确认 AI 自写命令：$cmd")
        ui.pushFloating("等待确认命令", "THINKING")
        ui.showFloatingInteraction("shellconfirm", "确认执行命令", cmd, listOf("批准执行", "拒绝"))
        store.state.value = store.state.value.copy(message = "AI 想执行一条自己编写的命令，需要你确认：$cmd")
        val approved = try {
            store.shellApprovalMailbox.receive()
        } finally {
            store.pendingShellCommand.value = null
            // 排空：等待期间用户可能连点了两次，残留值会污染下一次等待
            while (store.shellApprovalMailbox.tryReceive().isSuccess) { /* drain */ }
        }
        store.shellApprovedForTask = approved
        ui.log(
            AgentLog.Level.INFO,
            if (approved) "用户已批准 AI 自写命令，本任务内不再确认" else "用户已拒绝 AI 自写命令，本任务内不再执行自写命令",
        )
        return approved
    }

    /** 用户对「不可逆动作确认」的答复（悬浮窗 / App 内确认面板）。没有等待中的确认时调用是空操作。 */
    fun respondActionConfirmation(allow: Boolean) {
        if (store.pendingActionConfirmation.value == null) return
        store.actionConfirmationMailbox.trySend(allow)
    }

    /**
     * 不可逆动作（支付/删除/发送等）的确认闸门。
     *
     * 必须在动作**真正执行前**调用：置起待确认状态等待用户答复，超时未答复视为取消。
     * 与自写命令确认（ensureShellApproval）互相独立，互不复用状态。
     *
     * @return true = 用户允许执行；false = 用户取消或超时，调用方不得执行该动作
     */
    suspend fun awaitActionConfirmation(action: AgentAction): Boolean {
        val summary = buildString {
            append(actionLabel(action.type))
            action.target?.value?.takeIf { it.isNotBlank() }?.let { append("：$it") }
            action.text?.takeIf { it.isNotBlank() }?.let { append("（${it.take(30)}）") }
        }
        store.pendingActionConfirmation.value = com.phoneagent.engine.AgentEngine.PendingActionConfirmation(
            actionSummary = summary,
            reason = action.reasoning?.takeIf { it.isNotBlank() } ?: action.reason.orEmpty(),
        )
        ui.log(AgentLog.Level.WARN, "不可逆动作等待用户确认：$summary")
        store.state.value = store.state.value.copy(message = "AI 想执行不可逆操作「$summary」，等待你确认")
        ui.pushFloating("等待确认操作", "THINKING")
        val allowed = try {
            withTimeoutOrNull(ACTION_CONFIRM_TIMEOUT_MS) { store.actionConfirmationMailbox.receive() } ?: false
        } finally {
            store.pendingActionConfirmation.value = null
            // 排空：等待期间用户可能连点了多次，残留值会污染下一次等待
            while (store.actionConfirmationMailbox.tryReceive().isSuccess) { /* drain */ }
        }
        ui.log(
            AgentLog.Level.INFO,
            if (allowed) "用户已允许执行不可逆操作：$summary" else "用户取消（或超时未确认）不可逆操作：$summary",
        )
        return allowed
    }
}
