package com.phoneagent.engine.runtime

import android.content.Context
import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.data.store.TaskStore
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.AgentResponseParser
import com.phoneagent.engine.DeviceFacts
import com.phoneagent.engine.RuleScoper
import com.phoneagent.engine.protocolOf
import com.phoneagent.engine.runtime.EngineLimits.DISTILL_TIMEOUT_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 任务收尾（重构批次 8）：从 AgentEngine 迁出「任务记忆终态 / 模板健康回写 / 记忆提炼」。
 *
 * 迁出的理由：这三件事都发生在任务结束的那一刻，与主循环的决策逻辑无关，
 * 却因为读写同一批字段（任务记忆、执行历史、前台应用统计）长期留在 3400 行的引擎里。
 * 它们要的状态全部已在 [EngineStore] 上，因此这里不需要任何回调钩子——
 * 引擎只保留「什么时候该收尾」的判断，「收尾怎么做」搬到这里。
 *
 * 时序约束（不变）：收尾必须先落终态，再做耗时的记忆提炼，否则记忆页会长时间停在「进行中」。
 */
internal class TaskFinalizer(
    private val store: EngineStore,
    private val memory: MemoryStore,
    private val settings: AppSettings,
    private val aiClient: AiClient,
    private val appContext: Context,
    private val deviceFacts: DeviceFacts,
    private val telemetry: TelemetryRecorder,
    private val scope: CoroutineScope,
    private val log: (AgentLog.Level, String, String?) -> Unit,
) {

    /** 任务记忆的读-改-写锁：用户指导（主线程）与主循环进度写入都会改同一条记录 */
    private val taskMemoryLock = Any()

    /**
     * 对任务记忆做一次「读-改-写」并异步落库。
     * 加锁是因为用户指导来自悬浮窗（主线程），而进度记录在主循环线程，
     * 并发写入会丢掉其中一方刚追加的要求/做法。
     */
    fun mutateTaskMemory(transform: (TaskMemoryEntry) -> TaskMemoryEntry) {
        val updated = synchronized(taskMemoryLock) {
            val cur = store.currentTaskMemory ?: return
            transform(cur).also { store.currentTaskMemory = it }
        }
        scope.launch { runCatching { memory.upsertTaskMemory(updated) } }
    }

    /**
     * 任务收尾：写入终态并落库。
     * 只允许「进行中 → 终态」单向推进，重复收尾（如 stop() 之后主循环异常退出走到 finally）不会覆盖已定状态。
     */
    fun finishTaskMemory(status: String, conclusion: String = "") {
        val cur = store.currentTaskMemory ?: return
        if (cur.status != TaskMemoryEntry.STATUS_RUNNING) return
        mutateTaskMemory { it.withStatus(status, cur.completedSteps, conclusion) }
    }

    /** 按 taskId 收尾：只动指定任务的记忆，用于 run() 的兜底 finally（防止串到下一个任务） */
    fun finishTaskMemory(taskId: Long, status: String) {
        val cur = store.currentTaskMemory ?: return
        if (cur.taskId != taskId) return
        finishTaskMemory(status)
    }

    /**
     * 任务成功后的模板处理：复用了既有模板 → 回写健康状态（失败归零）。
     * 全新计划不在这里入库，由完成流程请用户确认后再写（见 AgentEngine.confirmSaveTemplate）。
     */
    suspend fun learnTemplate() {
        val tid = store.reusedTemplateId ?: return
        runCatching { TaskStore.updateTemplateHealth(appContext, tid, success = true) }
    }

    /**
     * 任务结束后的记忆提炼：独立调用一次模型，**不写 conversation、不进 chatHistory**，
     * 因此不污染主决策上下文。同一任务只跑一次；结果经内容去重合并写入，重复不会堆成多条。
     *
     * @param settingsVal 任务级设置快照（主循环已读过一次，这里不再挂起读 DataStore）
     */
    suspend fun distillMemories(task: String, outcome: String, settingsVal: AppSettings.Settings) {
        if (store.currentTaskId == store.lastDistilledTaskId) return
        store.lastDistilledTaskId = store.currentTaskId
        if (settingsVal.apiKey.isBlank()) return
        val stepsSummary = runCatching {
            store.executionHistory.value.takeLast(12).joinToString("\n") { rec ->
                val verb = HumanTranslator.actionVerb(rec.action?.type.orEmpty())
                val target = rec.action?.target?.value
                    ?: rec.action?.text?.take(30)
                    ?: rec.action?.summary?.take(30)
                    ?: ""
                "- $verb$target"
            }.take(1500)
        }.getOrDefault("")
        val prompt = AgentPrompts.memoryDistill(store.currentLang, task, outcome, stepsSummary)
        val reply = withTimeoutOrNull(DISTILL_TIMEOUT_MS) {
            aiClient.chat(
                baseUrl = settingsVal.apiBaseUrl,
                apiKey = settingsVal.apiKey,
                model = settingsVal.model,
                messages = listOf(
                    ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = prompt))),
                ),
                temperature = 0.2,
                protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal),
            ).getOrNull()
        } ?: return
        val items = AgentResponseParser.parseDistilledMemories(reply)
        if (items.isEmpty()) return
        // 同一条提炼产物同时落两边：
        // - AI 记忆：全量注入，承担"用户画像 + 通用记忆"（客观事实类）；
        // - 经验规则：带作用域注入，承担"与某个应用/任务相关的做法类经验"。
        // 作用域由本次执行的前台应用分布推断（见 RuleScoper），推断不出来就退化成 GLOBAL。
        val scopeInferred = RuleScoper.inferScope(
            task,
            store.foregroundPkgCounts.toMap(),
            appContext.packageName,
            deviceFacts.launcherAppNames(),
        )
        items.forEach { item ->
            val upsert = runCatching {
                memory.upsertAiMemory(item.content, item.category, task, "distill", item.confidence)
            }.getOrNull() ?: return@forEach
            telemetry.emitMemoryEvent(upsert, "r${store.currentTaskId}", store.completedSteps)
            runCatching {
                memory.upsertEvolvedRule(
                    content = item.content,
                    kind = item.category,
                    scopeKind = scopeInferred.kind,
                    scopeValue = scopeInferred.value,
                    sourceTask = task,
                    confidence = item.confidence,
                )
            }
        }
        store.memoryRawCache = null
        log(
            AgentLog.Level.INFO,
            "记忆提炼完成：新增/更新 ${items.size} 条（作用域=${scopeInferred.kind}${scopeInferred.value})",
            null,
        )
    }
}
