package com.phoneagent.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import com.phoneagent.device.a11y.ActionExecutor
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.core.ai.GlmDefaults
import com.phoneagent.data.prefs.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.phoneagent.domain.rules.EngineRules
import com.phoneagent.domain.rules.LocalDecisionEngine
import com.phoneagent.domain.rules.ShellCommands
import com.phoneagent.engine.execution.AppNameResolver
import com.phoneagent.engine.execution.CapabilityManager
import com.phoneagent.engine.execution.IntentResolver
import com.phoneagent.engine.execution.IntentTranslator
import com.phoneagent.engine.execution.VerifiedClickExecutor
import com.phoneagent.overlay.FloatingWindowService
import com.phoneagent.data.store.AiMemoryUpsert
import com.phoneagent.data.store.AnomalyMemoryEngine
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.data.store.ProfileLearner
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.Clarification
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.PlanResponse
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.domain.model.StepShot
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.network.CloudAgent
import com.phoneagent.engine.perception.PageAnnotator
import com.phoneagent.engine.perception.effectiveLabel
import com.phoneagent.device.screen.ScreenCapture
import com.phoneagent.core.security.DataSanitizer
import com.phoneagent.core.security.SensitivePageDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
    private val shizukuManager: com.phoneagent.device.shell.ShizukuManager? = null,
    private val adbTransport: com.phoneagent.device.shell.AdbBootstrapTransport? = null,
    private val termuxBridge: com.phoneagent.device.shell.TermuxBridge? = null,
    private val documentEngine: com.phoneagent.feature.document.DocumentEngine? = null,
    private val mcpManager: com.phoneagent.feature.mcp.McpManager? = null,
    /** 技能执行网关：把 AI 输出的"技能名/技能 id"归一化为意图或 MCP 调用（见 [SkillCompat.normalize]） */
    private val skillGateway: com.phoneagent.feature.skill.SkillExecutionGateway? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    companion object {
        /** 参数缺失追问的最大补全轮数：tap 缺 target / open_app 缺 app 时最多向 AI 追问几轮 */
        private const val MAX_PARAM_REFILL = 3

        /** 无障碍元素树精简阈值：小于等于该值时视为"无障碍读不到控件"，自动转视觉模型截图补足 */
        private const val VISION_FALLBACK_THRESHOLD = 3

        /** 外挂视觉 Agent 单次识别的超时（ms）：端侧 3B 在 CPU 上较慢，超时回退云端/本地，避免阻塞决策循环 */
        private const val EXTERNAL_VISION_TIMEOUT = 20_000L

        /** 决策链路最多保留的近期对话轮次（每轮 user+assistant 各算一条）。超过则截断早期历史，
         *  让长线任务上下文长度保持恒定，避免历史无限累积拖慢/带偏 AI */
        private const val MAX_DIALOG_TURNS = 6

        /** 单步云端决策看门狗（文档 v2.2 5.5）：超过则视为云端卡住，本步改为等待、下一轮重试，避免长线任务卡死 */
        private const val WATCHDOG_DECIDE_MS = 45_000L

        /** 单步视觉分析看门狗：云端视觉（OkHttp 读超时 120s）与端侧 3B（单次 20s）都可能长时间阻塞，
         *  没有它界面会一直停在“观察屏幕”（视觉分析发生在决策状态切换之前），看起来像卡死 */
        private const val WATCHDOG_VISION_MS = 25_000L

        /** 日志环形缓冲上限（v2.2.1 LogCollector），防长线任务内存膨胀 */
        private const val MAX_LOGS = 800

        /** 端侧决策来源标注（写入 StepTrace.visionSource，便于区分端侧/云端决策） */
        private const val LOCAL_DECISION_SOURCE = "端侧决策"

        /**
         * Termux 工具链命令白名单：这些命令在 adb shell 中通常不存在（Android 只带 toybox），
         * 故命中时一律交给 Termux 通道执行，不受执行通道偏好影响。
         */
        private val TERMUX_TOOL_COMMANDS = setOf(
            "curl", "wget", "python", "python3", "pip", "pip3", "jq", "sed", "awk",
            "grep", "tr", "base64", "openssl", "git", "node", "npm", "npx", "ffmpeg",
        )
        /** 调试轨迹（每步决策）最多保留条数：配图缩略化，双保险防内存溢出闪退 */
        private const val MAX_TRACES = 300
        /** 调试执行历史最多保留条数 */
        private const val MAX_EXECUTION_HISTORY = 400
        /** 对话历史（决策 prompt）最多保留条数：长线任务每步决策都 append，需上限防内存膨胀 */
        private const val MAX_CONVERSATION = 200

        /** 阶段切分粒度：每 [STAGE_SIZE] 步为一个阶段（文档 v2.2 5.1） */
        private const val STAGE_SIZE = 6

        /** 歧义检测 + 规划 */
        const val PLANNING_TEMPERATURE = 0.3
        /** 每步决策（正常）温度：引用 EngineRules，单一事实来源 */
        @Suppress("unused")
        const val DECISION_TEMPERATURE = EngineRules.DECISION_TEMPERATURE
        /** 失败 3 次后重规划温度：引用 EngineRules，单一事实来源 */
        @Suppress("unused")
        const val REPLAN_TEMPERATURE = EngineRules.REPLAN_TEMPERATURE

        /** 用户点「已手动处理」时发出的语义信号 */
        const val SELF_DISMISS_HINT = "[[自处理]]已手动处理完成，请继续观察当前页面并重新决策下一步"

        /** 任务内记忆事件流的保留上限（只用于界面提示，超出丢弃最早的） */
        private const val MAX_MEMORY_EVENTS = 20

        /** 记忆提炼的超时：独立于主流程，超时直接放弃，不阻塞任务收尾 */
        private const val DISTILL_TIMEOUT_MS = 12_000L

        /** MCP 技能单次调用的超时：远端服务不可达时不能让主循环干等 */
        private const val MCP_CALL_TIMEOUT_MS = 20_000L

        /** MCP 技能返回值注入 AI 上下文的最大字符数（与 shell 输出一致，防长文撑爆上下文） */
        private const val MAX_MCP_OUTPUT = 1200

        /** 连续「技能调用被拒」（未知/停用/缺参）次数上限：达到即收尾，避免 AI 反复白试 */
        private const val MAX_SKILL_ERROR_STREAK = 3
    }

    private val cloudAgent = CloudAgent(aiClient)
    private val localDecision = LocalDecisionEngine()
    private val memory = MemoryStore(appContext)
    private val anomalyEngine = AnomalyMemoryEngine(memory)
    private val profileLearner = ProfileLearner(memory)

    // ---- 意图化转译层（HPA动作执行逻辑优化文档 v2.1）：AI 输出意图，端侧按授权模式转译执行 ----
    private val capabilityManager = CapabilityManager(appContext, shizukuManager) { adbTransport?.isConnectedNow() == true }
    private val appNameResolver = AppNameResolver(appContext)
    private val intentResolver = IntentResolver()
    private val intentTranslator = IntentTranslator(
        capabilityManager, appNameResolver, intentResolver,
        // Termux 命令行通道可用性：转译层据此决定 fetch 这类"命令行取数"意图能否落地
        termuxAvailable = { termuxBridge?.isAvailable() == true },
    )
    /** decision 阶段对 hint 目标视觉定位得到的像素坐标，供转译层本次使用 */
    @Volatile
    private var lastVisualCoordinate: Pair<Int, Int>? = null

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> get() = _state.asStateFlow()

    /**
     * 本次任务内 AI 写入记忆的事件流（内存态，供 Agent 页实时插卡）。
     * 不读 DataStore：卡片要在写入瞬间出现，且撤销后立即消失。
     */
    private val _memoryEvents = MutableStateFlow<List<MemoryEvent>>(emptyList())
    val memoryEvents: StateFlow<List<MemoryEvent>> get() = _memoryEvents.asStateFlow()

    /** 同一任务只提炼一次记忆，避免重试/收尾分支重复写入 */
    @Volatile
    private var lastDistilledTaskId = -1L

    /** 本任务已加载的记忆简报缓存：每任务读一次库，逐步骤复用，不每步 IO；null 表示尚未加载 */
    @Volatile
    private var memoryBriefCache: String? = null

    /** 异常经验查询的页面指纹缓存：同一页面不重复查库 */
    @Volatile
    private var anomalyHintFingerprint: String = ""

    /** 当前页面命中的异常经验条目（未命中为 null），供该步结束时回写使用效果 */
    @Volatile
    private var currentAnomalyEntry: com.phoneagent.data.store.AnomalyMemoryEntry? = null

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
    private val _traces = MutableStateFlow<List<com.phoneagent.domain.model.StepTrace>>(emptyList())
    val traces: StateFlow<List<com.phoneagent.domain.model.StepTrace>> get() = _traces.asStateFlow()

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
    /** 连续「技能调用被拒」次数（未知技能 / 已停用 / 缺必填参数）；达到上限即收尾，避免 AI 反复白试 */
    private var skillErrorStreak = 0
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

    // ---- 长线任务工作记忆（文档 v2.2 5.2）----
    /**
     * 本次任务的任务记忆：目标 / 用户要求 / 已验证做法。
     * 旧的「最近 3 条进度摘要」是纯内存队列，轮转即丢，且长线任务的历史压缩会把更早的决策截掉，
     * 导致 AI 跑到后半程看不到最初目标；任务记忆每轮完整注入并落库，中断后记忆页仍可查。
     */
    @Volatile
    private var currentTaskMemory: TaskMemoryEntry? = null
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

    /** 本步 shell 输出的暂存：由 [finishShellResult] 写入、由 [recordStep] 消费进 StepRecord，供 Agent 页回显 */
    private var pendingShellOutput: String = ""

    /** 当前决策的流式输出（AI 正在生成的内容），用于在 Agent 页实时回显"AI 此刻在说什么" */
    private val _decisionStream = MutableStateFlow("")
    val decisionStream: StateFlow<String> = _decisionStream.asStateFlow()

    /**
     * 启动悬浮窗：仅在「设置里开启悬浮窗」且「已授权悬浮窗权限」时启动。
     * 悬浮窗是可选能力——关闭或未授权时任务照常执行，进度只在 App 内展示。
     * （App 在后台时 startForegroundService 可能受限，需兜底防崩溃）
     */
    private suspend fun maybeStartFloating() {
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
        // 环形缓冲：与 log() 一致，携带完整请求/响应大文本，必须同样截断防长任务内存膨胀
        val next = _logs.value + AgentLog(
            timestamp = System.currentTimeMillis(),
            level = AgentLog.Level.API,
            message = summary,
            detail = detail,
            taskId = currentTaskId,
            taskName = currentTaskName,
        )
        _logs.value = if (next.size > MAX_LOGS) next.takeLast(MAX_LOGS) else next
    }

    private fun addConversation(role: String, content: String, hasImage: Boolean = false) {
        // 环形缓冲：长线任务每步决策都 append 完整 prompt，需上限防内存膨胀
        val next = _conversation.value + ConversationMessage(role, content, System.currentTimeMillis(), hasImage)
        _conversation.value = if (next.size > MAX_CONVERSATION) next.takeLast(MAX_CONVERSATION) else next
    }

    fun clearDebug() {
        _logs.value = emptyList()
        _conversation.value = emptyList()
        _metrics.value = AgentMetrics()
        _executionHistory.value = emptyList()
        _traces.value = emptyList()
        // 同步清空磁盘持久化，避免重启后旧调试记录被再次回载
        com.phoneagent.data.store.DebugRecordsStore.clear(appContext)
    }

    /** 把当前的日志/轨迹/执行历史/对话持久化到磁盘（重启后 Debug 页回载查看） */
    private fun persistDebug() {
        runCatching {
            com.phoneagent.data.store.DebugRecordsStore.save(
                appContext, _logs.value, _traces.value, _executionHistory.value, _conversation.value,
            )
        }
    }

    init {
        // 启动时回载上次的调试记录：日志/轨迹（含截图缩略图）/执行历史/对话，实现持久化
        runCatching {
            val p = com.phoneagent.data.store.DebugRecordsStore.load(appContext) ?: return@runCatching
            _logs.value = p.logs
            _traces.value = p.traces
            _executionHistory.value = p.history
            _conversation.value = p.conversation
        }
    }

    /** 提交任务到队列并开始处理 */
    fun start(task: String) {
        if (task.isBlank()) return
        // 预热：若启用外挂视觉，异步预加载端侧 3B 模型，避免首次决策阻塞在模型加载
        scope.launch {
            val ext = runCatching { settings.settings.first().enableExternalVision }.getOrDefault(true)
            if (ext) com.phoneagent.device.vision.ExternalVisionProvider.loadModel(appContext)
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
            val tmpl = runCatching { com.phoneagent.data.store.TaskStore.matchTemplate(appContext, task) }.getOrNull()
            if (tmpl != null && tmpl.plan.steps.isNotEmpty()) {
                activePlan = tmpl.plan
                reusedTemplateId = tmpl.id
                _planStream.value = "【模板复用】已匹配历史模板「${tmpl.goal}」，脚本 ${tmpl.plan.steps.size} 步，免规划"
                runCatching { com.phoneagent.data.store.TaskStore.bumpExecution(appContext, tmpl.id) }
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
                        runCatching { com.phoneagent.overlay.CursorOverlayService.hide(appContext) }
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
        // 言行一致：规划时注入真实 shell 通道可用性（Shizuku 或无线 ADB），让规划与执行统一（shell 直接启动而非点击图标）
        val execContext = if (shellChannelAvailable()) {
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
            onRetry = { FloatingWindowService.resetThinking() },
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
                    com.phoneagent.domain.model.ClarificationOption(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        label = o["label"]?.jsonPrimitive?.contentOrNull ?: "",
                        description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        isDefault = o["is_default"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                    )
                } ?: emptyList()
                log(AgentLog.Level.AI, "需要澄清：$question")
                PlanPhase.Clarifying(com.phoneagent.domain.model.Clarification(question = question, options = options))
            } else {
                val planObj = root["plan"]?.jsonObject
                if (planObj != null) {
                    val stepsRaw = planObj["steps"] as? kotlinx.serialization.json.JsonArray
                    val steps = if (stepsRaw != null) com.phoneagent.domain.model.parseTaskSteps(stepsRaw) else emptyList()
                    val estimatedTime = planObj["estimated_time_seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val confidence = planObj["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val plan = com.phoneagent.domain.model.TaskPlan(steps = steps, estimatedTimeSeconds = estimatedTime, confidence = confidence)
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
    suspend fun lastCheckpoint(): com.phoneagent.data.store.Checkpoint? =
        runCatching { com.phoneagent.data.store.TaskStore.loadCheckpoint(appContext) }.getOrNull()

    /**
     * 断点续传（v2.2 5.3）：用最近一次检查点保存的任务与计划，重新启动该任务。
     * 由于计划步骤为原子动作且具备幂等保护，重复执行副作用操作会被跳过，安全可复用。
     */
    fun resumeFromCheckpoint() {
        scope.launch {
            val ck = runCatching { com.phoneagent.data.store.TaskStore.loadCheckpoint(appContext) }.getOrNull()
                ?: return@launch
            val plan = ck.planJson
                ?.let { runCatching { json.decodeFromString(com.phoneagent.domain.model.TaskPlan.serializer(), it) }.getOrNull() }
                ?: return@launch
            if (plan.steps.isEmpty()) return@launch
            pendingTask = ck.task
            activePlan = plan
            log(AgentLog.Level.INFO, "从检查点续传任务：${ck.task}（已完成 ${ck.completedSteps} 步）")
            com.phoneagent.core.notify.ActiveNotifier.notify(
                appContext, com.phoneagent.core.notify.ActiveNotifier.ID_CHECKPOINT,
                "已从断点恢复", "正在继续上次任务「${ck.task}」，已完成 ${ck.completedSteps} 步。",
            )
            if (job?.isActive != true) {
                job = scope.launch { run(ck.task, plan) }
            }
        }
    }

    /** 设置执行策略（v2.2 7，热切换一路生效） */
    suspend fun setExecutionStrategy(s: com.phoneagent.data.store.ExecutionStrategy) {
        runCatching { com.phoneagent.data.store.TaskStore.setStrategy(appContext, s) }
    }

    suspend fun currentStrategy(): com.phoneagent.data.store.ExecutionStrategy =
        runCatching { com.phoneagent.data.store.TaskStore.getStrategy(appContext) }
            .getOrDefault(com.phoneagent.data.store.ExecutionStrategy.AUTO)

    fun stop() {
        job?.cancel()
        job = null
        // 用户主动停止：任务记忆收尾为「已中断」，否则记忆页会永远显示「进行中」。
        // 没有进行中的任务时 finishTaskMemory 内部直接返回，不会凭空写库
        finishTaskMemory(TaskMemoryEntry.STATUS_ABORTED)
        com.phoneagent.device.vision.ExternalVisionProvider.unbind(appContext)
        AgentAccessibilityService.agentRunning = false
        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.IDLE)
        FloatingWindowService.stop(appContext)
        runCatching { com.phoneagent.overlay.CursorOverlayService.hide(appContext) }
        log(AgentLog.Level.WARN, "任务已停止")
    }

    /** 用户协作者：提供下一步指导 */
    fun provideUserHint(hint: String) {
        if (hint.isBlank()) return
        // 用户中途说的话就是新的任务要求，必须记进任务记忆并注入后续每一轮决策，
        // 否则长线任务跑到后面会把它忘掉（历史压缩只保留最近几轮对话）；
        // 「已手动处理」是语义信号不是要求，不记
        if (hint != SELF_DISMISS_HINT) mutateTaskMemory { it.withRequirement(hint) }
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
            // 每个任务结束后持久化调试记录（日志/轨迹/历史/对话），App 重启后 Debug 页仍可查看
            persistDebug()
        }
        AgentAccessibilityService.agentRunning = false
        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.IDLE)
    }

    /**
     * 执行任务。外层只做一件事：兜底收尾任务记忆。
     * 主循环里有多个提前 return / 异常退出点，逐个补状态容易漏；任务记忆一旦停在「进行中」，
     * 记忆页就会永远显示「进行中」，所以这里统一兜底。
     *
     * 兜底必须按 taskId 收尾：stop() 取消协程后 finally 是异步执行的，用户若立刻发起新任务，
     * 旧任务的 finally 会在新任务已经开始之后才跑到，此时 currentTaskMemory 已换成新任务的记忆，
     * 不加 taskId 判断就会把新任务误标为「未完成」（且新任务随后成功时会被终态守卫挡住写不进去）。
     */
    private suspend fun run(task: String, plan: TaskPlan? = null) {
        // 由 runInner 在创建任务记忆时把引用带出来，作为「本次任务的记忆」的唯一凭据
        var owned: TaskMemoryEntry? = null
        try {
            runInner(task, plan) { owned = it }
        } finally {
            val mem = owned
            if (mem != null) finishTaskMemory(mem.taskId, TaskMemoryEntry.STATUS_FAILED)
        }
    }

    private suspend fun runInner(
        task: String,
        plan: TaskPlan? = null,
        onMemoryCreated: (TaskMemoryEntry) -> Unit = {},
    ) {
        // 每次任务开始生成独立任务 ID，用于分任务日志查看与导出
        currentTaskId = System.currentTimeMillis()
        currentTaskName = task.take(60)
        // 每次任务开始彻底清空 AI 上下文：对话历史、失败/提前完成/无效命令计数、“上一条 shell 输出”、
        // 上一页截图快照与上一任务计划，避免上一个任务的指令/决策/命令串扰本任务（防止 AI 沿用不存在/失效的命令）
        _conversation.value = emptyList()
        consecutiveFailures = 0
        earlyDoneRejections = 0
        invalidCommandStreak = 0
        skillErrorStreak = 0
        lastShellOutput = ""
        lastSnapshot = ScreenSnapshot()
        activePlan = plan
        // 新任务重置长线工作记忆
        completedSteps = 0
        totalPlannedSteps = plan?.steps?.size ?: 0
        // 任务记忆：任务原文既是既定目标，也是第一条用户要求。
        // 先落库再执行，任务跑到一半（甚至中断）时记忆页也能看到它到底要做什么
        val taskMemory = TaskMemoryEntry(
            taskId = currentTaskId,
            taskName = task,
            goal = task,
            requirements = listOf(task),
            status = TaskMemoryEntry.STATUS_RUNNING,
        )
        currentTaskMemory = taskMemory
        onMemoryCreated(taskMemory)
        scope.launch { runCatching { memory.upsertTaskMemory(taskMemory) } }
        reusedTemplateId = null
        // 新任务清掉上一份文档预览，避免旧结果被误认为本次任务的产出
        documentEngine?.dismiss()
        // 重置主动反馈状态
        lastProgressAt = System.currentTimeMillis()
        noProgressNotified = false
        localDecisionStreak = 0
        localLoopNotified = false
        // 清空上一任务的记忆事件流（本任务产生的记忆卡片只发生在本次执行期间）
        _memoryEvents.value = emptyList()
        lastDistilledTaskId = -1L
        memoryBriefCache = null
        // 读取执行策略（v2.2 7）：记录当前模式，供任务标签区分
        val strategy = runCatching { com.phoneagent.data.store.TaskStore.getStrategy(appContext) }
            .getOrDefault(com.phoneagent.data.store.ExecutionStrategy.AUTO)
        log(AgentLog.Level.INFO, "任务开始：$task")
        log(AgentLog.Level.INFO, "执行策略=${strategy.name}")
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        currentLang = lang
        // 技能区块：可用 MCP 技能（含参数）+ 调用格式 + 已停用技能，随系统提示注入（一次任务构建一次）
        val skillsPrompt = skillPromptText()
        val messages = mutableListOf<ChatMessageDto>().apply {
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = AgentPrompts.system(lang, settingsVal.systemPrompt, settingsVal.hasVision, shellChannelAvailable(), skills = skillsPrompt)))))
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
        _state.value = AgentState(
            isRunning = true,
            task = task,
            phase = AgentState.Phase.OBSERVING,
            startedAtMillis = System.currentTimeMillis(),
        )
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
            var snapshot = observe()
            // 广告过滤（任务 7）：识别到"控件含广告信息 + 跳过/关闭/× 按钮"的广告时，
            // 直接点击关闭广告并跳过本轮 AI 决策；无按钮时剔除广告信息，保证广告不回传给 AI
            val adFilter = com.phoneagent.feature.adskip.AdContentFilter.filter(snapshot)
            if (adFilter.isAd) {
                if (adFilter.target != null) {
                    log(AgentLog.Level.INFO, adFilter.reason)
                    pushFloating("检测到广告，自动关闭", "ACTION")
                    AgentAccessibilityService.instance?.let { service ->
                        com.phoneagent.device.a11y.ActionExecutor(service).click(adFilter.target.centerX, adFilter.target.centerY)
                    }
                    delay(700)
                    continue
                } else {
                    log(AgentLog.Level.INFO, adFilter.reason)
                    snapshot = adFilter.cleanSnapshot
                }
            }
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
                com.phoneagent.device.screen.ScreenCapture.capture() else null
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
                if (hint.isBlank()) { finishTaskMemory(TaskMemoryEntry.STATUS_FAILED); stop(); return }
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
                // 端侧决策不经过 cloudDecide，必须在此补一条 trace：
                // Agent 页任务流以 trace 为骨架，漏了这一步界面上就完全看不到（像"页面自己变了"）
                recordLocalStepTrace(step, localIntent)
            } else {
                decidedIntent = cloudDecide(task, snapshot, annotated, messages, screenshot, settingsVal)
            }

            if (decidedIntent == null || decidedIntent.intent.isEmpty()) {
                log(AgentLog.Level.ERROR, "决策为空，停止")
                finishTaskMemory(TaskMemoryEntry.STATUS_FAILED)
                stop()
                return
            }
            var intent: AgentIntent = decidedIntent
            // 端侧连续多次决策 → 疑似死循环，主动反馈一次（v2.2.1 八）
            if (fromLocal) {
                localDecisionStreak++
                if (localDecisionStreak >= 5 && !localLoopNotified) {
                    localLoopNotified = true
                    log(AgentLog.Level.WARN, "连续 ${localDecisionStreak} 次端侧决策，可能死循环")
                    com.phoneagent.core.notify.ActiveNotifier.notify(
                        appContext, com.phoneagent.core.notify.ActiveNotifier.ID_LOCAL_LOOP,
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
                com.phoneagent.core.notify.ActiveNotifier.notify(
                    appContext, com.phoneagent.core.notify.ActiveNotifier.ID_NO_PROGRESS,
                    "AI 长时间没动静", "任务似乎卡住了，已延长检查时间；若仍未进展可用「已手动处理」接管。",
                )
            }
            // 3.5 技能归一化：AI 的 intent 字段可以填技能名 / 技能 id（含 MCP 技能）。
            //     内置技能 → 归一化为等价意图，继续走原转译链路（原逻辑一字不改）；
            //     MCP 技能 → 就地调用，把返回结果作为"上一步结果"回注上下文后进入下一轮，不产生设备动作；
            //     未知 / 已停用 / 缺参 → 把中文原因回注给 AI 纠正，连续多次仍不改正即收尾，避免死循环。
            val normalized = skillGateway?.normalize(intent)
            if (normalized != null) {
                when (normalized) {
                    is com.phoneagent.feature.skill.SkillCompat.Normalized.Intent -> {
                        skillErrorStreak = 0
                        intent = normalized.intent
                    }
                    is com.phoneagent.feature.skill.SkillCompat.Normalized.Mcp -> {
                        skillErrorStreak = 0
                        invokeMcpSkill(step, normalized, messages)
                        continue
                    }
                    is com.phoneagent.feature.skill.SkillCompat.Normalized.Error -> {
                        skillErrorStreak++
                        log(AgentLog.Level.WARN, "技能调用被拒绝（第 $skillErrorStreak 次）：${normalized.reason}")
                        if (skillErrorStreak >= MAX_SKILL_ERROR_STREAK) {
                            _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = normalized.reason)
                            pushFloating("技能不可用，已停止", "ERROR")
                            log(AgentLog.Level.ERROR, "连续 $skillErrorStreak 次技能调用被拒绝，停止任务：${normalized.reason}")
                            finishTaskMemory(TaskMemoryEntry.STATUS_FAILED)
                            stop()
                            return
                        }
                        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ ${normalized.reason}"))))
                        continue
                    }
                }
            }
            // 4. 转译：意图 → 内部命令（端侧按授权模式选通道/定位/算坐标，AI 无感知）。
            //    参数缺失（如 tap 没给 target、open_app 没给 app）时，端侧先向 AI 追问一次补全，再重转译，而非直接失败。
            var translation = intentTranslator.translate(intent, snapshot, lastVisualCoordinate)
            translation = fillMissingParam(translation, intent, snapshot, settingsVal)
            var action: AgentAction = AgentAction(type = "")
            var verify = com.phoneagent.engine.execution.VerifyResult(false, "", "", "")
            var isStructuralError = false
            var verified = false
            when (translation) {
                is IntentTranslator.TranslationResult.Command -> {
                    action = translation.action
                    addConversation(if (fromLocal) "local" else "assistant", intent.toString())
                    log(com.phoneagent.domain.model.AgentLog.Level.INFO, "意图转译为动作：intent=${intent.intent} → action.type=${action.type} pkg=${action.packageName} cmd=${action.command} target=${action.target}")
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
                        recordStep(step, action!!, "verified_success", "", "", verify.reason)
                        _state.value = _state.value.copy(phase = AgentState.Phase.DONE, message = action!!.summary ?: "任务完成", isRunning = false)
                        AgentAccessibilityService.agentRunning = false
                        // 任务完成后的收尾：模板处理（复用模板回写健康；全新计划经用户确认才入库）+ 检查点清空
                        runCatching { learnTemplate(task) }
                        runCatching { com.phoneagent.data.store.TaskStore.clearCheckpoint(appContext) }
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
                        // 任务记忆先收尾（目标已达成）：提炼再慢也不影响记忆页立刻变成「已完成」
                        finishTaskMemory(TaskMemoryEntry.STATUS_SUCCESS)
                        // 记忆提炼放最后：完成提示先给到用户，提炼再慢也不影响「已完成」的观感
                        // （独立调用一次模型，不写 conversation，因此不污染主决策上下文）
                        runCatching { distillMemories(task, action!!.summary ?: "任务完成") }
                        return
                    }
                    // 记忆写入：纯本地写库，不操作屏幕，不走通道/不截图/不重试
                    if (action!!.type == ActionType.REMEMBER) {
                        handleRemember(task, step, action!!)
                        continue
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
                    verify = com.phoneagent.engine.execution.VerifyResult(false, reason, "", "")
                    isStructuralError = true
                    verified = false
                }
                is IntentTranslator.TranslationResult.MissingParam -> {
                    // 绝大多数情况下已被 fillMissingParam 补全成 Command/Failed；此处仅作 sealed 穷尽兜底，按失败处理
                    val reason = translation.reason
                    log(AgentLog.Level.WARN, "意图缺参未补全：$reason")
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
                    verify = com.phoneagent.engine.execution.VerifyResult(false, reason, "", "")
                    isStructuralError = true
                    verified = false
                }
            }
            consecutiveFailures = if (verified) 0 else consecutiveFailures + 1
            // 异常经验命中后回写使用效果（成功/失败计数），供后续按命中率判断是否还值得复用
            currentAnomalyEntry?.let { entry ->
                runCatching { anomalyEngine.recordUse(entry, verified) }
                currentAnomalyEntry = null
            }
            // 步骤验证生效 → 记入长线工作记忆，供后续压缩历史后仍能感知进度
            if (verified) recordProgress(step, action)
            // 检查点持久化（v2.2 5.3）：每成功一步保存进度，中断后可查询/续传
            if (verified) runCatching {
                com.phoneagent.data.store.TaskStore.saveCheckpoint(
                    appContext, currentTaskName ?: task, activePlan,
                    completedSteps, totalPlannedSteps, currentTaskId,
                )
            }
            // 连续失败 ≥3 次才请求用户介入，避免单次动作失败频繁打断
            if (!verified && consecutiveFailures >= 3) {
                recordStep(step, action, "failed", verify.beforeFingerprint, verify.afterFingerprint, verify.reason)
                log(AgentLog.Level.ERROR, "动作 3 次未生效：${action.type}，请求用户介入")
                pushFloating("需要指导", "ERROR")
                showFloatingInteraction("guide", "需要你的协助", "动作「${action.type}」连续未能改变页面。请在窗内手动接管处理，或告诉 AI 该怎么做。")
                _needsUser.value = true
                _userHintRequest.tryEmit("动作「${action.type}」连续未能改变页面，请选择：手动接管 / 告诉 AI 怎么做。")
                val hint = awaitUserHint()
                if (hint.isBlank()) { finishTaskMemory(TaskMemoryEntry.STATUS_FAILED); stop(); return }
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
                            verify = com.phoneagent.engine.execution.VerifyResult(false, gTranslate.reason, "", "")
                            verified = false
                            log(AgentLog.Level.WARN, "引导后意图转译失败：${gTranslate.reason}")
                        }
                        is IntentTranslator.TranslationResult.MissingParam -> {
                            verify = com.phoneagent.engine.execution.VerifyResult(false, gTranslate.reason, "", "")
                            verified = false
                            log(AgentLog.Level.WARN, "引导后意图缺参：${gTranslate.reason}")
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
                val t = com.phoneagent.data.store.TaskStore.loadTemplates(appContext).firstOrNull { it.id == tid }
                com.phoneagent.data.store.TaskStore.updateTemplateHealth(appContext, tid, success = false)
                if ((t?.failedStreak ?: 0) + 1 >= 3) {
                    com.phoneagent.core.notify.ActiveNotifier.notify(
                        appContext, com.phoneagent.core.notify.ActiveNotifier.ID_TEMPLATE_FAILED,
                        "任务模板已失效", "这个任务的脚本连续失败多次，自动改走云端重新规划，建议手动检查一下。",
                    )
                }
            }
        }
        // 达到最大步数：任务没做完，任务记忆同样要收尾，不能停在「进行中」
        finishTaskMemory(TaskMemoryEntry.STATUS_FAILED)
        // 未完成的任务同样提炼一次记忆：失败路径里的经验（哪个入口走不通）往往更值得留
        runCatching { distillMemories(task, "未完成（达到最大步数限制）") }
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

    /**
     * 参数缺失补全：当转译结果因缺少必需参数 [IntentTranslator.TranslationResult.MissingParam]
     * （如 tap 没给 target、open_app 没给 app）而失败时，端侧不直接放弃，而是向 AI 发起一条补充请求，
     * 让它只补全所缺字段后返回完整意图，再重新转译。最多补 [MAX_PARAM_REFILL] 轮；
     * 补全后仍缺参则降级为 [IntentTranslator.TranslationResult.Failed]，交给上层按失败处理。
     */
    private suspend fun fillMissingParam(
        translation: IntentTranslator.TranslationResult,
        intent: AgentIntent,
        snapshot: ScreenSnapshot,
        settingsVal: AppSettings.Settings,
    ): IntentTranslator.TranslationResult {
        val missing = translation as? IntentTranslator.TranslationResult.MissingParam ?: return translation
        var cur = intent
        var result: IntentTranslator.TranslationResult = missing
        var missingField = missing.field
        var missingReason = missing.reason
        for (i in 0 until MAX_PARAM_REFILL) {
            val ask = "你上一步输出的意图缺少「$missingField」参数：$missingReason\n" +
                "请只输出补全后的完整意图 JSON（保留原 intent 及已有字段，仅补上缺失的 target 或 app 字段），不要任何解释。\n" +
                "例如 tap 需补 target:{\"by\":\"text\",\"value\":\"控件文字\"}；open_app 需补 app:\"应用名\"。\n原意图：$cur"
            val refilled = runCatching {
                aiClient.chatForAction(
                    baseUrl = settingsVal.apiBaseUrl, apiKey = settingsVal.apiKey, model = settingsVal.model,
                    messages = listOf(
                        ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = "你是手机智能体，负责把一句意图补全为合规 JSON 意图，只输出一个 JSON 对象。"))),
                        ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = ask))),
                    ),
                    screenshot = null, temperature = 0.2,
                ).getOrNull()?.action
            }.getOrNull()
            if (refilled == null) break
            cur = refilled
            result = intentTranslator.translate(refilled, snapshot, null)
            if (result is IntentTranslator.TranslationResult.Command) return result
            if (result is IntentTranslator.TranslationResult.Failed) return result
            (result as? IntentTranslator.TranslationResult.MissingParam)?.let {
                missingField = it.field
                missingReason = it.reason
            }
        }
        return IntentTranslator.TranslationResult.Failed(missingReason ?: "AI 补全参数后仍无法转译")
    }

    /**
     * 给“阻塞式”视觉调用套上看门狗。
     *
     * 云端视觉走 OkHttp 同步请求（读超时 120s），端侧 3B 走 AIDL 同步调用，
     * 直接 `withTimeoutOrNull { 阻塞调用 }` 无法打断（超时不会立即返回，仍要等阻塞调用结束），
     * 所以把它们放到独立协程里 await：超时后立刻返回 null，主循环继续往下走，
     * 被放弃的请求在后台自行结束（结果丢弃）。
     */
    private suspend fun <T> withVisionWatchdog(timeoutMs: Long, block: suspend () -> T): T? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val deferred = scope.async { runCatching { block() }.getOrNull() }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
            scope.cancel()
        }
    }

    private suspend fun cloudDecide(
        task: String,
        snapshot: ScreenSnapshot,
        annotated: com.phoneagent.engine.perception.AnnotatedPage,
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
        var localRegions: List<com.phoneagent.device.vision.DetectedControl>? = null
        var externalUsed = false
        var pageText = safeText
        var desc: String? = null
        if (screenshot != null) {
            val shot = screenshot
            // 观察已结束、视觉分析还没开始时先切状态：视觉链路（端侧 3B / 云端视觉）可能耗时数十秒，
            // 之前界面一直停在“观察屏幕”，用户会以为卡死
            _state.value = _state.value.copy(phase = AgentState.Phase.THINKING, message = "正在识别屏幕内容...")
            pushFloating("正在识别屏幕内容", "THINKING")
            // 看门狗：视觉链路全是阻塞式调用（云端 OkHttp 读超时 120s、端侧 3B 单次 20s 且同一步可能调用两次），
            // 超时即放弃视觉描述，仅用无障碍元素树继续决策，保证主循环不被打死
            val triedOnDevice3b = useOnDevice3b
            val visionDone = withVisionWatchdog(WATCHDOG_VISION_MS) {
                // 1) 优先：外挂端侧 3B 视觉 Agent 控件框选（类型 + 用途 + 归一化坐标）。
                //    混合模式下 3B 仅用于简单任务，复杂任务跳过此处直接走云端
                if (useOnDevice3b) {
                    log(AgentLog.Level.INFO, "外挂视觉 Agent 控件识别…")
                    val t0 = System.nanoTime()
                    val controls = com.phoneagent.device.vision.ExternalVisionProvider.detectControls(
                        context = appContext,
                        bitmap = shot,
                        timeoutMs = EXTERNAL_VISION_TIMEOUT,
                    )
                    recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                    if (controls.isNotEmpty()) {
                        externalUsed = true
                        localRegions = controls
                        desc = com.phoneagent.device.vision.ControlFormat.describe(controls)
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
                        screenshot = shot,
                        task = task,
                    ).getOrNull()
                    recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                }
                // 3) LOCAL，或 AUTO 云端失败/未配置 → 端侧（外挂 OCR/3B）识别兜底。
                //    主程序不再内置 OCR，本地读图统一由外挂视觉 Agent 承担（v2.2 迁移）
                //    同一步第 1 步已经找过外挂且没结果时不再重复调用（同一张图、同一服务，重试只是白等 20s）
                if (desc.isNullOrBlank() && !externalUsed && !triedOnDevice3b &&
                    (settingsVal.visionMode == "LOCAL" || settingsVal.visionMode == "AUTO")
                ) {
                    log(AgentLog.Level.INFO, "外挂视觉端侧识别（LOCAL/兜底）…")
                    val t0 = System.nanoTime()
                    val controls = com.phoneagent.device.vision.ExternalVisionProvider.detectControls(
                        context = appContext,
                        bitmap = shot,
                        timeoutMs = EXTERNAL_VISION_TIMEOUT,
                    )
                    recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                    if (controls.isNotEmpty()) {
                        externalUsed = true
                        localRegions = controls
                        desc = com.phoneagent.device.vision.ControlFormat.describe(controls)
                    } else {
                        log(AgentLog.Level.INFO, "外挂视觉不可用，本地无可识别控件")
                        desc = "（未识别到控件）"
                    }
                }
                if (!desc.isNullOrBlank()) pageText += "\n\n## 视觉描述（截图）\n$desc"
                true
            }
            if (visionDone == null) {
                log(AgentLog.Level.WARN, "视觉分析超过 ${WATCHDOG_VISION_MS / 1000}s 未返回，本步放弃视觉描述，改用无障碍元素树继续决策")
                desc = null
            }
        }
        val userText = AgentPrompts.decision(
            lang = currentLang,
            task = task,
            stepIndex = _state.value.stepCount,
            totalSteps = totalPlannedSteps.coerceAtLeast(1),
            currentStep = if (!planSteps.isNullOrBlank()) "按计划执行下一步" else "根据当前页面执行下一步",
            lastStepResult = lastStepResultText(),
            consecutiveFailures = consecutiveFailures,
            contextHint = DataSanitizer.sanitize(annotated.contextHint),
            // 记忆注入：让 AI 每一步都能看到已知偏好与既往经验，而不是只在规划阶段看得到
            memory = memoryBrief(snapshot),
        ) + planNote + "\n\n## 当前页面\n$pageText" +
            DataSanitizer.sanitize(com.phoneagent.engine.perception.PageAnnotator.knownControlsText(annotated.elements)) +
            AgentPrompts.situationalExtras(currentLang, task, termuxBridge?.isAvailable() == true) +
            taskMemoryText()
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
        val result: Result<com.phoneagent.core.ai.AiDecision>? = withTimeoutOrNull(WATCHDOG_DECIDE_MS) {
            aiClient.chatForAction(
                baseUrl = settingsVal.apiBaseUrl,
                apiKey = settingsVal.apiKey,
                model = settingsVal.model,
                // 长线任务历史压缩：只带系统消息 + 最近几轮 + 当前轮，避免上下文无限累积
                messages = chatHistory(messages, userMsg),
                screenshot = null,
                // 温度 v0.1 文档：每步决策 = 0.1；失败 3 次进入重规划 = 0.5
                temperature = decisionTemperature(),
                onRetry = {
                    FloatingWindowService.resetThinking()
                    // 重试会重放文本，流式回显同步清零，避免界面叠加两遍
                    _decisionStream.value = ""
                },
                onDelta = {
                    pushThinking(delta = it)
                    // 同步累积到 Agent 页的流式回显（悬浮窗与 App 内看到同一份内容）
                    _decisionStream.value += it
                },
            )
        }
        if (result == null) {
            log(AgentLog.Level.WARN, "单步云端决策超过 ${WATCHDOG_DECIDE_MS / 1000}s，本步改为等待，下一轮重试以防卡死")
            pushFloating("AI 决策超时，将自动重试", "THINKING")
            com.phoneagent.core.notify.ActiveNotifier.notify(
                appContext, com.phoneagent.core.notify.ActiveNotifier.ID_CLOUD_TIMEOUT,
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
        // 决策已完成：正文已落 trace（可在步骤卡的"原始数据"里回看），清空流式回显避免与下一步混淆
        _decisionStream.value = ""
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
                // 定位同样是阻塞式视觉调用（端侧 AIDL / 云端 OkHttp），套同一只看门狗：
                // 超时即放弃坐标（转译层会提示降级重定位），不让它拖死主循环
                val pos: Pair<Float, Float>? = withVisionWatchdog(EXTERNAL_VISION_TIMEOUT) {
                    when {
                    // 外挂视觉优先：端侧 3B 定位不准时退回已识别控件的本地匹配
                    externalUsed -> {
                        log(AgentLog.Level.INFO, "外挂视觉定位目标：$targetText")
                        val t0 = System.nanoTime()
                        val p = com.phoneagent.device.vision.ExternalVisionProvider.locate(
                            appContext, screenshot, targetText, EXTERNAL_VISION_TIMEOUT,
                        ) ?: com.phoneagent.device.vision.ControlFormat.locate(localRegions!!, targetText)
                        recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                        p
                    }
                    localRegions != null -> com.phoneagent.device.vision.ControlFormat.locate(localRegions, targetText)
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
     *  其余动作由执行层验证兜底，跳过二次调用以降低开销（门槛逻辑下沉 EngineRules，便于单测） */
    private fun needsReviewIntent(intent: AgentIntent, snapshot: ScreenSnapshot): Boolean =
        EngineRules.needsReviewIntent(intent, snapshot)

    /** 用独立的审核者 AI 复核执行者意图是否基于当前页面证据；
     *  审核者输出 {pass, why, freefix}，拒绝且给修正时用修正意图，否则沿用原意图（执行层兜底） */
    private suspend fun reviewAction(
        task: String,
        snapshot: ScreenSnapshot,
        annotated: com.phoneagent.engine.perception.AnnotatedPage,
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

    /**
     * 生成注入系统提示的技能区块：**只列出真正可被调用的技能**。
     *
     * 与旧实现（罗列所有服务器工具）的区别：MCP 工具必须先绑定为技能才可调用，
     * 因此这里只列注册表里 source=MCP 且已启用的技能——提示词与可执行能力严格一一对应，
     * AI 不会去调一个端侧根本不认识的工具。无技能/无服务器时返回空串，不增加负担。
     */
    private fun skillPromptText(): String {
        val gateway = skillGateway ?: return ""
        val mcpLines = gateway.enabledMcpSkills().map { gateway.mcpSkillLine(it) }
        val disabled = gateway.disabledSkills().map { it.name }
        if (mcpLines.isEmpty() && disabled.isEmpty() && !gateway.hasEnabledMcpServer()) return ""
        return AgentPrompts.skillSection(
            lang = currentLang,
            mcpLines = mcpLines,
            disabledNames = disabled,
            hasMcpServer = gateway.hasEnabledMcpServer(),
        )
    }

    /**
     * 执行一次 MCP 技能调用：就地调用远端工具，并把返回内容作为"上一步结果"注入下一轮决策上下文。
     *
     * 与 remember 同类——端侧代办、不触碰设备，因此不截图、不走执行通道、不做生效重试；
     * 本步只留档一条 StepRecord（Agent 页可见），随后进入下一轮由 AI 依据返回内容继续决策。
     */
    private suspend fun invokeMcpSkill(
        step: Int,
        mcp: com.phoneagent.feature.skill.SkillCompat.Normalized.Mcp,
        messages: MutableList<ChatMessageDto>,
    ) {
        val gateway = skillGateway ?: return
        val label = mcp.skill.name
        val target = "${mcp.target.server}/${mcp.target.tool}"
        log(AgentLog.Level.INFO, "调用 MCP 技能：$label（$target）参数=${mcp.args}")
        _state.value = _state.value.copy(phase = AgentState.Phase.ACTING, message = "调用技能：$label")
        pushFloating("调用技能：$label", "ACTING")
        // 远端服务不可达时不能让主循环干等：与单步决策同一思路，超时即按失败回注，让 AI 自己改路线
        val result = withTimeoutOrNull(MCP_CALL_TIMEOUT_MS) { gateway.invokeMcp(mcp.target, mcp.args) }
            ?: com.phoneagent.feature.mcp.McpCallResult(
                isError = true,
                content = "MCP 调用超时（${MCP_CALL_TIMEOUT_MS / 1000}s）：$target",
            )
        val text = result.content.trim().take(MAX_MCP_OUTPUT)
        val action = AgentAction(
            type = ActionType.MCP_CALL,
            reasoning = mcp.skill.description.take(60).ifBlank { label },
            reason = "MCP 技能：$label",
            confidence = 0.9,
        )
        recordStep(
            step = step,
            action = action,
            verification = if (result.isError) "unverified" else "verified_success",
            before = "",
            after = "",
            detail = if (result.isError) text.ifBlank { "MCP 调用失败" } else "MCP 返回：${text.ifBlank { "（空）" }}",
        )
        // 回注下一轮决策上下文：调用结果即"上一步结果"，AI 据此判断目标是否达成
        val injected = if (result.isError) {
            "技能「$label」调用失败：${text.ifBlank { "（无返回内容）" }}"
        } else {
            "技能「$label」返回内容：\n${text.ifBlank { "（空结果）" }}"
        }
        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = injected))))
        addConversation("assistant", injected)
        if (result.isError) {
            log(AgentLog.Level.WARN, "MCP 技能调用失败：$label → $text")
            pushFloating("技能调用失败：$label", "ERROR")
        } else {
            log(AgentLog.Level.INFO, "MCP 技能返回：${text.take(200)}")
            pushFloating("技能已返回：$label", "THINKING")
            recordProgress(step, action)
        }
        delay(200)
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

    /** 每步决策温度：失败越频繁越鼓励换思路（对应温度文档第三节，逻辑下沉 EngineRules） */
    private fun decisionTemperature(): Double = EngineRules.decisionTemperature(consecutiveFailures)

    /**
     * 判断动作是否属于"有副作用、需幂等保护"的操作（提交/发送/下单/支付/删除/发布等）。
     * 依据动作类型 + 目标 label/理由 中的触发词。
     */
    private fun isFinalSubmit(action: AgentAction, type: String): Boolean = EngineRules.isFinalSubmit(action, type)

    /** 幂等判定：当前页面是否已出现"完成成功"证据（避免重复执行副作用后再次触发） */
    private fun idempotencyDone(snapshot: ScreenSnapshot): Boolean = EngineRules.idempotencyDone(snapshot)

    private suspend fun executeWithVerify(action: AgentAction, snapshot: ScreenSnapshot): com.phoneagent.engine.execution.VerifyResult {
        val execT0 = System.nanoTime()
        // 规范化文档动作词汇
        val type = ActionType.ALIAS[action.type] ?: action.type

        // 文档写入动作：不依赖屏幕/无障碍，直接把内容落盘（结果在 Agent 页预览）
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
            return com.phoneagent.engine.execution.VerifyResult(true, "检测到页面已含完成证据（如「提交成功」），跳过重复副作用操作", "", "").also {
                recordExecMs((System.nanoTime() - execT0) / 1_000_000)
            }
        }

        val service = AgentAccessibilityService.instance ?: return com.phoneagent.engine.execution.VerifyResult(false, "无障碍服务不可用", "", "").also {
            recordExecMs((System.nanoTime() - execT0) / 1_000_000)
        }
        val executor = ActionExecutor(service)
        val verifier = VerifiedClickExecutor(executor)

        // 解析目标坐标（仅对需要坐标的动作类型校验非空）
        val target = resolveTarget(action, snapshot)
        val (x, y) = resolvePoint(action, target)

        val result = when (type) {
            ActionType.CLICK, ActionType.TAP -> {
                if (x == null || y == null) com.phoneagent.engine.execution.VerifyResult(false, "当前页面(${snapshot.packageName ?: "未知应用"})没有控件(${action.target?.value ?: "坐标"})：目标应用若未打开，先 launch 到该应用再操作，禁止点击不存在的控件", "", "")
                else verifier.executeAndVerify(snapshot, action) { executor.click(x, y).isSuccess() }
            }
            ActionType.LONG_CLICK, ActionType.LONG_PRESS -> {
                if (x == null || y == null) com.phoneagent.engine.execution.VerifyResult(false, "无法定位动作目标", "", "")
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
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, px, (py - screenHeight()).coerceIn(0, screenHeight() - 1)).isSuccess() }
            }
            ActionType.SWIPE_DOWN -> {
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, px, (py + screenHeight()).coerceIn(0, screenHeight() - 1)).isSuccess() }
            }
            ActionType.SWIPE_LEFT -> {
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, (px - screenWidth()).coerceIn(0, screenWidth() - 1), py).isSuccess() }
            }
            ActionType.SWIPE_RIGHT -> {
                val px = x ?: (screenWidth() / 2)
                val py = y ?: (screenHeight() / 2)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(px, py, (px + screenWidth()).coerceIn(0, screenWidth() - 1), py).isSuccess() }
            }
            ActionType.SCROLL, ActionType.SCROLL_TO -> verifier.executeAndVerify(snapshot, action) { executor.scroll(target, action.direction ?: action.text ?: "up").isSuccess() }
            ActionType.TYPE_TEXT -> {
                if ((x == null || y == null) && target == null) com.phoneagent.engine.execution.VerifyResult(false, "当前页面(${snapshot.packageName ?: "未知应用"})没有可输入控件(${action.target?.value ?: "坐标"})：目标应用若未打开，先 launch 到该应用，禁止在页面外凭空输入", "", "")
                else verifier.executeAndVerify(snapshot, action) { executor.typeText(action.text ?: "", target, x, y).isSuccess() }
            }
            ActionType.KEY -> handleKey(executor, action.keycode ?: "BACK")
            ActionType.LAUNCH -> {
                val targetPkg = action.packageName
                if (targetPkg.isNullOrBlank()) return com.phoneagent.engine.execution.VerifyResult(false, "launch 缺少包名", "", "").also {
                    recordExecMs((System.nanoTime() - execT0) / 1_000_000)
                }
                val launched = executor.launchApp(targetPkg).isSuccess()
                if (!launched) return com.phoneagent.engine.execution.VerifyResult(false, "启动应用失败: $targetPkg", "", "").also {
                    recordExecMs((System.nanoTime() - execT0) / 1_000_000)
                }
                // launch 动作不依赖包名检测（无障碍服务在某些版本上无法正确报告前台切换），
                // 只要启动指令发出就视为成功，让 AI 在下一轮观察新页面
                delay(800)
                com.phoneagent.engine.execution.VerifyResult(true, "已启动应用: $targetPkg", snapshot.packageName ?: "", targetPkg)
            }
            ActionType.OPEN -> executeNoVerify(executor) {
                // 优先直接深链 uri；否则按软件页面直达索引(app+page)解析直达方式
                val uri = action.uri
                if (!uri.isNullOrBlank()) {
                    executor.openUri(uri).isSuccess()
                } else {
                    val entry = com.phoneagent.domain.model.AppPageIndex.resolve(action.app, action.page)
                    when {
                        entry?.uri != null -> executor.openUri(entry.uri)
                        entry?.intentAction != null -> executor.openSettingsAction(entry.intentAction)
                        entry?.packageName != null -> executor.launchApp(entry.packageName)
                        else -> com.phoneagent.device.a11y.ActionExecutor.Result.Failure("未在软件页面索引中找到 ${action.app ?: "未知软件"} 页面${action.page ?: ""}")
                    }.isSuccess()
                }
            }
            ActionType.BACK -> executeNoVerify(executor) { executor.back().isSuccess() }
            ActionType.HOME -> executeNoVerify(executor) { executor.home().isSuccess() }
            ActionType.RECENTS -> executeNoVerify(executor) { executor.recents().isSuccess() }
            ActionType.WAIT -> { delay(action.timeoutMs ?: action.durationMs ?: 1000); com.phoneagent.engine.execution.VerifyResult(true, "等待完成", "", "") }
            ActionType.REFRESH -> com.phoneagent.engine.execution.VerifyResult(true, "刷新", "", "")
            else -> com.phoneagent.engine.execution.VerifyResult(false, "未知动作", "", "")
        }
        recordExecMs((System.nanoTime() - execT0) / 1_000_000)
        return result
    }

    /**
     * 执行文档写入动作：把 AI 产出的正文落盘，结果随后在 Agent 页任务流里预览。
     * @return 写成功的 VerifyResult；文档通道未启用时返回失败
     */
    private suspend fun executeWriteDoc(action: AgentAction): com.phoneagent.engine.execution.VerifyResult {
        val engine = documentEngine ?: return com.phoneagent.engine.execution.VerifyResult(false, "文档通道未启用", "", "")
        val content = action.text ?: return com.phoneagent.engine.execution.VerifyResult(false, "文档内容为空", "", "")
        val fileName = action.summary ?: ""
        // 直接落盘（内容已由 AI 决策产出，无需再次生成）
        val written = engine.writeDocument(content, fileName)
        return if (written.isBlank()) {
            com.phoneagent.engine.execution.VerifyResult(false, engine.error.value.ifBlank { "文档写入失败" }, "", "")
        } else {
            com.phoneagent.engine.execution.VerifyResult(true, "文档已生成，可在 Agent 页查看：$written", "", "")
        }
    }

    /**
     * 执行 shell 动作。
     * - 真实 shell 通道可用（Shizuku 或无线 ADB 已连接）：直接执行原 Shizuku 命令集
     *   （ShellCommands 解析），查询类命令输出回传 AI。
     * - 无真实 shell 通道：禁止执行 shell，把 AI 输出的友好命令翻译为等价的无障碍动作执行，
     *   保证任务在无 Shizuku/ADB 权限时仍能推进，且绝不真正调用 shell。
     */
    private suspend fun executeShellAction(action: AgentAction): com.phoneagent.engine.execution.VerifyResult {
        val cmd = action.command ?: return com.phoneagent.engine.execution.VerifyResult(false, "shell 命令为空", "", "")
        // Termux 工具链命令（curl / python / jq 等）：adb shell 里没有这些工具，
        // 按命令名判定直接交给 Termux，不受 executionChannel 偏好影响
        if (isTermuxToolCommand(cmd)) {
            val bridge = termuxBridge
            if (bridge == null || !bridge.isAvailable()) {
                return com.phoneagent.engine.execution.VerifyResult(
                    false, "该命令需要 Termux 通道（curl/python 等工具），但 Termux 未安装或未授权", "", "",
                )
            }
            val resolved = ShellCommands.resolve(cmd, screenWidth(), screenHeight())
                ?: return com.phoneagent.engine.execution.VerifyResult(false, "命令无法解析: $cmd", "", "")
            triggerCursorForShell(resolved)
            return finishShellResult(bridge.executeShell(resolved))
        }
        // 无真实 shell 通道：硬性禁止 shell，翻译为无障碍动作
        if (!shellChannelAvailable()) {
            return executeShellViaAccessibility(cmd)
        }
        val resolved = ShellCommands.resolve(cmd, screenWidth(), screenHeight())
            ?: return com.phoneagent.engine.execution.VerifyResult(false, "未知 shell 命令: $cmd", "", "")
        triggerCursorForShell(resolved)
        return runRealShell(resolved)
    }

    /**
     * shell 点击也要让用户看到光标：从解析后的完整命令里提取点击坐标，
     * 触发光标飞向该点。非点击命令（swipe 方向滑、文本处理等）静默跳过。
     */
    private suspend fun triggerCursorForShell(resolved: String) {
        val point = ShellCommands.parseTapPoint(resolved) ?: return
        com.phoneagent.overlay.CursorOverlayService.point(point.first, point.second)
    }

    /** 是否具备真实 shell 通道：按执行通道偏好判定（AUTO=无线ADB→Shizuku→Termux | ADB=仅无线ADB | SHIZUKU=仅Shizuku | TERMUX=仅Termux） */
    private suspend fun shellChannelAvailable(): Boolean {
        val channel = settings.settings.first().executionChannel
        return when (channel) {
            "ADB" -> adbTransport?.isConnected() == true
            "SHIZUKU" -> shizukuManager?.isAvailable() == true
            "TERMUX" -> termuxBridge?.isAvailable() == true
            // AUTO：无线 ADB → Shizuku → Termux（普通应用权限，仅作第三顺位兜底）
            else -> adbTransport?.isConnected() == true ||
                shizukuManager?.isAvailable() == true ||
                termuxBridge?.isAvailable() == true
        }
    }

    /**
     * 按执行通道偏好选择真实 shell 通道执行命令并回传输出。
     * AUTO 顺序：无线 ADB → Shizuku → Termux。
     * 注意 Termux 是**普通应用权限**的 Linux 环境，只适合 curl/python/文本处理等工具链命令，
     * 系统命令（am/pm/settings）会失败——该边界在提示词里对 AI 显式声明。
     */
    private suspend fun runRealShell(resolved: String): com.phoneagent.engine.execution.VerifyResult {
        val channel = settings.settings.first().executionChannel
        val adbShell: suspend (String) -> com.phoneagent.device.shell.ShizukuManager.ShellResult = { cmd ->
            val out = adbTransport?.executeShell(cmd)
            if (out == null) com.phoneagent.device.shell.ShizukuManager.ShellResult.Failure("无线 ADB 执行 shell 失败")
            else com.phoneagent.device.shell.ShizukuManager.ShellResult.Success(output = out)
        }
        val result: com.phoneagent.device.shell.ShizukuManager.ShellResult = when (channel) {
            "ADB" -> {
                if (adbTransport?.isConnected() == true) adbShell(resolved)
                else return com.phoneagent.engine.execution.VerifyResult(false, "无线 ADB 未连接，无真实 shell 通道", "", "")
            }
            "SHIZUKU" -> {
                if (shizukuManager?.isAvailable() == true) shizukuManager.executeShell(resolved)
                else return com.phoneagent.engine.execution.VerifyResult(false, "Shizuku 不可用，无真实 shell 通道", "", "")
            }
            "TERMUX" -> {
                if (termuxBridge?.isAvailable() == true) termuxBridge.executeShell(resolved)
                else return com.phoneagent.engine.execution.VerifyResult(
                    false, "Termux 不可用（未安装或未授予 RUN_COMMAND 权限）", "", "",
                )
            }
            else -> {
                if (adbTransport?.isConnected() == true) adbShell(resolved)
                else if (shizukuManager?.isAvailable() == true) shizukuManager.executeShell(resolved)
                else if (termuxBridge?.isAvailable() == true) termuxBridge.executeShell(resolved)
                else return com.phoneagent.engine.execution.VerifyResult(false, "无可用 shell 通道", "", "")
            }
        }
        return finishShellResult(result)
    }

    /**
     * 判断是否为 Termux 工具链命令（curl / python / jq 等）。
     * 取首个 token 的命令名并去掉绝对路径；`a && b` 这类组合只看首段。
     */
    private fun isTermuxToolCommand(cmd: String): Boolean {
        val body = cmd.trim().removePrefix("raw ").trim()
        if (body.isBlank()) return false
        val head = body.split(Regex("[\\s;&|]+")).firstOrNull()
            ?.substringAfterLast('/')
            ?.lowercase()
            .orEmpty()
        return head in TERMUX_TOOL_COMMANDS
    }

    /** shell 结果收口：捕获输出供 AI 决策复用，并转成执行层可验证结果 */
    private suspend fun finishShellResult(
        result: com.phoneagent.device.shell.ShizukuManager.ShellResult,
    ): com.phoneagent.engine.execution.VerifyResult = when (result) {
        is com.phoneagent.device.shell.ShizukuManager.ShellResult.Success -> {
            // 捕获输出：查询类命令回传 AI，指令类命令忽略
            lastShellOutput = result.output.trim().take(1200)
            pendingShellOutput = lastShellOutput
            delay(300)
            com.phoneagent.engine.execution.VerifyResult(true, "shell 执行成功", "", "")
        }
        is com.phoneagent.device.shell.ShizukuManager.ShellResult.Failure -> {
            lastShellOutput = ""
            // 失败原因同样回显给用户：不然页面上只看到"未生效"，不知道为什么
            pendingShellOutput = result.reason
            com.phoneagent.engine.execution.VerifyResult(false, result.reason, "", "")
        }
    }

    /**
     * Shizuku 不可用时：禁止执行 shell。将 AI 输出的友好命令（tap/lp/sw/back/key/text/launch 等）
     * 翻译为等价的无障碍动作执行；无法翻译的命令返回失败，促使 AI 改用无障碍动作重决策。
     */
    private suspend fun executeShellViaAccessibility(cmd: String): com.phoneagent.engine.execution.VerifyResult {
        val service = AgentAccessibilityService.instance
            ?: return com.phoneagent.engine.execution.VerifyResult(false, "Shizuku 不可用且无障碍服务不可用", "", "")
        val executor = ActionExecutor(service)
        val w = screenWidth()
        val h = screenHeight()
        val fail = { reason: String -> com.phoneagent.engine.execution.VerifyResult(false, reason, "", "") }

        val parsed = ShellCommands.parse(cmd)
            ?: return fail("Shizuku 不可用，命令无法转为无障碍动作: $cmd")
        val (name, args) = parsed

        // 结果包装：成功提示已用无障碍代替 shell
        fun wrap(r: ActionExecutor.Result): com.phoneagent.engine.execution.VerifyResult = when (r) {
            is ActionExecutor.Result.Success -> com.phoneagent.engine.execution.VerifyResult(true, "已用无障碍代替 shell 执行: $cmd", "", "")
            is ActionExecutor.Result.Failure -> com.phoneagent.engine.execution.VerifyResult(false, r.reason, "", "")
        }

        // 坐标类动作统一处理
        suspend fun coordOp(block: suspend (Int, Int) -> ActionExecutor.Result): com.phoneagent.engine.execution.VerifyResult {
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

    private suspend fun handleKey(executor: ActionExecutor, keycode: String): com.phoneagent.engine.execution.VerifyResult {
        return when (keycode.uppercase()) {
            "BACK" -> executeNoVerify(executor) { executor.back().isSuccess() }
            "HOME" -> executeNoVerify(executor) { executor.home().isSuccess() }
            "RECENT", "RECENTS" -> executeNoVerify(executor) { executor.recents().isSuccess() }
            "ENTER" -> com.phoneagent.engine.execution.VerifyResult(true, "回车（假定键盘已确认）", "", "")
            else -> com.phoneagent.engine.execution.VerifyResult(false, "未知按键 $keycode", "", "")
        }
    }

    private suspend fun executeNoVerify(executor: ActionExecutor, block: suspend () -> Boolean): com.phoneagent.engine.execution.VerifyResult {
        val ok = block()
        delay(500)
        return com.phoneagent.engine.execution.VerifyResult(ok, if (ok) "已执行" else "执行失败", "", "")
    }

    private fun recordMetrics(decision: com.phoneagent.core.ai.AiDecision) {
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
        decision: com.phoneagent.core.ai.AiDecision,
        visionSource: String,
        visionModel: String,
        visionDescription: String,
        screenshot: android.graphics.Bitmap?,
    ) {
        _traces.value = _traces.value + com.phoneagent.domain.model.StepTrace(
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
            // 截图入内存前缩略化（≤360px），配合 _traces 数量上限，避免多步全分辨率截图长期驻留导致 OOM 闪退
            screenshot = com.phoneagent.data.store.DebugRecordsStore.thumb(screenshot),
        )
        // 数量上限：环形保留最近 N 条轨迹，防长线/多任务场景内存无限增长
        if (_traces.value.size > MAX_TRACES) _traces.value = _traces.value.takeLast(MAX_TRACES)
    }

    /**
     * 端侧（本地）决策的步骤留档。
     *
     * 为什么必须留档：Agent 页任务流以 trace 为骨架（[AgentTimelineMapper.buildRuns]），
     * 端侧决策走的是 `localDecision.decide()` 直通分支、不经过 cloudDecide，
     * 若不在决策处补一条 trace，这一步在界面上完全不可见——用户的体感是"AI 没动，页面自己变了"。
     *
     * 与云端留档的差异：没有发给模型的上下文、没有 token 与延迟，用 [LOCAL_DECISION_SOURCE] 标注来源。
     */
    private fun recordLocalStepTrace(step: Int, intent: AgentIntent) {
        _traces.value = _traces.value + com.phoneagent.domain.model.StepTrace(
            taskId = currentTaskId,
            taskName = currentTaskName,
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
        if (_traces.value.size > MAX_TRACES) _traces.value = _traces.value.takeLast(MAX_TRACES)
    }

    // ==================== 记忆读写 ====================

    /**
     * 处理 AI 的记忆写入意图：落库 + 推入事件流（Agent 页当场插卡）。
     * 纯本地写库，不操作屏幕，因此不截图、不走执行通道、不重试。
     */
    private suspend fun handleRemember(task: String, step: Int, action: AgentAction) {
        val content = action.text.orEmpty().trim()
        if (content.isEmpty()) return
        val upsert = runCatching {
            memory.upsertAiMemory(
                content = content,
                category = action.summary.orEmpty(),
                sourceTask = task,
                source = "agent",
                confidence = action.confidence ?: 0.7,
            )
        }.getOrNull()
        log(AgentLog.Level.INFO, "写入记忆：$content")
        recordStep(step, action, "verified_success", "", "", "已写入记忆")
        if (upsert == null) return
        emitMemoryEvent(upsert, "r$currentTaskId", step)
        // 记忆变了 → 作废简报缓存，让后续步骤立刻用上刚记下的信息
        memoryBriefCache = null
        pushFloating("记住了：${content.take(20)}", "THINKING")
    }

    /** 记忆写入事件入流（内存态，供 Agent 页实时插卡；环形保留最近 N 条） */
    private fun emitMemoryEvent(upsert: AiMemoryUpsert, runKey: String, step: Int) {
        val event = MemoryEvent(
            id = upsert.entry.id,
            content = upsert.entry.content,
            category = upsert.entry.category,
            updated = upsert is AiMemoryUpsert.Updated,
            runKey = runKey,
            step = step,
            createdAt = System.currentTimeMillis(),
        )
        _memoryEvents.value = (_memoryEvents.value + event).takeLast(MAX_MEMORY_EVENTS)
    }

    /** 撤销一条刚写入的记忆：删库 + 从事件流移除（卡片随之消失） */
    suspend fun dismissMemoryEvent(id: Long) {
        runCatching { memory.deleteAiMemory(id) }
        _memoryEvents.value = _memoryEvents.value.filterNot { it.id == id }
        memoryBriefCache = null
    }

    /**
     * 本步要注入的记忆简报。
     * 画像 + AI 记忆每任务只读一次库并缓存（记忆被写入后缓存作废）；
     * 异常经验随页面变化，按页面指纹单独缓存，换页才重新查。
     */
    private suspend fun memoryBrief(snapshot: ScreenSnapshot): String {
        val base = memoryBriefCache ?: runCatching {
            MemoryBrief.build(profile = memory.loadProfile(), memories = memory.loadAiMemories())
        }.getOrDefault("").also { memoryBriefCache = it }
        val hint = anomalyHint(snapshot)
        if (hint.isBlank()) return base
        val line = "异常经验：${hint.take(60)}"
        return if (base.isBlank()) line else "$base\n$line"
    }

    /**
     * 当前页面命中的异常经验（一句话）。同一页面指纹只查一次库；
     * 命中的条目暂存到 [currentAnomalyEntry]，供该步结束时回写使用效果。
     */
    private suspend fun anomalyHint(snapshot: ScreenSnapshot): String {
        val fp = runCatching { com.phoneagent.engine.perception.PageFingerprint.computeMeaningful(snapshot) }
            .getOrDefault("")
        if (fp.isBlank()) return ""
        if (fp == anomalyHintFingerprint) return currentAnomalyEntry?.userSolution.orEmpty()
        anomalyHintFingerprint = fp
        val labels = snapshot.elements.mapNotNull { it.effectiveLabel() }.take(30)
        val hit = runCatching { anomalyEngine.findSolution(fp, labels) }.getOrNull()
        currentAnomalyEntry = hit
        return hit?.userSolution.orEmpty()
    }

    /**
     * 任务结束后的记忆提炼：独立调用一次模型，**不写 conversation、不进 chatHistory**，
     * 因此不污染主决策上下文。同一任务只跑一次；结果经内容去重合并写入，重复不会堆成多条。
     */
    private suspend fun distillMemories(task: String, outcome: String) {
        if (currentTaskId == lastDistilledTaskId) return
        lastDistilledTaskId = currentTaskId
        val settingsVal = runCatching { settings.settings.first() }.getOrNull() ?: return
        if (settingsVal.apiKey.isBlank()) return
        val stepsSummary = runCatching {
            _executionHistory.value.takeLast(12).joinToString("\n") { rec ->
                val verb = com.phoneagent.core.text.HumanTranslator.actionVerb(rec.action?.type.orEmpty())
                val target = rec.action?.target?.value
                    ?: rec.action?.text?.take(30)
                    ?: rec.action?.summary?.take(30)
                    ?: ""
                "- $verb$target"
            }.take(1500)
        }.getOrDefault("")
        val prompt = AgentPrompts.memoryDistill(currentLang, task, outcome, stepsSummary)
        val reply = withTimeoutOrNull(DISTILL_TIMEOUT_MS) {
            aiClient.chat(
                baseUrl = settingsVal.apiBaseUrl,
                apiKey = settingsVal.apiKey,
                model = settingsVal.model,
                messages = listOf(
                    ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = prompt))),
                ),
                temperature = 0.2,
            ).getOrNull()
        } ?: return
        val items = parseDistilledMemories(reply)
        if (items.isEmpty()) return
        items.forEach { item ->
            val upsert = runCatching {
                memory.upsertAiMemory(item.content, item.category, task, "distill", item.confidence)
            }.getOrNull() ?: return@forEach
            emitMemoryEvent(upsert, "r$currentTaskId", completedSteps)
        }
        memoryBriefCache = null
        log(AgentLog.Level.INFO, "记忆提炼完成：新增/更新 ${items.size} 条")
    }

    /** 提炼结果条目 */
    private data class DistilledMemory(val content: String, val category: String, val confidence: Double)

    /** 解析提炼输出 {"memories":[{content,category,confidence}]}，最多取 3 条 */
    private fun parseDistilledMemories(raw: String): List<DistilledMemory> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            val arr = root["memories"]?.jsonArray ?: return emptyList()
            arr.take(3).mapNotNull { el ->
                val obj = el.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (content.isEmpty()) null else DistilledMemory(
                    content = content,
                    category = obj["category"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.7,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun recordStep(
        step: Int,
        action: AgentAction,
        verification: String,
        before: String,
        after: String,
        detail: String = "",
    ) {
        val rec = StepRecord(
            step = step,
            action = action,
            verificationResult = verification,
            beforeFingerprint = before,
            afterFingerprint = after,
            isConfirmed = verification == "verified_success",
            // 消费本步暂存的 shell 输出（非 shell 步为空），并立即清空避免串到下一步
            shellOutput = pendingShellOutput.also { pendingShellOutput = "" },
            detail = detail,
        )
        _executionHistory.value = _executionHistory.value + rec
        // 数量上限：环形保留最近 N 条执行历史，防长线/多任务内存无限增长
        if (_executionHistory.value.size > MAX_EXECUTION_HISTORY) {
            _executionHistory.value = _executionHistory.value.takeLast(MAX_EXECUTION_HISTORY)
        }
    }

    /**
     * 每步执行完后留档最新一步：截屏 + 本地视觉模型框选 + 拼装执行说明。
     * 保留在 [StepShot] 中，新任务开始时会由 [start] 清空覆盖。
     * 框选标注独立于视觉模式，始终用本地视觉模型（OCR）执行，便于对照原图/识别图后续开发。
     */
    private suspend fun stepShotCapture(step: Int, action: AgentAction, verified: Boolean) {
        val snapshot = observe()
        val shot = com.phoneagent.device.screen.ScreenCapture.capture()
        var annotated: Bitmap? = null
        if (shot != null) {
            // 端侧控件识别已外移到外挂视觉 Agent；这里跨进程调用并画框（v2.2.1 截图标注）
            val controls = com.phoneagent.device.vision.ExternalVisionProvider.detectControls(
                context = appContext,
                bitmap = shot,
                timeoutMs = EXTERNAL_VISION_TIMEOUT,
            )
            if (controls.isNotEmpty()) annotated = com.phoneagent.device.vision.ControlFormat.drawBoxes(shot, controls)
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

    /** 每步执行成功后，把该步摘要写入任务记忆（已验证有效的做法，供后续步骤复用） */
    private fun recordProgress(step: Int, action: AgentAction) {
        completedSteps++
        lastProgressAt = System.currentTimeMillis()
        noProgressNotified = false
        val label = actionLabel(action.type)
        val reason = action.reasoning?.takeIf { it.isNotBlank() } ?: action.reason?.takeIf { it.isNotBlank() }
        val note = if (reason != null) "第${step}步: $label（$reason）" else "第${step}步: $label"
        // 写进任务记忆而不是易失的内存队列：记忆会落库并注入每轮决策，中途被压缩掉的历史也能找回来
        mutateTaskMemory { it.withMethod(note).copy(completedSteps = completedSteps) }
    }

    /** 渲染任务记忆，注入每轮决策上下文（目标与用户要求是固定部分，进度是滚动的） */
    private fun taskMemoryText(): String {
        val mem = currentTaskMemory ?: return ""
        val sb = StringBuilder()
        sb.append("\n## 任务记忆（本次任务的既定目标与用户要求，全程不可偏离）")
        sb.append("\n目标：${mem.goal}")
        if (mem.requirements.isNotEmpty()) {
            sb.append("\n用户要求：")
            mem.requirements.forEachIndexed { i, r -> sb.append("\n${i + 1}. $r") }
        }
        if (completedSteps > 0) {
            val total = if (totalPlannedSteps > 0) "/$totalPlannedSteps" else ""
            val stage = if (totalPlannedSteps > 0) {
                val totalStages = (totalPlannedSteps + STAGE_SIZE - 1) / STAGE_SIZE
                val cur = ((completedSteps + STAGE_SIZE - 1) / STAGE_SIZE).coerceIn(1, totalStages)
                " 阶段 $cur/$totalStages。"
            } else ""
            sb.append("\n## 执行进度（概览即可，勿重复执行已完成步骤）")
            sb.append("\n已完成 ${completedSteps}${total} 步。${stage}")
            if (mem.methods.isNotEmpty()) {
                sb.append("\n已验证有效的做法：")
                mem.methods.forEachIndexed { i, m -> sb.append("\n${i + 1}. $m") }
            }
        }
        return sb.toString()
    }

    /**
     * 对任务记忆做一次「读-改-写」并异步落库。
     * 加锁是因为用户指导来自悬浮窗（主线程），而进度记录在主循环线程，
     * 并发写入会丢掉其中一方刚追加的要求/做法。
     */
    private fun mutateTaskMemory(transform: (TaskMemoryEntry) -> TaskMemoryEntry) {
        val updated = synchronized(this) {
            val cur = currentTaskMemory ?: return
            transform(cur).also { currentTaskMemory = it }
        }
        scope.launch { runCatching { memory.upsertTaskMemory(updated) } }
    }

    /**
     * 任务收尾：写入终态并落库。
     * 只允许「进行中 → 终态」单向推进，重复收尾（如 stop() 之后主循环异常退出走到 finally）不会覆盖已定状态。
     */
    private fun finishTaskMemory(status: String) {
        val cur = currentTaskMemory ?: return
        if (cur.status != TaskMemoryEntry.STATUS_RUNNING) return
        mutateTaskMemory { it.withStatus(status, cur.completedSteps) }
    }

    /** 按 taskId 收尾：只动指定任务的记忆，用于 run() 的兜底 finally（见 run() 注释里的竞态说明） */
    private fun finishTaskMemory(taskId: Long, status: String) {
        val cur = currentTaskMemory ?: return
        if (cur.taskId != taskId) return
        finishTaskMemory(status)
    }

    /**
     * 任务成功后的模板处理：
     * - 复用了既有模板 → 回写健康状态（失败归零）
     * - 全新计划 → 不自动入库，由 completion 流程调用 requestConfirmSaveTemplate() 请用户确认
     */
    private suspend fun learnTemplate(task: String) {
        reusedTemplateId?.let { tid ->
            runCatching { com.phoneagent.data.store.TaskStore.updateTemplateHealth(appContext, tid, success = true) }
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
                com.phoneagent.data.store.TaskStore.upsertTemplate(
                    appContext,
                    com.phoneagent.data.store.TaskTemplate(
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

    private fun recordsIntoHistory(step: Int, action: AgentAction, verify: com.phoneagent.engine.execution.VerifyResult) {
        recordStep(step, action, if (verify.success) "verified_success" else "unverified", verify.beforeFingerprint, verify.afterFingerprint, verify.reason)
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
            hasScreenshot = com.phoneagent.device.screen.ScreenCapture.available(),
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

/**
 * 一次记忆写入事件（引擎内存态），供 Agent 页在任务流里实时插入记忆卡片。
 * [updated] 为 true 表示这条与已有记忆合并更新，而不是新增。
 */
data class MemoryEvent(
    val id: Long,
    val content: String,
    val category: String,
    val updated: Boolean,
    val runKey: String,
    val step: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

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