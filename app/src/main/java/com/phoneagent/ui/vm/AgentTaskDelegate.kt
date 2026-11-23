package com.phoneagent.ui.vm

import android.content.Context
import com.phoneagent.core.ai.ApiProtocol
import com.phoneagent.data.export.LogExporter
import com.phoneagent.data.store.Checkpoint
import com.phoneagent.data.store.ExecutionStrategy
import com.phoneagent.data.store.TaskStore
import com.phoneagent.data.store.TaskTemplate
import com.phoneagent.device.vision.StepShot
import com.phoneagent.device.vision.StepTrace
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.engine.AgentEngine
import com.phoneagent.engine.PlanPhase
import com.phoneagent.engine.TaskSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Agent 执行域：引擎状态镜像 + 任务生命周期动作 + AI 连通性探测 + 日志/诊断导出。
 *
 * 这是拆分里耦合最高的一块，但**没有**改动 `AgentEngine` / `EngineStore` 的任何接口：
 * 本类只是把原来散在 `MainViewModel` 里的转发收拢到一处，行为逐行保持原样。
 *
 * 引擎通过 [engineProvider] 惰性取（`by lazy`）：构造本对象不会拉起引擎对象图，
 * 首个真正读引擎的访问才触发构建，与拆分前「首帧不建引擎」的约束一致。
 */
