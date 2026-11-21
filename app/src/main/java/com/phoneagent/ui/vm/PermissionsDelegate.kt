package com.phoneagent.ui.vm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.device.screen.ScreenSharingService
import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.device.shell.TermuxBridge
import com.phoneagent.device.shell.TermuxStatus
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 通知权限的运行时请求码（Android 13+） */
private const val REQUEST_NOTIFICATIONS = 4401

/** 麦克风权限的运行时请求码（权限雷达与输入框录音按钮各自独立，互不干扰） */
private const val REQUEST_RECORD_AUDIO = 4402

/**
 * 权限域：无障碍 / 悬浮窗 / 截图 / Termux / Shizuku 状态，以及权限雷达的聚合与跳转。
 *
 * 界面只传 [Context] 进来（原有签名就是这样），所以本类不需要持有 Activity；
 * 唯一的协程入口是 Termux 的刷新与授权，跑在传进来的 [scope]（`viewModelScope`）上。
 */
@Suppress("TooManyFunctions")
class PermissionsDelegate(
    private val shizukuManager: ShizukuManager,
    private val termuxBridge: TermuxBridge,
    private val scope: CoroutineScope,
) {

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
        scope.launch {
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
        scope.launch {
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
        scope.launch {
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

    @Suppress("LongMethod")
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
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
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
            val asksAtRuntime = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                context is Activity
            if (!granted && asksAtRuntime) {
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
            val direct = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
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
    @Suppress("UnusedPrivateProperty")
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
    /** 由 UI 在页面可见时调用，与 [refreshStatus] 走同一个口径（开关已开 + 实例已连接） */
    fun refreshA11yState(context: Context) {
        _a11yEnabled.value = a11yUsable(context)
    }}
