package com.phoneagent.ui

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneagent.core.ai.ApiProtocol
import com.phoneagent.core.perf.StartupTrace
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.engine.AgentEngine
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.chat.ChatSessionState
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.data.export.LogExporter
import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.data.store.PagePathEdge
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.device.screen.ScreenSharingService
import com.phoneagent.engine.PromptLang
import com.phoneagent.feature.mcp.McpServerConfig
import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.feature.mcp.McpServerInfo
import com.phoneagent.data.store.McpStore
import com.phoneagent.feature.mcp.McpMarketplace
import com.phoneagent.feature.mcp.McpMarketplaceEntry
import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.device.shell.TermuxBridge
import com.phoneagent.device.shell.TermuxStatus
import com.phoneagent.device.shell.AdbStatus
import com.phoneagent.device.shell.ShizukuBootstrap
import com.phoneagent.device.shell.WirelessAdbPairingFlow
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillRegistry
import com.phoneagent.feature.test.TestConfig
import com.phoneagent.feature.test.TestEngine
import com.phoneagent.feature.test.TestPreset
import com.phoneagent.feature.test.TestRunSummary
import com.phoneagent.feature.document.DocumentEngine
import com.phoneagent.feature.document.DocResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.phoneagent.device.vision.StepShot
import com.phoneagent.device.vision.StepTrace

/** ACA 技能目录扫描结果的有效期：切 Tab 回来的重复触发走缓存，不重复扫盘 */
private const val ACA_SKILLS_TTL_MS = 60_000L

/** 通知权限的运行时请求码（Android 13+） */
private const val REQUEST_NOTIFICATIONS = 4401

/** 麦克风权限的运行时请求码（权限雷达与输入框录音按钮各自独立，互不干扰） */
private const val REQUEST_RECORD_AUDIO = 4402

/**
 * 全局共享 ViewModel：设置、Agent 运行状态、无障碍/截图权限。
 */
