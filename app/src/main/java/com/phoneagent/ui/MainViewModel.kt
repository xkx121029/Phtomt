package com.phoneagent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneagent.core.perf.StartupTrace
import com.phoneagent.engine.AgentEngine
import com.phoneagent.core.ai.ApiProtocol
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
import com.phoneagent.engine.PromptLang
import com.phoneagent.feature.mcp.McpServerConfig
import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.feature.mcp.McpServerInfo
import com.phoneagent.data.store.McpStore
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.phoneagent.ui.vm.AgentTaskDelegate
import com.phoneagent.ui.vm.DocumentDelegate
import com.phoneagent.ui.vm.McpDelegate
import com.phoneagent.ui.vm.MemoryDelegate
import com.phoneagent.ui.vm.PermissionsDelegate
import com.phoneagent.ui.vm.SettingsDelegate
import com.phoneagent.ui.vm.ShizukuBootstrapDelegate
import com.phoneagent.ui.vm.SkillDelegate
import com.phoneagent.ui.vm.TestDelegate
import com.phoneagent.ui.vm.UpdateDelegate
import com.phoneagent.device.vision.StepShot
import com.phoneagent.device.vision.StepTrace

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

    /** 设置与新手引导域的实现在 [SettingsDelegate]；这里的属性与函数签名保持不变，UI 无感。 */
    private val settingsDomain = SettingsDelegate(settings, viewModelScope)

    val settingsFlow: StateFlow<AppSettings.Settings> get() = settingsDomain.settingsFlow

    /** 新手引导状态：null = 既没有门控镜像、也还没从 DataStore 读到真实值（详见 [SettingsDelegate]） */
    val onboardingState: StateFlow<Boolean?> get() = settingsDomain.onboardingState

    /** 结束新手引导：把完成标记落盘，下次启动直接进主界面 */
    fun completeOnboarding() = settingsDomain.completeOnboarding()

    // ---- Agent 执行域：引擎状态镜像 + 任务生命周期（实现在 [AgentTaskDelegate]） ----
    private val agentDomain = AgentTaskDelegate({ engine }, viewModelScope)

    // 引擎派生的状态一律惰性取：这些属性一旦在构造期求值，就会立刻拉起整张引擎对象图
    val agentState: StateFlow<AgentState> get() = agentDomain.agentState

    val logs: StateFlow<List<AgentLog>> get() = agentDomain.logs
    val conversation: StateFlow<List<ConversationMessage>> get() = agentDomain.conversation

    /**
     * 对话页的会话状态（气泡 + 同步游标）。
     *
     * 由 ViewModel 持有而不是页面 `rememberSaveable`：气泡随会话无限增长，
     * 序列化进 Bundle 会在长会话上顶到 1MB 上限。详见 [ChatSessionState]。
     */
    internal val chatSession = ChatSessionState()

    val metrics: StateFlow<AgentMetrics> get() = agentDomain.metrics
    val executionHistory: StateFlow<List<StepRecord>> get() = agentDomain.executionHistory
    val stepShot: StateFlow<com.phoneagent.device.vision.StepShot> get() = agentDomain.stepShot
    val traces: StateFlow<List<com.phoneagent.device.vision.StepTrace>> get() = agentDomain.traces
    /** 任务会话归档（Agent 页侧边栏）：最新一次任务在最前 */
    val taskSessions: StateFlow<List<com.phoneagent.engine.TaskSession>> get() = agentDomain.taskSessions
    /** 当前对话的起点：实时视图只铺开这之后的任务，承接块也只取这之后的往来 */
    val conversationStart: StateFlow<Long> get() = agentDomain.conversationStart
    /**
     * 最近一次任务的终态（成功 / 失败 / 用户停止）。
     *
     * 引擎收尾会把 phase 落回 IDLE，所以「刚才那次到底怎么结束的」只能从这里取；
     * 主界面据此渲染终态卡，而不是退回一张空态。
     */
    val lastOutcome: StateFlow<com.phoneagent.domain.model.TaskOutcome?> get() = engine.lastOutcome
    val taskQueue: StateFlow<List<String>> get() = agentDomain.taskQueue
    val needsUser: StateFlow<Boolean> get() = agentDomain.needsUser
    val userHintRequest get() = agentDomain.userHintRequest
    val planPhase: StateFlow<com.phoneagent.engine.PlanPhase> get() = agentDomain.planPhase
    val planStream: StateFlow<String> get() = agentDomain.planStream

    /** AI 正在生成的决策内容（流式回显到 Agent 页，空串表示当前无输出） */
    val decisionStream: StateFlow<String> get() = agentDomain.decisionStream

    /** 将显示给用户的文本自动翻译成简体中文（AI 翻译，带缓存） */
    suspend fun translate(text: String): String = agentDomain.translate(text)

    /** 缓存命中返回译文；未命中返回 null（调用方走 600ms 防抖 + 真实翻译），实现见 [AgentTaskDelegate] */
    fun cachedTranslation(text: String): String? = agentDomain.cachedTranslation(text)

    fun putTranslation(text: String, translated: String) = agentDomain.putTranslation(text, translated)

    /**
     * 测试连接，并把结果记进「主模型验证记录」。
     *
     * @param record 这次测试是不是在验证**主模型**。默认 true：模型页/快速开始/首次引导的
     *   「测试连接」按的都是主模型；设置页「测试所有 API」逐项测视觉、思考模型时传 false，
     *   否则测一次视觉模型就把主模型的「已验证」顶掉。
     */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: String = "openai",
        record: Boolean = true,
    ): Result<String> {
        val result = engine.testConnection(baseUrl, apiKey, model, ApiProtocol.fromId(protocol))
        if (record) {
            // 指纹按被测的那份配置算：测试的是表单里还没保存的新 Key 时，指纹与已保存配置不符，
            // 首页照样报「未验证」——没落盘的配置不能算已接入
            settings.recordModelVerification(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                ok = result.isSuccess,
                error = result.exceptionOrNull()?.message ?: "",
            )
        }
        return result
    }

    /** 拉取端点可用模型列表（设置页"获取模型"） */
    suspend fun listModels(baseUrl: String, apiKey: String, protocol: String = "openai"): Result<List<String>> =
        agentDomain.listModels(baseUrl, apiKey, protocol)

    /** 探测单个模型的能力（识图 / 工具调用） */
    suspend fun probeModel(baseUrl: String, apiKey: String, model: String, protocol: String = "openai"): Result<com.phoneagent.core.ai.ModelAbility> =
        agentDomain.probeModel(baseUrl, apiKey, model, protocol)

    fun startPlanning(task: String) = agentDomain.startPlanning(task)

    /** 规划失败卡的重试：重新规划同一条待规划任务 */
    fun retryPlanning() = agentDomain.retryPlanning()

    /** 对话页直提（对话模式，只作答不执行，语义见 [AgentTaskDelegate.submitChatTurn]） */
    fun submitChatTurn(task: String) = agentDomain.submitChatTurn(task)

    fun answerClarification(option: com.phoneagent.domain.model.ClarificationOption) = agentDomain.answerClarification(option)
    fun approvePlan() = agentDomain.approvePlan()
    fun cancelPlanning() = agentDomain.cancelPlanning()

    /**
     * 新建对话：引擎划断上下文并复位实时态，文档预览一并撤下。
     * 历史任务仍留在 [taskSessions] 里可回看，只是不再进入新对话的上下文。
     */
    fun newConversation() {
        agentDomain.newConversation()
        documentEngine.dismiss()
    }

    // ---- 悬浮窗交互桥接：气泡窗按钮动作转发到引擎 ----
    // 注册动作在 AgentEngine.init 里（引擎是单例，注册生命周期必须跟引擎走）：
    // 旧实现挂在这里，界面一销毁回调就被清空，后台任务在悬浮窗上点「停止 / 批准」会没反应。

    override fun onCleared() {
        // 只做解绑，不停任务：引擎是 Koin 单例，任务生命周期不归界面管。
        // 旧实现在这里 engine.stop()，导致「退出界面 / 系统回收 Activity」= 任务被中断——
        // 长线任务只要切走就白跑。停止任务必须由用户显式操作（Agent 页停止 / 悬浮窗 / 通知栏）。
        super.onCleared()
    }

    fun clearDebug() = agentDomain.clearDebug()
    fun provideUserHint(hint: String) = agentDomain.provideUserHint(hint)
    fun dismissUser() = agentDomain.dismissUser()

    /** 正在等待用户确认的 AI 自写命令（null = 没有待确认项）；驱动 App 内协助面板的「批准执行 / 拒绝」 */
    val pendingShellCommand: StateFlow<String?> get() = agentDomain.pendingShellCommand
    fun resolveShellApproval(approve: Boolean) = agentDomain.resolveShellApproval(approve)

    // ---- 不可逆动作确认门：App 内任务流的确认卡与悬浮窗共用引擎的同一信箱 ----
    /** 引擎正在等待用户确认的不可逆动作（null = 无待确认项）；驱动任务流里的确认卡片 */
    val pendingActionConfirmation: StateFlow<AgentEngine.PendingActionConfirmation?> get() =
        agentDomain.pendingActionConfirmation
    /** 确认执行该动作 */
    fun confirmPendingAction() = agentDomain.confirmPendingAction()
    /** 拒绝该动作（AI 会换做法或中止该步） */
    fun denyPendingAction() = agentDomain.denyPendingAction()

    // ---- 权限域：实现在 [PermissionsDelegate] ----
    private val permissionsDomain = PermissionsDelegate(shizukuManager, termuxBridge, viewModelScope)

    val a11yEnabled: StateFlow<Boolean> get() = permissionsDomain.a11yEnabled

    val screenshotActive: StateFlow<Boolean> get() = permissionsDomain.screenshotActive

    val overlayGranted: StateFlow<Boolean> get() = permissionsDomain.overlayGranted

    /** Shizuku 连接状态 */
    val shizukuState: StateFlow<ShizukuManager.State> get() = permissionsDomain.shizukuState

    fun requestShizukuPermission(onResult: (Boolean) -> Unit) = permissionsDomain.requestShizukuPermission(onResult)

    // ---- Termux 执行通道（普通应用权限的 Linux 环境，非系统 shell） ----
    val termuxStatus: StateFlow<TermuxStatus> get() = permissionsDomain.termuxStatus

    /** 刷新 Termux 安装 / 授权状态（不实跑，开销小，进页面时调用） */
    fun refreshTermuxStatus() = permissionsDomain.refreshTermuxStatus()

    /**
     * 实跑一次探测命令，确认 `allow-external-apps=true` 等 Termux 侧配置真的到位。
     * 安装与授权都正常但探测失败时，通常是 Termux 里没开 allow-external-apps。
     */
    fun probeTermux() = permissionsDomain.probeTermux()

    /**
     * 静默授予 Termux 执行权限（RUN_COMMAND）给服务组件（细节见 [PermissionsDelegate.grantTermuxPermission]）。
     */
    fun grantTermuxPermission() = permissionsDomain.grantTermuxPermission()

    fun refreshOverlayPermission(context: Context) = permissionsDomain.refreshOverlayPermission(context)

    /** 打开系统悬浮窗授权页 */
    fun openOverlaySettings(context: Context) = permissionsDomain.openOverlaySettings(context)

    fun refreshStatus(context: Context) = permissionsDomain.refreshStatus(context)

    // ---- 权限雷达：聚合各类关键权限状态，一键跳转授权 ----
    val permissions: StateFlow<List<PermissionItem>> get() = permissionsDomain.permissions

    fun refreshPermissions(context: Context) = permissionsDomain.refreshPermissions(context)

    /** 一键跳转到对应权限授权页 */
    fun openPermissionSettings(context: Context, kind: PermissionKind) =
        permissionsDomain.openPermissionSettings(context, kind)

    /** 请求截屏权限（MediaProjection），resultCode/data 由 Activity 回传 */
    fun requestScreenshot(context: Context) = permissionsDomain.requestScreenshot(context)

    /** Activity 返回授权结果后启动截图服务 */
    fun startScreenshotService(activity: Activity, resultCode: Int, data: Intent?) =
        permissionsDomain.startScreenshotService(activity, resultCode, data)

    fun stopScreenshot() = permissionsDomain.stopScreenshot()

    fun saveSettings(value: AppSettings.Settings) = settingsDomain.saveSettings(value)

    /**
     * 保存并等落盘完成（用途见 [SettingsDelegate.saveSettingsNow]）。
     */
    suspend fun saveSettingsNow(value: AppSettings.Settings) = settingsDomain.saveSettingsNow(value)

    // ---- 自动更新：查询新版本、下载安装（实现在 [UpdateDelegate]） ----
    private val updateDomain = UpdateDelegate(updateManager)

    val updateState: StateFlow<com.phoneagent.feature.update.UpdateState> get() = updateDomain.updateState

    /**
     * 查询更新。返回是否发现新版本——启动时的静默检查据此决定要不要打扰用户。
     * @param baseUrl 官网地址，留空由更新模块回落到官方站点
     */
    suspend fun checkUpdate(baseUrl: String, channel: com.phoneagent.feature.update.UpdateChannel): Boolean =
        updateDomain.checkUpdate(baseUrl, channel)

    /** 下载并安装。下载跑在更新模块自己的作用域里，退出设置页也不会中断 */
    fun downloadAndInstallUpdate(
        release: com.phoneagent.feature.update.ReleaseInfo,
        download: com.phoneagent.feature.update.DownloadInfo,
    ) = updateDomain.downloadAndInstallUpdate(release, download)

    /** 由 UI 在页面可见时调用，与 [refreshStatus] 走同一个口径（开关已开 + 实例已连接） */
    fun refreshA11yState(context: Context) = permissionsDomain.refreshA11yState(context)

    fun startAgent(task: String) = agentDomain.startAgent(task)

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
            settingsDomain.saveSettingsNow(value)
            if (agentState.value.isRunning) startAgent(task) else startPlanning(task)
        }
    }

    fun stopAgent() = agentDomain.stopAgent()

    /** 当前任务是否被用户暂停；驱动状态条在「暂停 / 继续」之间切换 */
    val paused: StateFlow<Boolean> get() = agentDomain.paused

    /** 暂停当前任务（主循环在下一轮开头挂起，不消耗步数） */
    fun pauseAgent() = agentDomain.pauseAgent()

    /** 从暂停处继续执行 */
    fun resumeAgent() = agentDomain.resumeAgent()

    /** 重试最近一次任务（失败卡的「重试」出口） */
    fun retryLastTask() = agentDomain.retryLastTask()

    // ---- 长线任务：执行策略 / 断点续传 / 模板库 ----
    suspend fun currentStrategy(): com.phoneagent.data.store.ExecutionStrategy = agentDomain.currentStrategy()
    fun setExecutionStrategy(s: com.phoneagent.data.store.ExecutionStrategy) = agentDomain.setExecutionStrategy(s)
    fun resumeFromCheckpoint() = agentDomain.resumeFromCheckpoint()
    suspend fun lastCheckpoint(): com.phoneagent.data.store.Checkpoint? = agentDomain.lastCheckpoint()
    /** 清除长线任务的续传检查点（不影响任务模板） */
    fun clearCheckpoint(context: Context) = agentDomain.clearCheckpoint(context)
    suspend fun loadTemplates(context: Context): List<com.phoneagent.data.store.TaskTemplate> =
        agentDomain.loadTemplates(context)
    fun deleteTemplate(context: Context, id: String) = agentDomain.deleteTemplate(context, id)

    // ---- AI 标准化测试（实现在 [TestDelegate]） ----
    private val testDomain = TestDelegate(testEngine, viewModelScope)

    val testConfig: StateFlow<TestConfig> get() = testDomain.testConfig
    val testRunning: StateFlow<Boolean> get() = testDomain.testRunning
    val testSummary: StateFlow<TestRunSummary> get() = testDomain.testSummary
    val testStreamText: StateFlow<String> get() = testDomain.testStreamText
    val testLanguage: StateFlow<PromptLang> get() = testDomain.testLanguage
    val testCategory: StateFlow<String?> get() = testDomain.testCategory

    fun updateTestConfig(baseUrl: String, model: String, apiKey: String) =
        testDomain.updateTestConfig(baseUrl, model, apiKey)

    fun setTestLanguage(lang: PromptLang) = testDomain.setTestLanguage(lang)
    fun setTestCategory(category: String?) = testDomain.setTestCategory(category)
    fun getLanguageLabel(lang: PromptLang): String = testDomain.getLanguageLabel(lang)

    fun runTest(preset: TestPreset) = testDomain.runTest(preset)

    fun resetTest() = testDomain.resetTest()

    // ---- 文档结果：AI 生成的文档直接在 Agent 页任务流里预览（实现在 [DocumentDelegate]） ----
    private val documentDomain = DocumentDelegate(documentEngine)

    val docResult: StateFlow<DocResult?> get() = documentDomain.docResult

    fun dismissDoc() = documentDomain.dismissDoc()

    // ---- 记忆域：实现在 [MemoryDelegate] ----
    private val memoryDomain = MemoryDelegate(memoryStore, { engine }, viewModelScope)

    /** 本次任务内 AI 写入的记忆事件（引擎内存态），Agent 页据此实时插卡 */
    val memoryEvents: StateFlow<List<com.phoneagent.engine.MemoryEvent>> get() = memoryDomain.memoryEvents

    /** 本次任务内 AI "对用户说话"的事件（引擎内存态），Agent 页据此实时出气泡 */
    val sayEvents: StateFlow<List<com.phoneagent.engine.SayEvent>> get() = memoryDomain.sayEvents

    /** 本对话内 AI 澄清 → 用户选择 的往来（引擎内存态），Agent 页据此保留 问题 + 选择 */
    val clarifyEvents: StateFlow<List<com.phoneagent.engine.ClarifyAnswered>> get() = memoryDomain.clarifyEvents

    /**
     * 合并形态下的纯对话问答（引擎内存态）：Agent 页把「对话」Tab 的产出也画在同一条时间线上。
     * 与 [conversation] 不同，这里只存用户看得懂的 问题 + 回答，不含内部决策提示词。
     */
    val chatTurns: StateFlow<List<com.phoneagent.engine.runtime.ChatTurnEvent>> get() = memoryDomain.chatTurns

    /** 撤销一条刚写入的记忆：删库 + 从任务流移除卡片 */
    fun undoMemory(id: Long) = memoryDomain.undoMemory(id)

    val memoryAnomalies: StateFlow<List<AnomalyMemoryEntry>> get() = memoryDomain.memoryAnomalies

    val memoryProfile: StateFlow<List<ProfileEntry>> get() = memoryDomain.memoryProfile

    val memoryTaskMemories: StateFlow<List<TaskMemoryEntry>> get() = memoryDomain.memoryTaskMemories

    /** 页面记忆：任务执行自动沉淀的页面热点 */
    val pageMemories: StateFlow<List<com.phoneagent.data.store.PageMemoryEntry>> get() = memoryDomain.pageMemories

    /** 页面路径边：页面间的跳转记录 */
    val pagePathEdges: StateFlow<List<com.phoneagent.data.store.PagePathEdge>> get() = memoryDomain.pagePathEdges

    val memoryLoading: StateFlow<Boolean> get() = memoryDomain.memoryLoading

    /** 加载记忆数据（重新编排图谱） */
    fun refreshMemory() = memoryDomain.refreshMemory()

    /** 清空异常记忆 */
    fun clearAnomalyMemory() = memoryDomain.clearAnomalyMemory()

    /** 清空用户画像 */
    fun clearProfileMemory() = memoryDomain.clearProfileMemory()

    /** 删除单条任务记忆 */
    fun deleteTaskMemory(id: Long) = memoryDomain.deleteTaskMemory(id)

    /** 清空任务记忆 */
    fun clearTaskMemories() = memoryDomain.clearTaskMemories()

    /** 删除单条页面记忆（连带删除引用它的路径边） */
    fun deletePageMemory(id: Long) = memoryDomain.deletePageMemory(id)

    /** 清空页面记忆与路径 */
    fun clearPageMemories() = memoryDomain.clearPageMemories()

    // ---- 撤销删除 / 撤销清空（记忆页的撤销窗口用；实现与其余记忆域一起在 [MemoryDelegate]）----
    fun restoreAnomalies(entries: List<AnomalyMemoryEntry>) = memoryDomain.restoreAnomalies(entries)

    fun restoreProfile(entries: List<ProfileEntry>) = memoryDomain.restoreProfile(entries)

    fun restoreTaskMemories(entries: List<TaskMemoryEntry>) = memoryDomain.restoreTaskMemories(entries)

    fun restorePageMemories(entries: List<PageMemoryEntry>, edges: List<PagePathEdge>) =
        memoryDomain.restorePageMemories(entries, edges)

    // ---- 日志导出（实现见 data/export/LogExporter.kt，由 [AgentTaskDelegate] 转发） ----
    /** 把指定任务（或全部）的日志导出为文本文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存路径，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogs(context: Context, taskId: Long, taskName: String?): String =
        agentDomain.exportLogs(context, taskId, taskName)

    /** 分任务批量导出：每个任务导出一个独立 JSON 文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存汇总，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogsJsonAll(context: Context): String = agentDomain.exportLogsJsonAll(context)

    // ==================== 八、诊断报告（人话 + 原始 两区，v2.2.1） ====================
    /** 导出诊断报告：人话摘要区 + 原始数据区，导出前自动脱敏。
     *  @return 保存路径或错误信息（以 "ERR:" 开头） */
    fun exportDiagnosticReport(context: Context): String = agentDomain.exportDiagnosticReport(context)

    // ==================== HPA 迭代 A7：Skill / MCP / 提示词 / 无线 ADB UI ====================

    // ---- 技能域（内置技能库 + ACA 技能目录）：实现在 [SkillDelegate] ----
    private val skillDomain = SkillDelegate(skillRegistry, viewModelScope)

    val skills: StateFlow<List<Skill>> get() = skillDomain.skills

    fun skillAll(): List<Skill> = skillDomain.skillAll()

    val acaSkills: StateFlow<List<com.phoneagent.coding.api.AcaSkillSummary>> get() = skillDomain.acaSkills

    /** 扫描进行中（true 时主页显示"正在扫描"占位） */
    val acaScanning: StateFlow<Boolean> get() = skillDomain.acaScanning

    /** 损坏包的「目录名：原因」列表，主页以弱化文本提示 */
    val acaSkillErrors: StateFlow<List<String>> get() = skillDomain.acaSkillErrors

    /** 重新扫描 ACA 技能目录（流式追加、60 秒 TTL、重入与取消语义见 [SkillDelegate.refreshAcaSkills]） */
    fun refreshAcaSkills(context: Context, force: Boolean = false) =
        skillDomain.refreshAcaSkills(context, force)

    /** 在新增时自动分配一个未占用的 id */
    fun nextSkillId(prefix: String = "skill_user"): String = skillDomain.nextSkillId(prefix)

    fun addSkill(skill: Skill) = skillDomain.addSkill(skill)

    fun updateSkill(skill: Skill) = skillDomain.updateSkill(skill)

    fun removeSkill(id: String) = skillDomain.removeSkill(id)

    /**
     * 批量删除：解析与删除在 Default 线程执行（含持久化 IO），结果回主线程后再刷新快照。
     * @param onResult 主线程回调，参数为成功删除的数量
     */
    fun removeSkills(ids: Set<String>, onResult: (Int) -> Unit = {}) = skillDomain.removeSkills(ids, onResult)

    fun setSkillEnabled(id: String, enabled: Boolean) = skillDomain.setSkillEnabled(id, enabled)

    /**
     * 导入技能清单（JSON 文本）：JSON 解析在 Default 线程执行，结果回主线程刷新快照。
     * @param onResult 主线程回调，参数为导入数量（解析失败按 0 处理，不抛到主线程）
     */
    fun importSkills(text: String, onResult: (Int) -> Unit = {}) = skillDomain.importSkills(text, onResult)

    fun exportSkills(): String = skillDomain.exportSkills()

    // ---- MCP 服务器域：实现在 [McpDelegate] ----
    private val mcpDomain =
        McpDelegate(mcpManager, mcpStore, skillRegistry, viewModelScope) { skillDomain.refreshSkills() }

    val mcpServers: StateFlow<List<McpServerConfig>> get() = mcpDomain.mcpServers

    /** 新增 MCP 服务器；返回空串=成功，否则返回校验失败原因 */
    fun addMcpServer(name: String, url: String, token: String = ""): String = mcpDomain.addMcpServer(name, url, token)

    fun removeMcpServer(name: String) = mcpDomain.removeMcpServer(name)

    fun setMcpEnabled(name: String, enabled: Boolean) = mcpDomain.setMcpEnabled(name, enabled)

    suspend fun checkMcpServer(server: String): String = mcpDomain.checkMcpServer(server)

    /** 握手 + 枚举，返回结构化服务器信息（能力/协议/工具/参数，供 UI 展示） */
    suspend fun describeMcpServer(server: String): McpServerInfo? = mcpDomain.describeMcpServer(server)

    /** 服务器最近一次请求/响应原文（需求 5：展示请求 JSON） */
    fun mcpLastRequest(server: String): String = mcpDomain.mcpLastRequest(server)
    fun mcpLastResponse(server: String): String = mcpDomain.mcpLastResponse(server)

    suspend fun bindMcpServerSkills(server: String): Int = mcpDomain.bindMcpServerSkills(server)

    /** 内置 MCP 市场条目（分类/搜索） */
    val mcpMarketplace: List<McpMarketplaceEntry> get() = mcpDomain.mcpMarketplace
    fun mcpMarketplaceByCategory(category: String): List<McpMarketplaceEntry> =
        mcpDomain.mcpMarketplaceByCategory(category)
    fun mcpMarketplaceSearch(keyword: String): List<McpMarketplaceEntry> =
        mcpDomain.mcpMarketplaceSearch(keyword)

    // ---- 内置 Shizuku 启动域：实现在 [ShizukuBootstrapDelegate] ----
    private val shizukuBootstrapDomain = ShizukuBootstrapDelegate(shizukuBootstrap, adbPairingFlow, viewModelScope)

    val adbStatus: StateFlow<AdbStatus> get() = shizukuBootstrapDomain.adbStatus

    /** 一键启动/检测内置 Shizuku 通路：binder 可用直接就绪，否则经引导会话拉起内置 server */
    fun ensureShizukuReady(onResult: (String) -> Unit) = shizukuBootstrapDomain.ensureShizukuReady(onResult)

    /** 用界面输入的 6 位配对码完成首次启动引导，并就绪内置 Shizuku 通路 */
    fun pairShizuku(code: String, onResult: (String) -> Unit) = shizukuBootstrapDomain.pairShizuku(code, onResult)

    // ---- 首次启动引导的通知栏向导（沿用无线 ADB 配对流程，仅作为内置 Shizuku 的引导机制） ----
    val adbPairingMessage: String get() = shizukuBootstrapDomain.adbPairingMessage

    val adbPairingMessageFlow: StateFlow<String> get() = shizukuBootstrapDomain.adbPairingMessageFlow

    /** 点击「开始引导」：后台搜索系统无线调试服务，搜到后通过通知栏请求配对码 */
    fun startAdbDiscovery() = shizukuBootstrapDomain.startAdbDiscovery()

    fun cancelAdbPairing() = shizukuBootstrapDomain.cancelAdbPairing()
}