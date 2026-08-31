package com.phoneagent.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import com.phoneagent.a11y.ActionExecutor
import com.phoneagent.a11y.AgentAccessibilityService
import com.phoneagent.ai.AiClient
import com.phoneagent.ai.ChatMessageDto
import com.phoneagent.ai.ContentPart
import com.phoneagent.ai.GlmDefaults
import com.phoneagent.data.prefs.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.phoneagent.decision.LocalDecisionEngine
import com.phoneagent.execution.AppNameResolver
import com.phoneagent.execution.CapabilityManager
import com.phoneagent.execution.IntentResolver
import com.phoneagent.execution.IntentTranslator
import com.phoneagent.execution.VerifiedClickExecutor
import com.phoneagent.floating.FloatingWindowService
import com.phoneagent.memory.AnomalyMemoryEngine
import com.phoneagent.memory.MemoryStore
import com.phoneagent.memory.ProfileLearner
import com.phoneagent.model.AgentAction
import com.phoneagent.model.ActionTarget
import com.phoneagent.model.AgentIntent
import com.phoneagent.model.IntentType
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.phoneagent.model.AgentLog
import com.phoneagent.model.AgentMetrics
import com.phoneagent.model.AgentState
import com.phoneagent.model.ActionType
import com.phoneagent.model.Clarification
import com.phoneagent.model.ClarificationOption
import com.phoneagent.model.ConversationMessage
import com.phoneagent.model.PlanResponse
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.StepRecord
import com.phoneagent.model.StepShot
import com.phoneagent.model.TaskPlan
import com.phoneagent.model.UiElement
import com.phoneagent.network.CloudAgent
import com.phoneagent.perception.PageAnnotator
import com.phoneagent.perception.effectiveLabel
import com.phoneagent.screen.ScreenSharingService
import com.phoneagent.security.DataSanitizer
import com.phoneagent.security.SensitivePageDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Agent 引擎：编排“观察 → 本地/云端决策 → 带验证执行 → 记录”的 ReAct 循环。
 *
 * 集成（对应 HPAv2.0 计划文档）：
 * - 第 2 层：页面标注（页面类型/上下文/优先级）
 * - 第 3 层：页面指纹（执行前后验证）
 * - 第 4 层：带验证执行
 * - 第 5 层：端侧决策优先（弹窗/加载/异常/完成不调云端）
 * - 第 6 层：稳健 JSON 解析 + 重试
 * - 第 10 层：异常记忆（命中复用）
 * - 第 11 层：多任务队列
 * - 第 12 层：安全（敏感页只读 / 数据脱敏）
 */
