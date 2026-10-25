package com.phoneagent.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import com.phoneagent.device.a11y.ActionExecutor
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.a11y.NodeSelector
import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ApiProtocol
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.engine.prompt.AgentHookRegistry
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.core.ai.GlmDefaults
import com.phoneagent.core.ai.ModelAbility
import com.phoneagent.core.ai.ModelCatalogCodec
import com.phoneagent.core.ai.VisionRouting
import com.phoneagent.data.prefs.AppSettings
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.phoneagent.domain.rules.EngineRules
import com.phoneagent.domain.rules.LocalDecisionEngine
import com.phoneagent.domain.rules.SessionContext
import com.phoneagent.domain.rules.ShellCommands
import com.phoneagent.domain.rules.ShellCommands.Motion
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.ActionPolicy
import com.phoneagent.engine.execution.AppNameResolver
import com.phoneagent.engine.execution.CapabilityManager
import com.phoneagent.engine.execution.ClickRunner
import com.phoneagent.engine.execution.IntentResolver
import com.phoneagent.engine.execution.IntentTranslator
import com.phoneagent.engine.execution.ShellRules
import com.phoneagent.engine.execution.VerifiedClickExecutor
import com.phoneagent.engine.execution.VerifyRules
import com.phoneagent.engine.coding.CodingAgentBridge
import com.phoneagent.overlay.FloatingWindowService
import com.phoneagent.overlay.AgentStatusStore
import com.phoneagent.data.store.AiMemoryDedupe
import com.phoneagent.data.store.AiMemoryEntry
import com.phoneagent.data.store.AiMemoryUpsert
import com.phoneagent.data.store.AnomalyMemoryEngine
import com.phoneagent.data.store.EvolvedRule
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.data.store.ProfileLearner
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.engine.memory.PageMemoryRecorder
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
import com.phoneagent.engine.network.CloudAgent
import com.phoneagent.engine.perception.PageAnnotator
import com.phoneagent.domain.rules.effectiveLabel
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
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.UiBridge
import com.phoneagent.engine.runtime.TelemetryRecorder
import com.phoneagent.engine.runtime.TaskLifecycle
import com.phoneagent.engine.runtime.EngineLimits.ACTION_CONFIRM_TIMEOUT_MS
import com.phoneagent.engine.runtime.EngineLimits.DECISION_TEMPERATURE
import com.phoneagent.engine.runtime.EngineLimits.DISTILL_TIMEOUT_MS
import com.phoneagent.engine.runtime.EngineLimits.EXTERNAL_VISION_TIMEOUT
import com.phoneagent.engine.runtime.EngineLimits.LOCAL_DECISION_SOURCE
import com.phoneagent.engine.runtime.EngineLimits.MAX_BROWSE_MISSING_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_CLARIFY_EVENTS
import com.phoneagent.engine.runtime.EngineLimits.MAX_CONVERSATION
import com.phoneagent.engine.runtime.EngineLimits.MAX_CONVERSATION_CHATS
import com.phoneagent.engine.runtime.EngineLimits.MAX_DECISION_FAILURE_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_DEVICE_QUERY_OUTPUT
import com.phoneagent.engine.runtime.EngineLimits.MAX_DIALOG_TURNS
import com.phoneagent.engine.runtime.EngineLimits.MAX_EXECUTION_HISTORY
import com.phoneagent.engine.runtime.EngineLimits.MAX_LOGS
import com.phoneagent.engine.runtime.EngineLimits.MAX_MCP_OUTPUT
import com.phoneagent.engine.runtime.EngineLimits.MAX_MEMORY_EVENTS
import com.phoneagent.engine.runtime.EngineLimits.MAX_MODE_DENY_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_PARAM_REFILL
import com.phoneagent.engine.runtime.EngineLimits.MAX_PREVIOUS_TASKS
import com.phoneagent.engine.runtime.EngineLimits.MAX_SAY_EVENTS
import com.phoneagent.engine.runtime.EngineLimits.MAX_SAY_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_SENSITIVE_TAKEOVERS
import com.phoneagent.engine.runtime.EngineLimits.MAX_SKILL_ERROR_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_TASK_SESSIONS
import com.phoneagent.engine.runtime.EngineLimits.MAX_TRACES
import com.phoneagent.engine.runtime.EngineLimits.MCP_CALL_RETRY_TIMEOUT_MS
import com.phoneagent.engine.runtime.EngineLimits.MCP_CALL_TIMEOUT_MS
import com.phoneagent.engine.runtime.EngineLimits.NO_PROGRESS_MS
import com.phoneagent.engine.runtime.EngineLimits.PLANNING_TEMPERATURE
import com.phoneagent.engine.runtime.EngineLimits.RELOCATE_BEFORE_TYPES
import com.phoneagent.engine.runtime.EngineLimits.REPLAN_TEMPERATURE
import com.phoneagent.engine.runtime.EngineLimits.SELF_DISMISS_HINT
import com.phoneagent.engine.runtime.EngineLimits.STAGE_SIZE
import com.phoneagent.engine.runtime.EngineLimits.VISION_FALLBACK_THRESHOLD
import com.phoneagent.engine.runtime.EngineLimits.WATCHDOG_DECIDE_MS
import com.phoneagent.engine.runtime.EngineLimits.WATCHDOG_VISION_MS

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
    /**
     * 编码 agent 委托桥（Superagent 融合 Phase 4）：AppModule 注入的共享单例（Phase 5 编码页复用同一实例）。
     * 缺省时就地构造兜底；路由（router）的组装在本类内部完成，不经 AppModule 传参。
     */
    codingBridge: CodingAgentBridge? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 设备与环境事实（应用清单 / 屏幕 / 网络 / 电量 / 存储）的读取入口，见 [DeviceFacts] */
    private val deviceFacts = DeviceFacts(appContext)

    /** 引擎运行期状态仓：全部 StateFlow 与任务级 var 收口于此（重构批次 1，见 [EngineStore]） */
    private val store = EngineStore()

    private val cloudAgent = CloudAgent(aiClient)
    private val localDecision = LocalDecisionEngine(selfPackage = appContext.packageName)
    private val memory = MemoryStore(appContext)
    private val anomalyEngine = AnomalyMemoryEngine(memory)
    private val profileLearner = ProfileLearner(memory)
    /** UI 桥：悬浮窗推送 + 日志环形缓冲 + 对话追加（重构批次 2，见 [UiBridge]） */
    private val uiBridge = UiBridge(appContext, settings, store)
    /** 遥测记录器：指标 / 轨迹 / 事件流 / 会话归档 / 调试持久化（重构批次 2，见 [TelemetryRecorder]） */
    private val telemetry = TelemetryRecorder(appContext, memory, store)
    /** AI 连接工具：翻译 / 连接测试 / 模型列表 / 能力探测（重构批次 3，见 [com.phoneagent.engine.connect.AiTools]） */
    private val aiTools = com.phoneagent.engine.connect.AiTools(settings, aiClient)
    /**
     * 编码 agent 委托桥（Superagent 融合 Phase 4）：vibecoding 任务的启动/委托/停止编排。
     * 优先用 AppModule 注入的共享单例；未注入时就地组装（运行时随主包内置，管理器只需 context）。
     */
    private val codingAgentBridge = codingBridge ?: CodingAgentBridge(
        appContext, settings,
        com.phoneagent.coding.CodingRuntimeManager(appContext),
    )
    /** 停止旗标（Superagent 融合 Phase 4）：stop() 置位，编码委托/直答的挂起点轮询到即自行收尾 */
    @Volatile private var stopCodingRequested = false
    @Volatile private var chatStopRequested = false
    /** 任务生命周期：队列 / worker / 单任务协程启停（重构批次 5，见 [TaskLifecycle]）；router 为三形态分流 */
    private val lifecycle = TaskLifecycle(
        appContext, settings, scope, store, uiBridge,
        runTask = { task, plan -> run(task, plan) },
        safeReset = { taskId, message -> safeResetRuntime(taskId, message) },
        router = { task -> routeTask(task) },
    )
    /** 技能调用历史：fire-and-forget 留档每次技能调用（成功/失败/耗时/参数），供缺参推断与「上次教训」注入 */
    private val skillCallHistory = com.phoneagent.feature.skill.SkillCallHistory(appContext, scope)
    /** 技能执行器：技能提示词 / 宏接力 / MCP 调用 / 缺参推断（重构批次 6，见 [com.phoneagent.engine.skill.SkillRunner]） */
    private val skillRunner = com.phoneagent.engine.skill.SkillRunner(
        store, uiBridge, skillGateway, skillCallHistory,
        recordStep = { step, action, verification, before, after, detail ->
            recordStep(step, action, verification, before, after, detail)
        },
        recordProgress = { step, action -> recordProgress(step, action) },
    )
    /** 用户协作门：三套等待/确认信箱 + 搁置闸门（重构批次 7，见 [com.phoneagent.engine.runtime.UserGate]） */
    private val userGate = com.phoneagent.engine.runtime.UserGate(
        store, uiBridge,
        mutateTaskMemory = { transform -> mutateTaskMemory(transform) },
        actionLabel = { type -> actionLabel(type) },
    )
    /** 页面记忆采集器：任务执行自动沉淀页面热点与跳转路径（内存快照 + 异步落库，见 PageMemoryRecorder） */
    private val pageMemoryRecorder = PageMemoryRecorder(memory, scope)
    // ---- 运行期状态收口到 [EngineStore]（重构批次 1）：以下为裸名委托，读写语义与迁移前一致 ----
    private var lastSkillOutput by store::lastSkillOutput
    private var macroQueue by store::macroQueue
    private var macroArgs by store::macroArgs
    private var macroResults by store::macroResults
    private var macroName by store::macroName
    private var macroDispatched by store::macroDispatched
    private var pageMemoryHintFingerprint by store::pageMemoryHintFingerprint
    private var pageMemoryHintText by store::pageMemoryHintText

    // ---- 意图化转译层（HPA动作执行逻辑优化文档 v2.1）：AI 输出意图，端侧按授权模式转译执行 ----
    private val capabilityManager = CapabilityManager(appContext, shizukuManager) { adbTransport?.isConnectedNow() == true }
    private val appNameResolver = AppNameResolver(appContext)
    private val intentResolver = IntentResolver()
    /** 本机语音助手探测：assistant 意图据此拉起系统智能助手（系统入口优先、厂商包名兜底） */
    private val assistantDetector = com.phoneagent.device.a11y.VoiceAssistantDetector(appContext)
    private val intentTranslator = IntentTranslator(
        capabilityManager, appNameResolver, intentResolver,
        // Termux 命令行通道可用性：转译层据此决定 fetch 这类"命令行取数"意图能否落地
        termuxAvailable = { termuxBridge?.isAvailable() == true },
        // 语音助手探测 + 无障碍在线状态：assistant 意图据此选择"拉起+传话"或"仅拉起"通道
        assistantProbe = { assistantDetector.probe() },
        a11yReady = { com.phoneagent.device.a11y.AgentAccessibilityService.instance != null },
        // 动作模式：仅用于自由模式专属意图（shell / a11y）在转译层内的纵深防御自检。
        // 主门控在上面 run() 里——只有那里才能一并覆盖不走转译层的 browse_* 通道。
        actionMode = { activeActionMode },
    )
    /** 本次任务生效的动作模式（保守 / 均衡 / 自由）：每步决策都读它，转译层与门控共用同一个值（存于 [EngineStore]） */
    private var activeActionMode: ActionMode
        get() = store.activeActionMode
        set(value) { store.activeActionMode = value }
    /**
     * 内置浏览器通道：**与 [intentTranslator] 同级**的独立通道，browse_* 意图在这里落地，
     * 不进转译层策略表。网页读写走本 App 自己的 WebView（DOM 脚本），因此不受无障碍 / Shizuku /
     * 无线 ADB 是否可用影响——"手机没法自动操作"不该影响能不能上网。
     *
     * [BrowserChannel.readOnly] 用 lambda 现取模式：任务中途用户可能去开无障碍或接上无线 ADB。
     */
    private val browserChannel = com.phoneagent.feature.browser.BrowserChannel(
        readOnly = { capabilityManager.currentMode() == CapabilityManager.Mode.READONLY },
    )
    /** 上下文注入器：记忆/规则/异常经验/页面记忆/会话承接/环境事实（重构批次 6，见 [com.phoneagent.engine.context.ContextInjector]） */
    private val contextInjector = com.phoneagent.engine.context.ContextInjector(
        appContext, memory, anomalyEngine, appNameResolver, deviceFacts, store,
    )
    /** 规划流：歧义检测/澄清/计划生成/批准（重构批次 4，见 [com.phoneagent.engine.planning.PlanningFlow]） */
    private val planningFlow = com.phoneagent.engine.planning.PlanningFlow(
        appContext, settings, aiClient, memory, scope, store, uiBridge, contextInjector,
        shellChannelAvailable = { shellChannelAvailable() },
        reasoningCfg = { reasoningConfig(it) },
        emitSay = { text, runKey, step -> emitSayEvent(text, runKey, step) },
        rememberChatTurn = { q, a -> rememberChatTurn(q, a) },
        launchTask = { t, p -> launchSingleTask(t, p) },
    )
    /** 执行核心：带验证执行 / shell 通道 / 无障碍端点直调（重构批次 7b，见 [com.phoneagent.engine.execution.ExecutionCore]） */
    private val executionCore = com.phoneagent.engine.execution.ExecutionCore(
        appContext, settings, shizukuManager, adbTransport, termuxBridge, documentEngine,
        intentResolver, appNameResolver, store, uiBridge, contextInjector,
        recordExecMs = { ms -> recordExecMs(ms) },
        observe = { observe() },
    )
    /** 决策链路模型配置：主模型识图/视觉端点/思考端点（重构批次 8，见 [com.phoneagent.engine.decision.DecisionConfig]） */
    private val decisionConfig = com.phoneagent.engine.decision.DecisionConfig()
    /** 视觉结果缓存：页面未变时复用上一步视觉产物（阶段 B2，见 [com.phoneagent.engine.decision.VisionResultCache]） */
    private val visionCache = com.phoneagent.engine.decision.VisionResultCache()
    /** decision 阶段对 hint 目标视觉定位得到的像素坐标，供转译层本次使用（存于 [EngineStore]） */
    private var lastVisualCoordinate: Pair<Int, Int>?
        get() = store.lastVisualCoordinate
        set(value) { store.lastVisualCoordinate = value }

    private val _state by store::state
    val state: StateFlow<AgentState> get() = _state.asStateFlow()

    // ---- 以下 Flow / 序列号 / 计数器均存于 [EngineStore]，此处仅做裸名委托（重构批次 1）----

    private val _memoryEvents by store::memoryEvents
    val memoryEvents: StateFlow<List<MemoryEvent>> get() = _memoryEvents.asStateFlow()

    private val _sayEvents by store::sayEvents
    val sayEvents: StateFlow<List<SayEvent>> get() = _sayEvents.asStateFlow()

    private val saySeq by store::saySeq

    private val clarifySeq by store::clarifySeq

    private val _clarifyEvents by store::clarifyEvents
    val clarifyEvents: StateFlow<List<ClarifyAnswered>> get() = _clarifyEvents.asStateFlow()

    private var sayStreak: Int
        get() = store.sayStreak
        set(value) { store.sayStreak = value }

    /**
     * 任务会话归档（Agent 页侧边栏数据源），最新一次任务排在最前。
     * 任务开始时插入一条「进行中」记录，结束时按 taskId 就地收尾为终态。
     */
    private val _taskSessions by store::taskSessions
    val taskSessions: StateFlow<List<TaskSession>> get() = _taskSessions.asStateFlow()

    private var lastDistilledTaskId: Long
        get() = store.lastDistilledTaskId
        set(value) { store.lastDistilledTaskId = value }

    private var memoryRawCache by store::memoryRawCache

    private val foregroundPkgCounts by store::foregroundPkgCounts

    private val touchedRuleIds by store::touchedRuleIds

    private var sessionContextCache: String?
        get() = store.sessionContextCache
        set(value) { store.sessionContextCache = value }

    private val _conversationStart by store::conversationStart
    val conversationStart: StateFlow<Long> get() = _conversationStart.asStateFlow()

    private val conversationChats by store::conversationChats
    private val chatLock by store::chatLock

    private var anomalyHintFingerprint: String
        get() = store.anomalyHintFingerprint
        set(value) { store.anomalyHintFingerprint = value }

    private var currentAnomalyEntry: com.phoneagent.data.store.AnomalyMemoryEntry?
        get() = store.currentAnomalyEntry
        set(value) { store.currentAnomalyEntry = value }

    private val _logs by store::logs
    val logs: StateFlow<List<AgentLog>> get() = _logs.asStateFlow()

    private val _conversation by store::conversation
    val conversation: StateFlow<List<ConversationMessage>> get() = _conversation.asStateFlow()

    private val _metrics by store::metrics
    val metrics: StateFlow<AgentMetrics> get() = _metrics.asStateFlow()

    private val _executionHistory by store::executionHistory
    private val _stepShot by store::stepShot
    val executionHistory: StateFlow<List<StepRecord>> get() = _executionHistory.asStateFlow()
    val stepShot: StateFlow<StepShot> get() = _stepShot.asStateFlow()
    private val _traces by store::traces
    val traces: StateFlow<List<com.phoneagent.domain.model.StepTrace>> get() = _traces.asStateFlow()

    // ---- 规划/澄清/批准流程 ----
    private val _planPhase by store::planPhase
    val planPhase: StateFlow<PlanPhase> get() = _planPhase.asStateFlow()
    private val _planStream by store::planStream
    val planStream: StateFlow<String> get() = _planStream.asStateFlow()
    private var pendingTask by store::pendingTask
    private var activePlan by store::activePlan
    private var reusedTemplateId by store::reusedTemplateId
    private var pendingTemplateTask by store::pendingTemplateTask
    private var pendingTemplatePlan by store::pendingTemplatePlan
    private var invalidCommandStreak by store::invalidCommandStreak
    private var skillErrorStreak by store::skillErrorStreak
    private var browseMissingStreak by store::browseMissingStreak

    private var currentTaskId: Long
        get() = store.currentTaskId
        set(value) { store.currentTaskId = value }
    var reviewOverride: Boolean?
        get() = store.reviewOverride
        set(value) { store.reviewOverride = value }
    private var currentTaskName by store::currentTaskName
    private var earlyDoneRejections by store::earlyDoneRejections

    private var currentTaskMemory: com.phoneagent.data.store.TaskMemoryEntry?
        get() = store.currentTaskMemory
        set(value) { store.currentTaskMemory = value }
    private var completedSteps by store::completedSteps
    private var totalPlannedSteps by store::totalPlannedSteps

    private var lastProgressAt by store::lastProgressAt
    private var noProgressNotified by store::noProgressNotified
    private var localDecisionStreak by store::localDecisionStreak
    private var localLoopNotified by store::localLoopNotified

    // ---- 设置页工具型 AI 调用已收口到 connect/AiTools（重构批次 3），以下为转发 ----

    /** 若文本为中文则原样返回；否则调用 AI 翻译成简体中文（带缓存） */
    suspend fun translateText(text: String): String = aiTools.translateText(text)

    /** 粗略判断文本是否以中文为主（含中文字符比例 > 30%） */
    private fun isMostlyChinese(text: String): Boolean = aiTools.isMostlyChinese(text)

    // ---- 第 11 层：多任务队列（队列与 worker 已收口到 runtime/TaskLifecycle，重构批次 5） ----
    val taskQueue: StateFlow<List<String>> get() = lifecycle.taskQueue

    // ---- 第 9 层：用户协作 ----
    private val _userHintRequest by store::userHintRequest
    val userHintRequest: SharedFlow<String> get() = _userHintRequest.asSharedFlow()
    private val _needsUser by store::needsUser
    val needsUser: StateFlow<Boolean> get() = _needsUser.asStateFlow()
    private val userHintMailbox by store::userHintMailbox

    // ---- 自由模式：AI 自写命令的首次确认 ----
    private var shellApprovedForTask: Boolean?
        get() = store.shellApprovedForTask
        set(value) { store.shellApprovedForTask = value }

    private val _pendingShellCommand by store::pendingShellCommand
    val pendingShellCommand: StateFlow<String?> get() = _pendingShellCommand.asStateFlow()

    private val shellApprovalMailbox by store::shellApprovalMailbox

    // ---- 不可逆动作确认门（AgentAction.needsUserConfirmation）----
    /** 一条等待用户确认的不可逆动作摘要 */
    data class PendingActionConfirmation(
        /** 动作摘要（如「点按：确认支付」），供确认面板展示 */
        val actionSummary: String,
        /** AI 给出的动作理由/说明 */
        val reason: String,
    )

    /**
     * 待确认的不可逆动作；null = 没有正在等待的确认。
     * 与 [_needsUser]（用户指导等待）、[_pendingShellCommand]（自写命令确认）是三套互相独立的
     * 状态与信箱：确认等待若共用 needsUser 标志，一边收尾会把另一边的等待一起掐掉（互相覆盖）。
     */
    private val _pendingActionConfirmation by store::pendingActionConfirmation
    val pendingActionConfirmation: StateFlow<PendingActionConfirmation?> get() = _pendingActionConfirmation.asStateFlow()

    private val actionConfirmationMailbox by store::actionConfirmationMailbox

    /**
     * 用户对「不可逆动作确认」的答复（悬浮窗 / App 内确认面板的「允许执行 / 取消」按钮）。
     * 与 [resolveShellApproval] 同理**不受 [_needsUser] 约束**；没有等待中的确认时调用是空操作。
     */
    fun respondActionConfirmation(allow: Boolean) = userGate.respondActionConfirmation(allow)

    private var modeDenyStreak by store::modeDenyStreak

    private var taskPaused: Boolean
        get() = store.taskPaused
        set(value) { store.taskPaused = value }

    private var lastSnapshot by store::lastSnapshot
    private var currentLang by store::currentLang
    private var consecutiveFailures by store::consecutiveFailures

    private var sensitiveTakeoverStreak by store::sensitiveTakeoverStreak
    private var decisionFailureStreak by store::decisionFailureStreak
    private var lastAdClickTargetKey: String?
        get() = store.lastAdClickTargetKey
        set(value) { store.lastAdClickTargetKey = value }
    private var adAutoClickStreak: Int
        get() = store.adAutoClickStreak
        set(value) { store.adAutoClickStreak = value }
    private var lastShellOutput by store::lastShellOutput

    private var pendingShellOutput by store::pendingShellOutput

    private val _decisionStream by store::decisionStream
    val decisionStream: StateFlow<String> = _decisionStream.asStateFlow()

    /**
     * 启动悬浮窗：仅在「设置里开启悬浮窗」且「已授权悬浮窗权限」时启动。
     * 悬浮窗是可选能力——关闭或未授权时任务照常执行，进度只在 App 内展示。
     * （App 在后台时 startForegroundService 可能受限，需兜底防崩溃）
     */
    private suspend fun maybeStartFloating() = uiBridge.maybeStartFloating()

    private fun pushFloating(status: String, phase: String) = uiBridge.pushFloating(status, phase)

    private fun pushThinking(sent: String? = null, delta: String? = null) = uiBridge.pushThinking(sent, delta)

    private fun showFloatingInteraction(type: String, title: String, content: String, options: List<String>? = null) =
        uiBridge.showFloatingInteraction(type, title, content, options)

    private fun clearFloatingQuery() = uiBridge.clearFloatingQuery()

    /**
     * 上一步执行结果文本（✅/⚠️/❌），供决策 Prompt 使用。
     * 必须跟随提示词语言：英文提示词的三态段写作 verified / sent but unverified / failed，
     * 若这里仍回中文，模型得自己做一次中英映射，等于人为制造歧义。
     */
    private fun lastStepResultText(lang: PromptLang): String {
        val last = _executionHistory.value.lastOrNull() ?: return ""
        val action = last.action?.run { "${type}${reason?.let { "($it)" } ?: ""}" } ?: "-"
        val cn = lang == PromptLang.CN
        return when {
            last.isConfirmed -> if (cn) "✅ 已确认成功: $action" else "✅ verified: $action"
            last.verificationResult == "failed" -> if (cn) "❌ 未生效: $action" else "❌ failed: $action"
            else -> if (cn) "⚠️ 已发送但未确认: $action" else "⚠️ sent but unverified: $action"
        }
    }

    private fun log(level: AgentLog.Level, message: String, detail: String? = null) =
        uiBridge.log(level, message, detail)

    /**
     * 把「可能抛异常」的调用降级为 null，但显式放行协程取消。
     *
     * 主循环里所有兜底都必须用它而不是 `runCatching`：`runCatching` 会把
     * `CancellationException` 一并吞掉，`stop()` 发的取消信号就再也打不断任务，
     * 还会把「用户主动停止」误报成「执行异常」。
     *
     * 用 inline 是因为 `block` 内联后才能在主循环的挂起上下文里调用挂起函数。
     */
    private inline fun <T> tryOrNull(message: String, block: () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(AgentLog.Level.WARN, "$message：${e.message ?: e.javaClass.simpleName}")
            null
        }

    /**
     * 任务异常退出的兜底复位：把引擎从「运行中」拉回可再次启动的干净状态。
     *
     * 队列协程里的任何未捕获异常都会让 `while` 循环提前结束，若不在此复位，
     * 界面会永久停在「运行中」、无障碍服务的 Agent 标志也一直挂着。
     *
     * @param taskId 本次任务的 id；null 表示无归属信息（任务记忆尚未建立），只靠终态守卫
     * @param message 非空表示异常退出：状态置 ERROR 并把原因展示出来；null 表示正常收尾，回落 IDLE
     */
    private fun safeResetRuntime(taskId: Long?, message: String?) {
        if (!EngineRules.shouldFallbackReset(taskId, currentTaskId, _state.value.phase)) return
        AgentAccessibilityService.agentRunning = false
        _needsUser.value = false
        val s = _state.value
        _state.value = s.copy(
            isRunning = false,
            phase = if (message != null) AgentState.Phase.ERROR else AgentState.Phase.IDLE,
            message = message ?: s.message,
        )
        FloatingWindowService.stop(appContext)
        runCatching { com.phoneagent.overlay.CursorOverlayService.hide() }
        runCatching { com.phoneagent.overlay.PageMarkOverlayService.hide() }
    }

    private fun apiLog(request: String, response: String, latencyMs: Long) =
        uiBridge.apiLog(request, response, latencyMs)

    private fun addConversation(role: String, content: String, hasImage: Boolean = false) =
        uiBridge.addConversation(role, content, hasImage)

    fun clearDebug() = uiBridge.clearDebug()

    private fun persistDebug() = telemetry.persistDebug()

    init {
        // 启动时回载上次的调试记录（日志/轨迹/执行历史/对话/会话归档），实现持久化
        telemetry.loadPersistedDebug()
    }

    /** 提交任务到队列并开始处理（转发 [TaskLifecycle.start]，重构批次 5） */
    fun start(task: String) = lifecycle.start(task)

    private fun launchSingleTask(task: String, plan: TaskPlan? = null) = lifecycle.launchSingleTask(task, plan)

    // ==================== 对话边界 ====================

    /**
     * 新建对话：把对话起点推到现在，并清空只属于上一段对话的界面态与上下文。
     *
     * 清的是「本对话的上下文与实时视图」——上一段对话的任务仍留在侧边栏可回看，
     * 但它的目标/结论不再进提示词，它的执行痕迹也不再铺在实时视图里。
     *
     * 任务运行中不允许另起对话：否则实时视图与本任务互相矛盾（视图声称是新对话，
     * 却仍有任务在跑）。界面据此把入口置灰，这里再兜一层。
     */
    fun newConversation() {
        if (_state.value.isRunning) return
        _conversationStart.value = System.currentTimeMillis()
        synchronized(chatLock) { conversationChats.clear() }
        sessionContextCache = null
        _sayEvents.value = emptyList()
        _memoryEvents.value = emptyList()
        _clarifyEvents.value = emptyList()
        _conversation.value = emptyList()
        _planPhase.value = PlanPhase.Idle
        _planStream.value = ""
        pendingTask = ""
        activePlan = null
        // 上一段对话残留的「批准计划 / 需要澄清」面板必须收掉：任务已被作废，
        // 留着按钮只会点了没反应
        FloatingWindowService.interaction(null, null, null)
        log(AgentLog.Level.INFO, "已新建对话（上一段对话的上下文与视图已清空）")
    }

    /** 记下本对话内的一次纯对话问答，供下一轮承接（新建对话时清空） */
    private fun rememberChatTurn(question: String, answer: String) {
        if (question.isBlank() || answer.isBlank()) return
        synchronized(chatLock) {
            conversationChats += EngineStore.ConversationChat(
                at = System.currentTimeMillis(),
                question = question.trim().take(200),
                answer = answer.trim().take(200),
            )
            while (conversationChats.size > MAX_CONVERSATION_CHATS) conversationChats.removeAt(0)
        }
    }

    // ==================== 规划 / 澄清 / 批准（已收口到 planning/PlanningFlow，重构批次 4，以下为转发） ====================

    /** 开始规划：AI 流式思考并检测歧义。不直接执行。 */
    fun startPlanning(task: String) = planningFlow.startPlanning(task)

    /** 用户回答澄清问题后重新规划（流式） */
    fun answerClarification(option: ClarificationOption) = planningFlow.answerClarification(option)

    /** 用户批准计划后开始执行 */
    fun approvePlan() = planningFlow.approvePlan()

    fun cancelPlanning() = planningFlow.cancelPlanning()

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
            launchSingleTask(ck.task, plan)
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
        // 编码委托/直答会话的停止旗标（Superagent 融合 Phase 4）：这两个形态不走 ReAct 主循环，
        // cancelWorker 取消 job 协程同样能打断它们，但旗标能让挂起点先做状态复位（桥停止/状态中心清零）
        stopCodingRequested = true
        chatStopRequested = true
        lifecycle.cancelWorker()
        // 用户主动停止：任务记忆收尾为「已中断」，否则记忆页会永远显示「进行中」。
        // 没有进行中的任务时 finishTaskMemory 内部直接返回，不会凭空写库
        finishTaskMemory(TaskMemoryEntry.STATUS_ABORTED)
        // 停止即彻底结束：搁置标志必须一并清掉，否则下一个任务会带着上一个任务的搁置态起不来
        taskPaused = false
        // 停止时若正卡在「等待确认自写命令」，把待确认命令一并清掉：
        // 否则面板已收，协助面板仍挂着一个再也等不到答复的确认框
        _pendingShellCommand.value = null
        // 不可逆动作确认门同理：停止后不能再挂着一个等不到答复的确认面板
        _pendingActionConfirmation.value = null
        // 先把状态落到 IDLE 再走统一复位：safeResetRuntime 带 DONE 终态守卫，
        // 而「停止」的语义就是立刻回到空闲，即使任务刚好完成也要收起面板
        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.IDLE)
        safeResetRuntime(null, null)
        log(AgentLog.Level.WARN, "任务已停止")
    }

    // ---- 用户协作门已收口到 runtime/UserGate（重构批次 7），以下为转发 ----

    /** 用户搁置任务（悬浮窗上的「隐藏」按钮）：收起面板的同时把执行挂起（转发 [UserGate]，重构批次 7） */
    fun pauseTask() {
        if (store.taskPaused) return
        store.taskPaused = true
        log(AgentLog.Level.INFO, "任务已搁置：等待用户唤出悬浮窗")
    }

    /** 用户唤出悬浮窗：解除搁置，任务从当前步继续 */
    fun resumeTask() {
        if (!store.taskPaused) return
        store.taskPaused = false
        log(AgentLog.Level.INFO, "用户已唤出悬浮窗，任务继续执行")
    }

    private suspend fun awaitResume() = userGate.awaitResume()

    /** 用户协作者：提供下一步指导 */
    fun provideUserHint(hint: String) = userGate.provideUserHint(hint)

    fun dismissUser() = userGate.dismissUser()

    fun resolveShellApproval(approve: Boolean) = userGate.resolveShellApproval(approve)

    private suspend fun ensureShellApproval(action: AgentAction): Boolean = userGate.ensureShellApproval(action)

    private suspend fun awaitActionConfirmation(action: AgentAction): Boolean = userGate.awaitActionConfirmation(action)

    // processQueue 已随任务队列收口到 runtime/TaskLifecycle（重构批次 5）

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
        var cancelled = false
        try {
            // 新任务一律从「未搁置」开始：上一个任务的搁置标志不能带过来，否则任务起来就挂住
            taskPaused = false
            runInner(task, plan) { owned = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户停止 / 协程取消属于正常控制流，必须原样抛出：
            // 吞掉它会让 stop() 再也打不断任务，还会把「已停止」误报成「执行异常」
            cancelled = true
            throw e
        } catch (e: Throwable) {
            // runInner 内的未捕获异常在此收敛，不让它穿透到 processQueue ——
            // 否则队列协程会被一起带走，界面永久停在「运行中」、后续排队任务也不再执行
            log(AgentLog.Level.ERROR, "任务异常终止：${e.message}", e.stackTraceToString().take(1200))
            safeResetRuntime(owned?.taskId, "任务异常终止：${e.message ?: e.javaClass.simpleName}")
        } finally {
            val mem = owned
            if (mem != null) finishTaskMemory(mem.taskId, TaskMemoryEntry.STATUS_FAILED)
            // 归档本次任务会话（侧边栏数据源）。与上面同理按 taskId 归属，
            // 且不能有挂起调用：协程已被取消时挂起点会直接抛出，收尾就丢了
            mem?.taskId?.let { settleTaskSession(it, cancelled) }
            // 无论成功 / 失败 / 用户停止 / 异常，任务一结束就撤下点击光标。
            // 光标是「任务正在执行」的视觉反馈，任务结束后它仍停在最后一次点击的位置，
            // 屏幕上就凭空多出一个不属于任何操作的光标。
            // 归属判定同 [finishTaskMemory]：停止协程后 finally 是异步跑的，
            // 用户若立刻发起新任务，旧任务的收尾不能把新任务的光标一并撤掉
            if (mem == null || mem.taskId == currentTaskId) {
                runCatching { com.phoneagent.overlay.CursorOverlayService.hide() }
        runCatching { com.phoneagent.overlay.PageMarkOverlayService.hide() }
            }
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
        browseMissingStreak = 0
        decisionFailureStreak = 0
        lastShellOutput = ""
        lastSkillOutput = ""
        // 宏接力状态随任务复位：上个任务没跑完的宏步骤绝不串到本任务
        macroQueue = emptyList()
        macroArgs = emptyMap()
        macroResults = emptyList()
        macroName = ""
        macroDispatched = 0
        lastSnapshot = ScreenSnapshot()
        activePlan = plan
        // 新任务重置长线工作记忆
        completedSteps = 0
        totalPlannedSteps = plan?.steps?.size ?: 0
        // 页面记忆：清上一任务的内存快照与提示缓存（DataStore 里的历史记忆保留，供本任务命中参考）
        pageMemoryRecorder.reset()
        pageMemoryHintFingerprint = ""
        pageMemoryHintText = ""
        // 视觉缓存随任务复位：上一个任务的视觉产物（含任务目的性提问的描述）不带入本任务
        visionCache.invalidate()
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
        // 侧边栏：任务一开跑就先占一条「进行中」会话，用户随时能在任务列表里看到它
        startTaskSession(
            TaskSession(
                taskId = currentTaskId,
                title = task,
                plan = plan,
                status = TaskSession.Status.RUNNING,
            ),
        )
        reusedTemplateId = null
        // 新任务清掉上一份文档预览，避免旧结果被误认为本次任务的产出
        documentEngine?.dismiss()
        // 重置主动反馈状态
        lastProgressAt = System.currentTimeMillis()
        noProgressNotified = false
        localDecisionStreak = 0
        // 端侧决策引擎的连续本地计数也按任务复位：不清的话上一任务结尾的高计数
        // 会让新任务开头被强制走一次云端（防死循环计数跨任务残留）
        localDecision.reset()
        sensitiveTakeoverStreak = 0
        localLoopNotified = false
        // 清空上一任务的记忆事件流（本任务产生的记忆卡片只发生在本次执行期间）
        _memoryEvents.value = emptyList()
        // 同样清掉上一任务里 AI 说过的话，避免新任务开头飘着上一条气泡
        _sayEvents.value = emptyList()
        sayStreak = 0
        // 动作模式与本任务的确认状态一并复位：
        // 上一任务的「已批准自写命令」绝不能带到下一个任务（那等于永久授权）
        shellApprovedForTask = null
        _pendingShellCommand.value = null
        modeDenyStreak = 0
        while (shellApprovalMailbox.tryReceive().isSuccess) { /* drain */ }
        // 不可逆动作确认门同样按任务复位：上一任务的待确认动作与残留答复不能带进本任务
        _pendingActionConfirmation.value = null
        while (actionConfirmationMailbox.tryReceive().isSuccess) { /* drain */ }
        // 视觉坐标跨任务作废：上一任务留下的定位坐标对本次页面没有意义
        lastVisualCoordinate = null
        // 广告自动点击护栏按任务归零：上一个任务命中的广告目标与本次无关
        lastAdClickTargetKey = null
        adAutoClickStreak = 0
        lastDistilledTaskId = -1L
        memoryRawCache = null
        // 前台应用计数与规则命中计数同样按任务归零：它们描述的是"这一次任务"，跨任务累计会算错作用域
        foregroundPkgCounts.clear()
        touchedRuleIds.clear()
        // 环境与会话上下文按任务重算：应用数量可能变了，上一轮任务清单也变了
        deviceFacts.resetAppCache()
        sessionContextCache = null
        // 读取执行策略（v2.2 7）：记录当前模式，供任务标签区分
        val strategy = runCatching { com.phoneagent.data.store.TaskStore.getStrategy(appContext) }
            .getOrDefault(com.phoneagent.data.store.ExecutionStrategy.AUTO)
        log(AgentLog.Level.INFO, "任务开始：$task")
        log(AgentLog.Level.INFO, "执行策略=${strategy.name}")
        val settingsVal = settings.settings.first()
        // 动作模式：整个任务期间固定不动（每步读同一个值，档位不会中途漂移）
        activeActionMode = ActionMode.fromKey(settingsVal.actionMode)
        log(AgentLog.Level.INFO, "动作模式=${activeActionMode.label}（${activeActionMode.key}）")
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        currentLang = lang
        // 提示词里的"能看图"必须与实际是否真的发图一致：关掉"附送屏幕截图"后每步都不带图，
        // 此时仍告诉 AI"可视"会让它去描述图里看到的东西（实际没收到图）。
        // 判定走三态（见 VisionRouting）：AUTO 听模型库里真实探测出的能力，ON/OFF 是用户覆盖
        val effectiveHasVision = mainSeesImage(settingsVal) && settingsVal.attachScreenshot
        // 技能区块：可用 MCP 技能（含参数）+ 调用格式 + 已停用技能，随系统提示注入（一次任务构建一次）
        val skillsPrompt = skillPromptText()
        val messages = mutableListOf<ChatMessageDto>().apply {
            // task 只用于按任务类型裁剪与当下无关的大块（见 TaskKindDetector），不参与正文渲染
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = AgentPrompts.system(lang, settingsVal.systemPrompt, effectiveHasVision, shellChannelAvailable(), skills = skillsPrompt, actionMode = activeActionMode, task = task)))))
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = AgentPrompts.capabilitiesLang(lang, effectiveHasVision)))))
            // 执行通道与坐标对 AI 透明：端侧自动选择执行方式，AI 无需指定通道或坐标
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = when (lang) {
                PromptLang.CN -> "执行通道（无障碍/Shizuku）由端侧自动选择，无需你指定。打开应用用 open_app（写应用名即可）；目标定位与坐标计算全部由端侧完成，你不输出像素坐标。"
                PromptLang.EN -> "Execution channel (accessibility/Shizuku) is auto-selected on-device; you never specify it. Open apps with open_app (write the app name); target location and coordinate computing are all handled on-device — you never output pixel coordinates."
            }))))
        }
        // Hook 管线：任务级系统提示扩展（记忆/环境摘要等增强件在此注入；单个 hook 失败不影响其余）
        AgentHookRegistry.runSystemHooks(
            AgentHookRegistry.HookContext(task, lang, step = 0, actionModeKey = activeActionMode.key),
        ).forEach { messages.add(it) }

        val maxSteps = settingsVal.maxSteps
        var step = 0
        // 无障碍服务在本任务中是否观测到过在线：用于识别"任务中途掉线"（vivo 安全守护清除授权等）。
        // 服务一旦中途掉线，读屏与操作通道全断，继续跑只会变成"读不到→反复重启应用"的盲循环（真机 BUG 22）
        var a11ySeenAlive = false
        // 决策循环熔断（BUG 24）：最近一次"成功"动作的签名与连续同签名计数，见主循环内注释
        var lastVerifiedActionSig = ""
        var verifiedSameSigStreak = 0
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
            // 用户搁置：先挂起，唤出后才进入本轮。放在 step++ 之前，
            // 搁置期间不会白耗步数（否则搁久了会把规划步数耗尽，任务直接收尾）
            awaitResume()
            if (!coroutineContext.isActive) return
            step++
            _state.value = _state.value.copy(
                phase = AgentState.Phase.OBSERVING,
                stepCount = step,
                hasAccessibility = AgentAccessibilityService.isServiceEnabled(appContext),
            )
            pushFloating("正在观察屏幕", "OBSERVING")

            // 1. 观察 + 标注
            var snapshot = observe()
            // 无障碍服务任务中途掉线检测（真机 BUG 22）：先观测到在线、随后服务对象消失，
            // 说明授权被系统清除/服务被回收。此时必须暂停并引导用户重新授权，
            // 而不是继续消耗步数盲跑——那段盲循环每一步都在浪费 Token 与用户耐心。
            if (AgentAccessibilityService.instance != null) {
                a11ySeenAlive = true
            } else if (a11ySeenAlive) {
                log(AgentLog.Level.ERROR, "无障碍服务已断开（任务执行中途），暂停任务等待用户处理")
                pushFloating("无障碍服务已断开", "ERROR")
                val dropReason = "无障碍服务已断开，AI 读不到页面也无法操作。请到 系统设置→无障碍→已下载的服务 " +
                    "重新开启「Happy Agent 无障碍服务」，开启后点「已手动处理」继续任务。"
                showFloatingInteraction("guide", "无障碍服务已断开", dropReason)
                _needsUser.value = true
                _state.value = _state.value.copy(message = dropReason)
                val hint = awaitUserHint()
                if (hint.isBlank()) {
                    finishTaskMemory(TaskMemoryEntry.STATUS_FAILED); stop(); return
                }
                // 用户已处理：重新观测在线状态，避免一次瞬时未重连就再次误触暂停
                a11ySeenAlive = AgentAccessibilityService.instance != null
            }
            // 广告过滤（任务 7）：识别到"控件含广告信息 + 跳过/关闭/× 按钮"的广告时，
            // 直接点击关闭广告并跳过本轮 AI 决策；无按钮时剔除广告信息，保证广告不回传给 AI
            val adFilter = com.phoneagent.feature.adskip.AdContentFilter.filter(snapshot)
            if (adFilter.isAd) {
                if (adFilter.target != null) {
                    // 护栏：同一关闭目标连续命中 ≥2 次，说明自动点击无效（点了广告还在）。
                    // 此时停止自动点击，该轮也不再剔除广告内容，把真实页面交给 AI 重新决策
                    val targetKey = "${adFilter.target.centerX / 20}:${adFilter.target.centerY / 20}"
                    if (targetKey == lastAdClickTargetKey) adAutoClickStreak++ else {
                        lastAdClickTargetKey = targetKey
                        adAutoClickStreak = 1
                    }
                    if (adAutoClickStreak >= 2) {
                        log(AgentLog.Level.WARN, "广告自动关闭连续命中，已停止自动点击")
                    } else {
                        log(AgentLog.Level.INFO, adFilter.reason)
                        // 关闭广告属于「执行」动作，阶段必须写 ACTING：写成 ACTION 落到 FloatingUi.phaseColor
                        // 的兜底琥珀色上，跑马灯会闪出一个设置页阶段清单里根本没列的颜色
                        pushFloating("检测到广告，自动关闭", "ACTING")
                        AgentAccessibilityService.instance?.let { service ->
                            com.phoneagent.device.a11y.ActionExecutor(service).click(adFilter.target.centerX, adFilter.target.centerY)
                        }
                        delay(700)
                        continue
                    }
                } else {
                    log(AgentLog.Level.INFO, adFilter.reason)
                    snapshot = adFilter.cleanSnapshot
                }
            }
            lastSnapshot = snapshot
            val annotated = PageAnnotator.annotate(snapshot)
            // 语义 id 回填：annotate() 把 semanticId 标在它**新建的元素列表**（annotated.elements）里，
            // 原始 snapshot 的元素里 semanticId 全为 null；不回填的话，端侧决策的 by="id" 语义定位
            // 与转译层的语义控件命中（send/confirm/close/delete 等）就永远失败
            val semanticIndex = annotated.elements.mapNotNull { e -> e.semanticId?.let { e.index to it } }.toMap()
            val semanticSnapshot = snapshot.copy(
                elements = snapshot.elements.map { it.copy(semanticId = semanticIndex[it.index]) },
            )
            // 无障碍读不到控件（元素树稀疏，如游戏/WebView/in-app 渲染界面）时，即使未开启截图开关也自动截图，
            // 交给视觉模型（glm-4.6v-flash）描述+坐标定位，弥补元素树缺失
            val treeSparse = snapshot.elements.size <= VISION_FALLBACK_THRESHOLD
            // 混合路由：启用端侧视觉且（未开混合，或简单任务=元素树可读）→ 走端侧 3B 需要截图
            val needExternal3b = settingsVal.enableExternalVision &&
                (!settingsVal.smartVisionRoute || !treeSparse)
            // 截图链路（MediaProjection / 无障碍）在权限被回收或服务断开时会直接抛异常；
            // 截图只是增强信息，失败即降级为「本轮无截图」，用无障碍元素树继续决策。
            // 元素树稀疏/为空时无条件截图：这时元素树已经不足以支撑决策，视觉是唯一的信息来源，
            // 不能再被「截图总开关」「视觉总开关」卡住（否则页面在 AI 眼里完全不可见）
            val screenshot = if (settingsVal.attachScreenshot || needExternal3b || treeSparse)
                tryOrNull("截图失败") { com.phoneagent.device.screen.ScreenCapture.capture() } else null
            if (snapshot.elements.isEmpty()) {
                // 元素树为空的直接证据：区分"系统没给节点"与"被筛选条件挡掉"
                log(AgentLog.Level.WARN, "元素树为空：${AgentAccessibilityService.instance?.lastTreeStats ?: "无统计"}")
            }
            log(AgentLog.Level.INFO, "第 $step 轮观察：${snapshot.elements.size} 个元素，页面类型=${annotated.pageType}" +
                if (treeSparse) "（元素稀疏，自动启用视觉模型）" else "")

            // 2. 安全：敏感页只读
            if (SensitivePageDetector.isSensitive(snapshot)) {
                sensitiveTakeoverStreak++
                if (sensitiveTakeoverStreak > MAX_SENSITIVE_TAKEOVERS) {
                    // 连续多轮仍是敏感页：要么用户已无意继续，要么页面判定过于敏感。
                    // 明确失败收尾，而不是无限循环弹接管（用户每次都得手动点，体验死锁）
                    log(AgentLog.Level.WARN, "敏感页接管连续 $sensitiveTakeoverStreak 次，结束任务")
                    finishTaskMemory(TaskMemoryEntry.STATUS_FAILED); stop(); return
                }
                log(AgentLog.Level.WARN, "检测到敏感页面，拒绝执行动作，需用户介入")
                pushFloating("敏感页面", "ERROR")
                showFloatingInteraction("guide", "敏感页面保护", "检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后，在窗内告诉 AI 继续。")
                _needsUser.value = true
                val sensitiveReason = "检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后输入提示继续。"
                _userHintRequest.tryEmit(sensitiveReason)
                // 界面「需要协助」的原因取的是 agentState.message（userHintRequest 目前无订阅者），
                // 不同步写入的话用户只会看到一个没有任何说明的协作面板
                _state.value = _state.value.copy(message = sensitiveReason)
                val hint = awaitUserHint()
                if (hint.isBlank()) { finishTaskMemory(TaskMemoryEntry.STATUS_FAILED); stop(); return }
                continue
            }

            // 2.5 页面记忆：命中已记忆页面 → 屏幕淡色圆环标记 + 决策提示注入素材；新页面自动入库。
            //     放在敏感页判定之后：敏感页绝不写入页面记忆（Recorder 内部还有双保险）。
            //     未开启开关时什么都不做（任务收尾的 PageMarkOverlayService.hide 会兜底撤下标记）
            var pageMemoryHint = ""
            if (settingsVal.pageMemoryEnabled) {
                val hit = runCatching { pageMemoryRecorder.onPageObserved(snapshot, annotated) }.getOrNull()
                pageMemoryHint = runCatching { pageMemoryHintFor(snapshot, hit) }.getOrDefault("")
                runCatching { com.phoneagent.overlay.PageMarkOverlayService.update(appContext, hit?.hotspots.orEmpty()) }
            }

            // 2.9 宏接力：宏还有未派发的步骤 → 本轮跳过 AI 决策，端侧直接把下一步注入为当前意图。
            //     派发前先收割上一步的执行结果（供 {{stepN}} 引用）；上一步明确失败即终止宏（失败即停），
            //     终止说明回注消息，宏状态清空，下一轮交还 AI 决策循环。
            //     注入的意图照常走归一化 → ActionPolicy 门控 → 转译层全链路，不绕过任何安全机制。
            var macroIntent: AgentIntent? = null
            // 宏活跃判定：还有未派发步骤，或最后一步已派发但收尾消息还没回注（macroName 尚未清空）。
            // 只判 macroQueue 会在最后一步派发后漏掉收尾轮，AI 就看不到「宏已执行完毕」的说明
            if (macroQueue.isNotEmpty() || macroName.isNotBlank()) {
                macroIntent = nextMacroIntent(messages)
                if (macroIntent == null) continue // 宏已收尾/终止（说明已回注），回到正常决策循环
            }

            // 3. 端侧决策优先（输出的都是"意图"）
            // 跨步清理图片编码缓存：上一轮的截图早已回收，留着引用既不省事也占内存
            aiClient.clearImageCache()
            // 端侧决策异常（规则引擎内部越界等）不能让整个任务崩掉：降级为空，直接走云端决策。
            // 用回填过 semanticId 的快照决策：端侧 tapIntent 的 by="id" 定位依赖 semanticId
            // 宏接力轮不需要任何决策（端侧已定好做什么），local/cloud 决策双双跳过
            // 死循环强制复核（阶段 B3）：连续端侧决策达到阈值时跳过本轮端侧判定，强制走一次云端——
            // 原先这里只发通知不干预，AI 仍会继续端侧空转；云端复核一轮后 streak 清零，端侧节流自然恢复
            val forceCloudReview = com.phoneagent.engine.decision.DecisionPolicy.shouldForceCloudReview(localDecisionStreak)
            val localIntent = if (macroIntent == null && !forceCloudReview) {
                tryOrNull("端侧决策失败") { localDecision.decide(semanticSnapshot) }
            } else {
                if (forceCloudReview) log(AgentLog.Level.WARN, "连续 ${localDecisionStreak} 次端侧决策，本步强制云端复核")
                null
            }
            var decidedIntent: AgentIntent? = null
            var fromLocal = false
            if (macroIntent != null) {
                decidedIntent = macroIntent
                log(AgentLog.Level.INFO, "宏「$macroName」第 $macroDispatched 步注入：${macroIntent.intent} 参数=${macroIntent.args}")
            } else if (localIntent != null) {
                decidedIntent = localIntent
                fromLocal = true
                log(AgentLog.Level.AI, "端侧决策意图：${localIntent.intent}（${localIntent.reasoning ?: localIntent.reason}）")
                // 端侧决策不经过 cloudDecide，必须在此补一条 trace：
                // Agent 页任务流以 trace 为骨架，漏了这一步界面上就完全看不到（像"页面自己变了"）
                recordLocalStepTrace(step, localIntent)
            } else {
                // cloudDecide 内部含视觉链路与网络请求，异常面很宽：抛异常时降级为「等待重试」，
                // 而不是让异常穿透整个任务（既有的「正常返回 null → 决策为空停止」语义保持不变）
                var decisionThrew = false
                val decided = try {
                    cloudDecide(task, snapshot, annotated, messages, screenshot, settingsVal, pageMemoryHint)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    decisionThrew = true
                    log(AgentLog.Level.WARN, "云端决策异常：${e.message ?: e.javaClass.simpleName}")
                    null
                }
                // 护栏只统计本次新增的异常降级路径：连续异常说明链路真的坏了（如 API 地址错误、
                // 网络完全不可达），继续空转只会无限等待，需要收尾并告知用户
                decisionFailureStreak = if (decisionThrew) decisionFailureStreak + 1 else 0
                if (com.phoneagent.engine.decision.DecisionPolicy.isDecisionFailureFatal(decisionFailureStreak)) {
                    log(AgentLog.Level.ERROR, "决策链路连续 $decisionFailureStreak 次异常，停止任务")
                    _state.value = _state.value.copy(
                        phase = AgentState.Phase.ERROR,
                        message = "AI 决策链路持续异常，已停止任务",
                    )
                    pushFloating("决策链路异常，已停止", "ERROR")
                    finishTaskMemory(TaskMemoryEntry.STATUS_FAILED)
                    stop()
                    return
                }
                decidedIntent = if (decisionThrew) {
                    // 决策链路异常（网络抖动/API 抖动）：本步**不计步**——异常等待原先照常 step++，
                    // 连续抖动会把步数预算烧光、任务还没真正跑就到 maxSteps 收尾。
                    // 这里回退步数并稍等重试，只有连续异常达到护栏上限才收尾（见上方 decisionFailureStreak）
                    step--
                    _state.value = _state.value.copy(stepCount = step.coerceAtLeast(0))
                    delay(1500)
                    continue
                } else decided
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
            // 技能归一化异常（技能表解析/参数校验内部出错）不影响主流程：视为「无技能命中」继续转译
            val normalized = tryOrNull("技能归一化失败") { skillGateway?.normalize(intent) }
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
                    is com.phoneagent.feature.skill.SkillCompat.Normalized.Vision -> {
                        skillErrorStreak = 0
                        invokeVisionAsk(step, normalized, messages, screenshot, settingsVal)
                        continue
                    }
                    is com.phoneagent.feature.skill.SkillCompat.Normalized.Macro -> {
                        // 宏技能：先整宏预检（步骤技能存在且启用、意图类步骤过当前动作模式门控），
                        // 任一步被拒整宏不启动；预检通过后进入接力模式，由端侧按顺序派发每一步
                        // （每步照常走归一化 → ActionPolicy 门控 → 转译层全链路，不绕过任何安全机制）
                        skillErrorStreak = 0
                        val rejectReason = tryOrNull("宏预检失败") { macroPrecheckReason(normalized.skill) }
                        if (rejectReason != null) {
                            skillErrorStreak++
                            log(AgentLog.Level.WARN, "宏启动被拒：$rejectReason")
                            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ $rejectReason"))))
                            continue
                        }
                        macroName = normalized.skill.name
                        macroArgs = normalized.args
                        macroQueue = normalized.skill.steps
                        macroResults = emptyList()
                        macroDispatched = 0
                        log(AgentLog.Level.INFO, "宏「$macroName」预检通过，共 ${normalized.skill.steps.size} 步，开始接力执行")
                        pushFloating("执行宏：$macroName（${normalized.skill.steps.size} 步）", "ACTING")
                        continue
                    }
                    is com.phoneagent.feature.skill.SkillCompat.Normalized.Error -> {
                        // 缺参自动推断：因缺必填参数被拒时，端侧先从（历史成功参数/上一步结果/任务原文/页面文本）
                        // 推断补全并重归一化，命中即免去一轮向 AI 的补充追问；推断不出才落入原失败链路计数
                        val recovered = tryOrNull("缺参推断失败") { inferMissingArgs(intent, semanticSnapshot) }
                            ?.takeIf { it !is com.phoneagent.feature.skill.SkillCompat.Normalized.Error }
                        when (recovered) {
                            is com.phoneagent.feature.skill.SkillCompat.Normalized.Mcp -> {
                                skillErrorStreak = 0
                                invokeMcpSkill(step, recovered, messages)
                                continue
                            }
                            is com.phoneagent.feature.skill.SkillCompat.Normalized.Vision -> {
                                skillErrorStreak = 0
                                invokeVisionAsk(step, recovered, messages, screenshot, settingsVal)
                                continue
                            }
                            is com.phoneagent.feature.skill.SkillCompat.Normalized.Intent -> {
                                skillErrorStreak = 0
                                intent = recovered.intent
                            }
                            else -> {
                                skillErrorStreak++
                                // 宏步骤归一化被拒（如运行期技能被停用）= 失败即停：终止宏，交还 AI 决策循环
                                if (macroName.isNotBlank()) {
                                    log(AgentLog.Level.WARN, "宏「$macroName」步骤被归一化拒绝，宏终止")
                                    macroQueue = emptyList()
                                    macroName = ""
                                }
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
                }
            }
            // 3.6 动作模式门控：三档权限（保守 / 均衡 / 自由）的**唯一检查点**。
            //     位置是刻意的——技能归一化之后（此时 intent 已是标准意图名），浏览器分流之前
            //     （browse_* 走独立通道、不进转译层，只有卡在这里才能把它一并覆盖）。
            //     转译层内部对 shell/a11y 另有自检，那只是纵深防御，不替代此处。
            val modeVerdict = ActionPolicy.allows(activeActionMode, intent.intent)
            if (modeVerdict is ActionPolicy.Verdict.Denied) {
                modeDenyStreak++
                log(AgentLog.Level.WARN, "动作模式拒绝意图（第 $modeDenyStreak 次）：${intent.intent}｜${modeVerdict.reason}")
                if (modeDenyStreak >= MAX_MODE_DENY_STREAK) {
                    val stopReason = "连续 $modeDenyStreak 次尝试被「${activeActionMode.label}」动作模式拒绝，已停止任务。" +
                        "若这个任务确实需要这些动作，请在「Agent 页输入栏下方的动作模式」里放宽档位后重试。"
                    _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = stopReason)
                    pushFloating("动作模式不允许，已停止", "ERROR")
                    log(AgentLog.Level.ERROR, stopReason)
                    finishTaskMemory(TaskMemoryEntry.STATUS_FAILED)
                    stop()
                    return
                }
                messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ ${modeVerdict.reason}"))))
                // 宏步骤被动作模式拒绝 = 失败即停：终止宏并交还 AI 决策循环（预检已挡掉绝大多数情况，
                // 这里兜住"任务中途用户切了档位"的运行期变化）
                if (macroName.isNotBlank()) {
                    log(AgentLog.Level.WARN, "宏「$macroName」步骤被动作模式拒绝，宏终止")
                    macroQueue = emptyList()
                    macroName = ""
                }
                continue
            }
            modeDenyStreak = 0
            // 3.7 浏览器通道分流：browse_* 是**与设备能力无关**的独立通道，先于转译层落地。
            //     它不走 IntentTranslator 的策略表（不受无障碍/Shizuku/无线 ADB 影响），
            //     也不产生 AgentAction：网页读写由本 App 的 WebView 用 DOM 脚本完成，
            //     结果作为"上一步结果"回注下一轮决策。
            // 页面记忆：此刻意图已定稿（技能归一化之后），记为待记路径边的动作
            pageMemoryRecorder.onIntent(intent)
            if (browserChannel.handles(intent.intent)) {
                val (browseAction, browseVerify) = handleBrowseIntent(step, intent, messages)
                log(
                    AgentLog.Level.INFO,
                    "执行动作：${browseAction.type}（${if (browseVerify.success) "已验证生效" else "待确认"}）",
                )
                recordsIntoHistory(step, browseAction, browseVerify)
                pageMemoryRecorder.onStepResult(browseVerify.success)
                stepShotCapture(step, browseAction, browseVerify.success)
                continue
            }
            // 4. 转译：意图 → 内部命令（端侧按授权模式选通道/定位/算坐标，AI 无感知）。
            //    参数缺失（如 tap 没给 target、open_app 没给 app）时，端侧先向 AI 追问一次补全，再重转译，而非直接失败。
            // 转译异常（定位/坐标换算内部出错）按转译失败处理：交给既有失败链路回注 AI 纠正，而不是崩掉任务
            // 视觉坐标"消费一次"：上一步 see/视觉定位产出的坐标只供本次转译使用，取走即清空，
            // 不再在每轮开头无条件清零（那样 see 拿到的坐标下一步必被清掉，等于白定位）
            val carry = lastVisualCoordinate
            lastVisualCoordinate = null
            var translation = tryOrNull("意图转译失败") {
                intentTranslator.translate(intent, semanticSnapshot, carry)
            } ?: IntentTranslator.TranslationResult.Failed("意图转译异常，请重试")
            translation = tryOrNull("参数补全失败") { fillMissingParam(translation, intent, semanticSnapshot, settingsVal) }
                ?: translation
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
                    // 提前完成拦截门槛：有计划时 = 规划步数向上取整的 60%（不再叠加"至少 3 步"的下限，
                    // 否则两三步的小任务会被强行拦截到凑步数）；无计划时保持 3 步的经验值
                    val minDoneThreshold = if (activePlan != null && activePlan!!.steps.isNotEmpty())
                        Math.ceil(activePlan!!.steps.size * 0.6).toInt()
                    else 3
                    // 证据优先：当前页面已出现明确的"完成"证据时（复用 EngineRules.idempotencyDone，
                    // 与幂等保护同一口径；判定含稀疏守卫，普通列表页的历史"已完成"字样不算证据），
                    // 即便提前宣称完成也直接放行，不再为凑步数空跑；无证据时才沿用上面的步数门槛照旧拦截。
                    // 放弃（GIVE_UP）不属"完成"，不适用证据放行，仍按原门槛拦截。
                    val hasDoneEvidence = intent.intent != IntentType.GIVE_UP && idempotencyDone(snapshot)
                    if (action!!.type == ActionType.TASK_DONE && step < minDoneThreshold && !hasDoneEvidence) {
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
                        val doneSummary = action!!.summary ?: "任务完成"
                        // FINISH 与 GIVE_UP 都落到 TASK_DONE 动作（见转译层）：放弃绝不能按成功口径收尾
                        if (intent.intent == IntentType.GIVE_UP) {
                            log(AgentLog.Level.WARN, "任务已放弃：$doneSummary")
                            recordStep(step, action!!, VerifyRules.STATUS_VERIFIED, "", "", verify.reason)
                            // phase 不得写成 DONE：settleTaskSession 按 phase==DONE 判「已完成」，
                            // 放弃必须落到失败口径（侧边栏状态=失败）
                            _state.value = _state.value.copy(phase = AgentState.Phase.IDLE, message = "已放弃：$doneSummary", isRunning = false)
                            AgentAccessibilityService.agentRunning = false
                            // 放弃收尾：不学模板（learnTemplate）、不弹保存模板确认，收尾提示文案用「任务已放弃」
                            FloatingWindowService.showDone("任务已放弃")
                            // 任务记忆走失败口径，结论带「已放弃」前缀，会话承接时能看出是主动放弃
                            finishTaskMemory(TaskMemoryEntry.STATUS_FAILED, "已放弃：$doneSummary")
                            return
                        }
                        log(AgentLog.Level.INFO, "任务完成：$doneSummary")
                        recordStep(step, action!!, VerifyRules.STATUS_VERIFIED, "", "", verify.reason)
                        _state.value = _state.value.copy(phase = AgentState.Phase.DONE, message = doneSummary, isRunning = false)
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
                                FloatingWindowService.showDone(doneSummary)
                            }
                        }
                        // 任务记忆先收尾（目标已达成）：提炼再慢也不影响记忆页立刻变成「已完成」
                        // 完成说明一并落库，作为会话承接时"上一轮任务"的结论
                        finishTaskMemory(TaskMemoryEntry.STATUS_SUCCESS, doneSummary)
                        // 记忆提炼放最后：完成提示先给到用户，提炼再慢也不影响「已完成」的观感
                        // （独立调用一次模型，不写 conversation，因此不污染主决策上下文）
                        runCatching { distillMemories(task, doneSummary) }
                        return
                    }
                    // 记忆写入：纯本地写库，不操作屏幕，不走通道/不截图/不重试
                    if (action!!.type == ActionType.REMEMBER) {
                        handleRemember(task, step, action!!)
                        continue
                    }
                    // 本机信息查询：同类端侧代办，读到的内容作为"上一步结果"回注下一轮决策
                    if (action!!.type == ActionType.DEVICE_QUERY) {
                        handleDeviceQuery(step, action!!, messages)
                        continue
                    }
                    // AI 对用户说话：纯端侧呈现，不操作设备、不截图、不重试，
                    // 也不落 StepRecord（否则同一句话既是一张步骤卡又是一条气泡）
                    if (action!!.type == ActionType.SAY) {
                        if (sayStreak >= MAX_SAY_STREAK) {
                            sayStreak = 0
                            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text",
                                text = "⚠️ 你已连续说了 $MAX_SAY_STREAK 次而没有执行任何动作。请立刻输出真正的操作意图；只有确实需要向用户澄清关键信息时才可以再说一次。",
                            ))))
                            continue
                        }
                        handleSay(step, action!!)
                        sayStreak++
                        continue
                    }
                    // 引导用户回 Agent 页看结果：同样纯端侧，但要真的把人带回来（拉起本应用 + 切页）
                    if (action!!.type == ActionType.SHOW_AGENT) {
                        handleShowAgent(step, action!!)
                        sayStreak = 0
                        continue
                    }
                    // 自由模式：AI 自写命令在本任务首次执行前要向用户确认一次。
                    // 拒绝不等于放弃任务——把"用户拒绝了这条命令"回注给 AI，让它换条路子继续干。
                    if (!ensureShellApproval(action!!)) {
                        val denied = "用户拒绝执行这条命令：${action!!.command}。" +
                            "请不要重试同一条命令：改用转译层既有意图完成当前目标，或换一条用户更可能接受的命令。"
                        log(AgentLog.Level.WARN, "用户拒绝 AI 自写命令，已跳过执行：${action!!.command}")
                        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ $denied"))))
                        recordsIntoHistory(step, action!!, com.phoneagent.engine.execution.VerifyResult(false, denied, "", ""))
                        stepShotCapture(step, action!!, false)
                        continue
                    }
                    // 不可逆动作确认门：AI/转译层标记 needsUserConfirmation 的动作，执行前先问用户。
                    // 取消/超时则该步记跳过，并把用户的决定回注给 AI 换路子，而不是硬执行
                    if (action!!.needsUserConfirmation && !awaitActionConfirmation(action!!)) {
                        val cancelReason = "用户取消了该操作"
                        log(AgentLog.Level.WARN, "用户取消不可逆操作，已跳过执行：${action!!.type}")
                        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text",
                            text = "⚠️ 用户拒绝执行该不可逆操作，请改用其他方案或询问用户，不要重复请求同一操作。",
                        ))))
                        recordsIntoHistory(step, action!!, com.phoneagent.engine.execution.VerifyResult(false, cancelReason, "", ""))
                        stepShotCapture(step, action!!, false)
                        continue
                    }
                    // 真的动手了：把连续说话计数清掉
                    sayStreak = 0
                    verify = safeExecute(action!!, snapshot)
                    // 对确定性错误（未知命令/命令为空/参数无效）不重试，立即失败促使 AI 重新决策。
                    // 判据（哪些错误算结构性、哪些动作不参与外层重试）下沉 VerifyRules，可单测
                    isStructuralError = VerifyRules.isStructuralError(verify.reason)
                    verified = verify.success
                    var times = 1
                    // 点击类动作不再交给外层重跑：ClickRunner 内部已把"活节点直点 → 手势点击 → 快照坐标 →
                    // 滚动查找"逐级穷尽过一遍，外层再重跑只是把同一串动作重复执行（还可能造成重复副作用）
                    while (!verified &&
                        VerifyRules.shouldRetry(action!!.type, verify.reason, times, coroutineContext.isActive)
                    ) {
                        times++
                        log(AgentLog.Level.WARN, "动作未生效（第 $times 次重试）：${action!!.type}")
                        repeat(3) { delay(300) }
                        val v2 = safeExecute(action!!, observe())
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
            // 命令成功执行后清零「无效命令」计数：成功说明链路已恢复正常，旧账不该累计
            if (verified) invalidCommandStreak = 0
            // 决策循环熔断（真机 BUG 24 次因）：动作"成功"但同签名连续重复时，AI 会在同一位置打转
            // （实测：美团购物车面板元素树为空，AI 反复点同一个购物车容器 6+ 步，页面纹丝不动）。
            // 连续 4 次注入纠偏提示逼 AI 换方案；同签名出现 6 次则说明提示无效，升级为协助请求。
            if (verified && action.type != ActionType.WAIT && action.type != ActionType.SAY &&
                action.type != ActionType.REMEMBER && action.type != ActionType.DEVICE_QUERY &&
                action.type != ActionType.TASK_DONE
            ) {
                val sig = buildString {
                    append(action.type)
                    append('|').append(action.target?.value.orEmpty())
                    append('|').append(action.x).append(',').append(action.y)
                    append('|').append(action.keycode.orEmpty()).append('|').append(action.command.orEmpty())
                }
                if (sig == lastVerifiedActionSig) verifiedSameSigStreak++ else {
                    lastVerifiedActionSig = sig
                    verifiedSameSigStreak = 1
                }
                if (verifiedSameSigStreak == 4 || verifiedSameSigStreak == 6) {
                    val loopHint = if (verifiedSameSigStreak == 4) {
                        "⚠️ 你已连续 $verifiedSameSigStreak 步执行完全相同的动作「${action.type}」而页面没有推进。" +
                            "这个做法无效：请换一种完全不同的意图（如 scroll_to 滚动查找、see 看图确认、" +
                            "say 向用户说明障碍、open/press 返回上一级），不要重复同一动作。"
                    } else {
                        "⚠️ 你已连续 $verifiedSameSigStreak 步重复同一动作且毫无进展。立即停止该做法：" +
                            "要么换一种截然不同的方案，要么用 say 向用户说明当前卡点并请求指导。"
                    }
                    log(AgentLog.Level.WARN, "决策循环：连续 $verifiedSameSigStreak 步同签名动作「${action.type}」，注入纠偏提示")
                    messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = loopHint))))
                }
            } else if (verified) {
                lastVerifiedActionSig = ""
                verifiedSameSigStreak = 0
            }
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
                // 此处不先记失败留档：引导重试后无论成败都会走到下方统一留档，
                // 先记一遍会造成同一步双重留档；只有用户离开（本步直接 return）才补记
                log(AgentLog.Level.ERROR, "动作 3 次未生效：${action.type}，请求用户介入")
                pushFloating("需要指导", "ERROR")
                // 协助文案必须带上真实失败原因与服务状态（真机 BUG 23）：
                // 只说"连续未能改变页面"会把 wait/shell 这类本不期待页面变化的动作误报成异常，
                // 用户也无从判断是页面问题还是服务掉了
                val a11yState = if (AgentAccessibilityService.instance == null)
                    "（无障碍服务已断开）" else ""
                val lastReason = verify.reason.ifBlank { "页面未按预期变化" }
                val stuckReason = "动作「${action.type}」连续 $consecutiveFailures 次未生效：$lastReason$a11yState。" +
                    "请选择：手动接管 / 告诉 AI 怎么做。"
                showFloatingInteraction("guide", "需要你的协助", stuckReason)
                _needsUser.value = true
                _userHintRequest.tryEmit(stuckReason)
                // 同敏感页分支：协作原因需要落到 agentState.message，界面才能显示出来
                _state.value = _state.value.copy(message = stuckReason)
                val hint = awaitUserHint()
                if (hint.isBlank()) {
                    // 用户离开：这一步在 return，不会再走到下方统一留档，先补一条失败记录
                    recordStep(step, action, "failed", verify.beforeFingerprint, verify.afterFingerprint, verify.reason)
                    finishTaskMemory(TaskMemoryEntry.STATUS_FAILED); stop(); return
                }
                // 用户指导 → 加入上下文并让云端重新决策（失败自动重试一次）
                messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "用户提示：$hint 请据此重新决策下一步动作。" ))))
                pushThinking(sent = "用户提示：$hint")
                // 用户给的指导沉淀为异常记忆：与 anomalyEngine.findSolution 配对，
                // 下次同页再卡可直接端侧复用，不必再麻烦用户（2026-08-15 BUG-07 收尾：接线而非删除）
                runCatching {
                    profileLearner.learnAnomalySolution(
                        fingerprint = runCatching { com.phoneagent.engine.perception.PageFingerprint.computeMeaningful(snapshot) }.getOrDefault(""),
                        labels = snapshot.elements.mapNotNull { it.effectiveLabel() }.take(30),
                        anomalyType = "stuck_${action.type}",
                        description = stuckReason,
                        solution = hint,
                        resolvedAction = action.type,
                        appPackage = snapshot.packageName.orEmpty(),
                    )
                }
                // 引导后决策同样有看门狗（BUG 24）：用户给完指导若云端挂起，任务会带着"需要你的协助"
                // 的假象永久卡住，用户无从判断。超时按"引导失败"处理，走下方既有的收尾/重试分支。
                var guided = withVisionWatchdog(WATCHDOG_DECIDE_MS) {
                    cloudAgent.decideWithUserHint(settingsVal.apiBaseUrl, settingsVal.apiKey, settingsVal.model, messages, hint, onDelta = { pushThinking(delta = it) }, protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal)).getOrNull()
                }
                if (guided == null || guided.intent == IntentType.FINISH || guided.intent == IntentType.GIVE_UP) {
                    // AI 调用失败或认为任务完成：重试一次
                    guided = withVisionWatchdog(WATCHDOG_DECIDE_MS) {
                        cloudAgent.decideWithUserHint(settingsVal.apiBaseUrl, settingsVal.apiKey, settingsVal.model, messages, hint, onDelta = { pushThinking(delta = it) }, protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal)).getOrNull()
                    }
                }
                if (guided != null && guided.intent != IntentType.FINISH && guided.intent != IntentType.GIVE_UP) {
                    // 直接执行引导后的意图，不重走决策（避免变卦 + 节省一次云调用）。
                    // 浏览器意图走独立通道：这里不能 continue（后面的 recordsIntoHistory 要落档），
                    // 故用「合成动作 + 验证结果」接进既有链路，让留档与截图照常发生。
                    val gIntent = guided
                    // 页面记忆：引导恢复的意图同样记为待记路径边的动作
                    pageMemoryRecorder.onIntent(gIntent)
                    // 引导回来的意图同样要过动作模式门控：用户给的"指导"不能成为越权的旁路
                    val gDenied = ActionPolicy.allows(activeActionMode, gIntent.intent) as? ActionPolicy.Verdict.Denied
                    if (gDenied != null) {
                        log(AgentLog.Level.WARN, "引导后意图被动作模式拒绝：${gIntent.intent}｜${gDenied.reason}")
                        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ ${gDenied.reason}"))))
                        verify = com.phoneagent.engine.execution.VerifyResult(false, gDenied.reason, "", "")
                        verified = false
                    } else if (browserChannel.handles(gIntent.intent)) {
                        val (browseAction, browseVerify) = handleBrowseIntent(step, gIntent, messages)
                        action = browseAction
                        verify = browseVerify
                        verified = browseVerify.success
                    } else {
                        // 视觉坐标与常规路径同口径「消费一次」：引导后 AI 若给的是 by=hint 的视觉定位，
                        // 这里必须先取走上一步 see/视觉定位产出的坐标（取走即清空），否则会定位失败。
                        // 原先写死 null，等于引导路径永远拿不到视觉坐标。
                        val gCarry = lastVisualCoordinate
                        lastVisualCoordinate = null
                        val gTranslate = tryOrNull("引导后意图转译失败") {
                            intentTranslator.translate(gIntent, observe(), gCarry)
                        } ?: IntentTranslator.TranslationResult.Failed("引导后意图转译异常")
                        when (gTranslate) {
                            is IntentTranslator.TranslationResult.Command -> {
                                action = gTranslate.action
                                // 不可逆动作确认门与常规路径同口径：引导回来的动作若带 needsUserConfirmation，
                                // 执行前必须先问用户。原先这里直接执行，等于用户给一次指导就能越过确认门
                                // 执行删除/发送等不可逆操作（转译层会给 DELETE 等语义控件自动打上该标志）。
                                if (gTranslate.action.needsUserConfirmation && !awaitActionConfirmation(gTranslate.action)) {
                                    log(AgentLog.Level.WARN, "用户取消引导后的不可逆操作，已跳过执行：${gTranslate.action.type}")
                                    messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text",
                                        text = "⚠️ 用户拒绝执行该不可逆操作，请改用其他方案或询问用户，不要重复请求同一操作。",
                                    ))))
                                    verify = com.phoneagent.engine.execution.VerifyResult(false, "用户取消了该操作", "", "")
                                    verified = false
                                // 自写命令的首次确认在这里同样不能绕：引导路径若把它跳过，
                                // 等于「第一次自写命令」可以不被确认就执行
                                } else if (ensureShellApproval(gTranslate.action)) {
                                    verify = safeExecute(gTranslate.action, observe())
                                    verified = verify.success
                                    if (verified) {
                                        // 引导恢复成功同样要对齐常规路径的记账：清失败计数、记进度、存检查点
                                        // （原来漏了这一步，引导成功后进度不涨、失败计数也不清）
                                        consecutiveFailures = 0
                                        recordProgress(step, action)
                                        runCatching {
                                            com.phoneagent.data.store.TaskStore.saveCheckpoint(
                                                appContext, currentTaskName ?: task, activePlan,
                                                completedSteps, totalPlannedSteps, currentTaskId,
                                            )
                                        }
                                    } else {
                                        log(AgentLog.Level.WARN, "引导后动作仍未生效：${gTranslate.action.type}")
                                    }
                                } else {
                                    val denied = "用户拒绝执行这条命令：${gTranslate.action.command}。请改用既有意图完成当前目标。"
                                    messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ $denied"))))
                                    verify = com.phoneagent.engine.execution.VerifyResult(false, denied, "", "")
                                    verified = false
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
                } else {
                    // 引导两次都失败 / AI 认为任务已完成：绝不再重放接管前的旧失败动作
                    // （旧动作已验证失败，重放只会空转一回合并留下误导性留档，2026-08-18 BUG-004）。
                    // 补一条失败留档后回到循环顶部，用全新观测重新决策
                    log(AgentLog.Level.WARN, "用户引导后仍未获得有效动作（${guided?.intent ?: "调用失败"}），重新观测页面而非重放旧动作")
                    recordStep(step, action, "failed", verify.beforeFingerprint, verify.afterFingerprint, verify.reason)
                    continue
                }
            }

            log(AgentLog.Level.INFO, "执行动作：${action.type}（${if (verified) "已验证生效" else "待确认"}）")
            recordsIntoHistory(step, action, verify)
            // 页面记忆：动作是否真实生效（下一步换页时据此决定要不要记路径边）
            pageMemoryRecorder.onStepResult(verified)
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
                    "\n\n意图「${action.type}」执行无效：${verify.reason}\n请重新决策，使用 open_app/open/tap/long_press/input/swipe/press/wait/scroll_to/write_doc/browse_open/browse_read/browse_click/browse_input/browse_scroll/browse_back/finish/give_up 中正确的意图与参数。目标优先用 by_id/by_text，找不到用 by_hint；网页内的元素改用 browse_click + {\"by\":\"text\",\"value\":\"元素文字\"}。"
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
            // 无障碍端点直调的结果同样要回注：can_screenshot / capture_tree 这类端点本身就是"问一句话"，
            // 不回注等于 AI 问了却永远收不到答案，接下来只能靠猜
            if (action.type == ActionType.A11Y_CALL && verify.success && verify.reason.isNotBlank()) {
                messages.add(ChatMessageDto(role = "assistant", content = listOf(
                    ContentPart(type = "text", text = "无障碍端点输出：\n${verify.reason}"),
                )))
            }
            // 执行成功但有注意项（当前唯一来源：点击落点与目标不一致）同样要回注。
            // 成功路径此前只回报"执行了 CLICK"，AI 看不到"这一下其实点在了别的控件上"，
            // 下一步就会把并非本次操作带来的页面变化当成预期结果继续往下走
            if (verified && verify.note.isNotBlank()) {
                messages.add(ChatMessageDto(role = "assistant", content = listOf(
                    ContentPart(type = "text", text = "⚠️ ${verify.note}"),
                )))
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
            // 看门狗（BUG 24）：补全请求若云端挂起，主循环会停在转译阶段；超时按补全失败处理
            val refilled = withVisionWatchdog(WATCHDOG_DECIDE_MS) {
                aiClient.chatForAction(
                    baseUrl = settingsVal.apiBaseUrl, apiKey = settingsVal.apiKey, model = settingsVal.model,
                    messages = listOf(
                        ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = "你是手机智能体，负责把一句意图补全为合规 JSON 意图，只输出一个 JSON 对象。"))),
                        ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = ask))),
                    ),
                    screenshot = null, temperature = 0.2,
                    protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal),
                ).getOrNull()?.action
            }
            if (refilled == null) break
            cur = refilled
            result = tryOrNull("补全后转译失败") { intentTranslator.translate(refilled, snapshot, null) }
                ?: IntentTranslator.TranslationResult.Failed("AI 补全参数后仍无法转译")
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
        /** 页面记忆提示块（命中已记忆页面时非空，见 pageMemoryHintFor），空串不占上下文 */
        pageMemoryHint: String = "",
    ): AgentIntent? {
        // 数据脱敏后再发送
        val safeText = DataSanitizer.sanitize(snapshot.toAiText())
        val planSteps = activePlan?.steps?.mapIndexed { i, s -> "${i + 1}. ${s.description}" }?.joinToString("\n")
        val planNote = if (!planSteps.isNullOrBlank()) "\n\n## 已批准的执行计划\n$planSteps" else ""
        // 视觉链路：主模型不支持图片输入时，先用视觉模型描述截图，再让主模型基于文本决策。
        // visionMode: CLOUD=仅云端 | LOCAL=仅本地OCR | AUTO=优先云端、失败/未配置回退本地
        // 端侧视觉（enableExternalVision）优先于云端/本地，仅在未启用或不可用时才走后续来源。
        // 混合路由（smartVisionRoute）：端侧 3B 只认「简单任务」（元素树可读）——框选快、省云端额度；
        //  复杂任务（元素树稀疏，需强语义理解，如游戏/WebView/小程序）跳过 3B，直接走云端视觉。
        // 页面是否复杂：元素树稀疏即视为复杂（无障碍读不到控件，需强视觉理解）
        val complexPage = snapshot.elements.size <= VISION_FALLBACK_THRESHOLD
        // 元素树稀疏/为空时必须拿到视觉配置（force）：此时视觉是唯一的信息来源，
        // 不能因为「视觉总开关关着」就把这一路掐掉，否则 AI 面对的是完全不可见的页面
        val visionCfg = visionConfig(settingsVal, force = complexPage)
        // 云端视觉配置是否就绪：有配置且没有明确指定只走 LOCAL
        val cloudReady = visionCfg != null && settingsVal.visionMode != "LOCAL"
        // 主模型自身能识图（三态合流，见 VisionRouting）且本轮确实拍到了图 → 图片直接进主模型上下文。
        // 局部变量刻意不叫 mainSeesImage：那是"能力判定"，这里还要叠加"本轮有图"
        val mainGetsImage = mainSeesImage(settingsVal) && settingsVal.attachScreenshot && screenshot != null
        // 三条视觉链路（端侧 3B / 云端描述 / 端侧描述）怎么走，以及"主模型识图时跳过视觉描述"：
        // 路由判据（含"复杂任务且云端可用时跳过 3B，但云端不可用时必须回落 3B"）下沉 VisionRouting.route，可单测
        val route = VisionRouting.route(
            VisionRouting.RouteInput(
                complexPage = complexPage,
                cloudReady = cloudReady,
                smartRoute = settingsVal.smartVisionRoute,
                externalEnabled = settingsVal.enableExternalVision,
                mainGetsImage = mainGetsImage,
                skipDescWhenMainSees = settingsVal.skipVisionDescWhenMainSees,
            ),
        )
        val useOnDevice3b = route.useOnDevice3b
        val cloudVision = route.cloudVision
        val wantVisionDesc = route.wantVisionDesc
        var localRegions: List<com.phoneagent.device.vision.DetectedControl>? = null
        var externalUsed = false
        var pageText = safeText
        var desc: String? = null
        // 视觉缓存（阶段 B2）：页面指纹 + 截图尺寸 + 任务 id 命中时，整段跳过 3B/云端/OCR 视觉调用——
        // 页面未变时连续两步决策的视觉产物完全相同，重跑只是白耗 10-40s 与模型额度
        val visionFp = if (screenshot != null) {
            runCatching { com.phoneagent.engine.perception.PageFingerprint.computeMeaningful(snapshot) }.getOrDefault("")
        } else ""
        val cachedVision = visionCache.get(
            visionFp, screenshot?.width ?: 0, screenshot?.height ?: 0, store.currentTaskId,
        )
        if (cachedVision != null) {
            desc = cachedVision.desc
            localRegions = cachedVision.regions
            externalUsed = cachedVision.usedExternal
            if (!desc.isNullOrBlank()) pageText += "\n\n## 视觉描述（截图）\n$desc"
            log(AgentLog.Level.INFO, "页面未变，复用上一步视觉结果（省一次视觉调用）")
        }
        val needVisionRun = cachedVision == null
        if (screenshot != null && needVisionRun) {
            val shot = screenshot
            // 观察已结束、视觉分析还没开始时先切状态：视觉链路（端侧 3B / 云端视觉）可能耗时数十秒，
            // 之前界面一直停在“观察屏幕”，用户会以为卡死
            _state.value = _state.value.copy(phase = AgentState.Phase.THINKING, message = "正在识别屏幕内容...")
            pushFloating("正在识别屏幕内容", "THINKING")
            // 看门狗：视觉链路全是阻塞式调用（云端 OkHttp 读超时 120s、端侧 3B 单次 20s 且同一步可能调用两次），
            // 超时即放弃视觉描述，仅用无障碍元素树继续决策，保证主循环不被打死
            val triedOnDevice3b = useOnDevice3b
            val visionDone = withVisionWatchdog(WATCHDOG_VISION_MS) {
                // 1) 优先：端侧 3B 视觉控件框选（类型 + 用途 + 归一化坐标）。
                //    混合模式下 3B 仅用于简单任务，复杂任务跳过此处直接走云端
                if (useOnDevice3b) {
                    log(AgentLog.Level.INFO, "端侧视觉 3B 控件识别…")
                    val t0 = System.nanoTime()
                    val controls = com.phoneagent.device.vision.OnDeviceVision.detectControls(
                        context = appContext,
                        bitmap = shot,
                        timeoutMs = EXTERNAL_VISION_TIMEOUT,
                    )
                    recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                    if (controls.isNotEmpty()) {
                        externalUsed = true
                        localRegions = controls
                        // 跳过描述时仍保留框选坐标：hint 目标定位要靠它，文字描述则可以不给
                        if (wantVisionDesc) desc = com.phoneagent.device.vision.ControlFormat.describe(controls)
                    } else {
                        log(AgentLog.Level.INFO, "端侧视觉未就绪/不可用，回退云端或本地")
                    }
                }
                // 2) 云端视觉（只产文字描述，主模型能看图时整段跳过）
                if (desc.isNullOrBlank() && cloudVision && wantVisionDesc) {
                    log(AgentLog.Level.INFO, "视觉模型描述截图…（${visionCfg?.model}）")
                    val t0 = System.nanoTime()
                    // 提问带目的：把"这次任务想推进什么"直接写进提问，视觉模型不必再整屏铺开罗列
                    desc = aiClient.visionAsk(
                        baseUrl = visionCfg?.baseUrl ?: "",
                        apiKey = visionCfg?.apiKey ?: "",
                        model = visionCfg?.model ?: "",
                        screenshot = shot,
                        purpose = "根据用户任务「$task」，找出当前页面上可用于推进任务的操作目标",
                        protocol = protocolOf(visionCfg?.baseUrl ?: "", settingsVal),
                    ).getOrNull()?.text
                    recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                }
                // 3) LOCAL，或 AUTO 云端失败/未配置 → 端侧（OCR/3B）识别兜底。
                //    本地读图统一由进程内端侧视觉承担
                //    同一步第 1 步已经找过端侧视觉且没结果时不再重复调用（同一张图，重试只是白等）
                if (desc.isNullOrBlank() && !externalUsed && !triedOnDevice3b &&
                    (settingsVal.visionMode == "LOCAL" || settingsVal.visionMode == "AUTO")
                ) {
                    log(AgentLog.Level.INFO, "端侧视觉识别（LOCAL/兜底）…")
                    val t0 = System.nanoTime()
                    val controls = com.phoneagent.device.vision.OnDeviceVision.detectControls(
                        context = appContext,
                        bitmap = shot,
                        timeoutMs = EXTERNAL_VISION_TIMEOUT,
                    )
                    recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                    if (controls.isNotEmpty()) {
                        externalUsed = true
                        localRegions = controls
                        // 同第 1 步：坐标留着，描述按开关决定要不要
                        if (wantVisionDesc) desc = com.phoneagent.device.vision.ControlFormat.describe(controls)
                    } else {
                        // 只记日志，不往上下文里写「未识别到控件」：
                        // 那句话会被 AI 当成"页面上没有可操作控件"的事实，从而放弃尝试、编造动作或直接收尾，
                        // 而真实情况只是这一路视觉没结果，元素树/其它来源仍然有效
                        log(AgentLog.Level.WARN, "端侧视觉不可用，本地无可识别控件")
                    }
                }
                if (!desc.isNullOrBlank()) pageText += "\n\n## 视觉描述（截图）\n$desc"
                true
            }
            if (visionDone == null) {
                log(AgentLog.Level.WARN, "视觉分析超过 ${WATCHDOG_VISION_MS / 1000}s 未返回，本步放弃视觉描述，改用无障碍元素树继续决策")
                desc = null
            }
            // 元素树稀疏且视觉也没产出：这一步 AI 实际上"什么都看不到"，
            // 必须明确记下来，否则用户只会看到 AI 在乱猜，不知道是两条感知链路同时空了。
            // 主模型自己能看图（mainGetsImage）时不算"看不到"，那种情况下不发这条警告
            if (complexPage && desc.isNullOrBlank() && localRegions.isNullOrEmpty() && !mainGetsImage) {
                log(
                    AgentLog.Level.WARN,
                    "元素树稀疏（${snapshot.elements.size} 个元素）且视觉链路未产出内容：" +
                        "元素树统计=${AgentAccessibilityService.instance?.lastTreeStats ?: "无统计"}" +
                        "，请检查视觉模型配置",
                )
            }
            // 本步视觉链路真实跑过 → 按页面指纹写入缓存，供下一步页面未变时复用
            val shotForCache = screenshot
            if (needVisionRun && shotForCache != null && visionFp.isNotBlank()) {
                visionCache.put(
                    visionFp, shotForCache.width, shotForCache.height, store.currentTaskId,
                    com.phoneagent.engine.decision.VisionResultCache.Entry(desc, localRegions, externalUsed),
                )
            }
        }
        // 记忆与经验规则：两者互斥去重（同一条内容只注入一遍），故必须一起算
        val (memoryText, ruleText) = memoryAndRules(task, snapshot)
        val userText = AgentPrompts.decision(
            lang = currentLang,
            task = task,
            stepIndex = _state.value.stepCount,
            totalSteps = totalPlannedSteps.coerceAtLeast(1),
            currentStep = if (!planSteps.isNullOrBlank()) "按计划执行下一步" else "根据当前页面执行下一步",
            lastStepResult = lastStepResultText(currentLang),
            consecutiveFailures = consecutiveFailures,
            contextHint = DataSanitizer.sanitize(annotated.contextHint),
            // 记忆注入：让 AI 每一步都能看到已知偏好与既往经验，而不是只在规划阶段看得到
            memory = memoryText,
            // 经验规则注入：只放"与当前应用/任务相关"的那几条（见 RuleScoper），其余留在记忆里
            evolvedRules = ruleText,
        ) + planNote + "\n\n## 当前页面\n$pageText" +
            DataSanitizer.sanitize(com.phoneagent.engine.perception.PageAnnotator.knownControlsText(annotated.elements)) +
            AgentPrompts.situationalExtras(currentLang, task, termuxBridge?.isAvailable() == true) +
            taskMemoryText() +
            // 页面记忆：命中已记忆页面时注入已知热点与可去路径（空串不占上下文）
            pageMemoryHint +
            // 通知感知：通知监听开启时注入最新通知（验证码/提醒），屏幕元素树读不到的事实（空串不占上下文）
            com.phoneagent.feature.notify.NotificationFeed.decisionText(appContext.packageName) +
            // 数字助理上下文：用户刚从系统助理唤起（<90s）时带着当时正看着的页面，任务往往与它相关
            com.phoneagent.feature.assistant.AssistContextStore.freshText().let { assist ->
                if (assist.isBlank()) "" else "\n## 用户唤起助理时正看着的页面\n$assist\n"
            } +
            // 环境上下文（时间/前台应用/网络/电量）+ 会话承接（上一轮任务）：页面元素树里读不到的事实
            AgentPrompts.environment(currentLang, envFacts(snapshot)) +
            sessionContextText(task, currentLang)
        val userMsg = ChatMessageDto(role = "user", content = mutableListOf(ContentPart(type = "text", text = userText)))
        addConversation("user", userText, hasImage = mainGetsImage)

        _state.value = _state.value.copy(phase = AgentState.Phase.THINKING, message = "正在思考下一步...")
        pushFloating("正在思考下一步", "THINKING")
        // 实时展示发送给 AI 的决策上下文
        pushThinking(sent = userText)
        val startNano = System.nanoTime()
        // 主模型能识图（三态合流见 mainSeesImage）时把截图直接交给它；否则只收视觉描述后的文本，
        // 避免不支持图片的模型因 image_url 报错（旧行为是恒不发图，这里改为按能力发）
        // 流式生成，边生成边把返回内容实时显示到悬浮窗 + 通知
        // 看门狗：单步决策超时则本步改为等待、下一轮重试，避免长线任务因云端卡住而无限阻塞
        val result: Result<com.phoneagent.core.ai.AiDecision>? = withTimeoutOrNull(WATCHDOG_DECIDE_MS) {
            aiClient.chatForAction(
                baseUrl = settingsVal.apiBaseUrl,
                apiKey = settingsVal.apiKey,
                model = settingsVal.model,
                // 长线任务历史压缩：只带系统消息 + 最近几轮 + 当前轮，避免上下文无限累积；
                // 发送前过 Hook 出口管线（脱敏/翻译等增强件在此改写，失败保持原样）
                messages = AgentHookRegistry.runMessagePipeline(
                    chatHistory(messages, userMsg),
                    AgentHookRegistry.HookContext(task, currentLang, _state.value.stepCount, activeActionMode.key),
                ),
                screenshot = if (mainGetsImage) screenshot else null,
                // 温度 v0.1 文档：每步决策 = 0.1；失败 3 次进入重规划 = 0.5
                temperature = decisionTemperature(),
                protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal),
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
        // 失败必须显式抛回主循环：静默 return null 会让上层把"单次网络抖动"当成「决策为空」直接杀任务，
        // 且连续失败护栏（decisionFailureStreak）永远统计不到这条路径；
        // 抛出后由主循环计数降级为 wait 重试，达到上限才收尾
        val decision = result.getOrElse { err ->
            if (err is kotlinx.coroutines.CancellationException) throw err
            log(AgentLog.Level.ERROR, "AI 调用失败：${err.message ?: err.javaClass.simpleName}")
            throw err
        }
        // 决策已完成：正文已落 trace（可在步骤卡的"原始数据"里回看），清空流式回显避免与下一步混淆
        _decisionStream.value = ""
        apiLog(userText, decision.rawContent.ifBlank { "（无正文，可能为错误）" }, latencyMs)
        recordMetrics(decision)
        var intent = decision.action
        // 视觉定位：给元素树里定位不到的目标算出像素坐标，供转译层本次定位使用。
        // 只有"端侧在元素树里已经命中"的目标才跳过这一步——那种情况下转译层会直接精确定位到控件。
        // 不在这里清零 lastVisualCoordinate：坐标改为"消费一次"（转译前取走），上一步 see 的定位不再被本步冲掉
        val tTarget = intent.target
        // 视觉定位的触发条件从「只有 by=hint」放宽为「元素树里没命中」：
        // WebView/自绘页面里，AI 从视觉描述读到的文字往往并不在无障碍元素树中，
        // 以前它按 by=text 给出目标只会收到"目标定位失败"，而视觉模型本就能定位到它。
        // 先在元素树里试一次，命中就不花视觉的钱（by=hint 天然没有 id/文字，直接走视觉）。
        val offlineMiss = tTarget != null && tTarget.by != "coordinate" &&
            intentResolver.resolve(tTarget, snapshot).let { it.element == null && it.x == null }
        if (tTarget != null && (tTarget.by == "hint" || offlineMiss) && screenshot != null &&
            (cloudVision || localRegions != null || externalUsed)
        ) {
            val targetText = tTarget.value
            if (!targetText.isNullOrBlank()) {
                // 定位同样是阻塞式视觉调用（端侧 AIDL / 云端 OkHttp），套同一只看门狗：
                // 超时即放弃坐标（转译层会提示降级重定位），不让它拖死主循环
                val pos: Pair<Float, Float>? = withVisionWatchdog(EXTERNAL_VISION_TIMEOUT) {
                    // 1) 免费且即时：在已识别到的控件里做字符串匹配（先全等、再包含），零网络零 IPC。
                    //    能命中就绝不发远端 —— 旧逻辑先发 AIDL 远端、失败才回落本地，白等一次跨进程调用
                    val local = localRegions?.let { com.phoneagent.device.vision.ControlFormat.locate(it, targetText) }
                    when {
                        local != null -> local
                        // 2) 本地没匹配上，且端侧视觉这一路确实是活的：发一次精定位
                        externalUsed -> {
                            log(AgentLog.Level.INFO, "端侧视觉定位目标：$targetText")
                            val t0 = System.nanoTime()
                            val p = com.phoneagent.device.vision.OnDeviceVision.locate(
                                appContext, screenshot, targetText, EXTERNAL_VISION_TIMEOUT,
                            )
                            recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                            p
                        }
                        // 3) 最后才是云端：同一张图再上传一次，代价最高
                        cloudVision -> {
                            log(AgentLog.Level.INFO, "视觉模型定位目标：$targetText")
                            val t0 = System.nanoTime()
                            val ans = aiClient.visionAsk(
                                baseUrl = visionCfg?.baseUrl ?: "",
                                apiKey = visionCfg?.apiKey ?: "",
                                model = visionCfg?.model ?: "",
                                screenshot = screenshot,
                                purpose = "定位用户任务「$task」中需要的目标「$targetText」",
                                target = targetText,
                                protocol = protocolOf(visionCfg?.baseUrl ?: "", settingsVal),
                            ).getOrNull()
                            recordVisionMs((System.nanoTime() - t0) / 1_000_000)
                            val ax = ans?.x
                            val ay = ans?.y
                            // 云端这次定位已经花过钱：把结果并进本地表，本次与后续的字符串匹配都能命中，
                            // 不必为同一个目标再上传同一张图
                            if (ax != null && ay != null) {
                                localRegions = (localRegions ?: emptyList()) + com.phoneagent.device.vision.DetectedControl(
                                    label = targetText,
                                    role = "目标",
                                    purpose = "云端定位：$targetText",
                                    bounds = floatArrayOf(),
                                    cx = ax,
                                    cy = ay,
                                    source = "cloud",
                                )
                            }
                            if (ax != null && ay != null) ax to ay else null
                        }
                        else -> null
                    }
                }
                if (pos != null) {
                    lastVisualCoordinate = (pos.first * screenWidth()).toInt() to (pos.second * screenHeight()).toInt()
                }
            }
        }
        // 记录本轮决策的详细追踪（Debug「按任务分类」展示）。
        // 视觉来源与模型名的归属判据下沉 VisionRouting（用枚举替代原先的字符串字面量比对），可单测
        val visionSource = VisionRouting.sourceOf(
            externalUsed = externalUsed,
            mainGetsImage = mainGetsImage,
            hasDescription = !desc.isNullOrBlank(),
            cloudVision = cloudVision,
        )
        val visionModel = VisionRouting.modelOf(
            source = visionSource,
            cloudModel = visionCfg?.model ?: "",
            mainModel = settingsVal.model,
        )
        // say 不落决策轨迹：它不是一步操作，落档会在任务流里凭空多出一条「说」的步骤
        // （只有 trace、没有执行记录），读起来像是工具没被识别出来
        if (intent.intent != IntentType.SAY) {
            recordStepTrace(
                step = _state.value.stepCount,
                sent = userText,
                decision = decision,
                visionSource = visionSource.label,
                visionModel = visionModel,
                visionDescription = desc?.takeIf { it.isNotBlank() }
                    ?: if (mainGetsImage) "（主模型直接读取截图，本步未生成文字描述）" else "",
                screenshot = screenshot,
            )
        }

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
        // 看门狗（真机 BUG 24）：审核调用只有 OkHttp readTimeout 兜底，网关保活时永不触发——
        // 美团外卖任务在第 39 步后的审核调用挂起，主循环静默卡死 15 分钟。
        // 超时/失败一律降级放行执行者意图：审核是增强置信而非放行前置，不能反过来卡死任务。
        val raw = withVisionWatchdog(WATCHDOG_DECIDE_MS) {
            aiClient.chat(
                baseUrl = baseUrl, apiKey = apiKey, model = model,
                messages = messages, temperature = 0.1,
                protocol = protocolOf(baseUrl, settingsVal),
            ).getOrNull()
        } ?: run {
            FloatingWindowService.updateReviewText("审核超时或失败，沿用执行者「${intent.intent}」")
            log(AgentLog.Level.WARN, "审核 AI 超时（${WATCHDOG_DECIDE_MS / 1000}s）或失败，降级放行执行者意图")
            return intent
        }
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
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String, protocol: ApiProtocol = ApiProtocol.OPENAI): Result<String> =
        aiTools.testConnection(baseUrl, apiKey, model, protocol)

    suspend fun listModels(baseUrl: String, apiKey: String, protocol: ApiProtocol = ApiProtocol.OPENAI): Result<List<String>> =
        aiTools.listModels(baseUrl, apiKey, protocol)

    suspend fun probeModel(baseUrl: String, apiKey: String, model: String, protocol: ApiProtocol = ApiProtocol.OPENAI): Result<ModelAbility> =
        aiTools.probeModel(baseUrl, apiKey, model, protocol)

    // ---- 技能 / 宏 / MCP 已收口到 skill/SkillRunner（重构批次 6），以下为转发 ----

    private suspend fun skillPromptText(): String = skillRunner.skillPromptText()

    private fun macroPrecheckReason(skill: com.phoneagent.feature.skill.Skill): String? =
        skillRunner.macroPrecheckReason(skill)

    private fun nextMacroIntent(messages: MutableList<ChatMessageDto>): AgentIntent? =
        skillRunner.nextMacroIntent(messages)

    private suspend fun invokeMcpSkill(
        step: Int,
        mcp: com.phoneagent.feature.skill.SkillCompat.Normalized.Mcp,
        messages: MutableList<ChatMessageDto>,
    ) = skillRunner.invokeMcpSkill(step, mcp, messages)

    private suspend fun inferMissingArgs(
        intent: com.phoneagent.domain.model.AgentIntent,
        semanticSnapshot: com.phoneagent.domain.model.ScreenSnapshot?,
    ): com.phoneagent.feature.skill.SkillCompat.Normalized? =
        skillRunner.inferMissingArgs(intent, semanticSnapshot)

    /**
     * 执行一次「看图追问」：就当前截图向视觉模型提一个**带目的**的问题，回答作为"上一步结果"回注下一轮。
     *
     * 与 MCP 技能同类 —— 端侧代办、不触碰设备，故不截图、不走执行通道、不做生效重试；
     * 与每步自动描述的区别：目的由 AI 现场给出，回答只针对这件事，而不是整屏铺开罗列。
     */
    private suspend fun invokeVisionAsk(
        step: Int,
        vision: com.phoneagent.feature.skill.SkillCompat.Normalized.Vision,
        messages: MutableList<ChatMessageDto>,
        screenshot: Bitmap?,
        settingsVal: AppSettings.Settings,
    ) {
        val question = vision.question
        // 没有截图就没有可看的东西：直接回注原因让 AI 换一条路取信息，不要白等一次调用
        if (screenshot == null) {
            messages.add(
                ChatMessageDto(
                    role = "user",
                    content = listOf(ContentPart(
                        type = "text",
                        text = "⚠️ 本轮没有可用的屏幕截图，看图追问无法进行；请改用元素树里能读到的线索，" +
                            "或用 device_query / browse_* 取信息。",
                    )),
                ),
            )
            return
        }
        // force = true：追问是 AI 明确提出的取数请求，不受「视觉总开关」影响（与元素树稀疏时同理）
        val cfg = visionConfig(settingsVal, force = true)
        if (cfg == null) {
            messages.add(
                ChatMessageDto(
                    role = "user",
                    content = listOf(ContentPart(
                        type = "text",
                        text = "⚠️ 当前没有可用的视觉模型（未配置视觉模型地址/密钥），看图追问无法进行。",
                    )),
                ),
            )
            return
        }
        val target = vision.target
        log(AgentLog.Level.INFO, "看图追问：$question（定位目标=${target ?: "无"}）")
        _state.value = _state.value.copy(phase = AgentState.Phase.ACTING, message = "看图追问：${question.take(30)}")
        pushFloating("看图追问：${question.take(30)}", "ACTING")
        // 同一步骤的看门狗：云端视觉读超时可达 120s，套上它保证主循环不被拖死
        val t0 = System.nanoTime()
        val answer = withVisionWatchdog(WATCHDOG_VISION_MS) {
            aiClient.visionAsk(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.model,
                screenshot = screenshot,
                purpose = question,
                target = target,
                jsonMode = target != null,
                protocol = protocolOf(cfg.baseUrl, settingsVal),
            )
        }
        recordVisionMs((System.nanoTime() - t0) / 1_000_000)
        val ans = answer?.getOrNull()
        val text = ans?.text?.trim().orEmpty()
        val x = ans?.x
        val y = ans?.y
        val failed = ans == null || (text.isBlank() && (x == null || y == null))
        val action = AgentAction(
            type = IntentType.SEE,
            reasoning = question.take(60),
            reason = "看图追问：${question.take(30)}",
            confidence = 0.9,
        )
        val injected: String
        val detail: String
        when {
            failed -> {
                val why = if (answer == null) "视觉调用超过 ${WATCHDOG_VISION_MS / 1000}s 未返回" else "视觉调用失败"
                injected = "⚠️ 看图追问失败：$why。请改用其他方式取信息，不要重复追问同一件事。"
                detail = injected
            }
            // 带了目标且拿回坐标：写成像素坐标供**紧接着的那一步**复用一次（下一步开头会自然清零）
            x != null && y != null && target != null -> {
                lastVisualCoordinate = (x * screenWidth()).toInt() to (y * screenHeight()).toInt()
                injected = "看图追问「$question」的结论：${text.ifBlank { "（无文字说明）" }}\n" +
                    "目标「$target」位于比例坐标(x=${"%.3f".format(x)}, y=${"%.3f".format(y)})；" +
                    "如需点击它，可用 by_hint 写同一句目标描述，端侧会复用这次定位。"
                detail = "追问：$question\n目标：$target → 比例坐标(${"%.3f".format(x)}, ${"%.3f".format(y)})"
            }
            else -> {
                injected = "看图追问「$question」的结论：${text.ifBlank { "（模型没有给出内容）" }}"
                detail = "追问：$question\n$text"
            }
        }
        recordStep(
            step = step,
            action = action,
            verification = if (failed) VerifyRules.STATUS_UNVERIFIED else VerifyRules.STATUS_VERIFIED,
            before = "",
            after = "",
            detail = detail,
        )
        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = injected))))
        addConversation("assistant", injected)
        if (failed) {
            log(AgentLog.Level.WARN, "看图追问失败：$question")
            pushFloating("看图追问失败", "ERROR")
        } else {
            log(AgentLog.Level.INFO, "看图追问返回：${text.take(200)}")
            pushFloating("看图追问已返回", "THINKING")
            recordProgress(step, action)
        }
        delay(200)
    }

    // ---- 决策链路模型配置已收口到 decision/DecisionConfig（重构批次 8），以下为转发 ----

    private fun mainSeesImage(s: AppSettings.Settings): Boolean = decisionConfig.mainSeesImage(s)

    private fun visionConfig(settingsVal: AppSettings.Settings, force: Boolean = false): VisionConfig? =
        decisionConfig.visionConfig(settingsVal, force)

    private fun reasoningConfig(settingsVal: AppSettings.Settings): ReasoningConfig? =
        decisionConfig.reasoningConfig(settingsVal)

    /**
     * 端点协议解析：按归一化 URL 匹配模型库端点的协议档位。
     * 未入库的地址（旧三槽遗留配置等）一律回落 OpenAI 兼容，行为与历史版本一致。
     */
    private fun protocolOf(baseUrl: String, settingsVal: AppSettings.Settings): ApiProtocol =
        ApiProtocol.fromId(
            settingsVal.endpoints.firstOrNull { it.id == AiClient.normalizeBaseUrl(baseUrl) }?.protocol,
        )

    /** 每步决策温度：失败越频繁越鼓励换思路（对应温度文档第三节，逻辑下沉 EngineRules） */
    private fun decisionTemperature(): Double = EngineRules.decisionTemperature(consecutiveFailures)

    /**
     * 判断动作是否属于"有副作用、需幂等保护"的操作（提交/发送/下单/支付/删除/发布等）。
     * 依据动作类型 + 目标 label/理由 中的触发词。
     */
    private fun isFinalSubmit(action: AgentAction, type: String): Boolean = EngineRules.isFinalSubmit(action, type)

    /** 幂等判定：当前页面是否已出现"完成成功"证据（避免重复执行副作用后再次触发） */
    private fun idempotencyDone(snapshot: ScreenSnapshot): Boolean = EngineRules.idempotencyDone(snapshot)

    // ---- 执行家族已收口到 execution/ExecutionCore（重构批次 7b），以下为转发 ----

    private suspend fun safeExecute(action: AgentAction, snapshot: ScreenSnapshot): com.phoneagent.engine.execution.VerifyResult =
        executionCore.safeExecute(action, snapshot)

    private suspend fun shellChannelAvailable(): Boolean = executionCore.shellChannelAvailable()

    private fun recordMetrics(decision: com.phoneagent.core.ai.AiDecision) = telemetry.recordMetrics(decision)

    private fun recordVisionMs(elapsedMs: Long) = telemetry.recordVisionMs(elapsedMs)

    private fun recordExecMs(elapsedMs: Long) = telemetry.recordExecMs(elapsedMs)

    private fun recordStepTrace(
        step: Int,
        sent: String,
        decision: com.phoneagent.core.ai.AiDecision,
        visionSource: String,
        visionModel: String,
        visionDescription: String,
        screenshot: android.graphics.Bitmap?,
    ) = telemetry.recordStepTrace(step, sent, decision, visionSource, visionModel, visionDescription, screenshot)

    private fun recordLocalStepTrace(step: Int, intent: AgentIntent) = telemetry.recordLocalStepTrace(step, intent)

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
        recordStep(step, action, VerifyRules.STATUS_VERIFIED, "", "", "已写入记忆")
        if (upsert == null) return
        emitMemoryEvent(upsert, "r$currentTaskId", step)
        // 记忆变了 → 作废素材缓存，让后续步骤立刻用上刚记下的信息
        memoryRawCache = null
        pushFloating("记住了：${content.take(20)}", "THINKING")
    }

    /**
     * 处理 AI 的 device_query 意图：就地读取本机信息，把结果作为"上一步结果"注入下一轮决策。
     *
     * 与 remember / MCP 技能同类——端侧代办、不触碰设备，因此不截图、不走执行通道、不做生效重试。
     */
    private suspend fun handleDeviceQuery(step: Int, action: AgentAction, messages: MutableList<ChatMessageDto>) {
        val kind = action.text?.trim().orEmpty().ifBlank { "all" }
        val filter = action.summary?.trim().orEmpty()
        val text = runCatching { deviceQueryText(kind, filter) }
            .getOrDefault("查询失败")
            .take(MAX_DEVICE_QUERY_OUTPUT)
        log(AgentLog.Level.INFO, "查询本机信息：kind=$kind filter=${filter.ifBlank { "-" }} → ${text.take(120)}")
        recordStep(step, action, VerifyRules.STATUS_VERIFIED, "", "", "查询结果：${text.take(200)}")
        val injected = "device_query（kind=$kind）查询结果：\n$text"
        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = injected))))
        addConversation("assistant", injected)
        recordProgress(step, action)
        pushFloating("已查到本机信息", "THINKING")
        delay(200)
    }

    /**
     * 处理 AI 的 say 意图：把一句话呈现在任务流里，并把用户可能的追问空间留在原地。
     *
     * 与 remember / device_query 同类——端侧代办、不触碰设备，因此不截图、不走通道、不做重试，
     * 也**不写 StepRecord**（这句话是一条对话，不是一步操作）。
     */
    private fun handleSay(step: Int, action: AgentAction) {
        val text = action.text.orEmpty().trim()
        if (text.isEmpty()) return
        log(AgentLog.Level.INFO, "AI 说话：${text.take(120)}")
        emitSayEvent(text, "r$currentTaskId", step)
        pushFloating(text.take(20), "THINKING")
    }

    /**
     * 处理 AI 的 show_agent 意图：把用户带回 Agent 页当面看结果。
     *
     * 与 remember / device_query / say 同类——端侧代办、不触碰用户设备，因此不截图、不走通道、不做重试，
     * 也**不写 StepRecord**（这一步的意义就是"请你过来看"，不是一次设备操作）。
     *
     * 带上 text 时先按 write_doc 的同一条通道落成文档（于是它既在任务流里留档，也能整屏通读），
     * 再拉起本应用切到 Agent 页；是否整屏展示由界面按"此刻有没有文档"自行决定。
     */
    private fun handleShowAgent(step: Int, action: AgentAction) {
        val text = action.text.orEmpty().trim()
        val title = action.summary.orEmpty().trim()
        val written = if (text.isNotEmpty()) {
            documentEngine?.writeDocument(text, title)?.takeIf { it.isNotBlank() }
        } else null
        log(
            AgentLog.Level.INFO,
            "引导用户回 Agent 页：${written?.let { "并展示文档 $it" } ?: "看已有结果"}",
        )
        // 没带正文时任务流里没东西可看，留一句话说明为什么把用户叫回来
        if (written == null) {
            emitSayEvent(title.ifBlank { "结果已经准备好，回 Agent 页看吧" }, "r$currentTaskId", step)
        }
        pushFloating(if (written != null) "已回到 Agent 页看结果" else "已回到 Agent 页", "THINKING")
        com.phoneagent.ui.MainActivity.showAgentPage(appContext, showDocument = written != null)
    }

    /**
     * 处理 AI 的 browse_* 意图：交给内置浏览器通道（[com.phoneagent.feature.browser.BrowserChannel]）执行。
     *
     * 与 remember / device_query / MCP 技能同类——端侧代办、不操控用户设备、不需要无障碍通道与坐标，
     * 失败原因一律回注给 AI 让它自己纠正（比如先 browse_open）。
     *
     * 与旧实现的区别：**判定与执行都在通道里**（含只读护栏与中文措辞），这里只负责
     * 记账（日志 / 悬浮窗 / 失败计数 / 回注消息）；步骤留档（StepRecord / 执行流）由调用方统一做，
     * 因此两条调用路径（主循环与用户引导）不会出现双重留档。
     *
     * @return 合成动作 + 验证结果，供调用方按既有链路留档（浏览器意图不产生 AgentAction，故这里只造一条用于留档）
     */
    private suspend fun handleBrowseIntent(
        step: Int,
        intent: AgentIntent,
        messages: MutableList<ChatMessageDto>,
    ): Pair<AgentAction, com.phoneagent.engine.execution.VerifyResult> {
        val op = intent.intent
        val label = browserChannel.label(op)
        log(
            AgentLog.Level.INFO,
            "内置浏览器：$op uri=${intent.uri ?: "-"} target=${intent.target?.value ?: "-"} text=${intent.text ?: "-"}",
        )
        _state.value = _state.value.copy(phase = AgentState.Phase.ACTING, message = "浏览器：$label")
        pushFloating("浏览器：$label", "ACTING")
        // 用于留档的合成动作：浏览器意图不经转译层，但调试面板/执行流仍要看得到"这一步在干什么"
        val record = AgentAction(
            type = op,
            uri = intent.uri,
            target = intent.target?.let { ActionTarget(method = it.by, value = it.value) },
            text = intent.text,
            direction = intent.direction,
            reasoning = intent.reasoning,
            reason = intent.reasoning ?: intent.reason,
        )
        val outcome = runCatching {
            browserChannel.execute(intent)
        }.getOrElse {
            com.phoneagent.feature.browser.BrowserChannel.Outcome.Failed(
                "浏览器操作异常：${it.message ?: it::class.simpleName}",
            )
        }
        val text = when (outcome) {
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Ok -> outcome.text
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Missing -> outcome.reason
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Refused -> outcome.reason
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Failed -> outcome.reason
        }.trim().take(com.phoneagent.feature.browser.BrowserBridge.MAX_RESULT_CHARS)
        val injected = when (outcome) {
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Ok ->
                "内置浏览器（$op）结果：\n$text"
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Missing ->
                "⚠️ 内置浏览器（$op）缺少必要参数：$text"
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Refused ->
                "⛔ 内置浏览器（$op）已被端侧拒绝：$text"
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Failed ->
                "内置浏览器（$op）失败：$text\n请按提示调整：" +
                    "需要打开网页就先用 browse_open；需要知道当前页有什么可以点、可以填什么就先 browse_read" +
                    "（正文里的链接文字与「可操作元素」清单都在里面，清单里的文字可原样用于 browse_click / browse_input）。"
        }
        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = injected))))
        addConversation("assistant", injected)
        when (outcome) {
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Ok -> {
                browseMissingStreak = 0
                consecutiveFailures = 0
                recordProgress(step, record)
                pushFloating("浏览器：$label 完成", "THINKING")
            }
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Missing -> {
                // 缺参属"AI 表述不完整"：只提示一次让它补全，连续多次仍不补全则收尾，避免无限追问
                browseMissingStreak++
                consecutiveFailures++
                log(AgentLog.Level.WARN, "浏览器意图缺参（第 $browseMissingStreak 次）：$op → ${text.take(120)}")
                pushFloating("浏览器：$label 缺参数", "THINKING")
            }
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Refused -> {
                // 只读护栏的拒绝是"设计如此"，不是工具坏了：明确告诉 AI 不要再重试同一个动作
                browseMissingStreak = 0
                consecutiveFailures++
                log(AgentLog.Level.WARN, "只读模式拒绝浏览器操作：$op → ${text.take(120)}")
                pushFloating("浏览器：$label 已按只读规则拒绝", "THINKING")
            }
            is com.phoneagent.feature.browser.BrowserChannel.Outcome.Failed -> {
                browseMissingStreak = 0
                consecutiveFailures++
                log(AgentLog.Level.WARN, "浏览器操作失败（第 $consecutiveFailures 次）：$op → ${text.take(120)}")
                pushFloating("浏览器：$label 未成功", "THINKING")
            }
        }
        if (browseMissingStreak >= MAX_BROWSE_MISSING_STREAK) {
            log(AgentLog.Level.ERROR, "连续 $browseMissingStreak 次浏览器意图缺参，停止任务：$text")
            _state.value = _state.value.copy(
                phase = AgentState.Phase.ERROR,
                message = "浏览器意图连续缺少必要参数，已停止任务",
            )
            pushFloating("浏览器意图缺参，已停止", "ERROR")
            finishTaskMemory(TaskMemoryEntry.STATUS_FAILED)
            stop()
        }
        delay(200)
        val verify = com.phoneagent.engine.execution.VerifyResult(
            success = outcome is com.phoneagent.feature.browser.BrowserChannel.Outcome.Ok,
            reason = if (outcome is com.phoneagent.feature.browser.BrowserChannel.Outcome.Ok) "" else text,
            beforeFingerprint = "",
            afterFingerprint = "",
        )
        return record to verify
    }

    /** 记忆写入事件入流（转发 [TelemetryRecorder.emitMemoryEvent]，重构批次 2） */
    private fun emitMemoryEvent(upsert: AiMemoryUpsert, runKey: String, step: Int) =
        telemetry.emitMemoryEvent(upsert, runKey, step)

    /** AI 说话事件入流（转发 [TelemetryRecorder.emitSayEvent]，重构批次 2） */
    private fun emitSayEvent(text: String, runKey: String, step: Int) =
        telemetry.emitSayEvent(text, runKey, step)

    // ==================== 任务会话归档（Agent 页侧边栏） ====================

    private fun startTaskSession(session: TaskSession) = telemetry.startTaskSession(session)

    private fun settleTaskSession(taskId: Long, cancelled: Boolean) = telemetry.settleTaskSession(taskId, cancelled)

    private fun updateTaskSession(taskId: Long, transform: (TaskSession) -> TaskSession) =
        telemetry.updateTaskSession(taskId, transform)

    /** 回载/清空会话归档（由持久化层调用） */
    private fun replaceTaskSessions(list: List<TaskSession>) = telemetry.replaceTaskSessions(list)

    /** 撤销一条刚写入的记忆：删库 + 从事件流移除（卡片随之消失） */
    suspend fun dismissMemoryEvent(id: Long) = telemetry.dismissMemoryEvent(id)

    /**
     * 本步要注入的（记忆简报, 经验规则简报）。
     *
     * 两段必须一起算，因为**同一条内容只允许注入一遍**：命中的经验规则会先从记忆候选里剔除，
     * 否则"在美团要先关掉首页弹窗"会既出现在记忆段又出现在规则段，白占上下文还显得自相矛盾。
     * 反过来，没命中的规则（作用域不符 / 置信度不够）不会被剔除，它的内容仍由记忆段兜住，信息不丢。
     *
     * 画像 + 记忆 + 规则每任务只读一次库并缓存（记忆被写入后缓存作废）；
     * 异常经验随页面变化，按页面指纹单独缓存，换页才重新查。
     */
    private suspend fun memoryAndRules(task: String, snapshot: ScreenSnapshot): Pair<String, String> =
        contextInjector.memoryAndRules(task, snapshot)

    private suspend fun anomalyHint(snapshot: ScreenSnapshot): String = contextInjector.anomalyHint(snapshot)

    private suspend fun pageMemoryHintFor(snapshot: ScreenSnapshot, hit: com.phoneagent.data.store.PageMemoryEntry?): String =
        contextInjector.pageMemoryHintFor(snapshot, hit)

    private suspend fun pageMemoryPlanningLines(task: String, lang: PromptLang): List<String> =
        contextInjector.pageMemoryPlanningLines(task, lang)

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
                protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal),
            ).getOrNull()
        } ?: return
        val items = parseDistilledMemories(reply)
        if (items.isEmpty()) return
        // 同一条提炼产物同时落两边：
        // - AI 记忆：全量注入，承担"用户画像 + 通用记忆"（客观事实类）；
        // - 经验规则：带作用域注入，承担"与某个应用/任务相关的做法类经验"。
        // 作用域由本次执行的前台应用分布推断（见 RuleScoper），推断不出来就退化成 GLOBAL。
        val scope = RuleScoper.inferScope(
            task,
            foregroundPkgCounts.toMap(),
            appContext.packageName,
            deviceFacts.launcherAppNames(),
        )
        items.forEach { item ->
            val upsert = runCatching {
                memory.upsertAiMemory(item.content, item.category, task, "distill", item.confidence)
            }.getOrNull() ?: return@forEach
            emitMemoryEvent(upsert, "r$currentTaskId", completedSteps)
            runCatching {
                memory.upsertEvolvedRule(
                    content = item.content,
                    kind = item.category,
                    scopeKind = scope.kind,
                    scopeValue = scope.value,
                    sourceTask = task,
                    confidence = item.confidence,
                )
            }
        }
        memoryRawCache = null
        log(AgentLog.Level.INFO, "记忆提炼完成：新增/更新 ${items.size} 条（作用域=${scope.kind}${scope.value})")
    }

    /** 解析提炼输出 {"memories":[{content,category,confidence}]}，格式解析见 [AgentResponseParser] */
    private fun parseDistilledMemories(raw: String): List<AgentResponseParser.DistilledMemory> =
        AgentResponseParser.parseDistilledMemories(raw)

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
            // 执行层记录与决策层记录（StepTrace）用同一个 taskId 归属到同一次执行，
            // 调试页「执行流」才能把「这一步怎么决定的」和「这一步怎么执行的」并到一张卡里
            taskId = currentTaskId,
            taskName = currentTaskName,
            action = action,
            verificationResult = verification,
            beforeFingerprint = before,
            afterFingerprint = after,
            isConfirmed = verification == VerifyRules.STATUS_VERIFIED,
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
        // 本方法只负责「留档展示」，与任务推进无关：截图或跨进程识别失败时整段放弃即可，
        // 不能让展示链路的问题把人拖进任务异常终止
        tryOrNull("步骤留档失败") {
            val snapshot = observe()
            val shot = tryOrNull("截图失败") { com.phoneagent.device.screen.ScreenCapture.capture() }
            var annotated: Bitmap? = null
            if (shot != null) {
                // 端侧控件识别已进程内化；这里调用引擎并画框（v2.2.1 截图标注）
                val controls = com.phoneagent.device.vision.OnDeviceVision.detectControls(
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
    }

    /** 将一步动作拼装成人类可读的执行说明（用 AI 的 reasoning/reason + 验证结果）；拼装逻辑下沉 VerifyRules */
    private fun actionDescription(action: AgentAction, verified: Boolean): String =
        VerifyRules.actionDescription(action, verified, actionLabel(action.type))

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
    private fun finishTaskMemory(status: String, conclusion: String = "") {
        val cur = currentTaskMemory ?: return
        if (cur.status != TaskMemoryEntry.STATUS_RUNNING) return
        mutateTaskMemory { it.withStatus(status, cur.completedSteps, conclusion) }
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

    private fun recordsIntoHistory(
        step: Int,
        action: AgentAction,
        verify: com.phoneagent.engine.execution.VerifyResult,
    ) {
        recordStep(
            step, action, VerifyRules.historyStatus(verify.success),
            verify.beforeFingerprint, verify.afterFingerprint, verify.reason,
        )
    }

    /**
     * 等待用户输入。
     *
     * 信箱是单槽缓冲（CONFLATED），且 [provideUserHint] / [dismissUser] 只在 [_needsUser] 为 true 时投递：
     * 用户抢在订阅之前输入的内容会被缓冲住，订阅一到就能取走，不会像原先 SharedFlow 那样被静默丢弃。
     */
    private suspend fun awaitUserHint(): String = userGate.awaitUserHint()

    private suspend fun observe(): ScreenSnapshot {
        // 无障碍服务可能在任务执行中途被系统回收/关闭，此时读取元素树会抛异常；
        // 观察是每一步的起点，异常必须降级为「空快照」而不是终止任务
        val snapshot = tryOrNull("读取无障碍元素树失败") {
            AgentAccessibilityService.instance?.captureScreen()
        } ?: ScreenSnapshot(
            missingAccessibility = true,
            // 无障碍不可用时也填充真实屏幕尺寸（供 ShellCommands 比例坐标换算）
            screenWidth = screenWidth(),
            screenHeight = screenHeight(),
        )
        _state.value = _state.value.copy(
            hasAccessibility = AgentAccessibilityService.instance != null,
            hasScreenshot = com.phoneagent.device.screen.ScreenCapture.available(),
        )
        return snapshot
    }

    // ---- 上下文注入已收口到 context/ContextInjector（重构批次 6），以下为转发 ----

    private fun screenWidth(): Int = contextInjector.screenWidth()
    private fun screenHeight(): Int = contextInjector.screenHeight()

    // 真实屏幕尺寸（不依赖无障碍服务）：直接问 WindowManager，取数细节见 [DeviceFacts]
    private fun realScreenWidth(): Int = contextInjector.realScreenWidth()

    private fun realScreenHeight(): Int = contextInjector.realScreenHeight()

    private fun installedAppList(): String = contextInjector.installedAppList()

    /** 累计前台应用出现次数（同一任务内），供经验规则推断作用域；本 App 自己不计入 */
    private fun noteForeground(pkg: String) = contextInjector.noteForeground(pkg)

    private fun envFacts(snapshot: ScreenSnapshot? = null) = contextInjector.envFacts(snapshot)

    private fun deviceQueryText(kind: String, filter: String) = contextInjector.deviceQueryText(kind, filter)

    private fun sessionContextText(task: String, lang: PromptLang) = contextInjector.sessionContextText(task, lang)

    // ---- 任务路由（Superagent 融合 Phase 4）：普通对话 / 编码委托 / 原操控链路 ----

    /**
     * 纯对话直答：跳过感知循环，主模型一次问答完成。回答写入对话区，并按现有
     * 「上一问一答」承接机制留档（[rememberChatTurn]）。不适用「≥3 步」完成判定。
     * 系统提示走 [AgentPrompts.chatSystem]：直答端侧不解析意图、回答原样展示，
     * 操控链路的铁律/意图表/动作模式/技能表一概不注入（注入了会把直答模型带偏
     * 去输出意图 JSON 而不是回答本身），只保留身份与直答规则。
     * TODO(Superagent Phase 5+): 换成主模型流式输出，把 token 增量实时推进对话区。
     */
    private suspend fun chatDirectAnswer(task: String) {
        chatStopRequested = false
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        val system = AgentPrompts.chatSystem(lang, settingsVal.systemPrompt)
        val answer = aiClient.chat(
            baseUrl = settingsVal.apiBaseUrl,
            apiKey = settingsVal.apiKey,
            model = settingsVal.model,
            messages = listOf(
                ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = system))),
                ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = task))),
            ),
            temperature = CHAT_TEMPERATURE,
            protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal),
        ).getOrElse { "抱歉，这次没有答上来（${it.message ?: "网络或模型异常"}）。请稍后再试。" }
        addConversation("assistant", answer)
        rememberChatTurn(task, answer)
        AgentStatusStore.update(AgentStatusStore.Snapshot(task = task, status = answer.take(80)))
        log(AgentLog.Level.INFO, "对话直答完成")
    }

    /**
     * 三形态路由（Superagent 融合 Phase 4）：在任务进入执行体前判定一次。
     * - Coding：命中编码词且置信度最高 → 委托设备端编码 agent（不进 ReAct 感知循环），
     *   在队列 worker 的 job 协程内挂起等待会话结束，保持「同一时刻只允许一个任务」语义；
     * - Chat：命中对话词且无任何其他形态命中 → 主模型直答一步完成；
     * - Agent：其余情况走原 runTask 链路，行为不变。
     * 多需求并存时按各词表命中词数取置信度最高者，并在时间线提示其余需求可再下一道指令。
     * 判定唯一走 [TaskKindDetector]，与提示词裁块（[TaskKindDetector.flagsOf]）同源。
     */
    private suspend fun routeTask(task: String): RouteDecision {
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        val termux = termuxBridge?.isAvailable() == true
        val scores = TaskKindDetector.scores(lang, task, termux)
        val kind = TaskKindDetector.detect(lang, task, termux)
        val codingHit = kind.codingHit
        val chatPure = kind.chatHit && !codingHit && !kind.docHit && !kind.openHit &&
            !kind.browseHit && !kind.fetchHit && !kind.assistantHit
        // 多需求并存（chat+coding、coding+其他操作类）：取命中词数最高者，时间线提示其余需求
        val maxOps = maxOf(scores.chat, scores.agentOps)
        val multiNeeds = codingHit && maxOps > 0
        if (multiNeeds) {
            val chosen = if (scores.coding >= maxOps) "编码任务" else "手机操控任务"
            log(AgentLog.Level.INFO, "检测到多种需求，已按$chosen 处理，其余可再下一道指令")
        }
        return when {
            codingHit && scores.coding >= maxOf(scores.chat, scores.agentOps) -> RouteDecision.Coding {
                stopCodingRequested = false
                try {
                    codingAgentBridge.prompt(task)
                } finally {
                    if (stopCodingRequested) {
                        codingAgentBridge.stop()
                        AgentStatusStore.update(AgentStatusStore.Snapshot(task = task, status = "已停止", done = true))
                    }
                }
            }
            chatPure -> RouteDecision.Chat {
                try {
                    chatDirectAnswer(task)
                } finally {
                    if (chatStopRequested) {
                        AgentStatusStore.update(AgentStatusStore.Snapshot(task = task, status = "已停止", done = true))
                    }
                }
            }
            else -> RouteDecision.Agent
        }
    }
}

