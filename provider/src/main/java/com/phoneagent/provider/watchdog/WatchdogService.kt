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
 * 1. 无障碍掉线且开启「自动修复」→【只补缺】：组件不在启用列表时追加、
 *    总开关为 0 时补 1（持 WRITE_SECURE_SETTINGS 直写；未授权但 Shizuku
 *    在线时经 settings 命令兜底）。绝不摘除已存在的条目——与 ROM 防护
 *    打乒乓的"摘除再补回"只会招致永久拉黑（Operit 授权器同样从不摘除）；
 * 2. Shizuku 不在线且开启「自动恢复」→ 经无线 ADB 引导重拉（已配对可静默；
 *    失败按 [WatchdogConfig.SHIZUKU_BACKOFF_MS] 退避）。
 *
 * 服务实例的重绑完全交给系统：开关在列时 AMS 自己会重新 bind（进程活着
 * 但实例没连上通常是短暂的中间态，或系统稍后重试），我们不做任何干预。
 *
 * 生命周期：由 ProviderService.onCreate（配置开启时）/ setWatchdogConfig /
 * BootReceiver 启动；配置关闭时 stopSelf。与主应用的绑定无关——主应用解绑
 * 不停看门狗，这正是保活的意义。
 */
class WatchdogService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 受限设置已解除标志（进程内状态；进程重启后首轮巡检会重新探测） */
    @Volatile
    private var restrictedResolved = false

    /** 故障态起点（elapsedRealtime）：开关在列、总开关为 1，但服务实例始终未连接 */
    private var faultSinceMs = 0L

    /** 已执行的强制重绑次数（服务连上后归零；超过上限只提示用户手动处理） */
    private var rebindAttempts = 0

    /** 下次允许强制重绑的时刻（冷却锚点） */
    private var nextRebindAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // startForeground 失败必须吞掉：它在与无障碍服务同一进程里执行，
        // 一旦抛出（通知渠道/权限/ROM 限制）就会连带杀掉进程，
        // 系统随即把无障碍标记为「此服务出现故障无法运行」——比没有保活更糟
        runCatching { startForegroundCompat() }
            .onFailure { Log.w(TAG, "前台服务启动失败（保活弱化，但进程不受影响）：${it.message}") }
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

        // 0. 受限设置解除（vivo/OriginOS + Android 13+ 的首要根因）：
        //    ACTION_VIEW 侧载安装的应用会被标记为受限来源，用户开完无障碍后系统
        //    会自行撤销（开关弹回）——此前所有自动修复都会被这一层抹掉。
        //    Shizuku 在线时直接 appops 设 allow，一次成功后进程内记住不再查；
        //    解除失败且受限未过时，无障碍修复写了也是白写，直接跳过并记录原因
        if (ShellBridge.shizukuState() == 2 && !restrictedResolved) {
            val probe = ShellBridge.execShizuku("appops get $packageName ACCESS_RESTRICTED_SETTINGS")
            val mode = probe.output.trim().substringAfterLast("mode:").trim().substringBefore(' ')
            if (probe.success && mode == "allow") {
                restrictedResolved = true
            } else {
                val set = ShellBridge.execShizuku("appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
                if (set.success) {
                    restrictedResolved = true
                    WatchdogConfig.recordRepair(this, "已解除侧载应用的受限设置限制（此前系统会自动撤销无障碍开关）")
                    Log.i(TAG, "已解除受限设置限制")
                } else {
                    Log.w(TAG, "受限设置解除失败：${set.reason}")
                    WatchdogConfig.recordRepairOncePerDay(
                        this, "受限设置未解除（需在系统设置-应用信息-菜单中允许，或完成 Shizuku 引导），自动修复可能被系统撤销"
                    )
                }
            }
        }

        // 1. 无障碍自动修复：两级链路——优先直写安全设置（进程已获 WRITE_SECURE_SETTINGS
        //    时零依赖），失败再经 Shizuku 执行 settings 命令兜底；两条链路都只补缺
        if (cfg.autoRepairA11y && !A11yBridge.isConnected()) {
            when {
                WatchdogConfig.canRepairA11y(this) -> {
                    val masterWasOff = !WatchdogConfig.a11yMasterEnabled(this)
                    if (WatchdogConfig.repairAccessibility(this)) {
                        val why = when {
                            masterWasOff -> "检测到无障碍总开关被外部关闭（疑似系统防护/更新重置），已自动补开"
                            else -> "无障碍服务掉线，已自动重新启用（直写安全设置）"
                        }
                        WatchdogConfig.recordRepair(this, why)
                        Log.i(TAG, why)
                    }
                    // 修复后不追加任何动作：实例重绑由 AMS 负责，通常数秒内完成；
                    // 若持续连不上（ROM 限制），重复写入只会招致防护升级——保持静默
                }
                // 未授权但有可用 Shizuku：settings 命令兜底；带退避防服务崩溃时反复拉起
                ShellBridge.shizukuState() == 2 && WatchdogConfig.a11yBackoffElapsed() -> {
                    WatchdogConfig.a11yNextAttemptAt =
                        SystemClock.elapsedRealtime() + A11Y_SHIZUKU_BACKOFF_MS
                    val flat = WatchdogConfig.a11yFlat(this)
                    // 先读现值：已在列表里说明只是实例掉线（系统自会重绑），仅补总开关；
                    // 否则追加——绝不覆盖（会抹掉用户其它应用的辅助服务）
                    val current = ShellBridge.execShizuku("settings get secure enabled_accessibility_services")
                    val list = current.output.trim().removeSurrounding("\"")
                    // 归一化查重：全量/短形式条目都算已在列（与直写路径 WatchdogConfig.entryMatches 同语义）
                    val cls = "com.phoneagent.provider.a11y.ProviderAccessibilityService"
                    val alreadyOn = current.success && list.split(':').any { entry ->
                        val cn = android.content.ComponentName.unflattenFromString(entry.trim()) ?: return@any false
                        var c = cn.className
                        if (c.startsWith(".")) c = cn.packageName + c
                        cn.packageName.equals(packageName, true) && c.equals(cls, true)
                    }
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

        // 1b. 故障态恢复：开关在列、总开关为 1，但服务实例持续未连接。
        //     这正是小米/HyperOS 的「此服务出现故障无法运行」——AMS 已把服务标记为崩溃，
        //     重复写相同值不会触发重新 bind，只能让列表真正变化一次（摘除→立刻补回）。
        //     严格限次限频：最多 3 次、每次间隔 10 分钟，之后停手并提示用户手动关开一次，
        //     避免与 ROM 无障碍防护形成拉锯导致应用被永久拉黑
        recoverStuckA11y(cfg)

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

    /**
     * 「开关在列但服务故障」的有限次自愈（小米/HyperOS 显示「此服务出现故障无法运行」）。
     *
     * 触发条件必须三者同时满足：组件在启用列表、总开关为 1、实例未连接；
     * 且持续 [FAULT_REBIND_AFTER_MS] 未恢复。满足后最多重置 [MAX_REBIND_ATTEMPTS] 次、
     * 每次间隔 [REBIND_COOLDOWN_MS]，超过即停手并提示用户手动关开一次——
     * 无限重试会与 ROM 无障碍防护形成拉锯，最终被永久拉黑（用户只能去安全中心解除）。
     */
    private suspend fun recoverStuckA11y(cfg: WatchdogConfig.Config) {
        val now = SystemClock.elapsedRealtime()
        val connected = A11yBridge.isConnected()
        if (connected || !WatchdogConfig.isInA11yList(this) || !WatchdogConfig.a11yMasterEnabled(this)) {
            faultSinceMs = 0L
            if (connected) rebindAttempts = 0
            return
        }
        if (!cfg.autoRepairA11y) return
        // 首次观察到故障态只打点，给 AMS 留出正常重绑时间
        if (faultSinceMs == 0L) {
            faultSinceMs = now
            return
        }
        if (now - faultSinceMs < FAULT_REBIND_AFTER_MS || now < nextRebindAtMs) return
        if (rebindAttempts >= MAX_REBIND_ATTEMPTS) {
            WatchdogConfig.recordRepairOncePerDay(
                this,
                "无障碍开关已开启但服务未运行（系统标记为故障），自动重置已尝试 $MAX_REBIND_ATTEMPTS 次仍未恢复。" +
                    "请手动关闭再开启该开关；若反复出现，请在系统设置里把服务组件的省电策略设为「无限制」并允许自启动",
            )
            return
        }
        nextRebindAtMs = now + REBIND_COOLDOWN_MS
        faultSinceMs = now
        rebindAttempts++
        val ok = when {
            WatchdogConfig.canRepairA11y(this) -> WatchdogConfig.forceRebindA11y(this)
            ShellBridge.shizukuState() == 2 -> rebindViaShizuku()
            else -> false
        }
        if (ok) {
            WatchdogConfig.recordRepair(
                this,
                "无障碍处于故障状态（开关在列但服务不运行），已自动重置开关一次（第 $rebindAttempts 次）",
            )
            Log.i(TAG, "已重置无障碍开关以清除故障态（第 $rebindAttempts 次）")
        } else {
            Log.w(TAG, "故障态重置无法执行：既无安全设置写入授权，Shizuku 也不可用")
        }
    }

    /** 经 Shizuku 执行「先摘除再补回」的重置（语义与直写路径一致） */
    private suspend fun rebindViaShizuku(): Boolean {
        val flat = WatchdogConfig.a11yFlat(this)
        val cls = "com.phoneagent.provider.a11y.ProviderAccessibilityService"
        val cur = ShellBridge.execShizuku("settings get secure enabled_accessibility_services")
        if (!cur.success) return false
        val list = cur.output.trim().removeSurrounding("\"")
        val without = list.split(':').filter { entry ->
            if (entry.isBlank()) return@filter false
            val cn = android.content.ComponentName.unflattenFromString(entry.trim())
                ?: return@filter true
            var c = cn.className
            if (c.startsWith(".")) c = cn.packageName + c
            !(cn.packageName.equals(packageName, true) && c.equals(cls, true))
        }.joinToString(":")
        val restored = if (without.isBlank()) flat else "$without:$flat"
        val off = ShellBridge.execShizuku("settings put secure enabled_accessibility_services \"$without\"")
        // 与直写路径同理：两次写入必须隔开，否则 AMS 的 ContentObserver 会把
        // 「摘除 → 补回」合并成一次"值没变"的变更，不会重新 bind 服务
        delay(500)
        val on = ShellBridge.execShizuku("settings put secure enabled_accessibility_services \"$restored\"")
        ShellBridge.execShizuku("settings put secure accessibility_enabled 1")
        return off.success && on.success
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

        /** 故障态判定窗口：开关在列但实例持续这么久未连接，才认为进入「故障」 */
        private const val FAULT_REBIND_AFTER_MS = 60_000L

        /** 故障态重置冷却与次数上限（防与 ROM 防护拉锯） */
        private const val REBIND_COOLDOWN_MS = 10 * 60_000L
        private const val MAX_REBIND_ATTEMPTS = 3

        /** 进程内存活标志：ProviderService/BootReceiver 判重入，主应用状态面板判「运行中」 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