class AgentEngine(
    private val settings: AppSettings,
    private val aiClient: AiClient,
    private val appContext: android.content.Context,
    private val shizukuManager: com.phoneagent.shizuku.ShizukuManager? = null,
    private val workAreaEngine: com.phoneagent.workspace.WorkAreaEngine? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    companion object {
        /** 无障碍元素树精简阈值：小于等于该值时视为"无障碍读不到控件"，自动转视觉模型截图补足 */
        private const val VISION_FALLBACK_THRESHOLD = 3

        /** 外挂视觉 Agent 单次识别的超时（ms）：端侧 3B 在 CPU 上较慢，超时回退云端/本地，避免阻塞决策循环 */
        private const val EXTERNAL_VISION_TIMEOUT = 20_000L

        /** 决策链路最多保留的近期对话轮次（每轮 user+assistant 各算一条）。超过则截断早期历史，
         *  让长线任务上下文长度保持恒定，避免历史无限累积拖慢/带偏 AI */
        private const val MAX_DIALOG_TURNS = 6

        /** 长线工作记忆保留的最近成功步骤条数（文档 v2.2 5.2 建议最近 3 步） */
        private const val MAX_PROGRESS_NOTES = 3

        /** 单步云端决策看门狗（文档 v2.2 5.5）：超过则视为云端卡住，本步改为等待、下一轮重试，避免长线任务卡死 */
        private const val WATCHDOG_DECIDE_MS = 45_000L

        /** 日志环形缓冲上限（v2.2.1 LogCollector），防长线任务内存膨胀 */
        private const val MAX_LOGS = 800

        /** 阶段切分粒度：每 [STAGE_SIZE] 步为一个阶段（文档 v2.2 5.1） */
        private const val STAGE_SIZE = 6

        /** 歧义检测 + 规划 */
        const val PLANNING_TEMPERATURE = 0.3
        /** 每步决策（正常） */
        const val DECISION_TEMPERATURE = 0.1
        /** 失败 3 次后重规划 */
        const val REPLAN_TEMPERATURE = 0.5

        /** 用户点「已手动处理」时发出的语义信号 */
        const val SELF_DISMISS_HINT = "[[自处理]]已手动处理完成，请继续观察当前页面并重新决策下一步"
    }

    private val cloudAgent = CloudAgent(aiClient)
    private val localDecision = LocalDecisionEngine()
    private val memory = MemoryStore(appContext)
    private val anomalyEngine = AnomalyMemoryEngine(memory)
    private val profileLearner = ProfileLearner(memory)

    // ---- 意图化转译层（HPA动作执行逻辑优化文档 v2.1）：AI 输出意图，端侧按授权模式转译执行 ----
    private val capabilityManager = CapabilityManager(appContext, shizukuManager)
    private val appNameResolver = AppNameResolver(appContext)
    private val intentResolver = IntentResolver()
    private val intentTranslator = IntentTranslator(capabilityManager, appNameResolver, intentResolver)
    /** decision 阶段对 hint 目标视觉定位得到的像素坐标，供转译层本次使用 */
    @Volatile
    private var lastVisualCoordinate: Pair<Int, Int>? = null

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> get() = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<AgentLog>>(emptyList())
    val logs: StateFlow<List<AgentLog>> get() = _logs.asStateFlow()

    private val _conversation = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversation: StateFlow<List<ConversationMessage>> get() = _conversation.asStateFlow()

    private val _metrics = MutableStateFlow(AgentMetrics())
    val metrics: StateFlow<AgentMetrics> get() = _metrics.asStateFlow()

    private val _executionHistory = MutableStateFlow<List<StepRecord>>(emptyList())
    /** 当前任务最新一步的执行留档（截图+说明），新任务开始时清空 */
    private val _stepShot = MutableStateFlow(StepShot())
    val executionHistory: StateFlow<List<StepRecord>> get() = _executionHistory.asStateFlow()
    val stepShot: StateFlow<StepShot> get() = _stepShot.asStateFlow()
    /** 每步 AI 决策的详细追踪（Debug「按任务分类」页面展示） */
    private val _traces = MutableStateFlow<List<com.phoneagent.model.StepTrace>>(emptyList())
    val traces: StateFlow<List<com.phoneagent.model.StepTrace>> get() = _traces.asStateFlow()

    // ---- 规划/澄清/批准流程 ----
    private val _planPhase = MutableStateFlow<PlanPhase>(PlanPhase.Idle)
    val planPhase: StateFlow<PlanPhase> get() = _planPhase.asStateFlow()
    /** 规划阶段的流式输出文本（边思考边显示） */
    private val _planStream = MutableStateFlow("")
    val planStream: StateFlow<String> get() = _planStream.asStateFlow()
    private var pendingTask = ""
    private var activePlan: TaskPlan? = null
    /** 若当前计划来自模板复用，记录其 id（用于健康状态回写） */
    private var reusedTemplateId: String? = null
    /** 任务完成后待用户确认入库的用户模板：task/plan（仅用户主动确认才写入模板库） */
    private var pendingTemplateTask: String? = null
    private var pendingTemplatePlan: TaskPlan? = null
    /** 近最连续“命令不存在/参数无效”等结构性错误的次数；≥2 时强制 AI 改用无障碍动作并禁止继续 shell （防止一直编造不存在的命令） */
    private var invalidCommandStreak = 0
    private val translateCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 当前任务 ID（每次 run 开始时生成，用于分任务日志/导出） */
    @Volatile
    private var currentTaskId: Long = -1
    /** 执行审核运行期覆盖：null=跟随设置；否则本次任务强制开启/关闭审核 */
    @Volatile
    var reviewOverride: Boolean? = null
    /** 当前任务描述 */
    private var currentTaskName: String? = null
    /** 连续拒绝 AI 提前"任务完成"的次数，防死循环 */
    private var earlyDoneRejections = 0

    // ---- 长线任务工作记忆（v2.2 5.2）：已完成步骤的轻量摘要 ----
    /** 最近成功步骤的摘要（保持数量恒定，随新步骤滚动） */
    private val progressNotes = ArrayDeque<String>()
    /** 已完成（已验证生效）的步骤总数 */
    private var completedSteps = 0
    /** 本次计划的预计总步数（无计划则为 0，表示未知） */
    private var totalPlannedSteps = 0

    // ---- 主动反馈状态（v2.2.1 八）----
    /** 最近一次"步骤生效"的时间戳，用于检测长时间无进展 */
    private var lastProgressAt = 0L
    /** 5 分钟无进展提醒是否已发（每个任务一次） */
    private var noProgressNotified = false
    /** 连续由端侧决策的次数，用于"疑似死循环"检测 */
    private var localDecisionStreak = 0
    /** 死循环提醒是否已发（每个任务一次） */
    private var localLoopNotified = false
    /** 长时间无进展判定阈值 */
    private val NO_PROGRESS_MS = 5L * 60L * 1000L

    /** 若文本为中文则原样返回；否则调用 AI 翻译成简体中文（带缓存） */
    suspend fun translateText(text: String): String {
        if (text.isBlank() || isMostlyChinese(text)) return text
        translateCache[text]?.let { return it }
        val settingsVal = settings.settings.first()
        val prompt = "请把下面内容翻译成简体中文。只输出译文本身，不要任何修饰或解释：\n\n$text"
        val messages = listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = prompt))))
        val translated = aiClient.chatText(
            baseUrl = settingsVal.apiBaseUrl,
            apiKey = settingsVal.apiKey,
            model = settingsVal.model,
            messages = messages,
            temperature = 0.1,
        ).getOrDefault("").ifBlank { text }
        translateCache[text] = translated
        return translated
    }

    /** 粗略判断文本是否以中文为主（含中文字符比例 > 30%） */
    private fun isMostlyChinese(text: String): Boolean = EngineRules.isMostlyChinese(text)

    // ---- 第 11 层：多任务队列 ----
    private val _taskQueue = MutableStateFlow<List<String>>(emptyList())
    val taskQueue: StateFlow<List<String>> get() = _taskQueue.asStateFlow()

    // ---- 第 9 层：用户协作 ----
    private val _userHintRequest = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userHintRequest: SharedFlow<String> get() = _userHintRequest.asSharedFlow()
    private val _needsUser = MutableStateFlow(false)
    val needsUser: StateFlow<Boolean> get() = _needsUser.asStateFlow()
    private val _userHintResult = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var lastSnapshot: ScreenSnapshot = ScreenSnapshot()
    private var currentLang = PromptLang.CN
    private var consecutiveFailures = 0
    /** 最近一次 shell 命令输出（查询类命令回传给 AI 上下文） */
    private var lastShellOutput: String = ""

    /** 若已授予悬浮窗权限则启动悬浮窗（App 在后台时 startForegroundService 可能受限，需兜底防崩溃） */
    private fun maybeStartFloating() {
        if (android.provider.Settings.canDrawOverlays(appContext)) {
            runCatching { FloatingWindowService.start(appContext) }
        }
    }

    /** 实时推送进度到悬浮窗 */
    private fun pushFloating(status: String, phase: String) {
        val s = _state.value
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
    private fun pushThinking(sent: String? = null, delta: String? = null) {
        FloatingWindowService.updateThinking(sent = sent, delta = delta)
    }

    /** 显示悬浮窗交互面板（批准/澄清/指导），用户可窗内直接操作 */
    private fun showFloatingInteraction(type: String, title: String, content: String, options: List<String>? = null) {
        FloatingWindowService.interaction(type, title, content, options)
    }

    /** 清空悬浮窗中的提问内容 */
    private fun clearFloatingQuery() {
        FloatingWindowService.interaction(null, null, null)
        val s = _state.value
        FloatingWindowService.update(
            status = s.message ?: "执行中",
            task = s.task ?: "Happy Agent",
            reasoning = s.lastAction?.reasoning ?: s.message ?: "执行中",
            step = s.stepCount,
            total = 0,
            phase = s.phase.name,
        )
    }

    /** 上一步执行结果文本（✅/⚠️/❌），供决策 Prompt 使用 */
    private fun lastStepResultText(): String {
        val last = _executionHistory.value.lastOrNull() ?: return ""
        val action = last.action?.run { "${type}${reason?.let { "($it)" } ?: ""}" } ?: "-"
        return when {
            last.isConfirmed -> "✅ 已确认成功: $action"
            last.verificationResult == "failed" -> "❌ 未生效: $action"
            else -> "⚠️ 已发送但未确认: $action"
        }
    }

    private fun log(level: AgentLog.Level, message: String, detail: String? = null) {
        // 环形缓冲（v2.2.1 LogCollector）：仅保留最近 [MAX_LOGS] 条，防长线任务内存无限增长
        val next = _logs.value + AgentLog(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            detail = detail,
            taskId = currentTaskId,
            taskName = currentTaskName,
        )
        _logs.value = if (next.size > MAX_LOGS) next.takeLast(MAX_LOGS) else next
    }

    /** 记录完整 API 请求/响应（用于调试页日志，可展开查看全文） */
    private fun apiLog(request: String, response: String, latencyMs: Long) {
        val summary = "API 调用 · ${latencyMs}ms"
        val detail = "═══ 请求 ═══\n$request\n\n═══ 响应 ═══\n$response"
        _logs.value = _logs.value + AgentLog(
            timestamp = System.currentTimeMillis(),
            level = AgentLog.Level.API,
            message = summary,
            detail = detail,
            taskId = currentTaskId,
            taskName = currentTaskName,
        )
    }

    private fun addConversation(role: String, content: String, hasImage: Boolean = false) {
        _conversation.value = _conversation.value +
            ConversationMessage(role, content, System.currentTimeMillis(), hasImage)
    }

    fun clearDebug() {
        _logs.value = emptyList()
        _conversation.value = emptyList()
        _metrics.value = AgentMetrics()
        _executionHistory.value = emptyList()
        _traces.value = emptyList()
    }

    /** 提交任务到队列并开始处理 */
    fun start(task: String) {
        if (task.isBlank()) return
        // 预热：若启用外挂视觉，异步预加载端侧 3B 模型，避免首次决策阻塞在模型加载
        scope.launch {
            val ext = runCatching { settings.settings.first().enableExternalVision }.getOrDefault(true)
            if (ext) com.phoneagent.vision.ExternalVisionProvider.loadModel(appContext)
        }
        // 新任务开始时清空上一步的执行留档（截图+说明），由本次任务重新覆盖
        _stepShot.value = StepShot()
        _taskQueue.value = _taskQueue.value + task
        log(AgentLog.Level.INFO, "任务已加入队列：$task")
        if (job?.isActive != true) {
            job = scope.launch { processQueue() }
        }
    }

    // ==================== 规划 / 澄清 / 批准 ====================

    /** 开始规划：AI 流式思考并检测歧义。不直接执行。 */
    fun startPlanning(task: String) {
        if (task.isBlank()) return
        pendingTask = task
        _planStream.value = ""
        _planPhase.value = PlanPhase.Planning
        log(AgentLog.Level.INFO, "开始规划任务：$task")
        scope.launch {
            // 模板命中（v2.2 6.5）：目标相似且健康 → 直接复用历史脚本，零云端规划调用
            val tmpl = runCatching { com.phoneagent.task.TaskStore.matchTemplate(appContext, task) }.getOrNull()
            if (tmpl != null && tmpl.plan.steps.isNotEmpty()) {
                activePlan = tmpl.plan
                reusedTemplateId = tmpl.id
                _planStream.value = "【模板复用】已匹配历史模板「${tmpl.goal}」，脚本 ${tmpl.plan.steps.size} 步，免规划"
                runCatching { com.phoneagent.task.TaskStore.bumpExecution(appContext, tmpl.id) }
                _planPhase.value = PlanPhase.AwaitingApproval(tmpl.plan)
                val summary = tmpl.plan.steps.joinToString("\n") {
                    "${it.description}" + if (it.intent.isNotBlank()) " → ${it.intent}" else ""
                }.take(300)
                pushFloating("等待批准", "OBSERVING")
                showFloatingInteraction("approve", "执行计划（模板复用）", summary)
                log(AgentLog.Level.INFO, "模板命中，免规划：${tmpl.goal}")
                return@launch
            }
            try {
                val content = cloudPlanStream(task, null) { delta -> _planStream.value += delta }
                _planPhase.value = parsePlanResponse(content)
                if (_planPhase.value is PlanPhase.Error) {
                    log(AgentLog.Level.ERROR, "规划失败：$content")
                    pushFloating("规划失败", "ERROR")
                }
                // 规划完成后，需要用户交互时推送内容到悬浮窗
                when (val phase = _planPhase.value) {
                    is PlanPhase.Clarifying -> {
                        pushFloating("需要澄清", "OBSERVING")
                        showFloatingInteraction(
                            "clarify", "需要澄清", phase.clarification.question,
                            phase.clarification.options.map { it.label },
                        )
                    }
                    is PlanPhase.AwaitingApproval -> {
                        val plan = phase.plan
                        val summary = plan?.steps?.joinToString("\n") { s ->
                            "${s.description}" + (s.intent?.let { " → $it" } ?: "")
                        }?.take(300) ?: "计划已生成"
                        pushFloating("等待批准", "OBSERVING")
                        showFloatingInteraction("approve", "执行计划", summary)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                _planPhase.value = PlanPhase.Error("规划出错：${e.message}")
                log(AgentLog.Level.ERROR, "规划异常：${e.message}")
            }
        }
    }

    /** 用户回答澄清问题后重新规划（流式） */
    fun answerClarification(option: ClarificationOption) {
        val task = pendingTask
        if (task.isBlank()) return
        clearFloatingQuery()
        _planStream.value = ""
        _planPhase.value = PlanPhase.Planning
        scope.launch {
            try {
                val content = cloudPlanStream(task, option.label) { delta -> _planStream.value += delta }
                _planPhase.value = parsePlanResponse(content)
                if (_planPhase.value is PlanPhase.Error) {
                    log(AgentLog.Level.ERROR, "重新规划失败：$content")
                }
            } catch (e: Exception) {
                _planPhase.value = PlanPhase.Error("重新规划出错：${e.message}")
                log(AgentLog.Level.ERROR, "重新规划异常：${e.message}")
            }
        }
    }

    /** 用户批准计划后开始执行 */
    fun approvePlan() {
        val plan = activePlan
        val task = pendingTask
        if (task.isBlank()) return
        clearFloatingQuery()
        _planPhase.value = PlanPhase.Approved(plan)
        log(AgentLog.Level.INFO, "计划已批准：${plan?.steps?.size ?: 0} 步")
        if (job?.isActive != true) {
            job = scope.launch {
                runCatching { run(task, plan) }
                    .onFailure { e ->
                        log(AgentLog.Level.ERROR, "任务执行异常：${e.message}")
                        AgentAccessibilityService.agentRunning = false
                        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.ERROR, message = "执行异常：${e.message}")
                        FloatingWindowService.stop(appContext)
                    }
            }
        }
    }

    fun cancelPlanning() {
        clearFloatingQuery()
        _planPhase.value = PlanPhase.Idle
        pendingTask = ""
        activePlan = null
    }

    private suspend fun cloudPlanStream(task: String, answer: String?, onDelta: (String) -> Unit): String {
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        val profile = memory.loadProfile().takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.key}:${it.value}" } ?: ""
        val prompt = AgentPrompts.planning(lang, task, profile, installedAppList())
        // 言行一致：规划时注入 Shizuku 可用性，让规划与执行统一（shell 直接启动而非点击图标）
        val execContext = if (shizukuManager?.isAvailable() == true) {
            when (lang) {
                PromptLang.CN -> "\n\n# 执行环境（规划必须考虑）\n端侧已接管执行方式（选用 Shizuku/无障碍），屏幕 ${screenWidth()}x${screenHeight()}。第一类动作写 open_app + 应用名；点击页面内可见控件写 tap + target（by_id/by_text/by_hint）；坐标与通道都无需你操心。"
                PromptLang.EN -> "\n\n# Execution Environment (plan must consider)\nThe device handles execution (Shizuku/accessibility), screen ${screenWidth()}x${screenHeight()}. Write open_app + app name to open apps; tap in-page controls with tap + target (by_id/by_text/by_hint); coordinates and channel need no care."
            }
        } else ""
        val text = if (answer.isNullOrBlank()) "$prompt$execContext" else "$prompt$execContext\n\n用户已选择澄清项：$answer"
        val messages = listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = text))))
        // 链路聚合：规划等复杂任务优先使用思考模型（开启 thinking），未配置则回退主模型
        val reason = reasoningConfig(settingsVal)
        val planBase = reason ?: ReasoningConfig(settingsVal.apiBaseUrl, settingsVal.model, settingsVal.apiKey)
        // 实时展示发送给 AI 的规划提示词
        pushThinking(sent = text)
        val startNano = System.nanoTime()
        var content = aiClient.chatStream(
            baseUrl = planBase.baseUrl,
            apiKey = planBase.apiKey,
            model = planBase.model,
            messages = messages,
            // 温度 v0.1 文档：歧义检测+规划合并 = 0.3
            temperature = PLANNING_TEMPERATURE,
            // 边思考边把增量内容实时显示到悬浮窗 + 通知
            onDelta = { d ->
                onDelta(d)
                pushThinking(delta = d)
            },
            // 思考模型开启深度思考
            thinking = reason != null,
        ).getOrElse { throw it }
        // 流式只产出了思考内容、正文为空时，回退到非流式，避免解析失败
        if (content.isBlank()) {
            content = aiClient.chat(
                baseUrl = planBase.baseUrl,
                apiKey = planBase.apiKey,
                model = planBase.model,
                messages = messages,
                temperature = PLANNING_TEMPERATURE,
            ).getOrElse { throw it }
        }
        val latencyMs = (System.nanoTime() - startNano) / 1_000_000
        apiLog(text, content, latencyMs)
        return content
    }

    private fun parsePlanResponse(content: String): PlanPhase {
        return try {
            val obj = extractJsonObject(content)
            // 手动解析 JSON，兼容 steps 为字符串数组或对象数组
            val root = json.parseToJsonElement(obj).jsonObject
            val needsClarification = root["needs_clarification"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

            if (needsClarification) {
                val clarObj = root["clarification"]?.jsonObject
                val question = clarObj?.get("question")?.jsonPrimitive?.contentOrNull ?: ""
                val opts = clarObj?.get("options") as? kotlinx.serialization.json.JsonArray
                val options = opts?.mapNotNull { opt ->
                    val o = opt as? JsonObject ?: return@mapNotNull null
                    com.phoneagent.model.ClarificationOption(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        label = o["label"]?.jsonPrimitive?.contentOrNull ?: "",
                        description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        isDefault = o["is_default"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                    )
                } ?: emptyList()
                log(AgentLog.Level.AI, "需要澄清：$question")
                PlanPhase.Clarifying(com.phoneagent.model.Clarification(question = question, options = options))
            } else {
                val planObj = root["plan"]?.jsonObject
                if (planObj != null) {
                    val stepsRaw = planObj["steps"] as? kotlinx.serialization.json.JsonArray
                    val steps = if (stepsRaw != null) com.phoneagent.model.parseTaskSteps(stepsRaw) else emptyList()
                    val estimatedTime = planObj["estimated_time_seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val confidence = planObj["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val plan = com.phoneagent.model.TaskPlan(steps = steps, estimatedTimeSeconds = estimatedTime, confidence = confidence)
                    activePlan = plan
                    log(AgentLog.Level.AI, "规划完成：${plan.steps.size} 步")
                    PlanPhase.AwaitingApproval(plan)
                } else {
                    PlanPhase.Error("规划结果无法解析")
                }
            }
        } catch (e: Exception) {
            PlanPhase.Error("规划解析失败：${e.message}")
        }
    }

    /**
     * 从 AI 响应中提取 JSON 对象（纯逻辑实现见 [EngineRules.extractJsonObject]）。
     * 策略（按优先级）：
     * 1. 代码块包裹（```json ... ``` 或 ``` ... ```）
     * 2. 反向搜索：从末尾 '}' 向前匹配最外层完整 JSON 对象（推荐）
     * 3. 兜底：首 '{' 到末 '}'
     *
     * 反向搜索优势：AI 可能在 JSON 前输出解释文字，从末尾反向搜索能精准定位
     * 实际输出的 JSON 对象，避免被前文干扰。
     */
    private fun extractJsonObject(content: String): String = EngineRules.extractJsonObject(content)

    /**
     * 从字符串末尾反向搜索最外层完整 JSON 对象。
     * 从最后一个 '}' 开始，向前匹配括号直到找到对应的 '{'。
     * 正确处理字符串内的括号（不参与计数）。
     *
     * @return 提取到的 JSON 字符串，若未找到有效 JSON 则返回 null
     */
    private fun extractJsonBackward(text: String): String? = EngineRules.extractJsonBackward(text)

    /** 查询最近一次任务的检查点（供中断后展示/续传） */
    suspend fun lastCheckpoint(): com.phoneagent.task.Checkpoint? =
        runCatching { com.phoneagent.task.TaskStore.loadCheckpoint(appContext) }.getOrNull()

    /**
     * 断点续传（v2.2 5.3）：用最近一次检查点保存的任务与计划，重新启动该任务。
     * 由于计划步骤为原子动作且具备幂等保护，重复执行副作用操作会被跳过，安全可复用。
     */
    fun resumeFromCheckpoint() {
        scope.launch {
            val ck = runCatching { com.phoneagent.task.TaskStore.loadCheckpoint(appContext) }.getOrNull()
                ?: return@launch
            val plan = ck.planJson
                ?.let { runCatching { json.decodeFromString(com.phoneagent.model.TaskPlan.serializer(), it) }.getOrNull() }
                ?: return@launch
            if (plan.steps.isEmpty()) return@launch
            pendingTask = ck.task
            activePlan = plan
            log(AgentLog.Level.INFO, "从检查点续传任务：${ck.task}（已完成 ${ck.completedSteps} 步）")
            com.phoneagent.debug.ActiveNotifier.notify(
                appContext, com.phoneagent.debug.ActiveNotifier.ID_CHECKPOINT,
                "已从断点恢复", "正在继续上次任务「${ck.task}」，已完成 ${ck.completedSteps} 步。",
            )
            if (job?.isActive != true) {
                job = scope.launch { run(ck.task, plan) }
            }
        }
    }

    /** 设置执行策略（v2.2 7，热切换一路生效） */
    suspend fun setExecutionStrategy(s: com.phoneagent.task.ExecutionStrategy) {
        runCatching { com.phoneagent.task.TaskStore.setStrategy(appContext, s) }
    }

    suspend fun currentStrategy(): com.phoneagent.task.ExecutionStrategy =
        runCatching { com.phoneagent.task.TaskStore.getStrategy(appContext) }
            .getOrDefault(com.phoneagent.task.ExecutionStrategy.AUTO)

    fun stop() {
        job?.cancel()
        job = null
        com.phoneagent.vision.ExternalVisionProvider.unbind(appContext)
        AgentAccessibilityService.agentRunning = false
        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.IDLE)
        FloatingWindowService.stop(appContext)
        log(AgentLog.Level.WARN, "任务已停止")
    }

    /** 用户协作者：提供下一步指导 */
    fun provideUserHint(hint: String) {
        if (hint.isBlank()) return
        clearFloatingQuery()
        _userHintResult.tryEmit(hint)
    }

    fun dismissUser() {
        _needsUser.value = false
        clearFloatingQuery()
        // 「已手动处理」：不退出，发送语义信号让 Agent 继续观察页面并重新决策下一步
        _userHintResult.tryEmit(SELF_DISMISS_HINT)
    }

    private suspend fun processQueue() {
        while (coroutineContext.isActive && _taskQueue.value.isNotEmpty()) {
            val task = _taskQueue.value.first()
            _taskQueue.value = _taskQueue.value.drop(1)
            run(task, null)
        }
        AgentAccessibilityService.agentRunning = false
        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.IDLE)
    }

    private suspend fun run(task: String, plan: TaskPlan? = null) {
        // 每次任务开始生成独立任务 ID，用于分任务日志查看与导出
        currentTaskId = System.currentTimeMillis()
        currentTaskName = task.take(60)
        // 每次任务开始彻底清空 AI 上下文：对话历史、失败/提前完成/无效命令计数、“上一条 shell 输出”、
        // 上一页截图快照与上一任务计划，避免上一个任务的指令/决策/命令串扰本任务（防止 AI 沿用不存在/失效的命令）
        _conversation.value = emptyList()
        consecutiveFailures = 0
        earlyDoneRejections = 0
        invalidCommandStreak = 0
        lastShellOutput = ""
        lastSnapshot = ScreenSnapshot()
        activePlan = plan
        // 新任务重置长线工作记忆
        progressNotes.clear()
        completedSteps = 0
        totalPlannedSteps = plan?.steps?.size ?: 0
        reusedTemplateId = null
        // 重置主动反馈状态
        lastProgressAt = System.currentTimeMillis()
        noProgressNotified = false
        localDecisionStreak = 0
        localLoopNotified = false
        // 读取执行策略（v2.2 7）：记录当前模式，供任务标签区分
        val strategy = runCatching { com.phoneagent.task.TaskStore.getStrategy(appContext) }
            .getOrDefault(com.phoneagent.task.ExecutionStrategy.AUTO)
        log(AgentLog.Level.INFO, "任务开始：$task")
        log(AgentLog.Level.INFO, "执行策略=${strategy.name}")
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        currentLang = lang
        val messages = mutableListOf<ChatMessageDto>().apply {
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = AgentPrompts.system(lang, settingsVal.systemPrompt, settingsVal.hasVision, shizukuManager?.isAvailable() == true)))))
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = AgentPrompts.capabilitiesLang(lang, settingsVal.hasVision)))))
            // 执行通道与坐标对 AI 透明：端侧自动选择执行方式，AI 无需指定通道或坐标
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = when (lang) {
                PromptLang.CN -> "执行通道（无障碍/Shizuku）由端侧自动选择，无需你指定。打开应用用 open_app（写应用名即可）；目标定位与坐标计算全部由端侧完成，你不输出像素坐标。"
                PromptLang.EN -> "Execution channel (accessibility/Shizuku) is auto-selected on-device; you never specify it. Open apps with open_app (write the app name); target location and coordinate computing are all handled on-device — you never output pixel coordinates."
            }))))
        }

        val maxSteps = settingsVal.maxSteps
        var step = 0
        AgentAccessibilityService.agentRunning = true
        _state.value = AgentState(isRunning = true, task = task, phase = AgentState.Phase.OBSERVING)
        maybeStartFloating()
        pushFloating("开始执行", "OBSERVING")
        while (maxSteps <= 0 || step < maxSteps) {
            step++
            if (!coroutineContext.isActive) return
            _state.value = _state.value.copy(
                phase = AgentState.Phase.OBSERVING,
                stepCount = step,
                hasAccessibility = AgentAccessibilityService.isServiceEnabled(appContext),
            )
            pushFloating("正在观察屏幕", "OBSERVING")

            // 1. 观察 + 标注
            val snapshot = observe()
            lastSnapshot = snapshot
            val annotated = PageAnnotator.annotate(snapshot)
            // 无障碍读不到控件（元素树稀疏，如游戏/WebView/in-app 渲染界面）时，即使未开启截图开关也自动截图，
            // 交给视觉模型（glm-4.6v-flash）描述+坐标定位，弥补元素树缺失
            val treeSparse = snapshot.elements.size <= VISION_FALLBACK_THRESHOLD
            // 混合路由：开启外挂且（未开混合，或简单任务=元素树可读）→ 走端侧 3B 需要截图
            val needExternal3b = settingsVal.enableExternalVision &&
                (!settingsVal.smartVisionRoute || !treeSparse)
            val screenshot = if (settingsVal.attachScreenshot || needExternal3b ||
                (treeSparse && (settingsVal.visionEnabled || settingsVal.visionMode == "LOCAL")))
                ScreenSharingService.instance?.captureFrame() else null
            log(AgentLog.Level.INFO, "第 $step 轮观察：${snapshot.elements.size} 个元素，页面类型=${annotated.pageType}" +
                if (treeSparse && (settingsVal.visionEnabled || settingsVal.visionMode == "LOCAL")) "（元素稀疏，自动启用视觉模型）" else "")

            // 2. 安全：敏感页只读
            if (SensitivePageDetector.isSensitive(snapshot)) {
                log(AgentLog.Level.WARN, "检测到敏感页面，拒绝执行动作，需用户介入")
                pushFloating("敏感页面", "ERROR")
                showFloatingInteraction("guide", "敏感页面保护", "检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后，在窗内告诉 AI 继续。")
                _needsUser.value = true
                _userHintRequest.tryEmit("检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后输入提示继续。")
                val hint = awaitUserHint()
                if (hint.isBlank()) { stop(); return }
                continue
            }

            // 3. 端侧决策优先（输出的都是"意图"）
            lastVisualCoordinate = null
            val localIntent = localDecision.decide(snapshot)
            var decidedIntent: AgentIntent?
            var fromLocal = false
            if (localIntent != null) {
                decidedIntent = localIntent
                fromLocal = true
                log(AgentLog.Level.AI, "端侧决策意图：${localIntent.intent}（${localIntent.reasoning ?: localIntent.reason}）")
            } else {
                decidedIntent = cloudDecide(task, snapshot, annotated, messages, screenshot, settingsVal)
            }

            if (decidedIntent == null || decidedIntent.intent.isEmpty()) { log(AgentLog.Level.ERROR, "决策为空，停止"); stop(); return }
            val intent = decidedIntent!!
            // 端侧连续多次决策 → 疑似死循环，主动反馈一次（v2.2.1 八）
            if (fromLocal) {
                localDecisionStreak++
                if (localDecisionStreak >= 5 && !localLoopNotified) {
                    localLoopNotified = true
                    log(AgentLog.Level.WARN, "连续 ${localDecisionStreak} 次端侧决策，可能死循环")
                    com.phoneagent.debug.ActiveNotifier.notify(
                        appContext, com.phoneagent.debug.ActiveNotifier.ID_LOCAL_LOOP,
                        "任务可能卡住了", "AI 已在同一页面反复做出相同判断，正准备强制调整路线，避免原地打转。",
                    )
                }
            } else {
                localDecisionStreak = 0
            }
            // 长时间无进展 → 主动反馈一次（v2.2.1 八）
            if (completedSteps > 0 && !noProgressNotified &&
                System.currentTimeMillis() - lastProgressAt > NO_PROGRESS_MS
            ) {
                noProgressNotified = true
                log(AgentLog.Level.WARN, "任务长时间无进展，提醒用户")
                com.phoneagent.debug.ActiveNotifier.notify(
                    appContext, com.phoneagent.debug.ActiveNotifier.ID_NO_PROGRESS,
                    "AI 长时间没动静", "任务似乎卡住了，已延长检查时间；若仍未进展可用「已手动处理」接管。",
                )
            }
            // 4. 转译：意图 → 内部命令（端侧按授权模式选通道/定位/算坐标，AI 无感知）
            val translation = intentTranslator.translate(intent, snapshot, lastVisualCoordinate)
            var action: AgentAction = AgentAction(type = "")
            var verify = com.phoneagent.execution.VerifyResult(false, "", "", "")
            var isStructuralError = false
            var verified = false
            when (translation) {
                is IntentTranslator.TranslationResult.Command -> {
                    action = translation.action
                    addConversation(if (fromLocal) "local" else "assistant", intent.toString())
                    log(com.phoneagent.model.AgentLog.Level.INFO, "意图转译为动作：intent=${intent.intent} → action.type=${action.type} pkg=${action.packageName} cmd=${action.command} target=${action.target}")
                    _state.value = _state.value.copy(
                        phase = AgentState.Phase.ACTING, lastAction = intent,
                        message = action!!.reason ?: "",
                    )
                    pushFloating(action!!.reasoning ?: action!!.reason ?: "正在执行", "ACTING")
                    // 规划步数常多于实际执行步数：仅当完成度明显不足时（已执行 < 规划步数的60%）才拦截
                    val minDoneThreshold = if (activePlan != null && activePlan!!.steps.isNotEmpty())
                        maxOf(3, Math.ceil(activePlan!!.steps.size * 0.6).toInt())
                    else 3
                    if (action!!.type == ActionType.TASK_DONE && step < minDoneThreshold) {
                        // 要求 AI 重新决策，并注入纠偏提示
                        earlyDoneRejections++
                        if (earlyDoneRejections >= 3) {
                            // 连续 3 次拒绝仍坚持完成 → 按完成处理，避免死循环
                            log(AgentLog.Level.WARN, "AI 连续 3 次提前宣称完成，按完成处理避免死循环")
                            earlyDoneRejections = 0
                        } else {
                            log(AgentLog.Level.WARN, "AI 仅执行 $step 步即宣称完成（门槛 $minDoneThreshold 步），要求重新决策")
                            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text",
                                text = "⚠️ 你当前仅执行了 $step 步（任务通常至少需要 $minDoneThreshold 步）。请确认目标是否真的达成：只有亲眼在当前页面看到任务结果的明确证据才能输出 finish；否则继续执行真正需要的关键步骤，不要凑数，也不要提前结束。"
                            ))))
                            continue
                        }
                    }
                    if (action!!.type == ActionType.TASK_DONE) {
                        log(AgentLog.Level.INFO, "任务完成：${action!!.summary ?: "-"}")
                        recordStep(step, action!!, "verified_success", "", "")
                        _state.value = _state.value.copy(phase = AgentState.Phase.DONE, message = action!!.summary ?: "任务完成", isRunning = false)
                        AgentAccessibilityService.agentRunning = false
                        // 任务完成后的收尾：模板处理（复用模板回写健康；全新计划经用户确认才入库）+ 检查点清空
                        runCatching { learnTemplate(task) }
                        runCatching { com.phoneagent.task.TaskStore.clearCheckpoint(appContext) }
                        runCatching {
                            val planSteps = activePlan?.steps?.size ?: 0
                            if (reusedTemplateId == null && planSteps > 0) {
                                pendingTemplateTask = task
                                pendingTemplatePlan = activePlan
                                requestConfirmSaveTemplate()
                            } else {
                                FloatingWindowService.showDone(action!!.summary ?: "任务完成")
                            }
                        }
                        return
                    }
                    verify = executeWithVerify(action!!, snapshot)
                    // 对确定性错误（未知命令/命令为空/参数无效）不重试，立即失败促使 AI 重新决策
                    isStructuralError = verify.reason.contains("未知 shell 命令") ||
                        verify.reason.contains("命令为空") ||
                        verify.reason.contains("参数无效")
                    verified = verify.success
                    var times = 1
                    while (!verified && times < 3 && !isStructuralError && coroutineContext.isActive) {
                        times++
                        log(AgentLog.Level.WARN, "动作未生效（第 $times 次重试）：${action!!.type}")
                        repeat(3) { delay(300) }
                        val v2 = executeWithVerify(action!!, observe())
                        verified = v2.success
                    }
                }
                is IntentTranslator.TranslationResult.Failed -> {
                    val reason = translation.reason
                    log(AgentLog.Level.WARN, "意图转译失败：$reason")
                    addConversation("assistant", intent.toString())
                    _state.value = _state.value.copy(
                        phase = AgentState.Phase.ACTING, lastAction = intent,
                        message = "转译失败：$reason",
                    )
                    action = AgentAction(
                        type = intent.intent, reasoning = intent.reasoning,
                        reason = reason,
                        target = intent.target?.let { ActionTarget(method = it.by, value = it.value) },
                    )
                    verify = com.phoneagent.execution.VerifyResult(false, reason, "", "")
                    isStructuralError = true
                    verified = false
                }
            }
            consecutiveFailures = if (verified) 0 else consecutiveFailures + 1
            // 步骤验证生效 → 记入长线工作记忆，供后续压缩历史后仍能感知进度
            if (verified) recordProgress(step, action)
            // 检查点持久化（v2.2 5.3）：每成功一步保存进度，中断后可查询/续传
            if (verified) runCatching {
                com.phoneagent.task.TaskStore.saveCheckpoint(
                    appContext, currentTaskName ?: task, activePlan,
                    completedSteps, totalPlannedSteps, currentTaskId,
                )
            }
            // 连续失败 ≥3 次才请求用户介入，避免单次动作失败频繁打断
            if (!verified && consecutiveFailures >= 3) {
                recordStep(step, action, "failed", verify.beforeFingerprint, verify.afterFingerprint)
                log(AgentLog.Level.ERROR, "动作 3 次未生效：${action.type}，请求用户介入")
                pushFloating("需要指导", "ERROR")
                showFloatingInteraction("guide", "需要你的协助", "动作「${action.type}」连续未能改变页面。请在窗内手动接管处理，或告诉 AI 该怎么做。")
                _needsUser.value = true
                _userHintRequest.tryEmit("动作「${action.type}」连续未能改变页面，请选择：手动接管 / 告诉 AI 怎么做。")
                val hint = awaitUserHint()
                if (hint.isBlank()) { stop(); return }
                // 用户指导 → 加入上下文并让云端重新决策（失败自动重试一次）
                messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "用户提示：$hint 请据此重新决策下一步动作。" ))))
                pushThinking(sent = "用户提示：$hint")
                var guided = cloudAgent.decideWithUserHint(settingsVal.apiBaseUrl, settingsVal.apiKey, settingsVal.model, messages, hint, onDelta = { pushThinking(delta = it) }).getOrNull()
                if (guided == null || guided.intent == IntentType.FINISH || guided.intent == IntentType.GIVE_UP) {
                    // AI 调用失败或认为任务完成：重试一次
                    guided = cloudAgent.decideWithUserHint(settingsVal.apiBaseUrl, settingsVal.apiKey, settingsVal.model, messages, hint, onDelta = { pushThinking(delta = it) }).getOrNull()
                }
                if (guided != null && guided.intent != IntentType.FINISH && guided.intent != IntentType.GIVE_UP) {
                    // 直接执行引导后的意图（转译为命令），不重走决策（避免变卦 + 节省一次云调用）
                    val gTranslate = intentTranslator.translate(guided, observe(), null)
                    when (gTranslate) {
                        is IntentTranslator.TranslationResult.Command -> {
                            action = gTranslate.action
                            verify = executeWithVerify(gTranslate.action, observe())
                            verified = verify.success
                            if (!verified) {
                                log(AgentLog.Level.WARN, "引导后动作仍未生效：${gTranslate.action.type}")
                            }
                        }
                        is IntentTranslator.TranslationResult.Failed -> {
                            verify = com.phoneagent.execution.VerifyResult(false, gTranslate.reason, "", "")
                            verified = false
                            log(AgentLog.Level.WARN, "引导后意图转译失败：${gTranslate.reason}")
                        }
                    }
                }
            }

            log(AgentLog.Level.INFO, "执行动作：${action.type}（${if (verified) "已验证生效" else "待确认"}）")
            recordsIntoHistory(step, action, verify)
            // 每步执行完：截图并写下执行说明，供本地调试板块展示（最新一步）
            stepShotCapture(step, action, verified)

            // 记录上下文供多轮参考
            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "执行了 ${action.type}" +
                (action.reason?.let { "（$it）" } ?: "")))))
            // 结构错误（未知命令等）：明确告知 AI 命令无效及可用命令清单，促使下一轮纠正；
            // 连续 ≥2 次无效命令时升级：强制改用无障碍动作（id/label 定位），禁止继续 shell，避免 AI 一直编造不存在的命令
            if (!verified && isStructuralError) {
                invalidCommandStreak++
                val fix = if (action.type == ActionType.SHELL) {
                    if (invalidCommandStreak >= 2) {
                        "\n\n你已经连续 ${invalidCommandStreak} 次发出不存在的 shell 命令（${verify.reason}）。" +
                        "从这一步起【禁止再使用 type=\"shell\"】。改用无障碍动作 type=tap/long_press/type/scroll_to/launch + target.id 或 target.label（不需坐标，执行层会自动定位）。" +
                        "只有显式列在表格里的命令才存在，其它一律不允许：tap/lp/dt/sw/su/sd/sl/sr/key/back/home/recents/text/am/stop/dump/launch/brightness/screenshot/info/wifi_on/wifi_off/clip。不要编造、不要臆测命令。"
                    } else {
                        "\n\n你发出的 shell 命令无效：${verify.reason}\n请只使用以下友好命令：tap/lp/dt/sw/su/sd/sl/sr/key/back/home/recents/text/am/stop/dump/launch/brightness/screenshot/info/wifi_on/wifi_off/clip。坐标用比例（0~1）或像素。不要编造不存在的命令。"
                    }
                } else {
                    "\n\n意图「${action.type}」执行无效：${verify.reason}\n请重新决策，使用 open_app/open/tap/long_press/input/swipe/press/wait/scroll_to/write_doc/finish/give_up 中正确的意图与参数。目标优先用 by_id/by_text，找不到用 by_hint。"
                }
                messages.add(ChatMessageDto(role = "assistant", content = listOf(
                    ContentPart(type = "text", text = fix),
                )))
            }
            // shell 查询命令的输出注入 AI 上下文（info/dump/clip 等）
            if (action.type == ActionType.SHELL && lastShellOutput.isNotBlank()) {
                messages.add(ChatMessageDto(role = "assistant", content = listOf(
                    ContentPart(type = "text", text = "shell 命令输出：\n$lastShellOutput"),
                )))
                lastShellOutput = ""
            }
            messages.add(ChatMessageDto(role = "assistant", content = listOf(ContentPart(type = "text", text = action.type))))
            delay(400)
        }
        _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = "达到最大步数限制")
        log(AgentLog.Level.WARN, "达到最大步数限制，自动停止")
        pushFloating("达到最大步数限制", "ERROR")
        // 复用模板执行失败：回写健康状态，连续 3 次失效时主动反馈（v2.2.1 八）
        reusedTemplateId?.let { tid ->
            runCatching {
                val t = com.phoneagent.task.TaskStore.loadTemplates(appContext).firstOrNull { it.id == tid }
                com.phoneagent.task.TaskStore.updateTemplateHealth(appContext, tid, success = false)
                if ((t?.failedStreak ?: 0) + 1 >= 3) {
                    com.phoneagent.debug.ActiveNotifier.notify(
                        appContext, com.phoneagent.debug.ActiveNotifier.ID_TEMPLATE_FAILED,
                        "任务模板已失效", "这个任务的脚本连续失败多次，自动改走云端重新规划，建议手动检查一下。",
                    )
                }
            }
        }
        stop()
    }

    /**
     * 构造送往 AI 决策的消息列表，做长线任务的历史压缩：
     * - 系统消息（role="system"）全部保留（铁律/技能/通道说明，数量少且关键）
     * - 非系统对话只保留最近 [MAX_DIALOG_TURNS] 轮，早期轮次丢弃
     * - 追加当前轮的 user 消息
     * 每轮 user 消息已自带"第几步 + 上一步结果 + 当前页面 + 端侧已识别控件"，AI 无需完整旧历史即可决策当前位置，
     * 因此截断能显著降低长线任务的 token 成本与理解压力，且不丢失关键上下文。
     */
    private fun chatHistory(messages: List<ChatMessageDto>, currentUserMsg: ChatMessageDto): List<ChatMessageDto> {
        val system = messages.filter { it.role == "system" }
        val dialog = messages.filter { it.role != "system" }
        val recent = dialog.takeLast(MAX_DIALOG_TURNS * 2)
        return system + recent + currentUserMsg
    }

    private suspend fun cloudDecide(
        task: String,
        snapshot: ScreenSnapshot,
        annotated: com.phoneagent.perception.AnnotatedPage,
        messages: MutableList<ChatMessageDto>,
        screenshot: android.graphics.Bitmap?,
        settingsVal: AppSettings.Settings,
    ): AgentIntent? {
        // 数据脱敏后再发送
        val safeText = DataSanitizer.sanitize(snapshot.toAiText())
        val planSteps = activePlan?.steps?.mapIndexed { i, s -> "${i + 1}. ${s.description}" }?.joinToString("\n")
        val planNote = if (!planSteps.isNullOrBlank()) "\n\n## 已批准的执行计划\n$planSteps" else ""
        // 视觉链路：主模型不支持图片输入时，先用视觉模型描述截图，再让主模型基于文本决策。
        // visionMode: CLOUD=仅云端 | LOCAL=仅本地OCR | AUTO=优先云端、失败/未配置回退本地
        // 外挂视觉（enableExternalVision）优先于云端/本地，仅在未启用或不可用时才走后续来源。
        // 混合路由（smartVisionRoute）：端侧 3B 只认「简单任务」（元素树可读）——框选快、省云端额度；
        //  复杂任务（元素树稀疏，需强语义理解，如游戏/WebView/小程序）跳过 3B，直接走云端视觉。
        val visionCfg = visionConfig(settingsVal)
        // 页面是否复杂：元素树稀疏即视为复杂（无障碍读不到控件，需强视觉理解）
        val complexPage = snapshot.elements.size <= VISION_FALLBACK_THRESHOLD
        val hybrid = settingsVal.smartVisionRoute
        // 端侧 3B 是否用于本步：开启外挂且（未开混合，或当前为简单任务）
        val useOnDevice3b = settingsVal.enableExternalVision && (!hybrid || !complexPage)
        // 云端视觉是否可用：配置就绪，且（未开混合 / 复杂任务 / 简单任务但没启用 3B 只能靠云端）
        // 混合模式下简单任务有 3B 时主动跳过云端，把额度留给复杂任务
        val cloudVision = visionCfg != null && settingsVal.visionMode != "LOCAL" &&
            (!hybrid || complexPage || !settingsVal.enableExternalVision)
        var localRegions: List<com.phoneagent.vision.DetectedControl>? = null
        var externalUsed = false
        var pageText = safeText
        var desc: String? = null
        if (screenshot != null) {
            // 1) 优先：外挂端侧 3B 视觉 Agent 控件框选（类型 + 用途 + 归一化坐标）。
            //    混合模式下 3B 仅用于简单任务，复杂任务跳过此处直接走云端
            if (useOnDevice3b) {
                log(AgentLog.Level.INFO, "外挂视觉 Agent 控件识别…")
                val t0 = System.nanoTime()
                val controls = com.phoneagent.vision.ExternalVisionProvider.detectControls(
                    context = appContext,
                    bitmap = screenshot,
                    timeoutMs = EXTERNAL_VISION_TIMEOUT,
                )
                recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                if (controls.isNotEmpty()) {
                    externalUsed = true
                    localRegions = controls
                    desc = com.phoneagent.vision.ControlFormat.describe(controls)
                } else {
                    log(AgentLog.Level.INFO, "外挂视觉未就绪/不可用，回退云端或本地")
                }
            }
            // 2) 云端视觉
            if (desc.isNullOrBlank() && cloudVision) {
                log(AgentLog.Level.INFO, "视觉模型描述截图…（${visionCfg?.model}）")
                val t0 = System.nanoTime()
                desc = aiClient.visionDescribe(
                    baseUrl = visionCfg?.baseUrl ?: "",
                    apiKey = visionCfg?.apiKey ?: "",
                    model = visionCfg?.model ?: "",
                    screenshot = screenshot,
                    task = task,
                ).getOrNull()
                recordVisionMs((System.nanoTime() - t0) / 1_000_000)
            }
            // 3) LOCAL，或 AUTO 云端失败/未配置 → 端侧（外挂 OCR/3B）识别兜底。
            //    主程序不再内置 OCR，本地读图统一由外挂视觉 Agent 承担（v2.2 迁移）
            if (desc.isNullOrBlank() && !externalUsed &&
                (settingsVal.visionMode == "LOCAL" || settingsVal.visionMode == "AUTO")
            ) {
                log(AgentLog.Level.INFO, "外挂视觉端侧识别（LOCAL/兜底）…")
                val t0 = System.nanoTime()
                val controls = com.phoneagent.vision.ExternalVisionProvider.detectControls(
                    context = appContext,
                    bitmap = screenshot,
                    timeoutMs = EXTERNAL_VISION_TIMEOUT,
                )
                recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                if (controls.isNotEmpty()) {
                    externalUsed = true
                    localRegions = controls
                    desc = com.phoneagent.vision.ControlFormat.describe(controls)
                } else {
                    log(AgentLog.Level.INFO, "外挂视觉不可用，本地无可识别控件")
                    desc = "（未识别到控件）"
                }
            }
            if (!desc.isNullOrBlank()) pageText += "\n\n## 视觉描述（截图）\n$desc"
        }
        val userText = AgentPrompts.decision(
            lang = currentLang,
            task = task,
            stepIndex = _state.value.stepCount,
            totalSteps = (_state.value.stepCount).coerceAtLeast(1),
            currentStep = if (!planSteps.isNullOrBlank()) "按计划执行下一步" else "根据当前页面执行下一步",
            lastStepResult = lastStepResultText(),
            consecutiveFailures = consecutiveFailures,
            contextHint = annotated.contextHint,
        ) + planNote + "\n\n## 当前页面\n$pageText" +
            com.phoneagent.perception.PageAnnotator.knownControlsText(annotated.elements) +
            AgentPrompts.situationalExtras(currentLang, task) +
            progressSummaryText()
        val userMsg = ChatMessageDto(role = "user", content = mutableListOf(ContentPart(type = "text", text = userText)))
        addConversation("user", userText, hasImage = screenshot != null)

        _state.value = _state.value.copy(phase = AgentState.Phase.THINKING, message = "正在思考下一步...")
        pushFloating("正在思考下一步", "THINKING")
        // 实时展示发送给 AI 的决策上下文
        pushThinking(sent = userText)
        val startNano = System.nanoTime()
        // 主模型不收截图（只收视觉描述后的文本），避免不支持图片的模型报错；
        // 流式生成，边生成边把返回内容实时显示到悬浮窗 + 通知
        // 看门狗：单步决策超时则本步改为等待、下一轮重试，避免长线任务因云端卡住而无限阻塞
        val result: Result<com.phoneagent.ai.AiDecision>? = withTimeoutOrNull(WATCHDOG_DECIDE_MS) {
            aiClient.chatForAction(
                baseUrl = settingsVal.apiBaseUrl,
                apiKey = settingsVal.apiKey,
                model = settingsVal.model,
                // 长线任务历史压缩：只带系统消息 + 最近几轮 + 当前轮，避免上下文无限累积
                messages = chatHistory(messages, userMsg),
                screenshot = null,
                // 温度 v0.1 文档：每步决策 = 0.1；失败 3 次进入重规划 = 0.5
                temperature = decisionTemperature(),
                onDelta = { pushThinking(delta = it) },
            )
        }
        if (result == null) {
            log(AgentLog.Level.WARN, "单步云端决策超过 ${WATCHDOG_DECIDE_MS / 1000}s，本步改为等待，下一轮重试以防卡死")
            pushFloating("AI 决策超时，将自动重试", "THINKING")
            com.phoneagent.debug.ActiveNotifier.notify(
                appContext, com.phoneagent.debug.ActiveNotifier.ID_CLOUD_TIMEOUT,
                "AI 卡顿了一下", "云端暂时联系不上，已自动改为稍后重试，任务不会被中断。",
            )
            return AgentIntent(intent = "wait", waitMs = 1200, reasoning = "AI 决策超时，等待后重试")
        }
        val latencyMs = (System.nanoTime() - startNano) / 1_000_000
        val decision = result.getOrElse { err ->
            log(AgentLog.Level.ERROR, "AI 调用失败：${err.message}")
            _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = err.message ?: "AI 调用失败")
            return null
        }
        apiLog(userText, decision.rawContent.ifBlank { "（无正文，可能为错误）" }, latencyMs)
        recordMetrics(decision)
        var intent = decision.action
        // 视觉定位：对 hint 目标（元素树拿不到）给出像素坐标，供转译层本次定位使用。
        // by_id/by_text 由转译层在元素树中精确定位，无需这里算坐标。
        lastVisualCoordinate = null
        val tTarget = intent.target
        if (tTarget != null && tTarget.by == "hint" && screenshot != null &&
            (cloudVision || localRegions != null || externalUsed)
        ) {
            val targetText = tTarget.value
            if (!targetText.isNullOrBlank()) {
                val pos: Pair<Float, Float>? = when {
                    // 外挂视觉优先：端侧 3B 定位不准时退回已识别控件的本地匹配
                    externalUsed -> {
                        log(AgentLog.Level.INFO, "外挂视觉定位目标：$targetText")
                        val t0 = System.nanoTime()
                        val p = com.phoneagent.vision.ExternalVisionProvider.locate(
                            appContext, screenshot, targetText, EXTERNAL_VISION_TIMEOUT,
                        ) ?: com.phoneagent.vision.ControlFormat.locate(localRegions!!, targetText)
                        recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                        p
                    }
                    localRegions != null -> com.phoneagent.vision.ControlFormat.locate(localRegions, targetText)
                    cloudVision -> {
                        log(AgentLog.Level.INFO, "视觉模型定位目标：$targetText")
                        val t0 = System.nanoTime()
                        val p = aiClient.visionLocate(
                            baseUrl = visionCfg?.baseUrl ?: "",
                            apiKey = visionCfg?.apiKey ?: "",
                            model = visionCfg?.model ?: "",
                            screenshot = screenshot,
                            targetText = targetText,
                        ).getOrNull()
                        recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                        p
                    }
                    else -> null
                }
                if (pos != null) {
                    lastVisualCoordinate = (pos.first * screenWidth()).toInt() to (pos.second * screenHeight()).toInt()
                }
            }
        }
        // 记录本轮决策的详细追踪（Debug「按任务分类」展示）
                val visionSrc = when {
                    externalUsed -> "外挂3B"
                    !desc.isNullOrBlank() && cloudVision -> "云端"
                    !desc.isNullOrBlank() -> "本地OCR"
                    else -> "无"
                }
                val visionModel = when {
                    externalUsed -> "Qwen2.5-VL-3B (端侧)"
                    visionSrc == "云端" -> visionCfg?.model ?: ""
                    visionSrc == "本地OCR" -> "ML Kit 中文OCR"
                    else -> ""
                }
                recordStepTrace(
                    step = _state.value.stepCount,
                    sent = userText,
                    decision = decision,
                    visionSource = visionSrc,
                    visionModel = visionModel,
                    visionDescription = desc.orEmpty(),
                    screenshot = screenshot,
                )

        val reviewOn = (reviewOverride ?: settingsVal.enableReview) &&
            intent.intent != IntentType.GIVE_UP && needsReviewIntent(intent, snapshot)
        return if (reviewOn) {
            // 独立审核者复核：防止执行者脑补现状/点到不存在的控件（仅关键/无元素证据意图进入审核）
            log(AgentLog.Level.INFO, "审核者复核意图…")
            _state.value = _state.value.copy(message = "审核者复核意图…")
            reviewAction(task, snapshot, annotated, intent, settingsVal)
        } else {
            intent
        }
    }

    /** 本地硬规则预筛：仅当意图确实需要独立审核（完成判断、目标无元素证据的点击/输入）才升审核；
     *  其余动作由执行层验证兜底，跳过二次调用以降低开销 */
    private fun needsReviewIntent(intent: AgentIntent, snapshot: ScreenSnapshot): Boolean = when (intent.intent) {
        IntentType.FINISH, IntentType.GIVE_UP -> true
        IntentType.TAP, IntentType.LONG_PRESS, IntentType.INPUT, IntentType.SWIPE ->
            intent.target == null || intent.target.by == "hint"
        else -> false
    }

    /** 用独立的审核者 AI 复核执行者意图是否基于当前页面证据；
     *  审核者输出 {pass, why, freefix}，拒绝且给修正时用修正意图，否则沿用原意图（执行层兜底） */
    private suspend fun reviewAction(
        task: String,
        snapshot: ScreenSnapshot,
        annotated: com.phoneagent.perception.AnnotatedPage,
        intent: AgentIntent,
        settingsVal: AppSettings.Settings,
    ): AgentIntent {
        // 审核者模型：非链路聚合时用主模型；开了思考能力/链路聚合时用思考模型（同模型复核）
        val cfg = reasoningConfig(settingsVal)
        val baseUrl = cfg?.baseUrl ?: settingsVal.apiBaseUrl
        val apiKey = cfg?.apiKey ?: settingsVal.apiKey
        val model = cfg?.model ?: settingsVal.model
        val safePage = DataSanitizer.sanitize(snapshot.toAiText())
        val user = buildString {
            appendLine("## 任务")
            appendLine(task)
            appendLine()
            appendLine("## 执行者拟执行意图")
            appendLine(intent.toString())
            appendLine()
            appendLine("## 当前页面（真实证据）")
            appendLine("前台应用：${snapshot.packageName ?: "未知"}")
            appendLine(safePage)
            appendLine()
            appendLine("## 页面提示")
            appendLine(annotated.contextHint.ifBlank { "无" })
        }
        val messages = mutableListOf(
            ChatMessageDto(role = "system", content = mutableListOf(ContentPart(type = "text", text = AgentPrompts.reviewSystem(currentLang)))),
            ChatMessageDto(role = "user", content = mutableListOf(ContentPart(type = "text", text = user))),
        )
        val raw = aiClient.chat(
            baseUrl = baseUrl, apiKey = apiKey, model = model,
            messages = messages, temperature = 0.1,
        ).getOrNull() ?: return intent
        val root = runCatching { json.parseToJsonElement(extractJsonObject(raw)).jsonObject }.getOrNull()
        if (root == null) {
            FloatingWindowService.updateReviewText("审核结果解析失败，沿用执行者「${intent.intent}」")
            return intent
        }
        val pass = root["pass"]?.jsonPrimitive?.booleanOrNull ?: true
        if (pass) {
            FloatingWindowService.updateReviewText("✓ 执行者「${intent.intent}」有页面依据，审核通过")
            return intent
        }
        val why = root["why"]?.jsonPrimitive?.content ?: "无理由"
        FloatingWindowService.updateReviewText("✗ 执行者「${intent.intent}」被拒绝：$why")
        log(AgentLog.Level.WARN, "审核者拒绝：$why")
        // 审核者给出有证据的修正意图时采用；否则沿用原意图（由执行层兜底失败并注入反馈）
        val fix = root["freefix"]
        if (fix is kotlinx.serialization.json.JsonObject) {
            val correction = runCatching { json.decodeFromString<AgentIntent>(fix.toString()) }.getOrNull()
            if (correction != null) {
                FloatingWindowService.updateReviewText("✗ 执行者「${intent.intent}」被拒绝：$why；采用修正「${correction.intent}」")
                log(AgentLog.Level.WARN, "采用审核者修正意图：${correction.intent}")
                return correction
            }
        }
        return intent
    }

    /** 测试主模型连接是否可用（用于设置保存前的校验） */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> {
        val messages = listOf(
            ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请只回复：OK"))),
        )
        return aiClient.chat(
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            model = model.trim(),
            messages = messages,
            temperature = 0.1,
        )
    }

    /** 视觉模型配置：仅在视觉模型启用时生效；视觉 API Key 为空则回退主模型 Key */
    private fun visionConfig(settingsVal: AppSettings.Settings): VisionConfig? {
        if (!settingsVal.visionEnabled) return null
        val model = settingsVal.visionModel.trim()
        if (model.isBlank()) return null
        val baseUrl = settingsVal.visionBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
        val apiKey = settingsVal.visionApiKey.trim().ifBlank { settingsVal.apiKey }
        if (apiKey.isBlank()) return null
        return VisionConfig(baseUrl, model, apiKey)
    }

    /** 思考模型配置：
     *  - mainThinking 开启：主模型自身支持思考，规划等复杂任务直接用主模型 + thinking
     *  - 否则 enableChain 开启且配了 reasonModel：用独立思考模型
     *  - 都未满足：返回 null（规划用主模型普通模式） */
    private fun reasoningConfig(settingsVal: AppSettings.Settings): ReasoningConfig? {
        // 主模型思考能力开启 → 主模型即思考模型（同一 API 配置）
        if (settingsVal.mainThinking) {
            val baseUrl = settingsVal.apiBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
            val apiKey = settingsVal.apiKey.trim()
            if (apiKey.isBlank()) return null
            return ReasoningConfig(baseUrl, settingsVal.model.trim().ifBlank { GlmDefaults.MODEL }, apiKey)
        }
        if (!settingsVal.enableChain) return null
        val model = settingsVal.reasonModel.trim()
        if (model.isBlank()) return null
        val baseUrl = settingsVal.reasonBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
        val apiKey = settingsVal.reasonApiKey.trim().ifBlank { settingsVal.apiKey }
        if (apiKey.isBlank()) return null
        return ReasoningConfig(baseUrl, model, apiKey)
    }

    /** 每步决策温度：失败越频繁越鼓励换思路（对应温度文档第三节） */
    private fun decisionTemperature(): Double =
        if (consecutiveFailures >= 3) REPLAN_TEMPERATURE else DECISION_TEMPERATURE

    /**
     * 判断动作是否属于"有副作用、需幂等保护"的操作（提交/发送/下单/支付/删除/发布等）。
     * 依据动作类型 + 目标 label/理由 中的触发词。
     */
    private fun isFinalSubmit(action: AgentAction, type: String): Boolean = EngineRules.isFinalSubmit(action, type)

    /** 幂等判定：当前页面是否已出现"完成成功"证据（避免重复执行副作用后再次触发） */
    private fun idempotencyDone(snapshot: ScreenSnapshot): Boolean = EngineRules.idempotencyDone(snapshot)

    private suspend fun executeWithVerify(action: AgentAction, snapshot: ScreenSnapshot): com.phoneagent.execution.VerifyResult {
        val execT0 = System.nanoTime()
        // 规范化文档动作词汇
        val type = ActionType.ALIAS[action.type] ?: action.type

        // 文档写入动作：不依赖屏幕/无障碍，直接把内容写入工作区
        if (type == ActionType.WRITE_DOC) {
            return executeWriteDoc(action).also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        // SHELL 动作优先独立处理：Shizuku 可用时无需无障碍服务即可执行
        if (type == ActionType.SHELL) {
            return executeShellAction(action).also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        // 幂等保护（v2.2 5.4）：副作用意图（提交/发送/下单/支付/删除）已由页面证明完成 → 跳过，防重复副作用与误触
        if (isFinalSubmit(action, type) && idempotencyDone(snapshot)) {
            return com.phoneagent.execution.VerifyResult(true, "检测到页面已含完成证据（如「提交成功」），跳过重复副作用操作", "", "").also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        val service = AgentAccessibilityService.instance ?: return com.phoneagent.execution.VerifyResult(false, "无障碍服务不可用", "", "").also {
            recordExecMs((System.nanoTime() - execT0) / 1_000_000)
        }
        val executor = ActionExecutor(service)
        val verifier = VerifiedClickExecutor(executor)

        // 解析目标坐标（仅对需要坐标的动作类型校验非空）
        val target = resolveTarget(action, snapshot)
        val (x, y) = resolvePoint(action, target)

        val result = when (type) {
            ActionType.CLICK, ActionType.TAP -> {
                if (x == null || y == null) com.phoneagent.execution.VerifyResult(false, "当前页面(${snapshot.packageName ?: "未知应用"})没有控件(${action.target?.value ?: "坐标"})：目标应用若未打开，先 launch 到该应用再操作，禁止点击不存在的控件", "", "")
                else verifier.executeAndVerify(snapshot, action) { executor.click(x, y).isSuccess() }
            }
            ActionType.LONG_CLICK, ActionType.LONG_PRESS -> {
                if (x == null || y == null) com.phoneagent.execution.VerifyResult(false, "无法定位动作目标", "", "")
                else verifier.executeAndVerify(snapshot, action) { executor.longClick(x, y).isSuccess() }
            }
            ActionType.SWIPE -> {
                // swipe 常为纯手势（无 target），缺少坐标时以屏幕中心为起点，避免“无法定位动作目标”误判失败
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                val dist = action.distancePx
                    ?: if (action.direction == "left" || action.direction == "right") screenWidth() else screenHeight()
                val (ex, ey) = swipeEndpoints(px, py, action.direction, dist)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, ex, ey, action.durationMs ?: 400).isSuccess() }
            }
            ActionType.SWIPE_UP -> {
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, px, (py - screenHeight()).coerceAtLeast(0)).isSuccess() }
            }
            ActionType.SWIPE_DOWN -> {
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, px, (py + screenHeight()).coerceAtMost(screenHeight())).isSuccess() }
            }
            ActionType.SWIPE_LEFT -> {
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, (px - screenWidth()).coerceAtLeast(0), py).isSuccess() }
            }
            ActionType.SWIPE_RIGHT -> {
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, (px + screenWidth()).coerceAtMost(screenWidth()), py).isSuccess() }
            }
            ActionType.SCROLL, ActionType.SCROLL_TO -> verifier.executeAndVerify(snapshot, action) { executor.scroll(target, action.direction ?: action.text ?: "up").isSuccess() }
            ActionType.TYPE_TEXT -> {
                if ((x == null || y == null) && target == null) com.phoneagent.execution.VerifyResult(false, "当前页面(${snapshot.packageName ?: "未知应用"})没有可输入控件(${action.target?.value ?: "坐标"})：目标应用若未打开，先 launch 到该应用，禁止在页面外凭空输入", "", "")
                else verifier.executeAndVerify(snapshot, action) { executor.typeText(action.text ?: "", target, x, y).isSuccess() }
            }
            ActionType.KEY -> handleKey(executor, action.keycode ?: "BACK")
            ActionType.LAUNCH -> {
                val targetPkg = action.packageName
                if (targetPkg.isNullOrBlank()) return com.phoneagent.execution.VerifyResult(false, "launch 缺少包名", "", "").also {
                    recordExecMs((System.nanoTime() - execT0) / 1_000_000)
                }
                val launched = executor.launchApp(targetPkg).isSuccess()
                if (!launched) return com.phoneagent.execution.VerifyResult(false, "启动应用失败: $targetPkg", "", "").also {
                    recordExecMs((System.nanoTime() - execT0) / 1_000_000)
                }
                // launch 动作不依赖包名检测（无障碍服务在某些版本上无法正确报告前台切换），
                // 只要启动指令发出就视为成功，让 AI 在下一轮观察新页面
                delay(800)
                com.phoneagent.execution.VerifyResult(true, "已启动应用: $targetPkg", snapshot.packageName ?: "", targetPkg)
            }
            ActionType.OPEN -> executeNoVerify(executor) {
                // 优先直接深链 uri；否则按软件页面直达索引(app+page)解析直达方式
                val uri = action.uri
                if (!uri.isNullOrBlank()) {
                    executor.openUri(uri).isSuccess()
                } else {
                    val entry = com.phoneagent.model.AppPageIndex.resolve(action.app, action.page)
                    when {
                        entry?.uri != null -> executor.openUri(entry.uri)
                        entry?.intentAction != null -> executor.openSettingsAction(entry.intentAction)
                        entry?.packageName != null -> executor.launchApp(entry.packageName)
                        else -> com.phoneagent.a11y.ActionExecutor.Result.Failure("未在软件页面索引中找到 ${action.app ?: "未知软件"} 页面${action.page ?: ""}")
                    }.isSuccess()
                }
            }
            ActionType.BACK -> executeNoVerify(executor) { executor.back().isSuccess() }
            ActionType.HOME -> executeNoVerify(executor) { executor.home().isSuccess() }
            ActionType.RECENTS -> executeNoVerify(executor) { executor.recents().isSuccess() }
            ActionType.WAIT -> { delay(action.timeoutMs ?: action.durationMs ?: 1000); com.phoneagent.execution.VerifyResult(true, "等待完成", "", "") }
            ActionType.REFRESH -> com.phoneagent.execution.VerifyResult(true, "刷新", "", "")
            else -> com.phoneagent.execution.VerifyResult(false, "未知动作", "", "")
        }
        recordExecMs((System.nanoTime() - execT0) / 1_000_000)
        return result
    }

    /**
     * 执行文档写入动作：直接把生成内容写入工作区，供用户在工作区页查看。
     * @return 写成功的 VerifyResult；工作区未启用时返回失败
     */
    private suspend fun executeWriteDoc(action: AgentAction): com.phoneagent.execution.VerifyResult {
        val engine = workAreaEngine ?: return com.phoneagent.execution.VerifyResult(false, "工作区未启用", "", "")
        val content = action.text ?: return com.phoneagent.execution.VerifyResult(false, "文档内容为空", "", "")
        val fileName = action.summary ?: ""
        // 直接写入工作区（内容已由 AI 决策产出，无需再次生成）
        val written = engine.writeDocument(content, fileName)
        return if (written.isBlank()) {
            com.phoneagent.execution.VerifyResult(false, engine.error.value.ifBlank { "文档写入失败" }, "", "")
        } else {
            com.phoneagent.execution.VerifyResult(true, "文档已写入工作区：$written", "", "")
        }
    }

    /**
     * 执行 shell 动作。
     * - Shizuku 可用：直接通过 Shizuku 执行（不依赖无障碍服务），查询类命令输出回传 AI。
     * - Shizuku 不可用：禁止执行 shell，把 AI 输出的友好命令翻译为等价的无障碍动作执行，
     *   保证任务在无 Shizuku 权限时仍能推进，且绝不真正调用 shell。
     */
    private suspend fun executeShellAction(action: AgentAction): com.phoneagent.execution.VerifyResult {
        val cmd = action.command ?: return com.phoneagent.execution.VerifyResult(false, "shell 命令为空", "", "")
        // Shizuku 不可用：硬性禁止 shell，翻译为无障碍动作
        if (shizukuManager?.isAvailable() != true) {
            return executeShellViaAccessibility(cmd)
        }
        val resolved = ShellCommands.resolve(cmd, screenWidth(), screenHeight())
            ?: return com.phoneagent.execution.VerifyResult(false, "未知 shell 命令: $cmd", "", "")
        val result = shizukuManager.executeShell(resolved)
        return when (result) {
            is com.phoneagent.shizuku.ShizukuManager.ShellResult.Success -> {
                // 捕获输出：查询类命令回传 AI，指令类命令忽略
                lastShellOutput = result.output.trim().take(1200)
                delay(300)
                com.phoneagent.execution.VerifyResult(true, "shell 执行成功", "", "")
            }
            is com.phoneagent.shizuku.ShizukuManager.ShellResult.Failure -> {
                lastShellOutput = ""
                com.phoneagent.execution.VerifyResult(false, result.reason, "", "")
            }
        }
    }

    /**
     * Shizuku 不可用时：禁止执行 shell。将 AI 输出的友好命令（tap/lp/sw/back/key/text/launch 等）
     * 翻译为等价的无障碍动作执行；无法翻译的命令返回失败，促使 AI 改用无障碍动作重决策。
     */
    private suspend fun executeShellViaAccessibility(cmd: String): com.phoneagent.execution.VerifyResult {
        val service = AgentAccessibilityService.instance
            ?: return com.phoneagent.execution.VerifyResult(false, "Shizuku 不可用且无障碍服务不可用", "", "")
        val executor = ActionExecutor(service)
        val w = screenWidth()
        val h = screenHeight()
        val fail = { reason: String -> com.phoneagent.execution.VerifyResult(false, reason, "", "") }

        val parsed = ShellCommands.parse(cmd)
            ?: return fail("Shizuku 不可用，命令无法转为无障碍动作: $cmd")
        val (name, args) = parsed

        // 结果包装：成功提示已用无障碍代替 shell
        fun wrap(r: ActionExecutor.Result): com.phoneagent.execution.VerifyResult = when (r) {
            is ActionExecutor.Result.Success -> com.phoneagent.execution.VerifyResult(true, "已用无障碍代替 shell 执行: $cmd", "", "")
            is ActionExecutor.Result.Failure -> com.phoneagent.execution.VerifyResult(false, r.reason, "", "")
        }

        // 坐标类动作统一处理
        suspend fun coordOp(block: suspend (Int, Int) -> ActionExecutor.Result): com.phoneagent.execution.VerifyResult {
            val (x, y) = ShellCommands.coord(args, w, h) ?: return fail("Shizuku 不可用，坐标无效: $cmd")
            return wrap(block(x, y))
        }

        return when (name) {
            "tap" -> coordOp { x, y -> executor.click(x, y) }
            "lp", "long_press" -> coordOp { x, y -> executor.longClick(x, y) }
            "dt", "double_tap" -> coordOp { x, y ->
                val first = executor.click(x, y)
                if (first is ActionExecutor.Result.Success) executor.click(x, y) else first
            }
            "su", "swipe_up" -> coordOp { x, y -> executor.swipe(x, y, x, (y - h * 0.25f).toInt().coerceAtLeast(0), 400) }
            "sd", "swipe_down" -> coordOp { x, y -> executor.swipe(x, y, x, (y + h * 0.25f).toInt().coerceAtMost(h), 400) }
            "sl", "swipe_left" -> coordOp { x, y -> executor.swipe(x, y, (x - w * 0.25f).toInt().coerceAtLeast(0), y, 400) }
            "sr", "swipe_right" -> coordOp { x, y -> executor.swipe(x, y, (x + w * 0.25f).toInt().coerceAtMost(w), y, 400) }
            "sw", "swipe" -> {
                val c = ShellCommands.coords(args, w, h)
                if (c == null) fail("Shizuku 不可用，滑动参数无效: $cmd")
                else wrap(executor.swipe(c[0], c[1], c[2], c[3], 400))
            }
            "back" -> wrap(executor.back())
            "home" -> wrap(executor.home())
            "recents" -> wrap(executor.recents())
            "key" -> when (args.trim().uppercase()) {
                "BACK" -> wrap(executor.back())
                "HOME" -> wrap(executor.home())
                "RECENTS", "RECENT", "APP_SWITCH" -> wrap(executor.recents())
                else -> fail("Shizuku 不可用，按键 $args 无法通过无障碍执行: $cmd")
            }
            "text", "type" -> {
                val t = args.trim().removeSurrounding("\"").removeSurrounding("'")
                if (t.isEmpty()) fail("Shizuku 不可用，输入内容为空: $cmd")
                else wrap(executor.typeText(t, null))
            }
            "launch" -> {
                val pkg = args.trim()
                if (pkg.isEmpty()) fail("Shizuku 不可用，缺少包名: $cmd")
                else wrap(executor.launchApp(pkg))
            }
            "am" -> {
                val intent = args.trim()
                val pkg = intent.substringBefore('/').substringBefore(':').trim()
                if (pkg.isEmpty()) fail("Shizuku 不可用，无法解析 Activity 包名: $cmd")
                else wrap(executor.launchApp(pkg))
            }
            // 裸包名（如 com.tencent.mm）→ 视为实际动作 launch，自动补全并启动应用
            else -> {
                if (cmd.contains('.') && !cmd.contains(" "))
                    wrap(executor.launchApp(cmd))
                else fail("Shizuku 不可用，命令无法通过无障碍执行: $cmd")
            }
        }
    }

    /** 解析动作目标元素：优先 elementIndex，其次 target.method=id/label */
    private fun resolveTarget(action: AgentAction, snapshot: ScreenSnapshot): UiElement? {
        action.elementIndex?.let { idx -> snapshot.elements.firstOrNull { it.index == idx } }?.let { return it }
        val t = action.target ?: return null
        return when (t.method) {
            "label" -> snapshot.elements.firstOrNull {
                val label = it.effectiveLabel() ?: return@firstOrNull false
                label.contains(t.value, ignoreCase = true)
            }
            "id" -> snapshot.elements.firstOrNull {
                it.semanticId == t.value ||
                    (it.viewId ?: "").endsWith(t.value, ignoreCase = true)
            }
            else -> null
        }
    }

    /** 解析点击坐标：目标元素中心 > 比例坐标 > 直接 x/y */
    private fun resolvePoint(action: AgentAction, target: UiElement?): Pair<Int?, Int?> {
        if (target != null) return target.centerX to target.centerY
        action.x?.let { if (action.y != null) return action.x to action.y }
        val t = action.target ?: return null to null
        if (t.method == "coordinate") {
            val parts = t.value.split(",").map { it.trim().toFloatOrNull() }
            if (parts.size == 2 && parts[0] != null && parts[1] != null) {
                // 比例坐标收敛到屏幕内，防止越界点击
                return ((parts[0]!! * screenWidth()).toInt().coerceIn(0, screenWidth())) to
                    ((parts[1]!! * screenHeight()).toInt().coerceIn(0, screenHeight()))
            }
        }
        return null to null
    }

    /** 计算滑动终点（纯逻辑，见 [EngineRules.swipeEndpoints]） */
    private fun swipeEndpoints(x: Int, y: Int, direction: String?, distanceArg: Int?): Pair<Int, Int> =
        EngineRules.swipeEndpoints(x, y, direction, distanceArg, screenWidth(), screenHeight())

    private suspend fun handleKey(executor: ActionExecutor, keycode: String): com.phoneagent.execution.VerifyResult {
        return when (keycode.uppercase()) {
            "BACK" -> executeNoVerify(executor) { executor.back().isSuccess() }
            "HOME" -> executeNoVerify(executor) { executor.home().isSuccess() }
            "RECENT", "RECENTS" -> executeNoVerify(executor) { executor.recents().isSuccess() }
            "ENTER" -> com.phoneagent.execution.VerifyResult(true, "回车（假定键盘已确认）", "", "")
            else -> com.phoneagent.execution.VerifyResult(false, "未知按键 $keycode", "", "")
        }
    }

    private suspend fun executeNoVerify(executor: ActionExecutor, block: suspend () -> Boolean): com.phoneagent.execution.VerifyResult {
        val ok = block()
        delay(500)
        return com.phoneagent.execution.VerifyResult(ok, if (ok) "已执行" else "执行失败", "", "")
    }

    private fun recordMetrics(decision: com.phoneagent.ai.AiDecision) {
        val prev = _metrics.value
        val requestCount = prev.requestCount + 1
        val latencySum = prev.avgLatencyMs * prev.requestCount + decision.elapsedMs
        val avgLatency = if (requestCount > 0) latencySum / requestCount else 0
        val tps = if (decision.elapsedMs > 0) decision.completionTokens.toDouble() / decision.elapsedMs * 1000.0 else 0.0
        _metrics.value = prev.copy(
            totalTokens = prev.totalTokens + decision.totalTokens,
            promptTokens = prev.promptTokens + decision.promptTokens,
            completionTokens = prev.completionTokens + decision.completionTokens,
            lastLatencyMs = decision.elapsedMs,
            avgLatencyMs = avgLatency,
            tokensPerSec = tps,
            requestCount = requestCount,
        )
    }

    /** 观测性埋点：视觉环节单次耗时聚合（外挂3B/云端/本地识别 + 定位） */
    private fun recordVisionMs(elapsedMs: Long) {
        val prev = _metrics.value
        _metrics.value = prev.copy(
            visionCount = prev.visionCount + 1,
            visionTotalMs = prev.visionTotalMs + elapsedMs,
        )
    }

    /** 观测性埋点：执行环节单次耗时聚合（无障碍点击/输入/滑动等） */
    private fun recordExecMs(elapsedMs: Long) {
        val prev = _metrics.value
        _metrics.value = prev.copy(
            execCount = prev.execCount + 1,
            execTotalMs = prev.execTotalMs + elapsedMs,
        )
    }

    private fun recordStepTrace(
        step: Int,
        sent: String,
        decision: com.phoneagent.ai.AiDecision,
        visionSource: String,
        visionModel: String,
        visionDescription: String,
        screenshot: android.graphics.Bitmap?,
    ) {
        _traces.value = _traces.value + com.phoneagent.model.StepTrace(
            taskId = currentTaskId,
            taskName = currentTaskName,
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
            screenshot = screenshot,
        )
    }

    private fun recordStep(step: Int, action: AgentAction, verification: String, before: String, after: String) {
        val rec = StepRecord(
            step = step,
            action = action,
            verificationResult = verification,
            beforeFingerprint = before,
            afterFingerprint = after,
            isConfirmed = verification == "verified_success",
        )
        _executionHistory.value = _executionHistory.value + rec
    }

    /**
     * 每步执行完后留档最新一步：截屏 + 本地视觉模型框选 + 拼装执行说明。
     * 保留在 [StepShot] 中，新任务开始时会由 [start] 清空覆盖。
     * 框选标注独立于视觉模式，始终用本地视觉模型（OCR）执行，便于对照原图/识别图后续开发。
     */
    private suspend fun stepShotCapture(step: Int, action: AgentAction, verified: Boolean) {
        val snapshot = observe()
        val shot = ScreenSharingService.instance?.captureFrame()
        var annotated: Bitmap? = null
        if (shot != null) {
            // 端侧控件识别已外移到外挂视觉 Agent；这里跨进程调用并画框（v2.2.1 截图标注）
            val controls = com.phoneagent.vision.ExternalVisionProvider.detectControls(
                context = appContext,
                bitmap = shot,
                timeoutMs = EXTERNAL_VISION_TIMEOUT,
            )
            if (controls.isNotEmpty()) annotated = com.phoneagent.vision.ControlFormat.drawBoxes(shot, controls)
        }
        _stepShot.value = StepShot(
            step = step,
            actionType = action.type,
            description = actionDescription(action, verified),
            verified = verified,
            screenshot = shot,
            annotatedScreenshot = annotated,
        )
    }

    /** 将一步动作拼装成人类可读的执行说明（用 AI 的 reasoning/reason + 验证结果） */
    private fun actionDescription(action: AgentAction, verified: Boolean): String {
        val reason = action.reasoning?.takeIf { it.isNotBlank() } ?: action.reason?.takeIf { it.isNotBlank() }
        return buildString {
            append(if (verified) "✅ 已生效" else "⚠️ 待确认")
            append(" · ${actionLabel(action.type)}")
            reason?.let { append("\n$it") }
        }
    }

    /** 每步执行成功后，把该步摘要写入长线工作记忆（保持最近 [MAX_PROGRESS_NOTES] 条） */
    private fun recordProgress(step: Int, action: AgentAction) {
        completedSteps++
        lastProgressAt = System.currentTimeMillis()
        noProgressNotified = false
        val label = actionLabel(action.type)
        val reason = action.reasoning?.takeIf { it.isNotBlank() } ?: action.reason?.takeIf { it.isNotBlank() }
        val note = if (reason != null) "第${step}步: $label（$reason）" else "第${step}步: $label"
        if (progressNotes.size >= MAX_PROGRESS_NOTES) progressNotes.removeFirst()
        progressNotes.addLast(note)
    }

    /** 渲染长线工作记忆摘要，注入每轮决策上下文（v2.2 5.2 固定部分：进度摘要） */
    private fun progressSummaryText(): String {
        if (completedSteps == 0) return ""
        val total = if (totalPlannedSteps > 0) "/$totalPlannedSteps" else ""
        val recent = progressNotes.joinToString("；")
        // 阶段视图（v2.2 5.1）：按每 STAGE_SIZE 步一位阶段
        val stage = if (totalPlannedSteps > 0) {
            val totalStages = (totalPlannedSteps + STAGE_SIZE - 1) / STAGE_SIZE
            val cur = ((completedSteps + STAGE_SIZE - 1) / STAGE_SIZE).coerceIn(1, totalStages)
            " 阶段 $cur/$totalStages。"
        } else ""
        return "\n## 执行进度（长线任务参照，概览即可，勿重复执行已完成步骤）\n已完成 ${completedSteps}${total} 步。${stage}最近操作：$recent"
    }

    /**
     * 任务成功后的模板处理：
     * - 复用了既有模板 → 回写健康状态（失败归零）
     * - 全新计划 → 不自动入库，由 completion 流程调用 requestConfirmSaveTemplate() 请用户确认
     */
    private suspend fun learnTemplate(task: String) {
        reusedTemplateId?.let { tid ->
            runCatching { com.phoneagent.task.TaskStore.updateTemplateHealth(appContext, tid, success = true) }
        }
    }

    /** 任务完成后，通过悬浮窗交互请用户确认是否保存为模板（不自动入库） */
    private fun requestConfirmSaveTemplate() {
        showFloatingInteraction(
            type = "savetemplate",
            title = "保存执行模板",
            content = "本次任务已成功完成，是否将执行步骤保存为模板，供下次直接复用？",
        )
    }

    /**
     * 用户在悬浮窗对"保存模板"确认的结果：
     * - save=true → 将该次任务的执行计划写入用户模板库（用户主动确认后才入库）
     * - save=false → 丢弃，不入库
     */
    fun confirmSaveTemplate(save: Boolean) {
        val task = pendingTemplateTask ?: return
        val plan = pendingTemplatePlan ?: return
        pendingTemplateTask = null
        pendingTemplatePlan = null
        // 应答后即隐藏"保存模板"交互面板
        clearFloatingQuery()
        if (!save) {
            // 用户选择不保存：直接显示完成动效
            FloatingWindowService.showDone("任务完成")
            return
        }
        scope.launch {
            try {
                val id = "tpl_" + java.util.UUID.randomUUID().toString().take(8)
                com.phoneagent.task.TaskStore.upsertTemplate(
                    appContext,
                    com.phoneagent.task.TaskTemplate(
                        id = id, goal = task.take(120), plan = plan,
                        executionCount = 1, successCount = 1, failedStreak = 0, enabled = true,
                    ),
                )
                log(AgentLog.Level.INFO, "用户确认，模板已入库：$task")
                FloatingWindowService.showDone("任务完成且已保存模板")
            } catch (e: Exception) {
                log(AgentLog.Level.WARN, "模板入库失败：${e.message}")
                FloatingWindowService.showDone("任务完成")
            }
        }
    }

    private fun actionLabel(type: String): String = EngineRules.actionLabel(type)

    private fun recordsIntoHistory(step: Int, action: AgentAction, verify: com.phoneagent.execution.VerifyResult) {
        recordStep(step, action, if (verify.success) "verified_success" else "unverified", verify.beforeFingerprint, verify.afterFingerprint)
    }

    private suspend fun awaitUserHint(): String {
        _needsUser.value = true
        val hint = _userHintResult.first()
        _needsUser.value = false
        return hint
    }

    private suspend fun observe(): ScreenSnapshot {
        val a11y = AgentAccessibilityService.instance
        val snapshot = a11y?.captureScreen() ?: ScreenSnapshot(
            missingAccessibility = true,
            // 无障碍不可用时也填充真实屏幕尺寸（供 ShellCommands 比例坐标换算）
            screenWidth = screenWidth(),
            screenHeight = screenHeight(),
        )
        _state.value = _state.value.copy(
            hasAccessibility = a11y != null,
            hasScreenshot = ScreenSharingService.instance?.captureFrame() != null,
        )
        return snapshot
    }

    private fun screenWidth(): Int = lastSnapshot.screenWidth.takeIf { it > 0 } ?: realScreenWidth()
    private fun screenHeight(): Int = lastSnapshot.screenHeight.takeIf { it > 0 } ?: realScreenHeight()

    /** 从系统 WindowManager 获取真实屏幕尺寸（不依赖无障碍服务） */
    private fun realScreenWidth(): Int {
        val wm = appContext.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
        val p = android.graphics.Point()
        wm?.defaultDisplay?.getRealSize(p)
        return if (p.x > 0) p.x else 1080
    }

    private fun realScreenHeight(): Int {
        val wm = appContext.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
        val p = android.graphics.Point()
        wm?.defaultDisplay?.getRealSize(p)
        return if (p.y > 0) p.y else 2400
    }

    /** 查询已安装应用（桌面启动器应用）的应用名列表，供规划提示词参考，让计划更贴近真实环境 */
    private fun installedAppList(): String {
        return runCatching {
            val pm = appContext.packageManager
            val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(launcher, 0)
                .mapNotNull { info ->
                    val label = info.loadLabel(pm).toString().trim()
                    if (label.isBlank()) null else "$label(${info.activityInfo.packageName})"
                }
                .distinct()
                .sorted()
                .take(60)
                .joinToString("、")
        }.getOrDefault("")
    }
}

private fun ActionExecutor.Result.isSuccess(): Boolean = this is ActionExecutor.Result.Success

/** 规划流程阶段 */
sealed class PlanPhase {
    /** 空闲，未开始规划 */
    data object Idle : PlanPhase()
    /** 正在向 AI 请求规划 */
    data object Planning : PlanPhase()
    /** 需要用户澄清歧义 */
    data class Clarifying(val clarification: Clarification) : PlanPhase()
    /** 计划已生成，等待用户批准 */
    data class AwaitingApproval(val plan: TaskPlan) : PlanPhase()
    /** 已批准，开始执行 */
    data class Approved(val plan: TaskPlan?) : PlanPhase()
    /** 规划失败 */
    data class Error(val message: String) : PlanPhase()
}

/** 视觉模型配置（截图描述 + 点击坐标定位） */
private data class VisionConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

/** 思考模型配置（规划/重规划等复杂任务，开启深度思考） */
private data class ReasoningConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)