/**
 * 待执行任务队列（线程安全）。
 *
 * 入队来自主线程（用户在界面提交任务），出队来自队列协程（Dispatchers.Default 调度器）。
 * 原先 AgentEngine 直接对 `MutableStateFlow.value` 做「读—改—写」，而读改写在并发时
 * 会互相覆盖，任务就此丢失；这里用一把锁把入队 / 出队 / 判空收敛为原子操作。
 */
/**
 * 解析某次调用应使用的 API 协议档位：按 baseUrl 在模型库端点表里查 protocol 字段
 * （用户在模型库编辑端点时选定），查不到（脏 URL / 端点已删）回落 OpenAI 兼容。
 */
internal fun protocolOf(baseUrl: String, settings: AppSettings.Settings): ApiProtocol =
    settings.endpoints.firstOrNull { it.id == ModelCatalogCodec.endpointId(baseUrl) }
        ?.let { ApiProtocol.fromId(it.protocol) }
        ?: ApiProtocol.OPENAI

internal class PendingTaskQueue {
    private val lock = Any()
    private val _items = MutableStateFlow<List<String>>(emptyList())

    /** 队列快照（供界面展示，只读） */
    val items: StateFlow<List<String>> get() = _items.asStateFlow()

    /** 追加一个待执行任务 */
    fun enqueue(task: String) {
        synchronized(lock) { _items.value = _items.value + task }
    }

