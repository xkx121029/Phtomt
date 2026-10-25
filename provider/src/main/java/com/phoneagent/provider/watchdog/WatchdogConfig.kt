package com.phoneagent.provider.watchdog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import com.phoneagent.provider.A11yBridge
import com.phoneagent.provider.ShellBridge
import org.json.JSONObject

/**
 * 看门狗配置与状态：持久化在 Provider 侧私有 SharedPreferences。
 *
 * 主应用经 AIDL（setWatchdogConfig / watchdogStatus）读写——两个应用无法共享
 * SharedPreferences 文件，AIDL 是唯一正规通道；配置跟随 Provider 存活，
 * 主应用卸载重装后重新下发即可。
 */
object WatchdogConfig {

    private const val PREFS = "watchdog"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_A11Y = "auto_repair_a11y"
    private const val KEY_AUTO_SHIZUKU = "auto_repair_shizuku"

    /** 累计修复记录（供主应用状态面板展示，也便于排查"服务为何被又打开了"） */
    private const val KEY_REPAIR_COUNT = "repair_count"
    private const val KEY_LAST_REPAIR_MSG = "last_repair_message"
    private const val KEY_LAST_REPAIR_AT = "last_repair_at"

    /** 巡检节奏：10s 一次足够灵敏，且唤醒开销可忽略（协程 delay，不持锁不持 WakeLock） */
    const val CHECK_INTERVAL_MS = 10_000L

    /** Shizuku 自动恢复失败后的退避：未配对/引导失败时 5 分钟内不再重试，避免空转耗电 */
    const val SHIZUKU_BACKOFF_MS = 5 * 60_000L

    /** Provider 内无障碍服务的组件（自动修复时写进 ENABLED_ACCESSIBILITY_SERVICES 的目标） */
    private val A11Y_COMPONENT = ComponentName(
        "com.phoneagent.provider",
        "com.phoneagent.provider.a11y.ProviderAccessibilityService",
    )

    data class Config(
        val enabled: Boolean,
        val autoRepairA11y: Boolean,
        val autoRepairShizuku: Boolean,
    )

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            autoRepairA11y = p.getBoolean(KEY_AUTO_A11Y, true),
            autoRepairShizuku = p.getBoolean(KEY_AUTO_SHIZUKU, false),
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putBoolean(KEY_AUTO_A11Y, config.autoRepairA11y)
            .putBoolean(KEY_AUTO_SHIZUKU, config.autoRepairShizuku)
            .apply()
    }

    /** 记录一次修复动作（成功与否都记，主应用面板能看到最近动作与时间） */
    fun recordRepair(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_REPAIR_COUNT, repairCount(context) + 1)
            .putString(KEY_LAST_REPAIR_MSG, message)
            .putLong(KEY_LAST_REPAIR_AT, System.currentTimeMillis())
            .apply()
    }

    private fun repairCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_REPAIR_COUNT, 0)

    /** 当前进程是否已持有 WRITE_SECURE_SETTINGS（server attach 时由 shell uid pm grant 授予） */
    fun canRepairA11y(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 无障碍自动修复：开关被系统/用户关闭时直接改写 ENABLED_ACCESSIBILITY_SERVICES。
     * 仅在持有 WRITE_SECURE_SETTINGS 时可用；写入后系统会自行重绑无障碍服务。
     * 返回是否实际执行了写回（false = 已开启 / 无权限）。
     */
    fun repairAccessibility(context: Context): Boolean {
        if (!canRepairA11y(context)) return false
        if (A11yBridge.isEnabled(context)) return false
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val flat = A11Y_COMPONENT.flattenToString()
        // 已在列表里只是实例未连接：系统会自己重绑，不能重复写入
        if (current.split(':').any { it.equals(flat, ignoreCase = true) }) return false
        val updated = if (current.isBlank()) flat else "$current:$flat"
        return Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            updated,
        )
    }

    /**
     * 状态快照 JSON（契约见 IDeviceProvider.watchdogStatus 注释）。
     * running 由 WatchdogService 存活的 static 标志提供，binder 侧只读不启动服务。
     */
    fun statusJson(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("enabled", p.getBoolean(KEY_ENABLED, false))
            put("autoRepairA11y", p.getBoolean(KEY_AUTO_A11Y, true))
            put("autoRepairShizuku", p.getBoolean(KEY_AUTO_SHIZUKU, false))
            put("running", WatchdogService.isRunning)
            put("a11yConnected", A11yBridge.isConnected())
            put("shizukuState", ShellBridge.shizukuState())
            put("canRepairA11y", canRepairA11y(context))
            put("repairCount", p.getInt(KEY_REPAIR_COUNT, 0))
            put("lastRepairMessage", p.getString(KEY_LAST_REPAIR_MSG, "") ?: "")
            put("lastRepairAtMs", p.getLong(KEY_LAST_REPAIR_AT, 0L))
        }.toString()
    }

    /** 配置开启时启动看门狗（幂等；已运行则前台通知仍在，服务 onCreate 不会重复） */
    fun startIfEnabled(context: Context) {
        if (load(context).enabled && !WatchdogService.isRunning) {
            context.startForegroundService(Intent(context, WatchdogService::class.java))
        }
    }

    /** 停止看门狗（配置关闭时由 AIDL 入口调用） */
    fun stop(context: Context) {
        context.stopService(Intent(context, WatchdogService::class.java))
    }

    /** 上次 Shizuku 恢复尝试的退避锚点（进程内状态，不持久化） */
    @Volatile
    var shizukuNextAttemptAt: Long = 0L

    /** now 是否已过 Shizuku 退避期 */
    fun shizukuBackoffElapsed(): Boolean = SystemClock.elapsedRealtime() >= shizukuNextAttemptAt

    /** Provider 无障碍服务的扁平组件名（Shizuku settings 命令兜底时写入用） */
    const val A11Y_FLAT = "com.phoneagent.provider/com.phoneagent.provider.a11y.ProviderAccessibilityService"

    /** 上次经 Shizuku 修复无障碍的退避锚点（进程内状态，不持久化） */
    @Volatile
    var a11yNextAttemptAt: Long = 0L

    /** now 是否已过无障碍 Shizuku 修复退避期 */
    fun a11yBackoffElapsed(): Boolean = SystemClock.elapsedRealtime() >= a11yNextAttemptAt
}