@Suppress("TooManyFunctions")
class AgentTaskDelegate(
    engineProvider: () -> AgentEngine,
    private val scope: CoroutineScope,
) {

    private val engine: AgentEngine by lazy { engineProvider() }

    // 引擎派生的状态一律惰性取：这些属性一旦在构造期求值，就会立刻拉起整张引擎对象图
    val agentState: StateFlow<AgentState> by lazy { engine.state }

    val logs: StateFlow<List<AgentLog>> by lazy { engine.logs }
    val conversation: StateFlow<List<ConversationMessage>> by lazy { engine.conversation }

    val metrics: StateFlow<AgentMetrics> by lazy { engine.metrics }
    val executionHistory: StateFlow<List<StepRecord>> by lazy { engine.executionHistory }
    val stepShot: StateFlow<StepShot> by lazy { engine.stepShot }
    val traces: StateFlow<List<StepTrace>> by lazy { engine.traces }
    /** 任务会话归档（Agent 页侧边栏）：最新一次任务在最前 */
    val taskSessions: StateFlow<List<TaskSession>> by lazy { engine.taskSessions }
    /** 当前对话的起点：实时视图只铺开这之后的任务，承接块也只取这之后的往来 */
    val conversationStart: StateFlow<Long> by lazy { engine.conversationStart }
    val taskQueue: StateFlow<List<String>> by lazy { engine.taskQueue }
    val needsUser: StateFlow<Boolean> by lazy { engine.needsUser }
    val userHintRequest by lazy { engine.userHintRequest }
    val planPhase: StateFlow<PlanPhase> by lazy { engine.planPhase }
    val planStream: StateFlow<String> by lazy { engine.planStream }

    /** AI 正在生成的决策内容（流式回显到 Agent 页，空串表示当前无输出） */
    val decisionStream: StateFlow<String> by lazy { engine.decisionStream }

    /** 当前任务是否被用户暂停；驱动状态条在「暂停 / 继续」之间切换 */
    val paused: StateFlow<Boolean> by lazy { engine.paused }

    /** 正在等待用户确认的 AI 自写命令（null = 没有待确认项）；驱动 App 内协助面板的「批准执行 / 拒绝」 */
    val pendingShellCommand: StateFlow<String?> by lazy { engine.pendingShellCommand }

    /** 引擎正在等待用户确认的不可逆动作（null = 无待确认项）；驱动任务流里的确认卡片 */
    val pendingActionConfirmation: StateFlow<AgentEngine.PendingActionConfirmation?> by lazy {
        engine.pendingActionConfirmation
    }

    // ---- AI 连通性 / 翻译 ----

    /** 将显示给用户的文本自动翻译成简体中文（AI 翻译，带缓存） */
    suspend fun translate(text: String): String = engine.translateText(text)

    /**
     * 界面侧的翻译结果缓存（按原文命中，最多 32 条）。
     *
     * 翻译结果的展示状态原先放在气泡自己的 `remember(text)` 里，而气泡随列表回收，
     * 滚出视野再滚回来就退回原文、再等 600ms 防抖加一次请求——同一句话在同一个会话里
     * 被反复「闪一下原文」。缓存提到 ViewModel（跨气泡、跨 Activity 重建存活）后，
     * 回滚时首帧就能拿到译文。上限 32 条是为了不让长会话的原文字符串无限驻留。
     */
    private val translationCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 32
    }

    /** 缓存命中返回译文；未命中返回 null（调用方走 600ms 防抖 + 真实翻译） */
    fun cachedTranslation(text: String): String? = synchronized(translationCache) { translationCache[text] }

    fun putTranslation(text: String, translated: String) {
        synchronized(translationCache) { translationCache[text] = translated }
    }

    /** 测试主模型连接是否可用（设置保存前校验用） */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: String = "openai",
    ): Result<String> =
        engine.testConnection(baseUrl, apiKey, model, ApiProtocol.fromId(protocol))

    /** 拉取端点可用模型列表（设置页"获取模型"） */
    suspend fun listModels(baseUrl: String, apiKey: String, protocol: String = "openai"): Result<List<String>> =
        engine.listModels(baseUrl, apiKey, ApiProtocol.fromId(protocol))

    /** 探测单个模型的能力（识图 / 工具调用） */
    suspend fun probeModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: String = "openai",
    ): Result<com.phoneagent.core.ai.ModelAbility> =
        engine.probeModel(baseUrl, apiKey, model, ApiProtocol.fromId(protocol))

    // ---- 任务生命周期 ----

    fun startPlanning(task: String) = engine.startPlanning(task)

    /** 规划失败卡的重试：重新规划同一条待规划任务（不是拿它去执行） */
    fun retryPlanning() = engine.retryPlanning()

    /**
     * 对话页直提（对话模式）：**只作答、不执行**。
     *
     * 走引擎的对话入口 [com.phoneagent.engine.AgentEngine.chatTurn]，由路由侧强制纯对话直答：
     * 在对话页说一句话不该开启任务执行模式（不设 isRunning、不开悬浮窗 / 跑马灯、
     * 不亮边缘光效、不碰无障碍操控）。需要操控手机的请求由直答如实说明，
     * 用户到 Agent 页下达即可。回答写入对话区（本页消费）。
     */
    fun submitChatTurn(task: String) = engine.chatTurn(task)

    fun answerClarification(option: ClarificationOption) = engine.answerClarification(option)
    fun approvePlan() = engine.approvePlan()
    fun cancelPlanning() = engine.cancelPlanning()

    /** 新建对话：引擎划断上下文并复位实时态（文档预览由门面另行撤下） */
    fun newConversation() = engine.newConversation()

    fun startAgent(task: String) {
        if (task.isBlank()) return
        engine.start(task)
    }

    fun stopAgent() = engine.stop()

    /** 暂停当前任务（主循环在下一轮开头挂起，不消耗步数） */
    fun pauseAgent() = engine.pauseTask()

    /** 从暂停处继续执行 */
    fun resumeAgent() = engine.resumeTask()

    /** 重试最近一次任务（失败卡的「重试」出口） */
    fun retryLastTask() = engine.retryLastTask()

    // ---- 长线任务：执行策略 / 断点续传 / 模板库 ----
    suspend fun currentStrategy(): ExecutionStrategy = engine.currentStrategy()
    fun setExecutionStrategy(s: ExecutionStrategy) {
        scope.launch { engine.setExecutionStrategy(s) }
    }
    fun resumeFromCheckpoint() = engine.resumeFromCheckpoint()
    suspend fun lastCheckpoint(): Checkpoint? = engine.lastCheckpoint()
    /** 清除长线任务的续传检查点（不影响任务模板） */
    fun clearCheckpoint(context: Context) {
        scope.launch { runCatching { TaskStore.clearCheckpoint(context) } }
    }
    suspend fun loadTemplates(context: Context): List<TaskTemplate> =
        runCatching { TaskStore.loadTemplates(context) }.getOrDefault(emptyList())
    fun deleteTemplate(context: Context, id: String) {
        scope.launch { runCatching { TaskStore.deleteTemplate(context, id) } }
    }

    // ---- 确认门 / 用户介入（App 内协助面板与悬浮窗共用引擎的同一信箱） ----

    fun clearDebug() = engine.clearDebug()
    fun provideUserHint(hint: String) = engine.provideUserHint(hint)
    fun dismissUser() = engine.dismissUser()

    fun resolveShellApproval(approve: Boolean) = engine.resolveShellApproval(approve)

    /** 确认执行该动作 */
    fun confirmPendingAction() = engine.respondActionConfirmation(true)

    /** 拒绝该动作（AI 会换做法或中止该步） */
    fun denyPendingAction() = engine.respondActionConfirmation(false)

    // ---- 日志与诊断导出（实现见 data/export/LogExporter.kt） ----

    /** 把指定任务（或全部）的日志导出为文本文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存路径，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogs(context: Context, taskId: Long, taskName: String?): String =
        LogExporter.exportLogs(context, logs.value, taskId, taskName)

    /** 分任务批量导出：每个任务导出一个独立 JSON 文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存汇总，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogsJsonAll(context: Context): String =
        LogExporter.exportLogsJsonAll(context, logs.value)

    /** 导出诊断报告：人话摘要区 + 原始数据区，导出前自动脱敏。
     *  @return 保存路径或错误信息（以 "ERR:" 开头） */
    fun exportDiagnosticReport(context: Context): String =
        LogExporter.exportDiagnosticReport(context, traces.value, logs.value, conversation.value)
}
