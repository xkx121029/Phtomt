package com.phoneagent.engine.runtime

import android.content.Context
import com.phoneagent.core.log.AppLog
import com.phoneagent.data.store.AiMemoryUpsert
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.engine.MemoryEvent
import com.phoneagent.engine.SayEvent
import com.phoneagent.engine.TaskSession
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentState
import com.phoneagent.engine.runtime.EngineLimits.LOCAL_DECISION_SOURCE
import com.phoneagent.engine.runtime.EngineLimits.MAX_LOGS
import com.phoneagent.engine.runtime.EngineLimits.MAX_MEMORY_EVENTS
import com.phoneagent.engine.runtime.EngineLimits.MAX_SAY_EVENTS
import com.phoneagent.engine.runtime.EngineLimits.MAX_TASK_SESSIONS
import com.phoneagent.engine.runtime.EngineLimits.MAX_TRACES

/**
 * 引擎遥测记录器（重构批次 2）：指标聚合、每步决策调试轨迹、记忆/say 事件流、
 * 任务会话归档与调试记录磁盘持久化。从 AgentEngine 原样迁入，行为不变。
 */
internal class TelemetryRecorder(
    private val appContext: Context,
    private val memory: MemoryStore,
    private val store: EngineStore,
) {
    init {
        // 日志双轨修复：把非 engine 层（core/device/feature 等）经 AppLog 写 logcat 的日志
        // 也收进应用内环形缓冲（调试页可见）。复用 UiBridge.log 同款 append 形态与 MAX_LOGS
        // 环形上限；引擎多次重建（TelemetryRecorder 多次构造）时直接覆盖 sink 取最后实例，
        // 天然避免重复挂接。TAG 以 "[tag] " 前缀并入正文，日志归属信息不丢失。
        AppLog.sink = { level, tag, msg, tr ->
            val text = buildString {
                append("[").append(tag).append("] ").append(msg)
                if (tr != null) append('\n').append(android.util.Log.getStackTraceString(tr))
            }
            val lvl = when (level) {
                android.util.Log.WARN -> AgentLog.Level.WARN
                android.util.Log.ERROR -> AgentLog.Level.ERROR
                else -> AgentLog.Level.INFO
            }
            val next = store.logs.value + AgentLog(
                timestamp = System.currentTimeMillis(),
                level = lvl,
                message = text,
                detail = null,
                taskId = store.currentTaskId,
                taskName = store.currentTaskName,
            )
            store.logs.value = if (next.size > MAX_LOGS) next.takeLast(MAX_LOGS) else next
        }
    }

    fun recordMetrics(decision: com.phoneagent.core.ai.AiDecision) {
        val prev = store.metrics.value
        val requestCount = prev.requestCount + 1
        val latencySum = prev.avgLatencyMs * prev.requestCount + decision.elapsedMs
        val avgLatency = if (requestCount > 0) latencySum / requestCount else 0
        val tps = if (decision.elapsedMs > 0) decision.completionTokens.toDouble() / decision.elapsedMs * 1000.0 else 0.0
        store.metrics.value = prev.copy(
            totalTokens = prev.totalTokens + decision.totalTokens,
            promptTokens = prev.promptTokens + decision.promptTokens,
            completionTokens = prev.completionTokens + decision.completionTokens,
            lastLatencyMs = decision.elapsedMs,
            avgLatencyMs = avgLatency,
            tokensPerSec = tps,
            requestCount = requestCount,
        )
    }

    /** 观测性埋点：视觉环节单次耗时聚合（端侧3B/云端/本地识别 + 定位） */
    fun recordVisionMs(elapsedMs: Long) {
        val prev = store.metrics.value
        store.metrics.value = prev.copy(
            visionCount = prev.visionCount + 1,
            visionTotalMs = prev.visionTotalMs + elapsedMs,
        )
    }

    /** 观测性埋点：执行环节单次耗时聚合（无障碍点击/输入/滑动等） */
    fun recordExecMs(elapsedMs: Long) {
        val prev = store.metrics.value
        store.metrics.value = prev.copy(
            execCount = prev.execCount + 1,
            execTotalMs = prev.execTotalMs + elapsedMs,
        )
    }

    fun recordStepTrace(
        step: Int,
        sent: String,
        decision: com.phoneagent.core.ai.AiDecision,
        visionSource: String,
        visionModel: String,
        visionDescription: String,
        screenshot: android.graphics.Bitmap?,
    ) {
        store.traces.value = store.traces.value + com.phoneagent.domain.model.StepTrace(
            taskId = store.currentTaskId,
            taskName = store.currentTaskName,
            step = step,
            sentText = sent,
            receivedText = decision.rawContent,
            promptTokens = decision.promptTokens,
            completionTokens = decision.completionTokens,
            totalTokens = decision.totalTokens,
            latencyMs = decision.elapsedMs,
            visionSource = visionSource,
            visionModel = visionModel,
            visionDescription = visionDescription,
            thinking = decision.thinking,
            // 截图入内存前缩略化（≤360px），配合 traces 数量上限，避免多步全分辨率截图长期驻留导致 OOM 闪退
            screenshot = com.phoneagent.data.store.DebugRecordsStore.thumb(screenshot),
        )
        // 数量上限：环形保留最近 N 条轨迹，防长线/多任务场景内存无限增长
        if (store.traces.value.size > MAX_TRACES) store.traces.value = store.traces.value.takeLast(MAX_TRACES)
    }

    /**
     * 端侧（本地）决策的步骤留档。
     *
     * 为什么必须留档：Agent 页任务流以 trace 为骨架（AgentTimelineMapper.buildRuns），
     * 端侧决策走的是 `localDecision.decide()` 直通分支、不经过 cloudDecide，
     * 若不在决策处补一条 trace，这一步在界面上完全不可见——用户的体感是"AI 没动，页面自己变了"。
     *
     * 与云端留档的差异：没有发给模型的上下文、没有 token 与延迟，用 [LOCAL_DECISION_SOURCE] 标注来源。
     */
    fun recordLocalStepTrace(step: Int, intent: AgentIntent) {
        store.traces.value = store.traces.value + com.phoneagent.domain.model.StepTrace(
            taskId = store.currentTaskId,
            taskName = store.currentTaskName,
            step = step,
            sentText = "",
            receivedText = intent.toString(),
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = 0,
            latencyMs = 0,
            // 复用"视觉来源"字段标注决策来源，便于在调试页区分端侧/云端
            visionSource = LOCAL_DECISION_SOURCE,
            visionModel = "",
            visionDescription = "",
            thinking = false,
        )
        if (store.traces.value.size > MAX_TRACES) store.traces.value = store.traces.value.takeLast(MAX_TRACES)
    }

    /** 记忆写入事件入流（内存态，供 Agent 页实时插卡；环形保留最近 N 条） */
    fun emitMemoryEvent(upsert: AiMemoryUpsert, runKey: String, step: Int) {
        val event = MemoryEvent(
            id = upsert.entry.id,
            content = upsert.entry.content,
            category = upsert.entry.category,
            updated = upsert is AiMemoryUpsert.Updated,
            runKey = runKey,
            step = step,
            createdAt = System.currentTimeMillis(),
        )
        store.memoryEvents.value = (store.memoryEvents.value + event).takeLast(MAX_MEMORY_EVENTS)
        // 同步挂到本次任务会话上：新任务开始会清空 memoryEvents，旧任务的记忆卡片
        // 若不在此刻归到会话里，切回旧任务时就再也看不到了
        updateTaskSession(runKey.removePrefix("r").toLongOrNull() ?: return) { cur ->
            cur.copy(memoryEvents = cur.memoryEvents + event)
        }
    }

    /** AI 说话事件入流（内存态，供 Agent 页实时出气泡；环形保留最近 N 条） */
    fun emitSayEvent(text: String, runKey: String, step: Int) {
        val event = SayEvent(
            id = store.saySeq.incrementAndGet(),
            text = text,
            runKey = runKey,
            step = step,
            createdAt = System.currentTimeMillis(),
        )
        store.sayEvents.value = (store.sayEvents.value + event).takeLast(MAX_SAY_EVENTS)
    }

    // ==================== 任务会话归档（Agent 页侧边栏） ====================

    /** 任务开始：插入一条「进行中」会话，最新在前 */
    fun startTaskSession(session: TaskSession) {
        val next = (listOf(session) + store.taskSessions.value.filterNot { it.taskId == session.taskId })
        store.taskSessions.value = if (next.size > MAX_TASK_SESSIONS) next.take(MAX_TASK_SESSIONS) else next
    }

    /**
     * 任务结束：按 taskId 就地收尾会话。
     *
     * 必须按 taskId 而非"当前任务"归属：`stop()` 取消协程后 finally 是异步执行的，
     * 用户若立刻发起新任务，旧任务的收尾会把新任务的会话误标成已结束。
     */
    fun settleTaskSession(taskId: Long, cancelled: Boolean) {
        val phase = store.state.value.phase
        val status = when {
            phase == AgentState.Phase.DONE -> TaskSession.Status.DONE
            cancelled -> TaskSession.Status.ABORTED
            else -> TaskSession.Status.FAILED
        }
        val records = store.executionHistory.value.filter { it.taskId == taskId }
        updateTaskSession(taskId) { cur ->
            cur.copy(
                endedAt = System.currentTimeMillis(),
                status = status,
                summary = store.state.value.message.orEmpty(),
                steps = maxOf(cur.steps, records.size),
                okSteps = maxOf(cur.okSteps, records.count { it.isConfirmed }),
            )
        }
        persistDebug()
    }

    /** 就地改写一条会话（不存在则忽略） */
    fun updateTaskSession(taskId: Long, transform: (TaskSession) -> TaskSession) {
        val current = store.taskSessions.value
        val idx = current.indexOfFirst { it.taskId == taskId }
        if (idx < 0) return
        store.taskSessions.value = current.toMutableList().also { it[idx] = transform(it[idx]) }
    }

    /** 回载/清空会话归档（由持久化层调用） */
    fun replaceTaskSessions(list: List<TaskSession>) {
        store.taskSessions.value = list.take(MAX_TASK_SESSIONS)
    }

    /** 撤销一条刚写入的记忆：删库 + 从事件流移除（卡片随之消失） */
    suspend fun dismissMemoryEvent(id: Long) {
        runCatching { memory.deleteAiMemory(id) }
        store.memoryEvents.value = store.memoryEvents.value.filterNot { it.id == id }
        store.memoryRawCache = null
    }

    /** 把当前的日志/轨迹/执行历史/对话（含任务会话归档）持久化到磁盘（重启后 Debug 页/侧边栏回载查看） */
    fun persistDebug() {
        runCatching {
            com.phoneagent.data.store.DebugRecordsStore.save(
                appContext, store.logs.value, store.traces.value, store.executionHistory.value, store.conversation.value,
                store.taskSessions.value,
            )
        }
    }

    /** 启动时回载上次的调试记录：日志/轨迹（含截图缩略图）/执行历史/对话，实现持久化 */
    fun loadPersistedDebug() {
        runCatching {
            val p = com.phoneagent.data.store.DebugRecordsStore.load(appContext) ?: return@runCatching
            store.logs.value = p.logs
            store.traces.value = p.traces
            store.executionHistory.value = p.history
            store.conversation.value = p.conversation
            // 存档里还挂着「进行中」的，说明上次是没走完收尾就被杀进程了，回载时直接判为已中断，
            // 否则侧边栏会永远显示一个不会结束的任务
            replaceTaskSessions(
                p.sessions.map {
                    if (it.status == TaskSession.Status.RUNNING) {
                        it.copy(status = TaskSession.Status.ABORTED, summary = it.summary.ifBlank { "应用重启，任务已中断" })
                    } else {
                        it
                    }
                },
            )
        }
    }
}
