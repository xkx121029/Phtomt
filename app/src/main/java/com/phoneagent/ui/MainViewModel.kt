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
import com.phoneagent.a11y.AgentAccessibilityService
import com.phoneagent.agent.AgentEngine
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.model.AgentLog
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import com.phoneagent.model.AgentMetrics
import com.phoneagent.model.AgentState
import com.phoneagent.model.ConversationMessage
import com.phoneagent.model.PermissionItem
import com.phoneagent.model.PermissionKind
import com.phoneagent.model.StepRecord
import com.phoneagent.memory.AnomalyMemoryEntry
import com.phoneagent.memory.MemoryStore
import com.phoneagent.memory.ProfileEntry
import com.phoneagent.screen.ScreenSharingService
import com.phoneagent.agent.PromptLang
import com.phoneagent.shizuku.ShizukuManager
import com.phoneagent.test.TestConfig
import com.phoneagent.test.TestEngine
import com.phoneagent.test.TestPreset
import com.phoneagent.test.TestRunSummary
import com.phoneagent.workspace.WorkAreaEngine
import com.phoneagent.workspace.WorkDisplay
import com.phoneagent.workspace.EditChatMessage
import com.phoneagent.workspace.WorkFile
import com.phoneagent.workspace.WorkLog
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
    private val workAreaEngine: WorkAreaEngine,
    private val memoryStore: MemoryStore,
) : ViewModel() {

    val settingsFlow: StateFlow<AppSettings.Settings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings.Settings())

    val agentState: StateFlow<AgentState> = engine.state

    val logs: StateFlow<List<AgentLog>> = engine.logs
    val conversation: StateFlow<List<ConversationMessage>> = engine.conversation
    val metrics: StateFlow<AgentMetrics> = engine.metrics
    val executionHistory: StateFlow<List<StepRecord>> = engine.executionHistory
    val taskQueue: StateFlow<List<String>> = engine.taskQueue
    val needsUser: StateFlow<Boolean> = engine.needsUser
    val userHintRequest = engine.userHintRequest
    val planPhase: StateFlow<com.phoneagent.agent.PlanPhase> = engine.planPhase
    val planStream: StateFlow<String> = engine.planStream

    /** 将显示给用户的文本自动翻译成简体中文（AI 翻译，带缓存） */
    suspend fun translate(text: String): String = engine.translateText(text)

    /** 测试主模型连接是否可用（设置保存前校验用） */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> =
        engine.testConnection(baseUrl, apiKey, model)

    fun startPlanning(task: String) = engine.startPlanning(task)
    fun answerClarification(option: com.phoneagent.model.ClarificationOption) = engine.answerClarification(option)
    fun approvePlan() = engine.approvePlan()
    fun cancelPlanning() = engine.cancelPlanning()

    // ---- 悬浮窗交互桥接：气泡窗按钮动作转发到引擎 ----
    init {
        com.phoneagent.floating.FloatingWindowService.onInteraction = { action, payload ->
            when (action) {
                "approve" -> approvePlan()
                "cancel" -> cancelPlanning()
                "clarify" -> answerClarification(com.phoneagent.model.ClarificationOption(id = payload, label = payload, description = payload))
                "hint" -> provideUserHint(payload)
                "dismiss" -> dismissUser()
                // 关闭悬浮窗 → 同步停止正在运行的任务
                "close" -> engine.stop()
            }
        }
    }

    override fun onCleared() {
        com.phoneagent.floating.FloatingWindowService.onInteraction = null
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

    // ---- 工作区：AI 文档生成 ----
    val workFiles: StateFlow<List<WorkFile>> = workAreaEngine.files
    val workGenerating: StateFlow<Boolean> = workAreaEngine.isGenerating
    val workActiveFile: StateFlow<String?> = workAreaEngine.activeFile
    val workPreview: StateFlow<String> = workAreaEngine.previewContent
    val workLogs: StateFlow<List<WorkLog>> = workAreaEngine.logs
    val workError: StateFlow<String> = workAreaEngine.error
    val workDisplay: StateFlow<WorkDisplay?> = workAreaEngine.display

    fun workRefreshFiles() = workAreaEngine.refreshFiles()
    fun workReadFile(name: String): String = workAreaEngine.readFile(name)
    fun workDeleteFile(name: String) = workAreaEngine.deleteFile(name)
    fun workGenerate(task: String, fileName: String = "") = workAreaEngine.generateDocument(task, fileName)
    fun workStop() = workAreaEngine.stop()
    fun workClearLogs() = workAreaEngine.clearLogs()
    fun workShowFile(name: String) = workAreaEngine.showFile(name)
    fun workDismissDisplay() = workAreaEngine.dismissDisplay()

    // ---- 工作区：文档预览编辑（AI 改写） ----
    val workEditingFile: StateFlow<String?> = workAreaEngine.editingFile
    val workEditingContent: StateFlow<String> = workAreaEngine.editingContent
    val workEditBusy: StateFlow<Boolean> = workAreaEngine.editBusy
    val workEditChat: StateFlow<List<EditChatMessage>> = workAreaEngine.editChat
    val workEditStream: StateFlow<String> = workAreaEngine.editStream
    val workEditError: StateFlow<String> = workAreaEngine.editError

    fun workOpenEditor(name: String) = workAreaEngine.openEditor(name)
    fun workCloseEditor() = workAreaEngine.closeEditor()
    fun workEditDocument(instruction: String) = workAreaEngine.editDocument(instruction)

    // ---- AI 记忆图谱 ----
    private val _memoryAnomalies = MutableStateFlow<List<AnomalyMemoryEntry>>(emptyList())
    val memoryAnomalies: StateFlow<List<AnomalyMemoryEntry>> get() = _memoryAnomalies.asStateFlow()

    private val _memoryProfile = MutableStateFlow<List<ProfileEntry>>(emptyList())
    val memoryProfile: StateFlow<List<ProfileEntry>> get() = _memoryProfile.asStateFlow()

    private val _memoryLoading = MutableStateFlow(false)
    val memoryLoading: StateFlow<Boolean> get() = _memoryLoading.asStateFlow()

    /** 加载记忆数据（重新编排图谱） */
    fun refreshMemory() {
        viewModelScope.launch {
            _memoryLoading.value = true
            _memoryAnomalies.value = memoryStore.loadAnomalies()
            _memoryProfile.value = memoryStore.loadProfile()
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

    // ---- 日志导出 ----
    /** 把指定任务（或全部）的日志导出为文本文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存路径，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogs(context: Context, taskId: Long, taskName: String?): String {
        return runCatching {
            val entries = if (taskId < 0) logs.value else logs.value.filter { it.taskId == taskId }
            if (entries.isEmpty()) return@runCatching "ERR:没有可导出的日志"

            val sb = StringBuilder()
            sb.appendLine("Happy Phone Agent 运行日志")
            sb.appendLine("导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            sb.appendLine("任务：${taskName ?: "全部"}  ·  共 ${entries.size} 条")
            sb.appendLine("═".repeat(48))
            entries.forEach { e ->
                sb.append("[${levelTag(e.level)}] ${formatTs(e.timestamp)} ${e.message}")
                e.detail?.let { sb.appendLine("\n$it") }
                sb.appendLine()
            }

            val resolver = context.contentResolver
            val fileName = "hpa_logs_${taskName?.take(12)?.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_") ?: "all"}_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.txt"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/HappyPhoneAgent")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching "ERR:无法创建导出文件"
            resolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
                ?: return@runCatching "ERR:无法写入导出文件"
            "已导出到 下载/HappyPhoneAgent/$fileName"
        }.getOrElse { "ERR:${it.message ?: "导出失败"}" }
    }

    /** 分任务批量导出：每个任务导出一个独立 JSON 文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存汇总，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogsJsonAll(context: Context): String {
        return runCatching {
            val all = logs.value
            val byTask = all.filter { it.taskId >= 0 }.groupBy { it.taskId }
            val groups = mutableListOf<Triple<Long, String?, List<AgentLog>>>()
            byTask.values.forEach { g -> groups += Triple(g.first().taskId, g.first().taskName, g) }
            val sys = all.filter { it.taskId < 0 }
            if (sys.isNotEmpty()) groups += Triple(-1L, "系统日志", sys)
            if (groups.isEmpty()) return@runCatching "ERR:没有可导出的日志"

            val resolver = context.contentResolver
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val written = mutableListOf<String>()
            groups.forEach { (tid, name, list) ->
                val safeName = name?.take(12)?.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_") ?: "task"
                val fileName = if (tid < 0) "hpa_logs_system_$stamp.json" else "hpa_logs_${tid}_${safeName}_$stamp.json"
                val jsonObj = kotlinx.serialization.json.buildJsonObject {
                    put("app", "Happy Phone Agent")
                    put("exported_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                    put("task_id", tid)
                    put("task_name", name ?: "")
                    put("log_count", list.size)
                    put("logs", kotlinx.serialization.json.buildJsonArray {
                        list.forEach { l ->
                            add(
                                kotlinx.serialization.json.buildJsonObject {
                                    put("ts", l.timestamp)
                                    put("time", formatTs(l.timestamp))
                                    put("level", l.level.name)
                                    put("message", l.message)
                                    l.detail?.takeIf { it.isNotBlank() }?.let { put("detail", it) }
                                }
                            )
                        }
                    })
                }
                val json = jsonObj.toString()
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/HappyPhoneAgent")
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching "ERR:无法创建导出文件"
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: return@runCatching "ERR:无法写入导出文件"
                written += fileName
            }
            "已批量导出 ${written.size} 个任务 JSON 到 下载/HappyPhoneAgent/"
        }.getOrElse { "ERR:${it.message ?: "导出失败"}" }
    }

    private fun levelTag(lvl: AgentLog.Level): String = when (lvl) {
        AgentLog.Level.ERROR -> "错误"
        AgentLog.Level.WARN -> "警告"
        AgentLog.Level.AI -> "AI"
        AgentLog.Level.INFO -> "信息"
        AgentLog.Level.API -> "API"
    }

    private fun formatTs(t: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(t))

}