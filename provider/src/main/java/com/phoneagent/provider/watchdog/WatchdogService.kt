package com.phoneagent.provider.watchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.phoneagent.provider.A11yBridge
import com.phoneagent.provider.ShellBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 看门狗前台服务：Provider 进程的保活锚点 + 通道自动修复。
 *
 * 为什么是前台服务：Provider 是无界面应用，进程没有可见组件兜底，
 * 主应用被杀/解绑后 AIDL 绑定消失，进程随时可能被系统回收——无障碍服务
 * 所在进程被回收后重拉成本高（尤其 Shizuku server 需要重新引导）。
 * FGS 以一条常驻低优先级通知为代价把进程钉在后台，是这类无 UI 组件
 * 唯一合规的常驻方式。
 *
 * 巡检内容（每 [WatchdogConfig.CHECK_INTERVAL_MS]）：
 * 1. 无障碍服务掉线且开启「自动修复」→ 持有 WRITE_SECURE_SETTINGS 时直接写回
 *    ENABLED_ACCESSIBILITY_SERVICES（server attach 时已 pm grant）；
 * 2. Shizuku 不在线且开启「自动恢复」→ 经无线 ADB 引导重拉（已配对可静默；
 *    失败按 [WatchdogConfig.SHIZUKU_BACKOFF_MS] 退避）。
 *
 * 生命周期：由 ProviderService.onCreate（配置开启时）/ setWatchdogConfig /
 * BootReceiver 启动；配置关闭时 stopSelf。与主应用的绑定无关——主应用解绑
 * 不停看门狗，这正是保活的意义。
 */
class WatchdogService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()
        scope.launch { loop() }
        Log.i(TAG, "看门狗已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        Log.i(TAG, "看门狗已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 巡检主循环：每步先读配置，配置被关闭则就地退出（AIDL 入口也会主动 stop，这里是兜底） */
    private suspend fun loop() {
        while (scope.isActive) {
            delay(WatchdogConfig.CHECK_INTERVAL_MS)
            runCatching { step() }.onFailure { Log.w(TAG, "巡检异常：${it.message}") }
        }
    }

    private suspend fun step() {
        val cfg = WatchdogConfig.load(this)
        if (!cfg.enabled) {
            stopSelf()
            return
        }

        // 1. 无障碍自动修复：两级链路——优先直写安全设置（进程已获 WRITE_SECURE_SETTINGS
        //    时零依赖），失败再经 Shizuku 执行 settings 命令兜底
        if (cfg.autoRepairA11y && !A11yBridge.isConnected()) {
            when {
                // 已授权：直写；返回 false 表示服务仍在启用列表（系统自会重绑），无需动作
                WatchdogConfig.canRepairA11y(this) -> {
                    val repaired = runCatching { WatchdogConfig.repairAccessibility(this) }.getOrDefault(false)
                    if (repaired) {
                        WatchdogConfig.recordRepair(this, "无障碍服务掉线，已自动重新启用（直写安全设置）")
                        Log.i(TAG, "已自动重新启用无障碍服务（直写）")
                    }
                }
                // 未授权但有可用 Shizuku：settings 命令兜底；带 60s 退避防服务崩溃时反复拉起
                ShellBridge.shizukuState() == 2 && WatchdogConfig.a11yBackoffElapsed() -> {
                    WatchdogConfig.a11yNextAttemptAt =
                        SystemClock.elapsedRealtime() + A11Y_SHIZUKU_BACKOFF_MS
                    val flat = WatchdogConfig.A11Y_FLAT
                    // 先读现值再追加：覆盖写会把用户其它应用的辅助服务抹掉（与直写路径同语义）；
                    // 已在列表里说明只是实例掉线，仅补总开关（部分 ROM 关服务时会把
                    // accessibility_enabled 一并清零），等系统自会重绑
                    val current = ShellBridge.execShizuku("settings get secure enabled_accessibility_services")
                    val list = current.output.trim().removeSurrounding("\"")
                    val alreadyOn = list.split(':').any { it.equals(flat, ignoreCase = true) }
                    val newList = when {
                        current.success && alreadyOn -> null
                        current.success && list.isNotBlank() -> "$list:$flat"
                        else -> flat
                    }
                    val r1 = newList?.let { ShellBridge.execShizuku("settings put secure enabled_accessibility_services \"$it\"") }
                    val r2 = ShellBridge.execShizuku("settings put secure accessibility_enabled 1")
                    if ((r1 == null || r1.success) && r2.success) {
                        val how = if (r1 == null) "补开总开关" else "重新启用"
                        WatchdogConfig.recordRepair(this, "无障碍服务掉线，已经 Shizuku 自动$how")
                        Log.i(TAG, "已经 Shizuku $how 无障碍服务")
                    } else {
                        val reason = r1?.takeIf { !it.success }?.reason ?: r2.reason
                        Log.w(TAG, "Shizuku 修复无障碍失败：$reason（${A11Y_SHIZUKU_BACKOFF_MS / 1000}s 后重试）")
                    }
                }
            }
        }

        // 2. Shizuku 自动恢复：仅离线且过了退避期才尝试，避免未配对设备上空转
        if (cfg.autoRepairShizuku &&
            ShellBridge.shizukuState() != 2 &&
            WatchdogConfig.shizukuBackoffElapsed()
        ) {
            WatchdogConfig.shizukuNextAttemptAt = SystemClock.elapsedRealtime() + WatchdogConfig.SHIZUKU_BACKOFF_MS
            val result = ShellBridge.adbEnsureReady()
            if (result.success) {
                WatchdogConfig.recordRepair(this, "Shizuku 服务离线，已自动重拉：${result.output.ifBlank { "成功" }}")
                Log.i(TAG, "已自动恢复 Shizuku 服务")
            } else {
                // 失败常见于未完成首次配对：静默退避，等用户在主应用完成引导后自然恢复
                Log.i(TAG, "Shizuku 自动恢复未成功：${result.reason}（${WatchdogConfig.SHIZUKU_BACKOFF_MS / 1000}s 后重试）")
            }
        }
    }

    /** API 29+ 显式声明 specialUse 类型（Android 14 强制），26-28 用旧重载 */
    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "服务组件保活",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(com.phoneagent.provider.R.string.provider_app_name))
            .setContentText(getString(com.phoneagent.provider.R.string.watchdog_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 1

        /** Shizuku 兜底修复无障碍的退避：服务若处于崩溃循环，避免每 10s 重写一次 */
        private const val A11Y_SHIZUKU_BACKOFF_MS = 60_000L

        /** 进程内存活标志：ProviderService/BootReceiver 判重入，主应用状态面板判「运行中」 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