    /** 取出队首任务；队列为空返回 null */
    fun poll(): String? = synchronized(lock) {
        val cur = _items.value
        if (cur.isEmpty()) null else {
            _items.value = cur.drop(1)
            cur.first()
        }
    }

    /** 是否还有待执行任务 */
    fun isNotEmpty(): Boolean = synchronized(lock) { _items.value.isNotEmpty() }
}

internal fun ActionExecutor.Result.isSuccess(): Boolean = this is ActionExecutor.Result.Success

/** 纯对话直答的采样温度（Superagent 融合 Phase 4）：略低于决策温度，稳定输出自然语言回答 */
private const val CHAT_TEMPERATURE = 0.3

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

/**
 * AI "对用户说话"的一条记录（引擎内存态），供 Agent 页在任务流里出对话气泡。
 * [id] 为任务内自增序号，用作列表 key（不能用内容哈希，AI 重复说同一句话会撞 key）。
 */
data class SayEvent(
    val id: Long,
    val text: String,
    val runKey: String,
    val step: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * AI 澄清歧义 → 用户选择 的一次往来（引擎内存态）。
 * 供 Agent 页在任务流里成组保留「问题 + 选择」，并在重规划时把两者一起注入上下文。
 */
data class ClarifyAnswered(
    val id: Long,
    val question: String,
    val answer: String,
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
    /**
     * 纯对话：不需要操作手机，AI 直接用一句话回答。
     * 这一阶段不请求批准、不进入执行，回答直接作为一条消息呈现（见 [settlePlanPhase]）。
     */
    data class Reply(val text: String) : PlanPhase()
    /** 已批准，开始执行 */
    data class Approved(val plan: TaskPlan?) : PlanPhase()
    /** 规划失败 */
    data class Error(val message: String) : PlanPhase()
}

// 视觉/思考模型配置已提为 internal 顶层类（见 DecisionModels.kt，重构批次 4）
