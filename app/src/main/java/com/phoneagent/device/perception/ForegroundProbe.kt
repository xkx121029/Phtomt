package com.phoneagent.device.perception

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * 前台应用用量统计探针：UsageStatsManager 交叉验证无障碍读到的前台应用。
 *
 * 用途有两处：
 * - 无障碍元素树为空/前台应用读不到时的兜底感知（需用户授予「使用情况访问权限」）；
 * - 与无障碍窗口事件交叉验证（两侧结论不一致时提示 AI 可能正处在过渡态）。
 *
 * 权限 PACKAGE_USAGE_STATS 是特殊权限（AppOps），无法运行时弹窗授予，
 * 只能引导用户去设置页开启；未授权时 [latestForeground] 直接返回 null，调用方静默跳过。
 */
class ForegroundProbe(private val context: Context) {

    /** 是否已授予使用情况访问权限（AppOps 检查，毫秒级开销） */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 最近一次前台应用包名（回看 [lookbackMs] 内的事件）；无权限或无事件返回 null */
    fun latestForeground(lookbackMs: Long = DEFAULT_LOOKBACK_MS): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm?.takeIf { hasUsageAccess() }?.queryEvents(now - lookbackMs, now)
            ?: return null
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // ACTIVITY_RESUMED（API 29+）即旧的 MOVE_TO_FOREGROUND：真实的前台切换信号
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                last = event.packageName
            }
        }
        return last
    }

    companion object {
        /** 回看窗口：窗口太长会取到切走前的旧应用，5 秒覆盖一次决策步的感知间隔 */
        const val DEFAULT_LOOKBACK_MS = 5_000L
    }
}
