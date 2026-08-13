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
import com.phoneagent.model.AgentMetrics
import com.phoneagent.model.AgentState
import com.phoneagent.model.ConversationMessage
import com.phoneagent.model.PermissionItem
import com.phoneagent.model.PermissionKind
import com.phoneagent.model.StepRecord
import com.phoneagent.screen.ScreenSharingService
import com.phoneagent.agent.PromptLang
import com.phoneagent.test.TestConfig
import com.phoneagent.test.TestEngine
import com.phoneagent.test.TestPreset
import com.phoneagent.test.TestRunSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun clearDebug() = engine.clearDebug()
    fun provideUserHint(hint: String) = engine.provideUserHint(hint)
    fun dismissUser() = engine.dismissUser()

    private val _a11yEnabled = MutableStateFlow(false)
    val a11yEnabled: StateFlow<Boolean> get() = _a11yEnabled.asStateFlow()

    private val _screenshotActive = MutableStateFlow(false)
    val screenshotActive: StateFlow<Boolean> get() = _screenshotActive.asStateFlow()

    private val _overlayGranted = MutableStateFlow(false)
    val overlayGranted: StateFlow<Boolean> get() = _overlayGranted.asStateFlow()

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

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }
}