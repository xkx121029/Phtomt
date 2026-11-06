// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.engine

import android.graphics.Bitmap
import com.phoneagent.device.a11y.ActionExecutor
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ApiProtocol
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.engine.prompt.AgentHookRegistry
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.core.ai.ModelAbility
import com.phoneagent.core.ai.ModelCatalogCodec
import com.phoneagent.core.ai.VisionRouting
import com.phoneagent.data.prefs.AgentSurface
import com.phoneagent.data.prefs.AppSettings
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.phoneagent.domain.rules.EngineRules
import com.phoneagent.domain.rules.LocalDecisionEngine
import com.phoneagent.domain.rules.ShellCommands
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.DegradationTrigger
import com.phoneagent.engine.execution.AppNameResolver
import com.phoneagent.engine.execution.CapabilityManager
import com.phoneagent.engine.execution.IntentResolver
import com.phoneagent.engine.execution.IntentTranslator
import com.phoneagent.engine.execution.VerifyRules
import com.phoneagent.overlay.FloatingWindowService
import com.phoneagent.overlay.AgentStatusStore
import com.phoneagent.data.store.AiMemoryUpsert
import com.phoneagent.data.store.AnomalyMemoryEngine
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.data.store.ProfileLearner
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.engine.memory.PageMemoryRecorder
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import kotlinx.serialization.json.booleanOrNull
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.Clarification
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.domain.model.StepShot
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.engine.network.CloudAgent
import com.phoneagent.engine.perception.PageAnnotator
import com.phoneagent.engine.perception.PageFingerprint
import com.phoneagent.domain.rules.effectiveLabel
import com.phoneagent.device.screen.ScreenCapture
import com.phoneagent.core.security.DataSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.LoopGuard
import com.phoneagent.engine.runtime.TaskNoticeCenter
import com.phoneagent.engine.runtime.StepObserver
import com.phoneagent.engine.runtime.StepTexts
import com.phoneagent.engine.runtime.UiBridge
import com.phoneagent.engine.runtime.TelemetryRecorder
import com.phoneagent.engine.runtime.TaskLifecycle
import com.phoneagent.engine.runtime.EngineLimits.EXTERNAL_VISION_TIMEOUT
import com.phoneagent.engine.runtime.EngineLimits.MAX_BROWSE_MISSING_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_CONVERSATION_CHATS
import com.phoneagent.engine.docs.retrieveSafely
import com.phoneagent.engine.runtime.EngineLimits.MAX_DEVICE_QUERY_OUTPUT
import com.phoneagent.engine.runtime.EngineLimits.MAX_DECISION_TIMEOUT_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_DIALOG_TURNS
import com.phoneagent.engine.runtime.EngineLimits.MAX_EXECUTION_HISTORY
import com.phoneagent.engine.runtime.EngineLimits.MAX_PARAM_REFILL
import com.phoneagent.engine.runtime.EngineLimits.METHOD_DOC_STEP_TIMEOUT_MS
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
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
class AgentEngine(
    private val settings: AppSettings,
    private val aiClient: AiClient,
    private val appContext: android.content.Context,
    private val shizukuManager: com.phoneagent.device.shell.ShizukuManager? = null,
    /** 执行通路启动器（原 AdbBootstrapTransport 位置，迁移后由 ShizukuBootstrap 门面承担） */
    private val adbTransport: com.phoneagent.device.shell.ShizukuBootstrap? = null,
    private val termuxBridge: com.phoneagent.device.shell.TermuxBridge? = null,
    private val documentEngine: com.phoneagent.feature.document.DocumentEngine? = null,
    /** 技能执行网关：把 AI 输出的"技能名/技能 id"归一化为意图或 MCP 调用（见 [SkillCompat.normalize]） */
    private val skillGateway: com.phoneagent.feature.skill.SkillExecutionGateway? = null,
    /**
     * 记忆存储：AppModule 注入 Koin 单例，与记忆页/设置页读写的是**同一个实例**。
     *
     * 原实现引擎内自建一份，与 UI 各持一份互不相干的内存态（页面记忆每步解码、UI 删除后引擎读不到新版）；
     * 缺省时就地构造只作为构造点兜底（测试/独立构造场景），生产装配始终显式注入。
     */
    memoryStore: MemoryStore? = null,
    /**
     * 云端执行文档仓库：规划与每步决策按任务命中的文档注入标准操作流程（见 feature/agentdoc）。
     * 缺省 null（单测/独立构造场景），生产装配由 AppModule 注入。
     */
    private val docRepository: com.phoneagent.feature.agentdoc.AgentDocRepository? = null,
    /**
     * 方法文档检索器：动手前按任务描述查一次「这件事怎么做成」。
     * 线上接口未就绪时用空实现（查不到 → 提示词写明「没有对应方法，按常规判断」并继续）。
     */
    private val methodDocRetriever: com.phoneagent.engine.docs.MethodDocRetriever =
        com.phoneagent.engine.docs.EmptyMethodDocRetriever,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * 最近一次读到的设置快照。
     *
     * 规划入口是同步函数（UI 直接调用），不能在那里挂起读 DataStore；
     * 由 init 的订阅持续刷新，供配置预检这类"必须先同步判定"的场景使用。
     * null = 尚未读到，预检此时放行（不能因为没读到就拦住用户）。
     */
    @Volatile
    private var latestSettings: com.phoneagent.data.prefs.AppSettings.Settings? = null

    /** 设备与环境事实（应用清单 / 屏幕 / 网络 / 电量 / 存储）的读取入口，见 [DeviceFacts] */
    private val deviceFacts = DeviceFacts(appContext)

    /** 引擎运行期状态仓：全部 StateFlow 与任务级 var 收口于此（重构批次 1，见 [EngineStore]） */
    private val store = EngineStore()

    private val cloudAgent = CloudAgent(aiClient)
    private val localDecision = LocalDecisionEngine(selfPackage = appContext.packageName)
    private val memory = memoryStore ?: MemoryStore(appContext)

    /** 系统直连执行器（不操控 GUI；命令由端侧拼装，AI 只选 op 与参数） */
    private val deviceOpsChannel by lazy {
        com.phoneagent.device.ops.DeviceOpsChannel(
            context = appContext,
            shell = { cmd -> deviceShell(cmd) },
            memory = memory,
            virtualDisplay = { virtualDisplay },
            // 失败时把"卡在哪一环"直接告诉用户与 AI，而不是统一报「需要注入通道」
            channelAdvice = { channelAdvice() },
        )
    }
    private val anomalyEngine = AnomalyMemoryEngine(memory)
    private val profileLearner = ProfileLearner(memory)
    /** UI 桥：悬浮窗推送 + 日志环形缓冲 + 对话追加（重构批次 2，见 [UiBridge]） */
    private val uiBridge = UiBridge(appContext, settings, store)
    /** 遥测记录器：指标 / 轨迹 / 事件流 / 会话归档 / 调试持久化（重构批次 2，见 [TelemetryRecorder]） */
    private val telemetry = TelemetryRecorder(appContext, memory, store)
    /** 任务收尾：任务记忆终态 / 模板健康回写 / 记忆提炼（重构批次 8，见 [TaskFinalizer]） */
    private val finalizer = com.phoneagent.engine.runtime.TaskFinalizer(
        store, memory, aiClient, appContext, deviceFacts, telemetry, scope,
        log = { level, message, detail -> log(level, message, detail) },
    )
    /** AI 连接工具：翻译 / 连接测试 / 模型列表 / 能力探测（重构批次 3，见 [com.phoneagent.engine.connect.AiTools]） */
    private val aiTools = com.phoneagent.engine.connect.AiTools(settings, aiClient)
    /** 停止旗标（Superagent 融合 Phase 4）：stop() 置位，直答的挂起点轮询到即自行收尾 */
    @Volatile private var chatStopRequested = false
    /**
     * 意图判定缓存（任务原文 → 云端判定结果）。
     *
     * 同一句话会被判定两次：Agent 页提交时先判一次决定进哪个执行体，
     * 批准/派发时 [routeTask] 还要再判一次。缓存把第二次的云端请求省掉，
     * 也让「先判 chat 再派发」这条路径不会因为两次判定不一致而跑到另一条链路上。
     * 只缓存拿到明确结果的判定；失败降级不进缓存，下一轮还能重新问云端。
     */
    private val intentCache = java.util.concurrent.ConcurrentHashMap<String, TaskIntent>()
    /** 任务生命周期：队列 / worker / 单任务协程启停（重构批次 5，见 [TaskLifecycle]）；router 按云端意图分流 */
    private val lifecycle = TaskLifecycle(
        appContext, settings, scope, store, uiBridge,
        runTask = { task, plan -> run(task, plan) },
        safeReset = { taskId, message -> safeResetRuntime(taskId, message) },
        router = { task, chatOnly -> routeTask(task, chatOnly) },
        // 配置预检要同步拿到当前设置（start 是同步入口）；极早期读不到时返回 null，预检放行
        settingsSnapshot = { latestSettings },
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
        mutateTaskMemory = { transform -> finalizer.mutateTaskMemory(transform) },
        actionLabel = { type -> actionLabel(type) },
    )
    /** 页面记忆采集器：任务执行自动沉淀页面热点与跳转路径（内存快照 + 异步落库，见 PageMemoryRecorder） */
    private val pageMemoryRecorder = PageMemoryRecorder(memory, scope)
    /** 主循环护栏：广告自动点击 / 同签名动作熔断 / 无进展看门狗（重构阶段 A，见 [LoopGuard]） */
    private val loopGuard = LoopGuard()
    /** 观察段执行器：页面标注 / semanticId 回填 / 截图决策与复用（重构阶段 B1，见 [StepObserver]） */
    private val stepObserver = StepObserver(
        capture = { tryOrNull("截图失败") { captureScreenshot() } },
        log = { level, message -> log(level, message) },
        treeStats = { AgentAccessibilityService.instance?.lastTreeStats },
        mainSeesImage = { s -> mainSeesImage(s) },
    )
    /**
     * 观察阶段执行器（阶段 B2）：读屏 → 掉线检测 → 广告过滤 → 标注截图 → 敏感页 → 页面记忆。
     * 带 continue/return 的控制流统一返回 StepOutcome，主循环不再内联这些分支。
     */
    private val observationPhase = com.phoneagent.engine.loop.ObservationPhase(
        readScreen = { observe() },
        observer = stepObserver,
        loopGuard = loopGuard,
        a11yOnline = { AgentAccessibilityService.instance != null },
        clickPoint = { x, y ->
            AgentAccessibilityService.instance?.let { service ->
                com.phoneagent.device.a11y.ActionExecutor(service).click(x, y)
            }
        },
        virtualDisplayActive = { virtualDisplayActive },
        onPageObserved = { snapshot, annotated -> pageMemoryRecorder.onPageObserved(snapshot, annotated) },
        pageMemoryHintFor = { snapshot, hit -> pageMemoryHintFor(snapshot, hit) },
        updatePageMarks = { hit ->
            com.phoneagent.overlay.PageMarkOverlayService.update(appContext, hit?.hotspots.orEmpty())
        },
        bumpSensitiveTakeover = { ++sensitiveTakeoverStreak },
        hooks = com.phoneagent.engine.loop.ObservationHooks(
            log = { level, message -> log(level, message) },
            pushFloating = { status, phase -> pushFloating(status, phase) },
            showInteraction = { type, title, content -> showFloatingInteraction(type, title, content) },
            setMessage = { message -> _state.value = _state.value.copy(message = message) },
            setNeedsUser = { _needsUser.value = it },
            emitHintRequest = { reason -> _userHintRequest.tryEmit(reason) },
            awaitUserHint = { awaitUserHint() },
        ),
    )
    /**
     * 决策阶段执行器（阶段 B3）：宏注入 → 端侧/云端决策 → 技能归一化 → 动作模式门控。
     * 带 continue/return 的分支统一返回 StepOutcome；浏览器通道的落地留给执行阶段。
     */
    private val decisionPhase = com.phoneagent.engine.loop.DecisionPhase(
        localDecision = localDecision,
        loopGuard = loopGuard,
        skillGateway = skillGateway,
        skillRunner = skillRunner,
        macro = com.phoneagent.engine.loop.MacroRelay(
            active = { macroQueue.isNotEmpty() || macroName.isNotBlank() },
            next = { messages -> nextMacroIntent(messages) },
            name = { macroName },
            dispatched = { macroDispatched },
            start = { normalized ->
                macroName = normalized.skill.name
                macroArgs = normalized.args
                macroQueue = normalized.skill.steps
                macroResults = emptyList()
                macroDispatched = 0
            },
            abort = {
                macroQueue = emptyList()
                macroName = ""
            },
        ),
        hooks = com.phoneagent.engine.loop.DecisionHooks(
            log = { level, message -> log(level, message) },
            pushFloating = { status, phase -> pushFloating(status, phase) },
            markDecisionFatal = { message ->
                _state.value = _state.value.copy(
                    phase = AgentState.Phase.ERROR,
                    message = message,
                    failureKind = AgentState.FailureKind.DECISION,
                )
            },
            markModeFatal = { message ->
                _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = message)
            },
            markSkillFatal = { message ->
                _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = message)
            },
            notifyLocalLoop = {
                com.phoneagent.core.notify.ActiveNotifier.notify(
                    appContext, com.phoneagent.core.notify.ActiveNotifier.ID_LOCAL_LOOP,
                    "任务可能卡住了", "AI 已在同一页面反复做出相同判断，正准备强制调整路线，避免原地打转。",
                )
            },
            notifyNoProgress = {
                com.phoneagent.core.notify.ActiveNotifier.notify(
                    appContext, com.phoneagent.core.notify.ActiveNotifier.ID_NO_PROGRESS,
                    "AI 长时间没动静", "任务似乎卡住了，已延长检查时间；若仍未进展可用「已手动处理」接管。",
                )
            },
            pageMemoryOnIntent = { intent -> pageMemoryRecorder.onIntent(intent) },
        ),
        cloudDecide = { task, snapshot, annotated, messages, screenshot, settingsVal, pageMemoryHint ->
            cloudDecide(task, snapshot, annotated, messages, screenshot, settingsVal, pageMemoryHint)
        },
        clearImageCache = { aiClient.clearImageCache() },
        invokeVisionAsk = { step, vision, messages, screenshot, settingsVal ->
            invokeVisionAsk(step, vision, messages, screenshot, settingsVal)
        },
        recordLocalTrace = { step, intent -> recordLocalStepTrace(step, intent) },
        decisionFailureStreak = { decisionFailureStreak },
        setDecisionFailureStreak = { decisionFailureStreak = it },
        skillErrorStreak = { skillErrorStreak },
        setSkillErrorStreak = { skillErrorStreak = it },
        modeDenyStreak = { modeDenyStreak },
        setModeDenyStreak = { modeDenyStreak = it },
        actionMode = { activeActionMode },
        completedSteps = { completedSteps },
    )
    /**
     * 执行阶段执行器（阶段 B4）：浏览器分流 → 转译/补参 → 完成判定 → 端侧代办 → 确认门 →
     * 执行+验证重试 → 留档/回注 → 连续失败引导。带 continue/return 的分支统一返回 StepOutcome。
     */
    private val executionPhase = com.phoneagent.engine.loop.ExecutionPhase(
        loopGuard = loopGuard,
        currentPlan = { activePlan },
        ui = com.phoneagent.engine.loop.ExecutionUiHooks(
            log = { level, message -> log(level, message) },
            pushFloating = { status, phase -> pushFloating(status, phase) },
            state = { _state.value },
            setState = { state -> _state.value = state },
            showFloatingInteraction = { type, title, content -> showFloatingInteraction(type, title, content) },
            setNeedsUser = { need -> _needsUser.value = need },
            emitHintRequest = { reason -> _userHintRequest.tryEmit(reason) },
            awaitUserHint = { awaitUserHint() },
            pushThinking = { sent, delta -> pushThinking(sent, delta) },
            showDone = { text -> FloatingWindowService.showDone(text) },
            setAgentRunning = { running -> AgentAccessibilityService.agentRunning = running },
            addConversation = { role, content -> addConversation(role, content) },
        ),
        record = com.phoneagent.engine.loop.ExecutionRecordHooks(
            recordStep = { step, action, status, before, after, reason ->
                recordStep(step, action, status, before, after, reason)
            },
            recordsIntoHistory = { step, action, verify -> recordsIntoHistory(step, action, verify) },
            stepShotCapture = { step, action, verified -> stepShotCapture(step, action, verified) },
            recordProgress = { step, action -> recordProgress(step, action) },
            saveCheckpoint = {
                com.phoneagent.data.store.TaskStore.saveCheckpoint(
                    appContext, currentTaskName ?: "", activePlan,
                    completedSteps, totalPlannedSteps, currentTaskId,
                )
            },
            takeShellOutput = { lastShellOutput.also { lastShellOutput = "" } },
            recordAnomalyUse = { verified ->
                currentAnomalyEntry?.let { entry ->
                    runCatching { anomalyEngine.recordUse(entry, verified) }
                    currentAnomalyEntry = null
                }
            },
            learnSolution = { snapshot, actionType, description, solution ->
                runCatching {
                    profileLearner.learnAnomalySolution(
                        fingerprint = runCatching {
                            com.phoneagent.engine.perception.PageFingerprint.computeMeaningful(snapshot)
                        }.getOrDefault(""),
                        labels = snapshot.elements.mapNotNull { it.effectiveLabel() }.take(30),
                        anomalyType = "stuck_$actionType",
                        description = description,
                        solution = solution,
                        resolvedAction = actionType,
                        appPackage = snapshot.packageName.orEmpty(),
                    )
                }
            },
            pageMemoryOnIntent = { intent -> pageMemoryRecorder.onIntent(intent) },
            pageMemoryOnStepResult = { verified -> pageMemoryRecorder.onStepResult(verified) },
        ),
        device = com.phoneagent.engine.loop.ExecutionDeviceHooks(
            takeVisualCoordinate = { lastVisualCoordinate.also { lastVisualCoordinate = null } },
            translate = { intent, snapshot, carry -> intentTranslator.translate(intent, snapshot, carry) },
            fillMissingParam = { translation, intent, snapshot, settingsVal ->
                fillMissingParam(translation, intent, snapshot, settingsVal)
            },
            execute = { action, snapshot -> safeExecute(action, snapshot) },
            reobserve = { observe() },
            ensureShellApproval = { action -> ensureShellApproval(action) },
            awaitActionConfirmation = { action -> awaitActionConfirmation(action) },
            handleRemember = { task, step, action -> handleRemember(task, step, action) },
            handleDeviceQuery = { step, action, messages -> handleDeviceQuery(step, action, messages) },
            handleDeviceOps = { step, action, messages -> handleDeviceOps(step, action, messages) },
            handleSay = { step, action -> handleSay(step, action) },
            handleShowAgent = { step, action -> handleShowAgent(step, action) },
            browserHandles = { op -> browserChannel.handles(op) },
            handleBrowse = { step, intent, messages -> handleBrowseIntent(step, intent, messages) },
            decideWithUserHint = { messages, hint ->
                // 与原文一致：先按看门狗调一次；失败/认为完成再调一次，两次都无效返回 null
                val sv = taskSettingsVal ?: settings.settings.first()
                suspend fun once(): AgentIntent? = withVisionWatchdog(WATCHDOG_DECIDE_MS) {
                    cloudAgent.decideWithUserHint(
                        sv.apiBaseUrl, sv.apiKey, sv.model, messages, hint,
                        onDelta = { pushThinking(delta = it) },
                        protocol = protocolOf(sv.apiBaseUrl, sv),
                    ).getOrNull()
                }
                var guided = once()
                if (guided == null || guided.intent == IntentType.FINISH || guided.intent == IntentType.GIVE_UP) {
                    guided = once()
                }
                guided
            },
            actionMode = { activeActionMode },
            idempotencyDone = { snapshot -> idempotencyDone(snapshot) },
            a11yDisconnectedNote = {
                if (AgentAccessibilityService.instance == null) "（无障碍服务已断开）" else ""
            },
        ),
        finish = com.phoneagent.engine.loop.ExecutionFinishHooks(
            finishTaskMemory = { status, note ->
                if (note != null) finalizer.finishTaskMemory(status, note)
                else finalizer.finishTaskMemory(status)
            },
            learnTemplate = { finalizer.learnTemplate() },
            clearCheckpoint = { com.phoneagent.data.store.TaskStore.clearCheckpoint(appContext) },
            onTaskCompleted = { doneSummary ->
                val planSteps = activePlan?.steps?.size ?: 0
                if (reusedTemplateId == null && planSteps > 0) {
                    pendingTemplateTask = store.lastFullTask
                    pendingTemplatePlan = activePlan
                    requestConfirmSaveTemplate()
                } else {
                    FloatingWindowService.showDone(doneSummary)
                }
            },
            distillMemories = { summary ->
                val sv = taskSettingsVal
                if (sv != null) finalizer.distillMemories(store.lastFullTask ?: "", summary, sv)
            },
            stopEngine = { stop() },
        ),
        counters = com.phoneagent.engine.loop.ExecutionCounters(
            earlyDoneRejections = { earlyDoneRejections },
            setEarlyDoneRejections = { earlyDoneRejections = it },
            sayStreak = { sayStreak },
            setSayStreak = { sayStreak = it },
            consecutiveFailures = { consecutiveFailures },
            setConsecutiveFailures = { consecutiveFailures = it },
            invalidCommandStreak = { invalidCommandStreak },
            setInvalidCommandStreak = { invalidCommandStreak = it },
        ),
    )
    /** 本次任务起跑时的设置快照（执行阶段做记忆提炼时用，保证与决策同源） */
    private var taskSettingsVal: AppSettings.Settings? = null
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
    private val assistantDetector = com.phoneagent.provider.a11y.VoiceAssistantDetector(appContext)
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
        // 配置预检要同步拿到当前设置：读不到（极早期）时返回默认值，让预检不阻断正常流程
        settingsSnapshot = { latestSettings ?: com.phoneagent.data.prefs.AppSettings.Settings() },
        docRepository = docRepository,
        methodDocRetriever = methodDocRetriever,
    )
    /** 执行核心：带验证执行 / shell 通道 / 无障碍端点直调（重构批次 7b，见 [com.phoneagent.engine.execution.ExecutionCore]） */
    private val executionCore = com.phoneagent.engine.execution.ExecutionCore(
        appContext, settings, shizukuManager, adbTransport, termuxBridge, documentEngine,
        intentResolver, appNameResolver, store, uiBridge, contextInjector,
        recordExecMs = { ms -> recordExecMs(ms) },
        observe = { observe() },
        // 副屏模式：只有本任务真正挂上副屏时动作才走副屏通道
        virtualDisplay = { if (virtualDisplayActive) virtualDisplay else null },
        // 执行通道偏好复用引擎持续缓存的设置快照，避免每条 shell 命令都挂起读 DataStore
        settingsSnapshot = { latestSettings },
    )
    /** 副屏模式代理（仅 Shizuku 通道可用时才有实例，见 [com.phoneagent.device.screen.VirtualDisplayAgent]） */
    private val virtualDisplay: com.phoneagent.device.screen.VirtualDisplayAgent? by lazy {
        shizukuManager?.let { com.phoneagent.device.screen.VirtualDisplayAgent(it) }
    }
    /** 本任务是否在副屏上运行：`ensure()` 成功才为 true，失败自动降级主屏模式 */
    private var virtualDisplayActive = false
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

    /** 合并形态：本对话内的纯对话问答（问题 + 回答），Agent 页据此出气泡 */
    private val _chatTurns by store::chatTurns
    val chatTurns: StateFlow<List<com.phoneagent.engine.runtime.ChatTurnEvent>> get() =
        _chatTurns.asStateFlow()

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

    companion object {
        /** 暂停态的状态条文案：暂停与"在思考"必须一眼可分，否则用户会以为任务卡住 */
        const val PAUSED_NOTE = "任务已暂停，点「继续」从当前步接着跑"
    }

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
    /** 连续「单步决策看门狗超时」次数：网络长期不通时用来收尾，见 cloudDecide */
    private var decisionTimeoutStreak = 0
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
    private fun lastStepResultText(lang: PromptLang): String =
        StepTexts.lastStepResult(_executionHistory.value, lang)

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
            // 走到这里 message 非空就是"异常终止"，归为未分类失败：失败卡会给「重试」出口
            failureKind = AgentState.FailureKind.UNKNOWN,
            retryingNote = "",
        )
        FloatingWindowService.stop(appContext)
        runCatching { com.phoneagent.overlay.CursorOverlayService.hide() }
        runCatching { com.phoneagent.overlay.PageMarkOverlayService.hide() }
        // 副屏随任务生命周期收尾：残留的 overlay_display_devices 会让系统一直挂着一块黑屏
        scope.launch { releaseVirtualDisplay() }
    }

    private fun apiLog(request: String, response: String, latencyMs: Long) =
        uiBridge.apiLog(request, response, latencyMs)

    private fun addConversation(role: String, content: String, hasImage: Boolean = false) =
        uiBridge.addConversation(role, content, hasImage)

    fun clearDebug() = uiBridge.clearDebug()

    private fun persistDebug() = telemetry.persistDebug()

    init {
        // 回载上次的调试记录（日志/轨迹/执行历史/对话/会话归档）。
        // **必须放在构造之后异步做**：这份存档随使用时长增长（上限 800 条日志 + 300 张缩略图），
        // 实测可达数 MB、回载要几百毫秒到数秒；同步放在这里会把首帧一起拖住
        // （冷启动时引擎在首帧的组合里构建）。异步回载与启动后新产生的数据按时间合并，见 TelemetryRecorder。
        scope.launch(Dispatchers.IO) { telemetry.loadPersistedDebug() }
        // 持续缓存设置快照：供同步入口（规划预检）读取，避免在那里挂起读 DataStore
        scope.launch {
            settings.settings.collect { latestSettings = it }
        }
        // 悬浮窗交互回调挂在引擎上而不是 ViewModel：引擎是单例、随进程存活，
        // 界面销毁后后台任务仍能在悬浮窗上点「停止 / 批准 / 澄清」。
        // 旧实现挂在 MainViewModel.init，onCleared 一清空，后台任务就再也收不到这些操作。
        FloatingWindowService.onInteraction = { action, payload ->
            when (action) {
                "approve" -> approvePlan()
                "cancel" -> cancelPlanning()
                "clarify" -> answerClarification(
                    com.phoneagent.domain.model.ClarificationOption(
                        id = payload,
                        label = payload,
                        description = payload,
                    ),
                )
                "hint" -> provideUserHint(payload)
                "dismiss" -> dismissUser()
                // 自由模式：AI 自写命令的首次确认（与 hint/dismiss 分开，见 resolveShellApproval 的说明）
                "shell_approve" -> resolveShellApproval(payload == "yes")
                // 任务完成：用户确认是否保存执行模板（主动确认才入库）
                "save_template" -> confirmSaveTemplate(payload == "yes")
                // 关闭悬浮窗 → 同步停止正在运行的任务（用户显式操作）
                "close" -> stop()
                // 收起悬浮窗（只留悬浮球）→ 搁置任务；点悬浮球唤出 → 从当前步继续
                "hide" -> pauseTask()
                "resume" -> resumeTask()
            }
        }
    }

    /** 提交任务到队列并开始处理（转发 [TaskLifecycle.start]，重构批次 5） */
    fun start(task: String) = lifecycle.start(task)

    /**
     * 重试最近一次任务：把它的**完整原文**重新提交一次。
     *
     * 失败卡上的「重试」走这里。任务原文由 [EngineStore.lastFullTask] 保留，
     * 不复用 [currentTaskName]（只留 60 字，重跑会丢需求细节）。
     * 没有可重试的任务时是空操作。
     */
    fun retryLastTask() {
        val task = store.lastFullTask
        if (task.isBlank()) return
        log(AgentLog.Level.INFO, "用户选择重试上一次任务")
        start(task)
    }

    /**
     * 对话页提交（对话模式）：只作答、不执行。
     *
     * 与 [start] 的唯一区别是带上了「对话页提交」标记，路由侧据此强制走纯对话直答，
     * 绝不分流到 Coding / Agent 执行体——对话页里说一句话不该开启任务执行模式
     * （不设 isRunning、不开悬浮窗 / 跑马灯、不亮边缘光效、不碰无障碍操控）。
     * 需要实操手机的请求由直答如实说明，并请用户到 Agent 页下达（见 chatSystem）。
     */
    fun chatTurn(task: String) = lifecycle.start(task, chatOnly = true)

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
        // 合并形态的时间线事件同属本对话：上一段对话的问答不再铺开
        _chatTurns.value = emptyList()
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

    /**
     * 开始规划：AI 流式思考并检测歧义。不直接执行。
     *
     * 合并形态（[AgentSurface.UNIFIED]）下，Agent 页是操控 + 对话的统一工作台：
     * 先交由云端判定意图，纯对话直接作答，其余才进手机操控的规划流。
     * 分开形态保持原样——规划流自己识别纯对话（[PlanPhase.Reply]），对话与编码各有独立页面。
     * 编码两种形态下都不经这里：Coding 页直接驱动编码 agent。
     */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun startPlanning(task: String) {
        if (task.isBlank()) return
        if (!unifiedSurface()) {
            planningFlow.startPlanning(task)
            return
        }
        // 配置缺失交给规划流：它会把原因写成一张可操作的失败卡（含「去配置」出口），
        // 而不是在这里静默吞掉
        if (latestSettings?.configProblem != null) {
            planningFlow.startPlanning(task)
            return
        }
        // 判定要调一次云端（约一秒），先给用户一声"在识别意图"的回显，避免提交后一片空白
        store.pendingTask = task
        store.sessionContextCache = null
        _sayEvents.value = emptyList()
        _clarifyEvents.value = emptyList()
        _planStream.value = "正在识别你的意图…"
        _planPhase.value = PlanPhase.Planning
        scope.launch {
            when (classifyIntent(task)) {
                // 对话：走队列的对话通道（只作答、不执行，不开悬浮窗/跑马灯）
                TaskIntent.CHAT -> {
                    _planPhase.value = PlanPhase.Idle
                    _planStream.value = ""
                    lifecycle.start(task, chatOnly = true)
                }
                // 操控：照旧进规划流（它会重新置 Planning 并清空这段回显）
                TaskIntent.AGENT -> planningFlow.startPlanning(task)
            }
        }
    }

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
        // 直答会话的停止旗标（Superagent 融合 Phase 4）：直答不走 ReAct 主循环，
        // cancelWorker 取消 job 协程同样能打断它，但旗标能让挂起点先做状态复位（状态中心清零）
        chatStopRequested = true
        lifecycle.cancelWorker()
        // 用户主动停止：任务记忆收尾为「已中断」，否则记忆页会永远显示「进行中」。
        // 没有进行中的任务时 finishTaskMemory 内部直接返回，不会凭空写库
        finalizer.finishTaskMemory(TaskMemoryEntry.STATUS_ABORTED)
        // 停止即彻底结束：搁置标志必须一并清掉，否则下一个任务会带着上一个任务的搁置态起不来
        store.setPaused(false)
        // 停止时若正卡在「等待确认自写命令」，把待确认命令一并清掉：
        // 否则面板已收，协助面板仍挂着一个再也等不到答复的确认框
        _pendingShellCommand.value = null
        // 不可逆动作确认门同理：停止后不能再挂着一个等不到答复的确认面板
        _pendingActionConfirmation.value = null
        // 先把状态落到 IDLE 再走统一复位：safeResetRuntime 带 DONE 终态守卫，
        // 而「停止」的语义就是立刻回到空闲，即使任务刚好完成也要收起面板。
        // failureKind 只在 ERROR 相位有意义：这里落 IDLE，顺手清掉上一轮的分类，
        // 否则下一个任务若因别的原因失败，界面可能读到残留分类
        _state.value = _state.value.copy(
            isRunning = false,
            phase = AgentState.Phase.IDLE,
            retryingNote = "",
            failureKind = AgentState.FailureKind.UNKNOWN,
        )
        safeResetRuntime(null, null)
        // 复用的截图缓存必须随任务结束释放：一张全屏位图动辄 8MB 原生内存，
        // 留在引擎单例上等于任务结束后还占着不放
        stepObserver.reset()
        log(AgentLog.Level.WARN, "任务已停止")
    }

    // ---- 用户协作门已收口到 runtime/UserGate（重构批次 7），以下为转发 ----

    /** 当前任务是否处于用户暂停（搁置）态，供状态条切换「暂停 / 继续」 */
    val paused: StateFlow<Boolean> get() = store.pausedState.asStateFlow()

    /** 用户搁置任务（悬浮窗上的「隐藏」按钮 / App 内状态条的「暂停」）：把执行挂起 */
    fun pauseTask() {
        if (store.taskPaused) return
        store.setPaused(true)
        // 搁置是用户主动行为，界面上必须看得见：否则状态条仍显示「第 N 步」，用户以为还在跑
        store.state.value = store.state.value.copy(message = PAUSED_NOTE)
        pushFloating("已暂停", "OBSERVING")
        log(AgentLog.Level.INFO, "任务已搁置：等待用户恢复")
    }

    /** 用户恢复：解除搁置，任务从当前步继续 */
    fun resumeTask() {
        if (!store.taskPaused) return
        store.setPaused(false)
        // 撤掉暂停提示：主循环下一轮会重新写回真实阶段文案
        if (store.state.value.message == PAUSED_NOTE) {
            store.state.value = store.state.value.copy(message = "")
        }
        pushFloating("继续执行", "OBSERVING")
        log(AgentLog.Level.INFO, "用户已恢复任务，从当前步继续执行")
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
            store.setPaused(false)
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
            if (mem != null) finalizer.finishTaskMemory(mem.taskId, TaskMemoryEntry.STATUS_FAILED)
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

    /**
     * 把「属于一次任务」的内存状态全部归零。
     *
     * 这些字段原先散落在 [runInner] 开头，几十行首尾无关的手工赋值；新增任务级字段时
     * 很容易漏掉一处，而漏掉的表现就是上一任务的残留串进下一任务（旧的已批准命令、
     * 旧的视觉坐标、旧的会话上下文）。收成一个方法后，加字段只需要改这里。
     *
     * 注意：依赖入参的状态（计划本身、步数上限）不在此处，留在 [runInner] 里按 plan 赋值。
     */
    private fun resetTaskScopedState() {
        // AI 上下文与各类连续计数
        _conversation.value = emptyList()
        consecutiveFailures = 0
        earlyDoneRejections = 0
        invalidCommandStreak = 0
        skillErrorStreak = 0
        browseMissingStreak = 0
        decisionFailureStreak = 0
        decisionTimeoutStreak = 0
        lastShellOutput = ""
        lastSkillOutput = ""
        // 宏接力状态随任务复位：上个任务没跑完的宏步骤绝不串到本任务
        macroQueue = emptyList()
        macroArgs = emptyMap()
        macroResults = emptyList()
        macroName = ""
        macroDispatched = 0
        lastSnapshot = ScreenSnapshot()
        // 页面记忆：清上一任务的内存快照与提示缓存（DataStore 里的历史记忆保留，供本任务命中参考）
        pageMemoryRecorder.reset()
        pageMemoryHintFingerprint = ""
        pageMemoryHintText = ""
        // 视觉缓存随任务复位：上一个任务的视觉产物（含任务目的性提问的描述）不带入本任务
        visionCache.invalidate()
        // 主循环护栏（广告点击 / 同签名熔断 / 无进展看门狗 / 端侧决策节流）按任务归零
        loopGuard.reset()
        // 端侧决策引擎的连续本地计数也按任务复位：不清的话上一任务结尾的高计数
        // 会让新任务开头被强制走一次云端（防死循环计数跨任务残留）
        localDecision.reset()
        sensitiveTakeoverStreak = 0
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
        lastDistilledTaskId = -1L
        memoryRawCache = null
        // 前台应用计数与规则命中计数同样按任务归零：它们描述的是"这一次任务"，跨任务累计会算错作用域
        foregroundPkgCounts.clear()
        touchedRuleIds.clear()
        // 环境与会话上下文按任务重算：应用数量可能变了，上一轮任务清单也变了
        deviceFacts.resetAppCache()
        sessionContextCache = null
    }

    private suspend fun runInner(
        task: String,
        plan: TaskPlan? = null,
        onMemoryCreated: (TaskMemoryEntry) -> Unit = {},
    ) {
        // 每次任务开始生成独立任务 ID，用于分任务日志查看与导出
        currentTaskId = System.currentTimeMillis()
        currentTaskName = task.take(60)
        // 完整原文单独留一份：「重试」要原样重跑，不能拿截断过的标题
        store.lastFullTask = task
        // 每次任务开始彻底清空 AI 上下文：对话历史、失败/提前完成/无效命令计数、“上一条 shell 输出”、
        // 上一页截图快照与上一任务计划，避免上一个任务的指令/决策/命令串扰本任务（防止 AI 沿用不存在/失效的命令）
        resetTaskScopedState()
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
        // 读取执行策略（v2.2 7）：记录当前模式，供任务标签区分
        val strategy = runCatching { com.phoneagent.data.store.TaskStore.getStrategy(appContext) }
            .getOrDefault(com.phoneagent.data.store.ExecutionStrategy.AUTO)
        log(AgentLog.Level.INFO, "任务开始：$task")
        log(AgentLog.Level.INFO, "执行策略=${strategy.name}")
        val settingsVal = settings.settings.first()
        // 任务级设置快照：执行阶段做记忆提炼/引导决策时复用同一份，避免中途读到的设置漂移
        taskSettingsVal = settingsVal
        // 动作模式：整个任务期间固定不动（每步读同一个值，档位不会中途漂移）
        activeActionMode = ActionMode.fromKey(settingsVal.actionMode)
        log(AgentLog.Level.INFO, "动作模式=${activeActionMode.label}（${activeActionMode.key}）")
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        currentLang = lang
        // 方法文档的注入分两处，刻意不在这里预取：
        // - 规划阶段在 PlanningFlow 里按任务描述检索（计划要按方法文档走）；
        // - 执行阶段在每步决策里按「任务 + 当前前台应用」检索（见 cloudDecide），
        //   页面状态变化时能换到对应应用的方法文档，且只注入紧凑块。
        // 这里预取会在两个阶段之外多查一次、并把整块文档塞进每一步，预算不可控。
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

        // 副屏模式：任务全程在模拟副屏上进行（不抢用户前台）。
        // 创建失败一律降级为主屏模式继续执行 —— 副屏是增强能力，不能成为任务的前置阻断
        virtualDisplayActive = prepareVirtualDisplay(settingsVal)
        if (virtualDisplayActive) {
            messages.add(
                ChatMessageDto(
                    role = "system",
                    content = listOf(
                        ContentPart(
                            type = "text",
                            text = if (lang == PromptLang.CN) {
                                "【副屏模式】任务运行在独立副屏（1080x1920）上：观察以副屏无障碍元素树为主；" +
                                    "截图可用时会附上，不可用时会明确告知且不带截图（不会拿主屏画面冒充副屏）。" +
                                    "所有触摸动作必须给坐标 —— click/long_click 用 target={method:\"coordinate\",value:\"比例x,比例y\"}（0~1 之间）；" +
                                    "输入文字前先点击输入框使其获得焦点；启动应用仍用 launch（包名）。"
                            } else {
                                "[Virtual display mode] The task runs on a separate virtual display (1080x1920): observation relies primarily on the accessibility tree of that display. " +
                                    "A screenshot is attached only when actually available from the virtual display — never a main-screen capture in disguise. " +
                                    "All touch actions must carry coordinates — click/long_click use target={method:\"coordinate\",value:\"ratioX,ratioY\"} (0..1). " +
                                    "Tap an input field before typing. Launch apps with launch (package name)."
                            },
                        ),
                    ),
                ),
            )
            log(AgentLog.Level.INFO, "副屏模式已启用：任务在模拟副屏上运行（display=${virtualDisplay?.displayId}）")
        }

        val maxSteps = settingsVal.maxSteps
        var step = 0
        // 无障碍服务在本任务中是否观测到过在线：用于识别"任务中途掉线"（vivo 安全守护清除授权等）。
        // 服务一旦中途掉线，读屏与操作通道全断，继续跑只会变成"读不到→反复重启应用"的盲循环（真机 BUG 22）
        var a11ySeenAlive = false
        AgentAccessibilityService.agentRunning = true
        _state.value = AgentState(
            isRunning = true,
            task = task,
            phase = AgentState.Phase.OBSERVING,
            startedAtMillis = System.currentTimeMillis(),
        )
        maybeStartFloating()
        pushFloating("开始执行", "OBSERVING")
        // 主循环壳（阶段 B2）：搁置等待 / 取消检查 / 步数推进 / 重试回退 / 失败与最大步数收尾
        // 全部收口到 TaskRunner；每轮固定三步（观察 → 决策 → 执行），各自返回 StepOutcome。
        val taskRunner = com.phoneagent.engine.loop.TaskRunner(
            awaitResume = { awaitResume() },
            isActive = { coroutineContext.isActive },
            onStepStart = { s ->
                _state.value = _state.value.copy(
                    phase = AgentState.Phase.OBSERVING,
                    stepCount = s,
                    hasAccessibility = AgentAccessibilityService.isServiceEnabled(appContext),
                )
                pushFloating("正在观察屏幕", "OBSERVING")
            },
            onStepRolledBack = { s ->
                _state.value = _state.value.copy(stepCount = s.coerceAtLeast(0))
            },
            onFatal = { outcome ->
                finalizer.finishTaskMemory(TaskMemoryEntry.STATUS_FAILED, outcome.taskMemoryNote)
                stop()
            },
            onMaxSteps = {
                _state.value = _state.value.copy(
                    phase = AgentState.Phase.ERROR,
                    message = "达到最大步数限制",
                    failureKind = AgentState.FailureKind.MAX_STEPS,
                )
                log(AgentLog.Level.WARN, "达到最大步数限制，自动停止")
                pushFloating("达到最大步数限制", "ERROR")
                // 收尾段（阶段 B4）：模板健康回写 + 任务记忆失败终态 + 失败路径记忆提炼已迁到 TaskFinalizer
                finalizer.finishOnMaxSteps(task, settingsVal)
                stop()
            },
        )
        // 单步编排（阶段 B2）：观察 → 决策 → 执行，任一阶段返回非 Continue 即短路。
        // - 观察（B2）：读屏 / 掉线检测 / 广告过滤 / 标注截图 / 敏感页 / 页面记忆
        // - 决策（B3）：宏注入 / 端侧优先 / 云端决策 / 技能归一化 / 动作模式门控
        // - 执行（B4）：浏览器分流 / 转译补参 / 执行与验证重试 / 留档回注 / 完成与放弃收尾
        // 阶段产物（快照/标注图/语义快照/截图/页面记忆提示）由 StepSession 承载，交给后续阶段复用。
        val stepRunner = com.phoneagent.engine.loop.StepRunner(
            observe = com.phoneagent.engine.loop.StepPhase { session ->
                val outcome = observationPhase.run(session)
                // 观察段只要没失败就回写两项跨步任务态（与迁移前一致）：
                // - a11ySeenAlive：迁移前在无障碍检查处就地赋值，这里在段尾统一回写，效果相同
                // - lastSnapshot：迁移前在广告过滤后赋值；唯一的差异是「广告自动关闭后提前进入
                //   下一轮」时这里会多写一次，而写的是同一份屏幕快照，对屏幕尺寸读取无影响
                if (outcome == com.phoneagent.engine.loop.StepOutcome.Continue) {
                    a11ySeenAlive = session.a11ySeenAlive
                    lastSnapshot = session.snapshot
                }
                outcome
            },
            decide = decisionPhase,
            execute = executionPhase,
        )
        taskRunner.run(maxSteps) { s ->
            val session = com.phoneagent.engine.loop.StepSession(
                task = task,
                plan = plan,
                step = s,
                settingsVal = settingsVal,
                lang = lang,
                messages = messages,
            ).also { it.a11ySeenAlive = a11ySeenAlive }
            stepRunner.run(session)
        }

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

    // 云端决策把重试/看门狗/工具编排收在一处，结构完整、拆分风险高，此处按规则豁免
    @Suppress("ComplexCondition", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
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
        // 云端执行文档：任务命中的标准操作流程。规划阶段已注入过一次，这里用**当前前台包名**再匹配一次——
        // 任务中途才切进目标应用（比如先在外卖 App 下单、跳到支付宝付款）时也能拿到对应手册。
        // 正文有磁盘缓存，正常情况下是本地读取，不增加决策延迟；离线/失败有一分钟冷却（见 AgentDocRepository）。
        val docHint = if (settingsVal.docSyncEnabled) {
            runCatching { docRepository?.docsFor(task, snapshot.packageName, settingsVal) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { AgentPrompts.docsBlock(currentLang, it) }
                .orEmpty()
        } else ""
        // 执行期方法文档：按「任务 + 当前前台应用」在本地检索（离线可用，网络只做后台增量同步）。
        // 前台包名就是当前的页面状态——任务中途切进目标应用时，能换到那个应用的方法文档。
        // 命中才注入紧凑块（预算 450 字符）；第一步没命中时明确说一次"没有方法文档"，
        // 之后不重复刷屏（规划阶段已经说过一遍，执行期只做与当前页面相关的增量提示）。
        var stepDocs = methodDocRetriever.retrieveSafely(
            task = task,
            foregroundPackage = snapshot.packageName,
            timeoutMs = METHOD_DOC_STEP_TIMEOUT_MS,
            limit = 2,
        )
        // 任务描述本身太笼统时（比如「帮我处理一下」），补一次「当前页面语境」检索；
        // 只在首次没命中时补查，避免把页面噪声词混进正常检索把结果带偏。
        if (stepDocs.isEmpty() && annotated.contextHint.isNotBlank()) {
            val pageContext = DataSanitizer.sanitize(annotated.contextHint).take(30)
            if (pageContext.isNotBlank()) {
                stepDocs = methodDocRetriever.retrieveSafely(
                    task = "$task $pageContext",
                    foregroundPackage = snapshot.packageName,
                    timeoutMs = METHOD_DOC_STEP_TIMEOUT_MS,
                    limit = 2,
                )
            }
        }
        if (_state.value.stepCount == 0) {
            if (stepDocs.isEmpty()) {
                log(AgentLog.Level.INFO, "方法文档：没有对应方法，按常规探索继续")
            } else {
                log(AgentLog.Level.INFO, "方法文档：命中 ${stepDocs.size} 篇（${stepDocs.first().summary.take(40)}）")
            }
        }
        val methodHint = when {
            stepDocs.isNotEmpty() -> AgentPrompts.methodDocStepBlock(currentLang, stepDocs)
            _state.value.stepCount == 0 -> AgentPrompts.methodDocNoMatchBlock(currentLang)
            else -> ""
        }
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
        ) + planNote + docHint + methodHint + "\n\n## 当前页面\n$pageText" +
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
                onRetry = { reason, waitMs ->
                    FloatingWindowService.resetThinking()
                    // 重试会重放文本，流式回显同步清零，避免界面叠加两遍
                    _decisionStream.value = ""
                    // 把「在等什么、等多久」透到状态条：否则用户只看到界面停住，以为卡死
                    val note = if (waitMs > 0) "$reason，${(waitMs / 1000).coerceAtLeast(1)} 秒后重试" else reason
                    _state.value = _state.value.copy(retryingNote = note, message = note)
                    pushFloating(note, "THINKING")
                    log(AgentLog.Level.WARN, "AI 调用重试：$note")
                },
                onDelta = {
                    pushThinking(delta = it)
                    // 同步累积到 Agent 页的流式回显（悬浮窗与 App 内看到同一份内容）
                    _decisionStream.value += it
                },
            )
        }
        if (result == null) {
            decisionTimeoutStreak += 1
            log(
                AgentLog.Level.WARN,
                "单步云端决策超过 ${WATCHDOG_DECIDE_MS / 1000}s，本步改为等待，下一轮重试以防卡死" +
                    "（连续第 $decisionTimeoutStreak 次）",
            )
            // 连续超时不可能是「抖动」了：网络多半一直不通。继续 wait 会在 maxSteps=0 的配置下无限空转，
            // 抛给主循环按失败收尾，让用户看到「检查网络后重试」，而不是永远停在准备中。
            if (decisionTimeoutStreak >= MAX_DECISION_TIMEOUT_STREAK) {
                throw com.phoneagent.engine.runtime.DecisionTimeoutExhaustedException(decisionTimeoutStreak)
            }
            pushFloating("AI 决策超时，将自动重试", "THINKING")
            // 超时降级必须进用户可见出口：只写日志/通知的话，任务卡状态里看不出「已改用等待重试」
            TaskNoticeCenter.publishDegradation(
                DegradationTrigger.MODEL_TIMEOUT,
                detail = "${WATCHDOG_DECIDE_MS / 1000}s",
                method = "等待后重试",
                source = "decision",
            )
            com.phoneagent.core.notify.ActiveNotifier.notify(
                appContext, com.phoneagent.core.notify.ActiveNotifier.ID_CLOUD_TIMEOUT,
                "AI 卡顿了一下", "云端暂时联系不上，已自动改为稍后重试，任务不会被中断。",
            )
            return AgentIntent(intent = "wait", waitMs = 1200, reasoning = "AI 决策超时，等待后重试")
        }
        decisionTimeoutStreak = 0
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
        // 重试提示同理只在"正在等"时成立：这一轮既然拿到了结果，提示必须撤掉，
        // 否则状态条会一直挂着一条已经过去的重试说明
        if (_state.value.retryingNote.isNotEmpty()) {
            _state.value = _state.value.copy(retryingNote = "")
        }
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
        // 条件由多个协议字段组合而成，抽成具名布尔量会分散逻辑，此处按规则豁免
        @Suppress("ComplexCondition")
        val offlineMiss = tTarget != null && tTarget.by != "coordinate" &&
            intentResolver.resolve(tTarget, snapshot).let { it.element == null && it.x == null }
        if (tTarget != null && (tTarget.by == "hint" || offlineMiss) &&
            (cloudVision || localRegions != null || externalUsed)
        ) {
            // 按需补图：默认不再每步抓帧，只有真的要视觉定位时才现场截一帧
            val shot = screenshot ?: tryOrNull("视觉定位补截图失败") { captureScreenshot() }
            val targetText = tTarget.value
            if (shot != null && !targetText.isNullOrBlank()) {
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
                                appContext, shot, targetText, EXTERNAL_VISION_TIMEOUT,
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
                                screenshot = shot,
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
        // 按需截图：默认不再每步抓帧，AI 明确要「看」时才现场补一帧（只截这一次）
        val shot = screenshot ?: tryOrNull("看图追问补截图失败") { captureScreenshot() }
        // 连补抓都失败才回注原因，让 AI 换一条路取信息，不要白等一次调用
        if (shot == null) {
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
                screenshot = shot,
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
     * 处理系统直连意图（device_info / device_control / settings_op / clipboard /
     * alarm_manage / virtual_display / memory_get）。
     *
     * 与 device_query 同类：纯端侧代办、不操控 GUI，失败原因作为「上一步结果」回注，
     * 由 AI 自己纠正（换 op、换参数或改走 GUI）。
     */
    private suspend fun handleDeviceOps(step: Int, action: AgentAction, messages: MutableList<ChatMessageDto>) {
        val family = action.endpoint.orEmpty()
        val op = action.op.orEmpty()
        val result = runCatching {
            deviceOpsChannel.run(family, op.ifBlank { null }, action.args.orEmpty())
        }.getOrElse {
            com.phoneagent.device.ops.DeviceOpsChannel.OpsResult.Fail("系统直连执行异常：${it.message ?: "未知原因"}")
        }
        val ok = result is com.phoneagent.device.ops.DeviceOpsChannel.OpsResult.Ok
        val text = when (result) {
            is com.phoneagent.device.ops.DeviceOpsChannel.OpsResult.Ok -> result.text
            is com.phoneagent.device.ops.DeviceOpsChannel.OpsResult.Fail -> result.reason
        }
        log(
            if (ok) AgentLog.Level.INFO else AgentLog.Level.WARN,
            "系统直连：$family/${op.ifBlank { "-" }} → ${text.take(160)}",
        )
        recordStep(
            step, action,
            VerifyRules.historyStatus(ok),
            "", "", "直连结果：${text.take(200)}",
        )
        val title = "device_ops（$family${op.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""}）"
        val injected = "$title ${if (ok) "结果" else "失败"}：\n$text"
        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = injected))))
        addConversation("assistant", injected)
        recordProgress(step, action)
        pushFloating(if (ok) "已执行系统直连" else "直连未完成", "THINKING")
        delay(200)
    }

    /**
     * 系统直连的注入命令通道：直接复用正常执行链的通道选择（通道偏好 / Shizuku / ADB 引导会话 / Termux）。
     *
     * 之前这里另写了一套「Shizuku 可用就用，否则无线 ADB」的简化选择：既忽略用户在设置里选的
     * 通道偏好，也永远走不到 Termux，导致提示词说「注入通道可用」、真到执行却返回 null——
     * 所有系统直连（闹钟/日历/应用管控/命令行/个人数据）都被判成「没有通道」。
     */
    private suspend fun deviceShell(command: String): String? {
        val r = executionCore.execShellViaChannel(command)
        return when (r) {
            is com.phoneagent.device.shell.ShizukuManager.ShellResult.Success -> r.output
            is com.phoneagent.device.shell.ShizukuManager.ShellResult.Failure -> null
        }
    }

    /**
     * 注入通道诊断：服务组件是否安装、Shizuku 是否就绪/已授权、无线 ADB 是否连接。
     * 只读端侧状态，不做任何探测或授权请求（避免在失败回执里顺手弹窗）。
     */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod")
    private fun channelAdvice(): String {
        val shizukuState = shizukuManager?.state?.value
        val shizukuText = when (shizukuState) {
            com.phoneagent.device.shell.ShizukuManager.State.READY -> "就绪"
            com.phoneagent.device.shell.ShizukuManager.State.PERMISSION_DENIED -> "已运行但未授权"
            null -> "未启用"
            else -> "未运行"
        }
        val providerInstalled = com.phoneagent.device.provider.ProviderManager.isInstalled(appContext)
        val providerBound = com.phoneagent.device.provider.ProviderManager.connected.value
        val providerError = com.phoneagent.device.provider.ProviderManager.lastError.value
        val adbConnected = adbTransport?.isConnectedNow() == true
        val tips = buildList {
            if (!providerInstalled) add("先到「设置 → 权限」安装服务组件")
            if (providerInstalled && !providerBound) {
                add("服务组件未连上（${providerError ?: "在设置页重新绑定"}）")
            }
            if (shizukuText != "就绪") add("在设置页一键启动并授权 Shizuku")
            if (!adbConnected) add("或完成一次无线 ADB 配对")
        }
        val state = "通道自检：服务组件=${if (providerInstalled) "已安装" else "未安装"}" +
            "/${if (providerBound) "已绑定" else "未绑定"}，" +
            "Shizuku=$shizukuText，无线ADB=${if (adbConnected) "已连接" else "未连接"}"
        return if (tips.isEmpty()) state else "$state；建议：${tips.joinToString("，")}"
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
            finalizer.finishTaskMemory(TaskMemoryEntry.STATUS_FAILED)
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
        loopGuard.onProgress()
        val label = actionLabel(action.type)
        val reason = action.reasoning?.takeIf { it.isNotBlank() } ?: action.reason?.takeIf { it.isNotBlank() }
        val note = if (reason != null) "第${step}步: $label（$reason）" else "第${step}步: $label"
        // 写进任务记忆而不是易失的内存队列：记忆会落库并注入每轮决策，中途被压缩掉的历史也能找回来
        finalizer.mutateTaskMemory { it.withMethod(note).copy(completedSteps = completedSteps) }
    }

    /** 渲染任务记忆，注入每轮决策上下文（拼装规则下沉 [StepTexts]，可单测） */
    private fun taskMemoryText(): String =
        StepTexts.taskMemory(currentTaskMemory, completedSteps, totalPlannedSteps)

    /** 任务完成后，通过悬浮窗交互请用户确认是否保存为模板（不自动入库） */
    private fun requestConfirmSaveTemplate() {
        showFloatingInteraction(
            type = "savetemplate",
            title = "保存执行模板",
            content = "本次任务已完成，是否将步骤保存为模板，供下次复用？",
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

    /**
     * 截图：副屏模式只读副屏（screencap -d），主屏模式读主屏。
     *
     * 副屏截图失败（实测 Android 16 的 screencap 只认物理屏）时**绝不回退主屏截图**：
     * 把主屏画面当成副屏发给模型，等于让模型对着另一个屏的画面算坐标，是假运行。
     * 这里如实返回 null 并写明原因，决策层改用副屏无障碍元素树。
     */
    private suspend fun captureScreenshot(): Bitmap? {
        if (virtualDisplayActive) {
            virtualDisplay?.screenshot()?.let { bytes ->
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return bmp
            }
            log(
                AgentLog.Level.WARN,
                "副屏截图不可用（${virtualDisplay?.lastScreenshotError ?: "原因未知"}）：" +
                    "本轮不带截图，只用副屏无障碍元素树决策",
            )
            return null
        }
        return com.phoneagent.device.screen.ScreenCapture.capture()
    }

    /**
     * 准备副屏：开关打开且 Shizuku 可用时创建模拟副屏。
     * 任何环节失败都返回 false（降级主屏），并在日志里留下可读原因。
     */
    private suspend fun prepareVirtualDisplay(settingsVal: AppSettings.Settings): Boolean {
        if (!settingsVal.virtualDisplayEnabled) return false
        val vd = virtualDisplay
        if (vd == null) {
            log(AgentLog.Level.WARN, "副屏模式需要 Shizuku 通道，当前不可用，已降级主屏模式")
            return false
        }
        return when (val r = vd.ensure()) {
            is com.phoneagent.device.screen.VirtualDisplayAgent.Outcome.Ok -> true
            is com.phoneagent.device.screen.VirtualDisplayAgent.Outcome.Fail -> {
                log(AgentLog.Level.WARN, "副屏创建失败，已降级主屏模式：${r.reason}")
                com.phoneagent.device.screen.VirtualDisplayAgent.lastDiagnostic
                    .takeIf { it.isNotBlank() }
                    ?.let { log(AgentLog.Level.INFO, "副屏诊断：$it") }
                false
            }
        }
    }

    /** 释放副屏（任务结束/停止时调用；未启用时无副作用） */
    private suspend fun releaseVirtualDisplay() {
        if (!virtualDisplayActive) return
        virtualDisplayActive = false
        runCatching { virtualDisplay?.destroy() }
    }

    private suspend fun observe(): ScreenSnapshot {
        // 副屏模式：先读副屏的无障碍树（跨屏窗口），再尝试副屏截图；两条都拿不到才降级主屏
        if (virtualDisplayActive) return observeVirtualDisplay()
        // 无障碍服务可能在任务执行中途被系统回收/关闭，此时读取元素树会抛异常；
        // 观察是每一步的起点，异常必须降级为「空快照」而不是终止任务
        val a11y = AgentAccessibilityService.instance
        if (a11y == null) {
            // 「只截图不读树」的直接证据：说明是会话没连上，而不是元素树被筛选条件清空
            log(
                AgentLog.Level.WARN,
                "无障碍会话未连接：" +
                    (com.phoneagent.device.provider.ProviderManager.lastError.value
                        ?: "服务组件未绑定或无障碍服务未开启") + "，本轮只能靠截图",
            )
        }
        val snapshot = tryOrNull("读取无障碍元素树失败") {
            a11y?.captureScreen()
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

    /**
     * 副屏观察：无障碍树优先，截图兜底，两者都不行才降级主屏。
     *
     * 铁律（用户裁定）：不拿主屏截图冒充副屏画面。副屏读不到树时明确标注"走树/无截图"，
     * 降级时必须把原因写进日志（界面事件流会显示同一句）。
     */
    private suspend fun observeVirtualDisplay(): ScreenSnapshot {
        val vd = virtualDisplay
        val displayId = vd?.displayId
        if (vd == null || displayId == null) {
            return degradeVirtualDisplay("副屏代理或 displayId 丢失（创建阶段异常）")
        }
        val a11y = AgentAccessibilityService.instance
        val tree = if (a11y == null) {
            null
        } else {
            tryOrNull("读取副屏元素树失败") { a11y.captureScreenOnDisplay(displayId) }
        }
        val stats = a11y?.lastTreeStats ?: "未连接无障碍会话"
        if (tree != null && tree.elements.isNotEmpty()) {
            val shot = vd.screenshot()
            if (shot == null) {
                log(
                    AgentLog.Level.INFO,
                    "副屏截图不可用（${vd.lastScreenshotError ?: "原因未知"}），本轮按无障碍树观察（$stats）",
                )
            }
            return tree.copy(
                screenWidth = if (tree.screenWidth > 0) tree.screenWidth else com.phoneagent.device.screen.VirtualDisplayAgent.WIDTH,
                screenHeight = if (tree.screenHeight > 0) tree.screenHeight else com.phoneagent.device.screen.VirtualDisplayAgent.HEIGHT,
                hasScreenshot = shot != null,
            )
        }
        // 树读不到：副屏截图是第二条路（能拿到就仍算副屏观察，只是没有元素树）
        val shot = vd.screenshot()
        if (shot != null) {
            return ScreenSnapshot(
                packageName = null,
                missingAccessibility = true,
                screenWidth = com.phoneagent.device.screen.VirtualDisplayAgent.WIDTH,
                screenHeight = com.phoneagent.device.screen.VirtualDisplayAgent.HEIGHT,
                hasScreenshot = true,
            )
        }
        return degradeVirtualDisplay(
            "副屏无障碍树不可用（$stats），" +
                "副屏截图也不可用（${vd.lastScreenshotError ?: "原因未知"}）",
        )
    }

    /**
     * 副屏两条观察路径都失败时降级主屏：释放副屏、复位开关、写清原因，然后返回主屏观察结果。
     * 降级是"明示"的——日志与界面事件流都会出现同一句原因，不再静默换屏。
     */
    private suspend fun degradeVirtualDisplay(reason: String): ScreenSnapshot {
        virtualDisplayActive = false
        runCatching { virtualDisplay?.destroy() }
        log(
            AgentLog.Level.WARN,
            "副屏不可用，已降级主屏模式（后续步骤在主屏执行）：$reason",
        )
        pushFloating("副屏不可用，已降级主屏", "OBSERVING")
        return observe()
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

    // ---- 任务路由（Superagent 融合 Phase 4）：普通对话 / 原操控链路 ----

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
        // 提问先上屏：回答要等模型回来（数秒），先把用户那句话放成气泡，
        // 否则合并形态下提交完会退回空态，看起来像什么都没发生
        val turnId = beginChatTurn(task)
        var answer = ""
        try {
            val settingsVal = settings.settings.first()
            val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
            val system = AgentPrompts.chatSystem(lang, settingsVal.systemPrompt)
            answer = aiClient.chat(
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
        } finally {
            // 合并形态：对话页被并入 Agent 页后，这一问一答要靠时间线气泡留在原地。
            // 停止/取消也要收尾，否则气泡会永远停在「正在回答」
            finishChatTurn(turnId, answer)
        }
    }

    /** 当前是否「合并为一个 Agent」的工作台形态（设置缺省即合并：三个能力必须合起来） */
    private fun unifiedSurface(): Boolean =
        AgentSurface.normalize(latestSettings?.agentSurface) == AgentSurface.UNIFIED

    /** 提问先上屏：记下本条问题（回答留空，界面显示「正在回答」），返回用于回填回答的 id */
    private fun beginChatTurn(question: String): Long {
        val id = store.chatTurnSeq.incrementAndGet()
        val ev = com.phoneagent.engine.runtime.ChatTurnEvent(
            id = id,
            at = System.currentTimeMillis(),
            question = question.trim().take(200),
            answer = "",
        )
        store.chatTurns.value = (store.chatTurns.value + ev).takeLast(MAX_CHAT_TURNS)
        return id
    }

    /** 回答回填到同一条问答里（保持问答成组，不在时间线上另起一条） */
    private fun finishChatTurn(id: Long, answer: String) {
        val text = answer.ifBlank { "（已停止）" }
        store.chatTurns.value = store.chatTurns.value.map { if (it.id == id) it.copy(answer = text) else it }
    }

    /**
     * 云端意图判定（合并形态的唯一判定点）。
     * 失败 / 拿不到明确结果一律降级为 [TaskIntent.AGENT]：操控链路能力最完整，判错也还能补救。
     */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun classifyIntent(task: String): TaskIntent {
        intentCache[task]?.let { return it }
        val settingsVal = runCatching { settings.settings.first() }.getOrNull() ?: return TaskIntent.AGENT
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        val raw = runCatching {
            aiClient.chat(
                baseUrl = settingsVal.apiBaseUrl,
                apiKey = settingsVal.apiKey,
                model = settingsVal.model,
                messages = listOf(
                    ChatMessageDto(
                        role = "system",
                        content = listOf(ContentPart(type = "text", text = IntentRouter.systemPrompt(lang))),
                    ),
                    ChatMessageDto(
                        role = "user",
                        content = listOf(ContentPart(type = "text", text = task)),
                    ),
                ),
                // 分流只要一个分类结果：温度 0 让它稳定，不需要任何发挥
                temperature = 0.0,
                protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal),
            ).getOrNull()
        }.getOrNull()
        val parsed = raw?.let(IntentRouter::parse)
        if (parsed == null) {
            log(AgentLog.Level.WARN, "意图判定未得到明确结果，按手机操控任务处理：${raw?.take(60).orEmpty()}")
            return TaskIntent.AGENT
        }
        if (intentCache.size >= MAX_INTENT_CACHE) intentCache.clear()
        intentCache[task] = parsed
        log(AgentLog.Level.INFO, "意图判定：${IntentRouter.label(parsed)}")
        return parsed
    }

    /**
     * 合并形态的两形态路由：在任务进入执行体前判定一次。
     * - Chat：主模型直答一步完成；
     * - Agent：走原 runTask 链路，行为不变。
     *
     * 编码不在本链路里：它是独立入口（Coding 页直接驱动编码 agent）。
     * 判定交给云端（[classifyIntent]）而不是端侧词表：词表只认字面词，
     * 分不清「帮我把这段代码解释一下」该答还是该动手。两种处置共用同一套执行体，
     * 所以这里只决定「进哪条」，不改变任何一条的内部行为。
     *
     * [chatOnly] 为「对话页提交」标记：置位时**不做判定**、直接给 Chat 决策。
     * 对话页里发一句话是聊天，不该因为判定成操控就滑进执行链路。
     */
    private suspend fun routeTask(task: String, chatOnly: Boolean = false): RouteDecision {
        if (chatOnly) return chatRoute(task)
        return when (classifyIntent(task)) {
            TaskIntent.CHAT -> chatRoute(task)
            TaskIntent.AGENT -> RouteDecision.Agent
        }
    }

    /** 纯对话决策：主模型直答一步完成；收尾按停止旗标补「已停止」状态 */
    private fun chatRoute(task: String) = RouteDecision.Chat {
        try {
            chatDirectAnswer(task)
        } finally {
            if (chatStopRequested) {
                AgentStatusStore.update(AgentStatusStore.Snapshot(task = task, status = "已停止", done = true))
            }
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

/** 队列项：任务文本 + 是否来自对话页（对话页提交只作答、不进执行体，见 [TaskLifecycle.start]） */
internal data class PendingTask(val text: String, val chatOnly: Boolean)

internal class PendingTaskQueue {
    private val lock = Any()
    private val _items = MutableStateFlow<List<String>>(emptyList())

    /** 与 [_items] 一一对应的「对话页提交」标记：与队列内容在同一把锁内增删，下标始终对齐 */
    private val chatOnlyFlags = ArrayList<Boolean>()

    /** 队列快照（供界面展示，只读） */
    val items: StateFlow<List<String>> get() = _items.asStateFlow()

    /** 追加一个待执行任务；[chatOnly] 语义见 [TaskLifecycle.start] */
    fun enqueue(task: String, chatOnly: Boolean = false) {
        synchronized(lock) {
            _items.value = _items.value + task
            chatOnlyFlags += chatOnly
        }
    }

    /** 取出队首任务（连同其队列标记）；队列为空返回 null */
    fun poll(): PendingTask? = synchronized(lock) {
        val cur = _items.value
        if (cur.isEmpty()) null else {
            _items.value = cur.drop(1)
            PendingTask(cur.first(), chatOnlyFlags.removeAt(0))
        }
    }

    /** 是否还有待执行任务 */
    fun isNotEmpty(): Boolean = synchronized(lock) { _items.value.isNotEmpty() }
}

internal fun ActionExecutor.Result.isSuccess(): Boolean = this is ActionExecutor.Result.Success

/** 纯对话直答的采样温度（Superagent 融合 Phase 4）：略低于决策温度，稳定输出自然语言回答 */
private const val CHAT_TEMPERATURE = 0.3

/** 合并形态时间线保留的对话问答条数（本对话内滚动保存，新建对话即清空） */
private const val MAX_CHAT_TURNS = 20

/** 意图判定缓存容量：同一句话复用判定，超出即整表清空（判定本身很轻，不值得做成 LRU） */
private const val MAX_INTENT_CACHE = 32

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