class MainViewModel(
    private val settings: AppSettings,
    engineProvider: () -> AgentEngine,
    private val testEngine: TestEngine,
    private val shizukuManager: ShizukuManager,
    private val termuxBridge: TermuxBridge,
    private val documentEngine: DocumentEngine,
    private val memoryStore: MemoryStore,
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val mcpStore: McpStore,
    private val shizukuBootstrap: ShizukuBootstrap,
    private val adbPairingFlow: WirelessAdbPairingFlow,
    private val updateManager: com.phoneagent.feature.update.UpdateManager,
) : ViewModel() {

    /**
     * 引擎对象图：约 30 个协作者 + 4 个 OkHttpClient，**用到时才构建**。
     *
     * 它原本是构造参数，于是 `koinViewModel()` 在首帧的组合期就把整张图在主线程上建出来，
     * 首帧被这一整段串住。改成惰性之后，构造 MainViewModel 只碰 [settings]；
     * 界面在首帧先画骨架屏（见 [ActivityContent] 的 engineReady 门控），
     * 首个读引擎的访问发生在首帧之后。注入方式仍是「构造参数 + 惰性求值」，
     * 没有全局可变状态、没有服务定位器：测试直接传 `{ fakeEngine }` 即可。
     */
    private val engine: AgentEngine by lazy {
        StartupTrace.span("引擎单例构建（首帧后）") { engineProvider() }
    }

    val settingsFlow: StateFlow<AppSettings.Settings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings.Settings())

    /**
     * 新手引导状态：null = 既没有门控镜像、也还没从 DataStore 读到真实值。
     *
     * 先用 [AppSettings.onboardingGate] 的 SharedPreferences 镜像回答门控（同步读、毫秒级），
     * 随后仍以 DataStore 的值为准覆盖它——镜像只是加速，不改权威源。
     * 不能直接用 [settingsFlow] 判定——它的初始值是默认 Settings（onboardingDone=false），
     * 老用户冷启动会先闪一帧引导页再跳回主界面。
     */
    val onboardingState: StateFlow<Boolean?> = flow<Boolean?> {
        settings.onboardingGate()?.let { emit(it) }
        emitAll(settings.settings.map { it.onboardingDone as Boolean? })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 结束新手引导：把完成标记落盘，下次启动直接进主界面 */
    fun completeOnboarding() {
        viewModelScope.launch { settings.markOnboardingDone() }
    }

    // 引擎派生的状态一律惰性取：这些属性一旦在构造期求值，就会立刻拉起整张引擎对象图
    val agentState: StateFlow<AgentState> by lazy { engine.state }

    val logs: StateFlow<List<AgentLog>> by lazy { engine.logs }
    val conversation: StateFlow<List<ConversationMessage>> by lazy { engine.conversation }

    /**
     * 对话页的会话状态（气泡 + 同步游标）。
     *
     * 由 ViewModel 持有而不是页面 `rememberSaveable`：气泡随会话无限增长，
     * 序列化进 Bundle 会在长会话上顶到 1MB 上限。详见 [ChatSessionState]。
     */
    internal val chatSession = ChatSessionState()

    val metrics: StateFlow<AgentMetrics> by lazy { engine.metrics }
    val executionHistory: StateFlow<List<StepRecord>> by lazy { engine.executionHistory }
    val stepShot: StateFlow<com.phoneagent.device.vision.StepShot> by lazy { engine.stepShot }
    val traces: StateFlow<List<com.phoneagent.device.vision.StepTrace>> by lazy { engine.traces }
    /** 任务会话归档（Agent 页侧边栏）：最新一次任务在最前 */
    val taskSessions: StateFlow<List<com.phoneagent.engine.TaskSession>> by lazy { engine.taskSessions }
    /** 当前对话的起点：实时视图只铺开这之后的任务，承接块也只取这之后的往来 */
    val conversationStart: StateFlow<Long> by lazy { engine.conversationStart }
    val taskQueue: StateFlow<List<String>> by lazy { engine.taskQueue }
    val needsUser: StateFlow<Boolean> by lazy { engine.needsUser }
    val userHintRequest by lazy { engine.userHintRequest }
    val planPhase: StateFlow<com.phoneagent.engine.PlanPhase> by lazy { engine.planPhase }
    val planStream: StateFlow<String> by lazy { engine.planStream }

    /** AI 正在生成的决策内容（流式回显到 Agent 页，空串表示当前无输出） */
    val decisionStream: StateFlow<String> by lazy { engine.decisionStream }

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
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String, protocol: String = "openai"): Result<String> =
        engine.testConnection(baseUrl, apiKey, model, ApiProtocol.fromId(protocol))

    /** 拉取端点可用模型列表（设置页"获取模型"） */
    suspend fun listModels(baseUrl: String, apiKey: String, protocol: String = "openai"): Result<List<String>> =
        engine.listModels(baseUrl, apiKey, ApiProtocol.fromId(protocol))

    /** 探测单个模型的能力（识图 / 工具调用） */
    suspend fun probeModel(baseUrl: String, apiKey: String, model: String, protocol: String = "openai"): Result<com.phoneagent.core.ai.ModelAbility> =
        engine.probeModel(baseUrl, apiKey, model, ApiProtocol.fromId(protocol))

    fun startPlanning(task: String) = engine.startPlanning(task)

    /**
     * 对话页直提（对话模式）：**只作答、不执行**。
     *
     * 走引擎的对话入口 [com.phoneagent.engine.AgentEngine.chatTurn]，由路由侧强制纯对话直答：
     * 在对话页说一句话不该开启任务执行模式（不设 isRunning、不开悬浮窗 / 跑马灯、
     * 不亮边缘光效、不碰无障碍操控）。需要操控手机的请求由直答如实说明，
     * 用户到 Agent 页下达即可。回答写入对话区（本页消费）。
     */
    fun submitChatTurn(task: String) = engine.chatTurn(task)

    fun answerClarification(option: com.phoneagent.domain.model.ClarificationOption) = engine.answerClarification(option)
    fun approvePlan() = engine.approvePlan()
    fun cancelPlanning() = engine.cancelPlanning()

    /**
     * 新建对话：引擎划断上下文并复位实时态，文档预览一并撤下。
     * 历史任务仍留在 [taskSessions] 里可回看，只是不再进入新对话的上下文。
     */
    fun newConversation() {
        engine.newConversation()
        documentEngine.dismiss()
    }

    // ---- 悬浮窗交互桥接：气泡窗按钮动作转发到引擎 ----
    // 注册动作在 AgentEngine.init 里（引擎是单例，注册生命周期必须跟引擎走）：
    // 旧实现挂在这里，界面一销毁回调就被清空，后台任务在悬浮窗上点「停止 / 批准」会没反应。
    init {
        // 启动时加载持久化的 MCP 服务器配置
        viewModelScope.launch {
            val saved = mcpStore.load()
            if (saved.isNotEmpty() || mcpManager.servers.isNotEmpty()) {
                mcpManager.replaceAll(saved)
            }
        }
    }

    override fun onCleared() {
        // 只做解绑，不停任务：引擎是 Koin 单例，任务生命周期不归界面管。
        // 旧实现在这里 engine.stop()，导致「退出界面 / 系统回收 Activity」= 任务被中断——
        // 长线任务只要切走就白跑。停止任务必须由用户显式操作（Agent 页停止 / 悬浮窗 / 通知栏）。
        super.onCleared()
    }

    fun clearDebug() = engine.clearDebug()
    fun provideUserHint(hint: String) = engine.provideUserHint(hint)
    fun dismissUser() = engine.dismissUser()

    /** 正在等待用户确认的 AI 自写命令（null = 没有待确认项）；驱动 App 内协助面板的「批准执行 / 拒绝」 */
    val pendingShellCommand: StateFlow<String?> by lazy { engine.pendingShellCommand }
    fun resolveShellApproval(approve: Boolean) = engine.resolveShellApproval(approve)

    // ---- 不可逆动作确认门：App 内任务流的确认卡与悬浮窗共用引擎的同一信箱 ----
    /** 引擎正在等待用户确认的不可逆动作（null = 无待确认项）；驱动任务流里的确认卡片 */
    val pendingActionConfirmation: StateFlow<AgentEngine.PendingActionConfirmation?> by lazy {
        engine.pendingActionConfirmation
    }
    /** 确认执行该动作 */
    fun confirmPendingAction() = engine.respondActionConfirmation(true)
    /** 拒绝该动作（AI 会换做法或中止该步） */
    fun denyPendingAction() = engine.respondActionConfirmation(false)

    private val _a11yEnabled = MutableStateFlow(false)
    val a11yEnabled: StateFlow<Boolean> get() = _a11yEnabled.asStateFlow()

    private val _screenshotActive = MutableStateFlow(false)
    val screenshotActive: StateFlow<Boolean> get() = _screenshotActive.asStateFlow()

    private val _overlayGranted = MutableStateFlow(false)
    val overlayGranted: StateFlow<Boolean> get() = _overlayGranted.asStateFlow()

    /** Shizuku 连接状态 */
    val shizukuState: StateFlow<ShizukuManager.State> = shizukuManager.state

    fun requestShizukuPermission(onResult: (Boolean) -> Unit) {
        shizukuManager.requestPermission(onResult)
    }

    // ---- Termux 执行通道（普通应用权限的 Linux 环境，非系统 shell） ----
    private val _termuxStatus = MutableStateFlow(TermuxStatus())
    val termuxStatus: StateFlow<TermuxStatus> get() = _termuxStatus.asStateFlow()

    /** 刷新 Termux 安装 / 授权状态（不实跑，开销小，进页面时调用） */
    fun refreshTermuxStatus() {
        // 状态在 Provider 进程内采集：先回显缓存，再异步经 AIDL 拉一次最新值
        applyTermuxStatus()
        viewModelScope.launch {
            termuxBridge.refresh()
            applyTermuxStatus()
        }
    }

    private fun applyTermuxStatus() {
        _termuxStatus.value = TermuxStatus(
            installed = termuxBridge.isInstalled(),
            permissionGranted = termuxBridge.hasRunCommandPermission(),
            probed = false,
            ready = false,
            message = "",
        )
    }

    /**
     * 实跑一次探测命令，确认 `allow-external-apps=true` 等 Termux 侧配置真的到位。
     * 安装与授权都正常但探测失败时，通常是 Termux 里没开 allow-external-apps。
     */
    fun probeTermux() {
        viewModelScope.launch {
            val result = termuxBridge.probe()
            val ok = result is ShizukuManager.ShellResult.Success
            _termuxStatus.value = TermuxStatus(
                installed = termuxBridge.isInstalled(),
                permissionGranted = termuxBridge.hasRunCommandPermission(),
                probed = true,
                ready = ok,
                message = when (result) {
                    is ShizukuManager.ShellResult.Success -> result.output.ifBlank { "探测通过" }
                    is ShizukuManager.ShellResult.Failure -> result.reason
                },
            )
        }
    }

    /**
     * 静默授予 Termux 执行权限（RUN_COMMAND）给服务组件。
     *
     * 授权对象必须是 Provider：真正调 Termux 的进程是它，主应用自己申请不到。
     * 走 Shizuku 的 `pm grant`，不需要用户翻系统设置；Shizuku 没启动时直接给出原因。
     */
    fun grantTermuxPermission() {
        viewModelScope.launch {
            val result = termuxBridge.grantRunCommandPermission()
            val message = when (result) {
                is ShizukuManager.ShellResult.Success ->
                    if (termuxBridge.hasRunCommandPermission()) "已授予执行权限，可点「实跑探测」确认"
                    else "命令已执行但权限仍未生效，请改用「在系统设置中授权」"
                is ShizukuManager.ShellResult.Failure -> "授权失败：${result.reason}"
            }
            _termuxStatus.value = TermuxStatus(
                installed = termuxBridge.isInstalled(),
                permissionGranted = termuxBridge.hasRunCommandPermission(),
                probed = false,
                ready = false,
                message = message,
            )
        }
    }

    fun refreshOverlayPermission(context: Context) {
        _overlayGranted.value = android.provider.Settings.canDrawOverlays(context)
    }

    /** 打开系统悬浮窗授权页 */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 无障碍「真正可用」的统一口径：系统设置里的服务开关已打开，且服务实例已连接。
     * 只查开关会漏掉「开关开着但实例没连上」（服务被系统杀掉/未拉起）的情况，
     * 只查实例则覆盖不了开机未拉起的状态——两个条件同时满足才算可用。
     */
    private fun a11yUsable(context: Context): Boolean =
        AgentAccessibilityService.isServiceEnabled(context) && AgentAccessibilityService.instance != null

    fun refreshStatus(context: Context) {
        _a11yEnabled.value = a11yUsable(context)
        refreshOverlayPermission(context)
        shizukuManager.refreshState()
    }

    // ---- 权限雷达：聚合各类关键权限状态，一键跳转授权 ----
    private val _permissions = MutableStateFlow<List<PermissionItem>>(emptyList())
    val permissions: StateFlow<List<PermissionItem>> get() = _permissions.asStateFlow()

    fun refreshPermissions(context: Context) {
        // 无障碍由「服务组件」（独立安装的 Provider）提供：组件没装时，系统无障碍列表里根本没有
        // 可开启的条目，直接跳过去是死路。这里如实标为未授权，并把下一步写进说明。
        val providerReady = runCatching { ProviderManager.isInstalled(context) }.getOrDefault(false)
        _permissions.value = listOf(
            PermissionItem(
                kind = PermissionKind.ACCESSIBILITY,
                title = "无障碍服务",
                description = when {
                    !providerReady -> "需先安装「服务组件」（系统会要求允许安装未知应用），装完再回来开启"
                    !AgentAccessibilityService.isServiceEnabled(context) -> "AI 接管手机执行操作的必需能力"
                    // 开关开着但服务实例不在线（被 ROM 杀掉/崩溃重置）：只显示「完成」会让人
                    // 对着绿灯排查任务为什么不动，这里如实说出下一步
                    !a11yUsable(context) -> "开关已开但服务未连接：下拉通知栏或重进本应用后重试"
                    else -> "AI 接管手机执行操作的必需能力"
                },
                // 与引擎同一口径（开关已开 + 实例已连接），否则「雷达显示完成、任务跑不动」
                granted = providerReady && a11yUsable(context),
            ),
            PermissionItem(
                kind = PermissionKind.OVERLAY,
                title = "悬浮窗",
                description = "实时显示任务进度与跑马灯",
                granted = Settings.canDrawOverlays(context),
            ),
            PermissionItem(
                kind = PermissionKind.WRITE_SETTINGS,
                title = "修改系统设置",
                description = "调节亮度、息屏时间、自动旋转等设备设置",
                granted = Settings.System.canWrite(context),
            ),
            PermissionItem(
                kind = PermissionKind.AUTOSTART,
                title = "自启动与后台",
                description = "省电白名单 + 厂商自启动管理",
                granted = isIgnoringBatteryOptimizations(context),
            ),
            PermissionItem(
                kind = PermissionKind.NOTIFICATIONS,
                title = "通知",
                description = "任务进度与完成提醒（Android 13 起需单独授权）",
                granted = hasNotificationPermission(context),
            ),
            // 使用情况访问权是 AppOps 特殊权限（不能运行时弹窗），只能跳系统页开启；
            // 用途是前台应用交叉验证，未开启时引擎静默跳过，不影响主流程，因此排在最后
            PermissionItem(
                kind = PermissionKind.USAGE_ACCESS,
                title = "使用情况访问权",
                description = "校验当前前台应用，元素树读不到时兜底感知",
                granted = com.phoneagent.device.perception.ForegroundProbe(context).hasUsageAccess(),
                optional = true,
            ),
            // 安装未知应用：自更新静默安装不可用时的回退路径，以及首次安装服务组件。
            // 未授权时系统安装器会静默拒绝，用户只看到「安装已被阻止」
            PermissionItem(
                kind = PermissionKind.INSTALL_PACKAGES,
                title = "安装未知应用",
                description = "用于安装服务组件与应用内更新",
                granted = ProviderManager.canInstallPackages(context),
            ),
            // 所有文件访问（MANAGE_EXTERNAL_STORAGE）：编码工作区要按真实路径读写用户选的目录。
            // Android 11 起属特殊权限，只能在系统设置页手动开启
            PermissionItem(
                kind = PermissionKind.ALL_FILES,
                title = "所有文件访问",
                description = "编码工作区读写你选择的任意目录",
                granted = hasAllFilesAccess(),
                optional = true,
            ),
            // 勿扰访问权限：没有 Shizuku 通道时，device_control 的 dnd 只能走系统 API。
            // 未声明该权限时 isNotificationPolicyAccessGranted 恒 false，那条路径永远走不到
            PermissionItem(
                kind = PermissionKind.DND_ACCESS,
                title = "勿扰访问权限",
                description = "无 shell 通道时也能切换勿扰模式",
                granted = runCatching {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                        ?.isNotificationPolicyAccessGranted == true
                }.getOrDefault(false),
                optional = true,
            ),
            // 麦克风：语音输入（运行时会弹系统授权框，与输入框里的录音按钮共用同一份状态）
            PermissionItem(
                kind = PermissionKind.MICROPHONE,
                title = "麦克风",
                description = "语音输入，长按说话转成文字",
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
                optional = true,
            ),
            // QUERY_ALL_PACKAGES 已整体移除：应用清单改走 MAIN+LAUNCHER 的 intent 查询
            // （见 LauncherCatalog），不需要任何授权，也不再是雷达条目
            // Shizuku 不再进权限雷达：设置页下方的「内置 Shizuku」通道卡已覆盖其状态与授权
        )
    }

    /** Android 13+ 的通知权限；低版本安装即授予 */
    private fun hasNotificationPermission(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 「所有文件访问」：Android 11 起才存在该特殊权限，更早的版本靠存储运行时权限即可 */
    private fun hasAllFilesAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

    /** 一键跳转到对应权限授权页 */
    fun openPermissionSettings(context: Context, kind: PermissionKind) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        // 自启动与后台：优先拉起「允许应用忽略电池优化」的系统确认弹窗
        //（清单已声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，允许直接弹窗）；
        // 被机型拦截/无弹窗时回退厂商自启动管理页，最后回退电池优化白名单列表页
        if (kind == PermissionKind.AUTOSTART) {
            // 关键：从 Activity 上下文启动时不加 NEW_TASK——该弹窗的 taskAffinity 归系统设置，
            // 加了 NEW_TASK 会把设置任务切到前台、弹窗盖在设置上而不是本应用上
            val fromActivity = context is Activity
            val fallbacks = listOfNotNull(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")),
                getAutostartIntent(context),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            fallbacks.any {
                if (!fromActivity) it.addFlags(flags)
                runCatching { context.startActivity(it) }.isSuccess
            }
            return
        }
        // 服务组件没装时无障碍列表里没有可开启条目：先去装组件，装完回来再开无障碍
        if (kind == PermissionKind.ACCESSIBILITY &&
            runCatching { ProviderManager.isInstalled(context) }.getOrDefault(false).not()
        ) {
            ProviderManager.install(context)
            return
        }
        if (kind == PermissionKind.NOTIFICATIONS) {
            val granted = hasNotificationPermission(context)
            if (!granted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && context is Activity) {
                // 13+ 首次直接弹系统授权框；被永久拒绝后系统不再弹，回退到应用通知设置页
                androidx.core.app.ActivityCompat.requestPermissions(
                    context, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS,
                )
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(flags)
                runCatching { context.startActivity(intent) }
            }
            return
        }
        // 使用情况访问权没有运行时授权框，只有系统设置页（部分 ROM 支持带 package 直达本应用条目）
        if (kind == PermissionKind.USAGE_ACCESS) {
            val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(flags)
            val global = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(flags)
            runCatching { context.startActivity(direct) }
                .recoverCatching { context.startActivity(global) }
            return
        }
        // 安装未知应用：按来源应用单独授权，带 package 直达本应用条目
        if (kind == PermissionKind.INSTALL_PACKAGES) {
            ProviderManager.openInstallPermissionSettings(context)
            return
        }
        // 所有文件访问：Android 11+ 的系统特殊权限页，ROM 精简时回落到权限总表
        if (kind == PermissionKind.ALL_FILES) {
            val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(flags)
            val global = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(flags)
            runCatching { context.startActivity(direct) }
                .recoverCatching { context.startActivity(global) }
            return
        }
        // 勿扰访问权限：系统只提供这个专用页面，没有更细的直达入口
        if (kind == PermissionKind.DND_ACCESS) {
            val direct = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(flags)
            // 部分 ROM 精简了勿扰访问页：回落到本应用的通知设置页，至少能往上翻一级
            val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(flags)
            runCatching { context.startActivity(direct) }
                .recoverCatching { context.startActivity(fallback) }
            return
        }
        // 麦克风：Activity 上下文能直接弹系统授权框；被永久拒绝后回落到应用详情页
        if (kind == PermissionKind.MICROPHONE) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted && context is Activity) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    context, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO,
                )
            } else {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(flags)
                runCatching { context.startActivity(intent) }
            }
            return
        }
        val intent = when (kind) {
            PermissionKind.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionKind.OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            PermissionKind.WRITE_SETTINGS ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            else -> return
        }.addFlags(flags)
        runCatching { context.startActivity(intent) }
    }

    /** 厂商自启动管理页 Intent，无法打开返回 null */
    private fun getAutostartIntent(context: Context): Intent? {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val intents = when (manufacturer) {
            "xiaomi", "redmi" -> listOf(
                Intent().setComponent(android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            )
            "huawei", "honor" -> listOf(
                Intent().setComponent(android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            )
            "oppo" -> listOf(
                Intent().setComponent(android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            )
            "vivo" -> listOf(
                Intent().setComponent(android.content.ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            )
            "samsung" -> listOf(
                Intent().setComponent(android.content.ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity")),
            )
            "meizu" -> listOf(
                Intent().setComponent(android.content.ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity")),
            )
            else -> emptyList()
        }
        val pm = context.packageManager
        return intents.firstOrNull { it.resolveActivity(pm) != null }
    }

    /** 请求截屏权限（MediaProjection），resultCode/data 由 Activity 回传 */
    fun requestScreenshot(context: Context) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 由 MainActivity 通过 Activity Result API 发起，这里仅标记流程
        _screenshotActive.value = false
    }

    /** Activity 返回授权结果后启动截图服务 */
    fun startScreenshotService(activity: Activity, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            activity.startForegroundService(ScreenSharingService.createIntent(activity, resultCode, data))
            _screenshotActive.value = true
        }
    }

    fun stopScreenshot() {
        ScreenSharingService.instance?.stopSelf()
        _screenshotActive.value = false
    }

    fun saveSettings(value: AppSettings.Settings) {
        viewModelScope.launch { settings.update(value) }
    }

    /**
     * 保存并等落盘完成。
     *
     * 只有"保存完立刻要用新配置"的流程才需要它：快速开始里填完 Key 直接发任务，
     * 若走 [saveSettings] 的异步写入，引擎可能在写入完成前就按旧配置起跑。
     */
    suspend fun saveSettingsNow(value: AppSettings.Settings) {
        settings.update(value)
    }

    // ---- 自动更新：查询新版本、下载安装 ----
    val updateState: StateFlow<com.phoneagent.feature.update.UpdateState> = updateManager.state

    /**
     * 查询更新。返回是否发现新版本——启动时的静默检查据此决定要不要打扰用户。
     * @param baseUrl 官网地址，留空由更新模块回落到官方站点
     */
    suspend fun checkUpdate(baseUrl: String, channel: com.phoneagent.feature.update.UpdateChannel): Boolean =
        updateManager.check(baseUrl, channel)

    /** 下载并安装。下载跑在更新模块自己的作用域里，退出设置页也不会中断 */
    fun downloadAndInstallUpdate(
        release: com.phoneagent.feature.update.ReleaseInfo,
        download: com.phoneagent.feature.update.DownloadInfo,
    ) {
        updateManager.downloadAndInstall(release, download)
    }

    /** 由 UI 在页面可见时调用，与 [refreshStatus] 走同一个口径（开关已开 + 实例已连接） */
    fun refreshA11yState(context: Context) {
        _a11yEnabled.value = a11yUsable(context)
    }

    fun startAgent(task: String) {
        if (task.isBlank()) return
        engine.start(task)
    }

    /**
     * 保存配置并起跑任务：写入设置后立刻发任务，写入完成之前不启动引擎。
     *
     * 为什么必须在 ViewModel 作用域里做：开屏引导最后一页点「试试」之后，引导组合会
     * 立即被销毁（[onboardingDismissed] 置真），页面协程随之取消——按"页面协程里
     * 先 save 再 start"的写法，写入很可能中途被取消，任务用旧配置起跑。
     *
     * 运行中走 [startAgent]（排队追加），空闲走 [startPlanning]，
     * 与深链 / 分享入口的路由完全一致。
     */
    fun saveThenStartTask(value: AppSettings.Settings, task: String) {
        if (task.isBlank()) return
        viewModelScope.launch {
            settings.update(value)
            if (agentState.value.isRunning) startAgent(task) else startPlanning(task)
        }
    }

    fun stopAgent() = engine.stop()

    /** 当前任务是否被用户暂停；驱动状态条在「暂停 / 继续」之间切换 */
    val paused: StateFlow<Boolean> by lazy { engine.paused }

    /** 暂停当前任务（主循环在下一轮开头挂起，不消耗步数） */
    fun pauseAgent() = engine.pauseTask()

    /** 从暂停处继续执行 */
    fun resumeAgent() = engine.resumeTask()

    /** 重试最近一次任务（失败卡的「重试」出口） */
    fun retryLastTask() = engine.retryLastTask()

    // ---- 长线任务：执行策略 / 断点续传 / 模板库 ----
    suspend fun currentStrategy(): com.phoneagent.data.store.ExecutionStrategy = engine.currentStrategy()
    fun setExecutionStrategy(s: com.phoneagent.data.store.ExecutionStrategy) {
        viewModelScope.launch { engine.setExecutionStrategy(s) }
    }
    fun resumeFromCheckpoint() = engine.resumeFromCheckpoint()
    suspend fun lastCheckpoint(): com.phoneagent.data.store.Checkpoint? = engine.lastCheckpoint()
    /** 清除长线任务的续传检查点（不影响任务模板） */
    fun clearCheckpoint(context: Context) {
        viewModelScope.launch { runCatching { com.phoneagent.data.store.TaskStore.clearCheckpoint(context) } }
    }
    suspend fun loadTemplates(context: Context): List<com.phoneagent.data.store.TaskTemplate> =
        runCatching { com.phoneagent.data.store.TaskStore.loadTemplates(context) }.getOrDefault(emptyList())
    fun deleteTemplate(context: Context, id: String) {
        viewModelScope.launch { runCatching { com.phoneagent.data.store.TaskStore.deleteTemplate(context, id) } }
    }

    // ---- AI 标准化测试 ----
    val testConfig: StateFlow<TestConfig> = testEngine.config
    val testRunning: StateFlow<Boolean> = testEngine.running
    val testSummary: StateFlow<TestRunSummary> = testEngine.summary
    val testStreamText: StateFlow<String> = testEngine.streamText
    val testLanguage: StateFlow<PromptLang> = testEngine.testLanguage
    val testCategory: StateFlow<String?> = testEngine.testCategory

    fun updateTestConfig(baseUrl: String, model: String, apiKey: String) =
        testEngine.updateConfig(baseUrl, model, apiKey)

    fun setTestLanguage(lang: PromptLang) = testEngine.setTestLanguage(lang)
    fun setTestCategory(category: String?) = testEngine.setTestCategory(category)
    fun getLanguageLabel(lang: PromptLang): String = testEngine.getLanguageLabel(lang)

    fun runTest(preset: TestPreset) {
        viewModelScope.launch { testEngine.runPreset(preset) }
    }

    fun resetTest() = testEngine.reset()

    // ---- 文档结果：AI 生成的文档直接在 Agent 页任务流里预览 ----
    val docResult: StateFlow<DocResult?> = documentEngine.result

    fun dismissDoc() = documentEngine.dismiss()

    // ---- AI 记忆图谱 ----
    /** 本次任务内 AI 写入的记忆事件（引擎内存态），Agent 页据此实时插卡 */
    val memoryEvents: StateFlow<List<com.phoneagent.engine.MemoryEvent>> get() = engine.memoryEvents

    /** 本次任务内 AI "对用户说话"的事件（引擎内存态），Agent 页据此实时出气泡 */
    val sayEvents: StateFlow<List<com.phoneagent.engine.SayEvent>> get() = engine.sayEvents

    /** 本对话内 AI 澄清 → 用户选择 的往来（引擎内存态），Agent 页据此保留 问题 + 选择 */
    val clarifyEvents: StateFlow<List<com.phoneagent.engine.ClarifyAnswered>> get() = engine.clarifyEvents

    /**
     * 合并形态下的纯对话问答（引擎内存态）：Agent 页把「对话」Tab 的产出也画在同一条时间线上。
     * 与 [conversation] 不同，这里只存用户看得懂的 问题 + 回答，不含内部决策提示词。
     */
    val chatTurns: StateFlow<List<com.phoneagent.engine.runtime.ChatTurnEvent>> get() = engine.chatTurns

    /** 撤销一条刚写入的记忆：删库 + 从任务流移除卡片 */
    fun undoMemory(id: Long) {
        viewModelScope.launch {
            engine.dismissMemoryEvent(id)
            refreshMemory()
        }
    }

    private val _memoryAnomalies = MutableStateFlow<List<AnomalyMemoryEntry>>(emptyList())
    val memoryAnomalies: StateFlow<List<AnomalyMemoryEntry>> get() = _memoryAnomalies.asStateFlow()

    private val _memoryProfile = MutableStateFlow<List<ProfileEntry>>(emptyList())
    val memoryProfile: StateFlow<List<ProfileEntry>> get() = _memoryProfile.asStateFlow()

    private val _memoryTaskMemories = MutableStateFlow<List<TaskMemoryEntry>>(emptyList())
    val memoryTaskMemories: StateFlow<List<TaskMemoryEntry>> get() = _memoryTaskMemories.asStateFlow()

    /** 页面记忆：任务执行自动沉淀的页面热点 */
    private val _pageMemories = MutableStateFlow<List<com.phoneagent.data.store.PageMemoryEntry>>(emptyList())
    val pageMemories: StateFlow<List<com.phoneagent.data.store.PageMemoryEntry>> get() = _pageMemories.asStateFlow()

    /** 页面路径边：页面间的跳转记录 */
    private val _pagePathEdges = MutableStateFlow<List<com.phoneagent.data.store.PagePathEdge>>(emptyList())
    val pagePathEdges: StateFlow<List<com.phoneagent.data.store.PagePathEdge>> get() = _pagePathEdges.asStateFlow()

    private val _memoryLoading = MutableStateFlow(false)
    val memoryLoading: StateFlow<Boolean> get() = _memoryLoading.asStateFlow()

    /** 加载记忆数据（重新编排图谱） */
    fun refreshMemory() {
        viewModelScope.launch {
            _memoryLoading.value = true
            _memoryAnomalies.value = memoryStore.loadAnomalies()
            _memoryProfile.value = memoryStore.loadProfile()
            _memoryTaskMemories.value = memoryStore.loadTaskMemories()
            _pageMemories.value = memoryStore.loadPageMemories()
            _pagePathEdges.value = memoryStore.loadPageEdges()
            _memoryLoading.value = false
        }
    }

    /** 清空异常记忆 */
    fun clearAnomalyMemory() {
        viewModelScope.launch {
            memoryStore.saveAnomalies(emptyList())
            refreshMemory()
        }
    }

    /** 清空用户画像 */
    fun clearProfileMemory() {
        viewModelScope.launch {
            memoryStore.saveProfile(emptyList())
            refreshMemory()
        }
    }

    // ---- 任务记忆（任务执行中的工作记忆）----
    /** 删除单条任务记忆 */
    fun deleteTaskMemory(id: Long) {
        viewModelScope.launch {
            memoryStore.deleteTaskMemory(id)
            refreshMemory()
        }
    }

    /** 清空任务记忆 */
    fun clearTaskMemories() {
        viewModelScope.launch {
            memoryStore.clearTaskMemories()
            refreshMemory()
        }
    }

    // ---- 页面记忆（任务执行自动沉淀的页面热点与路径）----
    /** 删除单条页面记忆（连带删除引用它的路径边） */
    fun deletePageMemory(id: Long) {
        viewModelScope.launch {
            memoryStore.deletePageMemory(id)
            refreshMemory()
        }
    }

    /** 清空页面记忆与路径 */
    fun clearPageMemories() {
        viewModelScope.launch {
            memoryStore.clearPageMemories()
            refreshMemory()
        }
    }

    // ---- 撤销删除 / 撤销清空（记忆页的撤销窗口用）----
    // 记忆条目都是完整可序列化对象，删除前整份快照由调用方持有；撤销即把快照写回。
    // 这不是「假装有撤销」：真的重新落盘，界面随即刷新出原来的条目。

    /** 撤销异常记忆的删除/清空：把整份快照写回 */
    fun restoreAnomalies(entries: List<AnomalyMemoryEntry>) {
        viewModelScope.launch {
            memoryStore.saveAnomalies(entries)
            refreshMemory()
        }
    }

    /** 撤销用户画像的删除/清空：把整份快照写回 */
    fun restoreProfile(entries: List<ProfileEntry>) {
        viewModelScope.launch {
            memoryStore.saveProfile(entries)
            refreshMemory()
        }
    }

    /** 撤销任务记忆的删除/清空：把整份快照写回 */
    fun restoreTaskMemories(entries: List<TaskMemoryEntry>) {
        viewModelScope.launch {
            memoryStore.saveTaskMemories(entries)
            refreshMemory()
        }
    }

    /** 撤销页面记忆的删除/清空：把整份页面列表与路径边写回 */
    fun restorePageMemories(entries: List<PageMemoryEntry>, edges: List<PagePathEdge>) {
        viewModelScope.launch {
            memoryStore.restorePageMemories(entries, edges)
            refreshMemory()
        }
    }

    // ---- 日志导出（实现已外迁至 data/export/LogExporter.kt，此处保留薄委托给 UI 调用） ----
    /** 把指定任务（或全部）的日志导出为文本文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存路径，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogs(context: Context, taskId: Long, taskName: String?): String =
        LogExporter.exportLogs(context, logs.value, taskId, taskName)

    /** 分任务批量导出：每个任务导出一个独立 JSON 文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存汇总，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogsJsonAll(context: Context): String =
        LogExporter.exportLogsJsonAll(context, logs.value)

    // ==================== 八、诊断报告（人话 + 原始 两区，v2.2.1） ====================
    /** 导出诊断报告：人话摘要区 + 原始数据区，导出前自动脱敏。
     *  @return 保存路径或错误信息（以 "ERR:" 开头） */
    fun exportDiagnosticReport(context: Context): String =
        LogExporter.exportDiagnosticReport(context, traces.value, logs.value, conversation.value)

    // ==================== HPA 迭代 A7：Skill / MCP / 提示词 / 无线 ADB UI ====================

    // ---- Skill 列表快照（UI 观察；每次变更后刷新） ----
    private val _skills = MutableStateFlow<List<Skill>>(skillRegistry.all())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    fun skillAll(): List<Skill> = skillRegistry.all()

    private fun refreshSkills() { _skills.value = skillRegistry.all() }

    // ---- ACA（编码 agent）可用技能：流式扫描 filesDir/skills，逐条刷入列表 ----
    private val _acaSkills = MutableStateFlow<List<com.phoneagent.coding.skill.AcaSkillInfo>>(emptyList())
    val acaSkills: StateFlow<List<com.phoneagent.coding.skill.AcaSkillInfo>> = _acaSkills.asStateFlow()

    /** 扫描进行中（true 时主页显示"正在扫描"占位） */
    private val _acaScanning = MutableStateFlow(false)
    val acaScanning: StateFlow<Boolean> = _acaScanning.asStateFlow()

    /** 损坏包的「目录名：原因」列表，主页以弱化文本提示 */
    private val _acaSkillErrors = MutableStateFlow<List<String>>(emptyList())
    val acaSkillErrors: StateFlow<List<String>> = _acaSkillErrors.asStateFlow()

    private var acaScanJob: kotlinx.coroutines.Job? = null

    /** 上次扫描完成的时间；0 表示从未扫过 */
    private var acaScannedAt = 0L

    /**
     * 重新扫描 ACA 技能目录。流式：每发现一个有效包就立刻追加进 [_acaSkills]，
     * 配合主页的级联入场动画形成逐条流出的效果；扫描期间重入直接忽略。
     *
     * 60 秒内已经扫过且没有强制刷新时直接复用上次结果：主页的 LaunchedEffect
     * 每次切回 Tab 都会调用一次，而扫盘是真 IO，没必要为同一份目录反复跑。
     *
     * @param force 目录刚被改动（装上/删掉技能包）时传 true，跳过缓存
     */
    fun refreshAcaSkills(context: Context, force: Boolean = false) {
        if (_acaScanning.value) return
        val fresh = System.currentTimeMillis() - acaScannedAt < ACA_SKILLS_TTL_MS
        if (!force && acaScannedAt != 0L && fresh) return
        acaScanJob?.cancel()
        acaScanJob = viewModelScope.launch {
            _acaScanning.value = true
            try {
                _acaSkills.value = emptyList()
                _acaSkillErrors.value = emptyList()
                // 扫盘在 Default 线程，回调里只做状态追加（StateFlow 写是线程安全的）
                val errors = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.phoneagent.coding.skill.AcaSkillCatalog.streamScan(context.applicationContext) { info ->
                        _acaSkills.value = _acaSkills.value + info
                    }
                }
                _acaSkillErrors.value = errors.map { (dir, err) -> "$dir：$err" }
                acaScannedAt = System.currentTimeMillis()
            } finally {
                // 无论成功、失败还是被取消，都必须复位；否则标志永久为 true，后续扫描会被永久跳过
                _acaScanning.value = false
            }
        }
    }


    /** 在新增时自动分配一个未占用的 id */
    fun nextSkillId(prefix: String = "skill_user"): String {
        var i = 1
        while (skillRegistry.byId("${prefix}_$i") != null) i++
        return "${prefix}_$i"
    }

    fun addSkill(skill: Skill) {
        viewModelScope.launch { skillRegistry.add(skill); refreshSkills() }
    }

    fun updateSkill(skill: Skill) {
        viewModelScope.launch { skillRegistry.edit(skill); refreshSkills() }
    }

    fun removeSkill(id: String) {
        viewModelScope.launch { skillRegistry.remove(id); refreshSkills() }
    }

    /**
     * 批量删除：解析与删除在 Default 线程执行（含持久化 IO），结果回主线程后再刷新快照。
     * @param onResult 主线程回调，参数为成功删除的数量
     */
    fun removeSkills(ids: Set<String>, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                skillRegistry.removeAll(ids)
            }
            refreshSkills()
            onResult(n)
        }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { skillRegistry.setEnabled(id, enabled); refreshSkills() }
    }

    /**
     * 导入技能清单（JSON 文本）：JSON 解析在 Default 线程执行，结果回主线程刷新快照。
     * @param onResult 主线程回调，参数为导入数量（解析失败按 0 处理，不抛到主线程）
     */
    fun importSkills(text: String, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { skillRegistry.importJson(text).imported }.getOrDefault(0)
            }
            refreshSkills()
            onResult(imported)
        }
    }

    fun exportSkills(): String = skillRegistry.exportJson()

    // ---- MCP 服务器（观察可变管理器，配置变更后持久化） ----
    val mcpServers: StateFlow<List<McpServerConfig>> = mcpManager.serversFlow

    /** 变更后把服务器列表持久化到本地 */
    private fun persistMcpServers() {
        viewModelScope.launch { mcpStore.save(mcpManager.servers) }
    }

    /** 新增 MCP 服务器；返回空串=成功，否则返回校验失败原因 */
    fun addMcpServer(name: String, url: String, token: String = ""): String {
        val v = mcpManager.addServer(McpServerConfig(name = name.trim(), url = url.trim(), token = token.trim()))
        if (!v.ok) return v.reason
        persistMcpServers()
        return ""
    }

    fun removeMcpServer(name: String) {
        if (mcpManager.removeServer(name)) persistMcpServers()
    }

    fun setMcpEnabled(name: String, enabled: Boolean) {
        if (mcpManager.setServerEnabled(name, enabled)) persistMcpServers()
    }

    suspend fun checkMcpServer(server: String): String {
        val r = mcpManager.check(server)
        return if (r.ok) "连接正常，枚举到 ${r.tools.size} 个工具" else "连接失败：${r.message}"
    }

    /** 握手 + 枚举，返回结构化服务器信息（能力/协议/工具/参数，供 UI 展示） */
    suspend fun describeMcpServer(server: String): McpServerInfo? = mcpManager.describe(server)

    /** 服务器最近一次请求/响应原文（需求 5：展示请求 JSON） */
    fun mcpLastRequest(server: String): String = mcpManager.lastRequestJson(server)
    fun mcpLastResponse(server: String): String = mcpManager.lastResponseJson(server)

    suspend fun bindMcpServerSkills(server: String): Int {
        val prefix = "mcp_${server.lowercase()}_"
        val added = mcpManager.bindToolsToSkills(server, prefix, skillRegistry)
        refreshSkills()
        return added
    }

    /** 内置 MCP 市场条目（分类/搜索） */
    val mcpMarketplace: List<McpMarketplaceEntry> get() = McpMarketplace.all()
    fun mcpMarketplaceByCategory(category: String): List<McpMarketplaceEntry> = McpMarketplace.byCategory(category)
    fun mcpMarketplaceSearch(keyword: String): List<McpMarketplaceEntry> = McpMarketplace.search(keyword)

    // ---- 内置 Shizuku 启动状态（引导会话复用无线 ADB，仅作启动机制，不再作为独立通道暴露） ----
    val adbStatus: StateFlow<AdbStatus> = shizukuBootstrap.status

    /** 一键启动/检测内置 Shizuku 通路：binder 可用直接就绪，否则经引导会话拉起内置 server */
    fun ensureShizukuReady(onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(
                when (val r = shizukuBootstrap.ensureReady()) {
                    is ShizukuBootstrap.ReadyResult.Ready -> "内置 Shizuku 已就绪，可执行 shell"
                    is ShizukuBootstrap.ReadyResult.Error -> r.message
                }
            )
        }
    }

    /** 用界面输入的 6 位配对码完成首次启动引导，并就绪内置 Shizuku 通路 */
    fun pairShizuku(code: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            if (code.length != 6 || !code.all { it.isDigit() }) {
                onResult("配对码须为 6 位数字")
                return@launch
            }
            onResult(
                when (val r = shizukuBootstrap.pairAndEnsureReady(code)) {
                    is ShizukuBootstrap.ReadyResult.Ready -> "启动成功，内置 Shizuku 已就绪"
                    is ShizukuBootstrap.ReadyResult.Error -> r.message
                }
            )
        }
    }

    // ---- 首次启动引导的通知栏向导（沿用无线 ADB 配对流程，仅作为内置 Shizuku 的引导机制） ----
    val adbPairingMessage: String
        get() = adbPairingFlow.message

    val adbPairingMessageFlow: StateFlow<String> get() = adbPairingFlow.messageFlow

    /** 点击「开始引导」：后台搜索系统无线调试服务，搜到后通过通知栏请求配对码 */
    fun startAdbDiscovery() = adbPairingFlow.startDiscovery(viewModelScope)

    fun cancelAdbPairing() = adbPairingFlow.cancel()
}