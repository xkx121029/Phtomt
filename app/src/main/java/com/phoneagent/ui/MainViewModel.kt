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
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.engine.AgentEngine
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.data.export.LogExporter
import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.device.screen.ScreenSharingService
import com.phoneagent.engine.PromptLang
import com.phoneagent.feature.mcp.McpServerConfig
import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.feature.mcp.McpServerInfo
import com.phoneagent.data.store.McpStore
import com.phoneagent.feature.mcp.McpMarketplace
import com.phoneagent.feature.mcp.McpMarketplaceEntry
import com.phoneagent.engine.prompt.PromptTemplate
import com.phoneagent.data.store.PromptTemplateStore
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局共享 ViewModel：设置、Agent 运行状态、无障碍/截图权限。
 */
class MainViewModel(
    private val settings: AppSettings,
    private val engine: AgentEngine,
    private val testEngine: TestEngine,
    private val shizukuManager: ShizukuManager,
    private val termuxBridge: TermuxBridge,
    private val documentEngine: DocumentEngine,
    private val memoryStore: MemoryStore,
    private val skillRegistry: SkillRegistry,
    private val mcpManager: McpManager,
    private val mcpStore: McpStore,
    private val promptTemplateStore: PromptTemplateStore,
    private val shizukuBootstrap: ShizukuBootstrap,
    private val adbPairingFlow: WirelessAdbPairingFlow,
) : ViewModel() {

    val settingsFlow: StateFlow<AppSettings.Settings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings.Settings())

    val agentState: StateFlow<AgentState> = engine.state

    val logs: StateFlow<List<AgentLog>> = engine.logs
    val conversation: StateFlow<List<ConversationMessage>> = engine.conversation
    val metrics: StateFlow<AgentMetrics> = engine.metrics
    val executionHistory: StateFlow<List<StepRecord>> = engine.executionHistory
    val stepShot: StateFlow<com.phoneagent.domain.model.StepShot> = engine.stepShot
    val traces: StateFlow<List<com.phoneagent.domain.model.StepTrace>> = engine.traces
    /** 任务会话归档（Agent 页侧边栏）：最新一次任务在最前 */
    val taskSessions: StateFlow<List<com.phoneagent.engine.TaskSession>> = engine.taskSessions
    val taskQueue: StateFlow<List<String>> = engine.taskQueue
    val needsUser: StateFlow<Boolean> = engine.needsUser
    val userHintRequest = engine.userHintRequest
    val planPhase: StateFlow<com.phoneagent.engine.PlanPhase> = engine.planPhase
    val planStream: StateFlow<String> = engine.planStream

    /** AI 正在生成的决策内容（流式回显到 Agent 页，空串表示当前无输出） */
    val decisionStream: StateFlow<String> = engine.decisionStream

    /** 将显示给用户的文本自动翻译成简体中文（AI 翻译，带缓存） */
    suspend fun translate(text: String): String = engine.translateText(text)

    /** 测试主模型连接是否可用（设置保存前校验用） */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> =
        engine.testConnection(baseUrl, apiKey, model)

    /** 拉取端点可用模型列表（设置页"获取模型"） */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        engine.listModels(baseUrl, apiKey)

    /** 探测单个模型的能力（识图 / 工具调用） */
    suspend fun probeModel(baseUrl: String, apiKey: String, model: String): Result<com.phoneagent.core.ai.ModelAbility> =
        engine.probeModel(baseUrl, apiKey, model)

    fun startPlanning(task: String) = engine.startPlanning(task)
    fun answerClarification(option: com.phoneagent.domain.model.ClarificationOption) = engine.answerClarification(option)
    fun approvePlan() = engine.approvePlan()
    fun cancelPlanning() = engine.cancelPlanning()

    // ---- 悬浮窗交互桥接：气泡窗按钮动作转发到引擎 ----
    init {
        com.phoneagent.overlay.FloatingWindowService.onInteraction = { action, payload ->
            when (action) {
                "approve" -> approvePlan()
                "cancel" -> cancelPlanning()
                "clarify" -> answerClarification(com.phoneagent.domain.model.ClarificationOption(id = payload, label = payload, description = payload))
                "hint" -> provideUserHint(payload)
                "dismiss" -> dismissUser()
                // 任务完成：用户确认是否保存执行模板（主动确认才入库）
                "save_template" -> engine.confirmSaveTemplate(payload == "yes")
                // 关闭悬浮窗 → 同步停止正在运行的任务
                "close" -> engine.stop()
                // 收起悬浮窗（只留悬浮球）→ 搁置任务，AI 在下一轮前挂起；
                // 点悬浮球唤出 → 解除搁置，从当前步继续
                "hide" -> engine.pauseTask()
                "resume" -> engine.resumeTask()
            }
        }
        // 启动时加载持久化的 MCP 服务器配置
        viewModelScope.launch {
            val saved = mcpStore.load()
            if (saved.isNotEmpty() || mcpManager.servers.isNotEmpty()) {
                mcpManager.replaceAll(saved)
            }
        }
    }

    override fun onCleared() {
        com.phoneagent.overlay.FloatingWindowService.onInteraction = null
        engine.stop()
        super.onCleared()
    }

    fun clearDebug() = engine.clearDebug()
    fun provideUserHint(hint: String) = engine.provideUserHint(hint)
    fun dismissUser() = engine.dismissUser()

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

    fun refreshStatus(context: Context) {
        _a11yEnabled.value = AgentAccessibilityService.isServiceEnabled(context)
        refreshOverlayPermission(context)
        shizukuManager.refreshState()
    }

    // ---- 权限雷达：聚合各类关键权限状态，一键跳转授权 ----
    private val _permissions = MutableStateFlow<List<PermissionItem>>(emptyList())
    val permissions: StateFlow<List<PermissionItem>> get() = _permissions.asStateFlow()

    fun refreshPermissions(context: Context) {
        _permissions.value = listOf(
            PermissionItem(
                kind = PermissionKind.ACCESSIBILITY,
                title = "无障碍服务",
                description = "AI 接管手机执行操作的必需能力",
                granted = AgentAccessibilityService.isServiceEnabled(context),
            ),
            PermissionItem(
                kind = PermissionKind.OVERLAY,
                title = "悬浮窗",
                description = "实时显示任务进度与跑马灯",
                granted = Settings.canDrawOverlays(context),
            ),
            PermissionItem(
                kind = PermissionKind.AUTOSTART,
                title = "自启动与后台",
                description = "省电白名单 + 厂商自启动管理",
                granted = isIgnoringBatteryOptimizations(context),
            ),
            PermissionItem(
                kind = PermissionKind.QUERY_ALL_PACKAGES,
                title = "获取已安装程序",
                description = "用于识别并启动目标应用",
                granted = hasQueryAllPackages(context),
            ),
            PermissionItem(
                kind = PermissionKind.SHIZUKU,
                title = "Shizuku",
                description = "ADB 级权限，执行 shell 命令",
                granted = shizukuManager.isAvailable(),
            ),
        )
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasQueryAllPackages(context: Context): Boolean {
        // Android 11+ QUERY_ALL_PACKAGES 为 normal 权限，声明即授予
        // 实际检测：尝试查询大量包，若数量远超可见限制则已授权
        return try {
            val pm = context.packageManager
            // 声明了 QUERY_ALL_PACKAGES 后 checkSelfPermission 返回 GRANTED
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.QUERY_ALL_PACKAGES
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** 一键跳转到对应权限授权页 */
    fun openPermissionSettings(context: Context, kind: PermissionKind) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val intent = when (kind) {
            PermissionKind.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionKind.OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            PermissionKind.AUTOSTART -> {
                // 先尝试厂商自启动管理页，失败则回退到电池优化白名单页
                val manufacturerIntent = getAutostartIntent(context)
                manufacturerIntent ?: Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"))
            }
            PermissionKind.QUERY_ALL_PACKAGES ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            PermissionKind.SHIZUKU -> {
                val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                intent ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
            }
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

    fun refreshA11yState() {
        // 由 UI 在页面可见时调用，读取无障碍服务实例状态
        _a11yEnabled.value = AgentAccessibilityService.instance != null
    }

    fun startAgent(task: String) {
        if (task.isBlank()) return
        engine.start(task)
    }

    fun stopAgent() = engine.stop()

    // ---- 长线任务：执行策略 / 断点续传 / 模板库 ----
    suspend fun currentStrategy(): com.phoneagent.data.store.ExecutionStrategy = engine.currentStrategy()
    fun setExecutionStrategy(s: com.phoneagent.data.store.ExecutionStrategy) {
        viewModelScope.launch { engine.setExecutionStrategy(s) }
    }
    fun resumeFromCheckpoint() = engine.resumeFromCheckpoint()
    suspend fun lastCheckpoint(): com.phoneagent.data.store.Checkpoint? = engine.lastCheckpoint()
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

    private val _memoryLoading = MutableStateFlow(false)
    val memoryLoading: StateFlow<Boolean> get() = _memoryLoading.asStateFlow()

    /** 加载记忆数据（重新编排图谱） */
    fun refreshMemory() {
        viewModelScope.launch {
            _memoryLoading.value = true
            _memoryAnomalies.value = memoryStore.loadAnomalies()
            _memoryProfile.value = memoryStore.loadProfile()
            _memoryTaskMemories.value = memoryStore.loadTaskMemories()
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

    /** 批量删除：返回成功删除数量 */
    fun removeSkills(ids: Set<String>): Int {
        val n = skillRegistry.removeAll(ids)
        refreshSkills()
        return n
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { skillRegistry.setEnabled(id, enabled); refreshSkills() }
    }

    /** 导入技能清单（JSON 文本），返回导入数量 */
    fun importSkills(text: String): Int {
        val report = skillRegistry.importJson(text)
        refreshSkills()
        return report.imported
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

    // ---- 提示词模板库 ----
    private val _templates = MutableStateFlow<List<PromptTemplate>>(promptTemplateStore.all())
    val templates: StateFlow<List<PromptTemplate>> = _templates.asStateFlow()

    fun saveTemplate(id: String, name: String, body: String) {
        viewModelScope.launch {
            promptTemplateStore.upsert(PromptTemplate(id, name, body, isBuiltIn = promptTemplateStore.byId(id)?.isBuiltIn ?: false))
            _templates.value = promptTemplateStore.all()
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch { promptTemplateStore.remove(id); _templates.value = promptTemplateStore.all() }
    }

    // ---- Shizuku / 无线 ADB 状态 ----
    val adbStatus: StateFlow<AdbStatus> = shizukuBootstrap.status

    fun ensureAdbReady(onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(
                when (val r = shizukuBootstrap.ensureReady()) {
                    is ShizukuBootstrap.ReadyResult.Ready -> "执行通路已就绪（无线 ADB / Shizuku）"
                    is ShizukuBootstrap.ReadyResult.Error -> r.message
                }
            )
        }
    }

    /** 用界面输入的 6 位配对码完成无线 ADB 配对并建立执行通路 */
    fun pairAdb(code: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            if (code.length != 6 || !code.all { it.isDigit() }) {
                onResult("配对码须为 6 位数字")
                return@launch
            }
            onResult(
                when (val r = shizukuBootstrap.pairAndEnsureReady(code)) {
                    is ShizukuBootstrap.ReadyResult.Ready -> "配对成功，无线 ADB 执行通路已就绪"
                    is ShizukuBootstrap.ReadyResult.Error -> r.message
                }
            )
        }
    }

    // ---- 无线 ADB「开始配对」通知栏向导（模拟 Shizuku） ----
    val adbPairingMessage: String
        get() = adbPairingFlow.message

    val adbPairingMessageFlow: StateFlow<String> get() = adbPairingFlow.messageFlow

    /** 点击「开始配对」：后台搜索无线调试服务，搜到后通过通知栏请求配对码 */
    fun startAdbDiscovery() = adbPairingFlow.startDiscovery(viewModelScope)

    fun cancelAdbPairing() = adbPairingFlow.cancel()
}