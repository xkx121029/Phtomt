package com.phoneagent.engine.runtime

import android.content.Context
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.overlay.FloatingWindowService
import kotlinx.coroutines.flow.first
import com.phoneagent.engine.runtime.EngineLimits.MAX_CONVERSATION
import com.phoneagent.engine.runtime.EngineLimits.MAX_LOGS

/**
 * 引擎 UI 桥（重构批次 2）：悬浮窗推送 + 日志环形缓冲 + 对话流追加。
 * 从 AgentEngine 原样迁入，行为不变；状态读写一律经 [EngineStore]。
 */
internal class UiBridge(
    private val appContext: Context,
    private val settings: AppSettings,
    private val store: EngineStore,
) {
    /**
     * 启动悬浮窗：仅在「设置里开启悬浮窗」且「已授权悬浮窗权限」时启动。
     * 悬浮窗是可选能力——关闭或未授权时任务照常执行，进度只在 App 内展示。
     * （App 在后台时 startForegroundService 可能受限，需兜底防崩溃）
     */
    suspend fun maybeStartFloating() {
        val enabled = settings.settings.first().floatingWindowEnabled
        if (enabled && android.provider.Settings.canDrawOverlays(appContext)) {
            runCatching { FloatingWindowService.start(appContext) }
        }
        // 点击光标覆盖层：与悬浮窗同为可选视觉反馈，同样受悬浮窗权限约束
        val settingsVal = settings.settings.first()
        if (settingsVal.cursorOverlayEnabled && android.provider.Settings.canDrawOverlays(appContext)) {
            runCatching { com.phoneagent.overlay.CursorOverlayService.show(appContext, settingsVal.cursorClickSync) }
        }
    }

    /** 实时推送进度到悬浮窗 */
    fun pushFloating(status: String, phase: String) {
        val s = store.state.value
        FloatingWindowService.update(
            status = status,
            task = s.task ?: "Happy Agent",
            reasoning = s.lastAction?.reasoning ?: s.message ?: status,
            step = s.stepCount,
            total = 0,
            phase = phase,
        )
    }

    /** 实时推送 AI 思考（发送给 AI 的内容 + 流式返回的内容）到悬浮窗，并同步更新通知 */
    fun pushThinking(sent: String? = null, delta: String? = null) {
        FloatingWindowService.updateThinking(sent = sent, delta = delta)
    }

    /** 显示悬浮窗交互面板（批准/澄清/指导），用户可窗内直接操作 */
    fun showFloatingInteraction(type: String, title: String, content: String, options: List<String>? = null) {
        FloatingWindowService.interaction(type, title, content, options)
    }

    /** 清空悬浮窗中的提问内容 */
    fun clearFloatingQuery() {
        FloatingWindowService.interaction(null, null, null)
        val s = store.state.value
        FloatingWindowService.update(
            status = s.message ?: "执行中",
            task = s.task ?: "Happy Agent",
            reasoning = s.lastAction?.reasoning ?: s.message ?: "执行中",
            step = s.stepCount,
            total = 0,
            phase = s.phase.name,
        )
    }

    fun log(level: AgentLog.Level, message: String, detail: String? = null) {
        // 环形缓冲（v2.2.1 LogCollector）：仅保留最近 [MAX_LOGS] 条，防长线任务内存无限增长
        val next = store.logs.value + AgentLog(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            detail = detail,
            taskId = store.currentTaskId,
            taskName = store.currentTaskName,
        )
        store.logs.value = if (next.size > MAX_LOGS) next.takeLast(MAX_LOGS) else next
    }

    /** 记录完整 API 请求/响应（用于调试页日志，可展开查看全文） */
    fun apiLog(request: String, response: String, latencyMs: Long) {
        val summary = "API 调用 · ${latencyMs}ms"
        val detail = "═══ 请求 ═══\n$request\n\n═══ 响应 ═══\n$response"
        // 环形缓冲：与 log() 一致，携带完整请求/响应大文本，必须同样截断防长任务内存膨胀
        val next = store.logs.value + AgentLog(
            timestamp = System.currentTimeMillis(),
            level = AgentLog.Level.API,
            message = summary,
            detail = detail,
            taskId = store.currentTaskId,
            taskName = store.currentTaskName,
        )
        store.logs.value = if (next.size > MAX_LOGS) next.takeLast(MAX_LOGS) else next
    }

    fun addConversation(role: String, content: String, hasImage: Boolean = false) {
        // 环形缓冲：长线任务每步决策都 append 完整 prompt，需上限防内存膨胀
        val next = store.conversation.value + ConversationMessage(role, content, System.currentTimeMillis(), hasImage)
        store.conversation.value = if (next.size > MAX_CONVERSATION) next.takeLast(MAX_CONVERSATION) else next
    }

    fun clearDebug() {
        store.logs.value = emptyList()
        store.conversation.value = emptyList()
        store.metrics.value = AgentMetrics()
        store.executionHistory.value = emptyList()
        store.traces.value = emptyList()
        // 任务会话归档同步清空：明细都没了，侧边栏再留着任务条目只会点出空页面
        // （等价 TelemetryRecorder.replaceTaskSessions(emptyList())，直接置空避免跨类依赖）
        store.taskSessions.value = emptyList()
        // 同步清空磁盘持久化，避免重启后旧调试记录被再次回载
        com.phoneagent.data.store.DebugRecordsStore.clear(appContext)
    }
}
