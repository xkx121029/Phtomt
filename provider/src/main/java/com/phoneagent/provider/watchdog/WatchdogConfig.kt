package com.phoneagent.provider.watchdog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import com.phoneagent.provider.A11yBridge
import com.phoneagent.provider.ProviderApplication
import com.phoneagent.provider.ShellBridge
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 看门狗配置与状态：持久化在 Provider 侧私有 SharedPreferences。
 *
 * 主应用经 AIDL（setWatchdogConfig / watchdogStatus）读写——两个应用无法共享
 * SharedPreferences 文件，AIDL 是唯一正规通道；配置跟随 Provider 存活，
 * 主应用卸载重装后重新下发即可。
 *
 * 自动修复的设计原则（照 Operit 授权器）：【只补缺，不摘除】。
 * Operit 的 provider 从不写安全设置做"摘除再补回"式重置——那类操作会和
 * ROM 的无障碍防护（HyperOS 风险管控 / Android 13+ 受限设置）形成拉锯，
 * 几轮之后 ROM 会把应用的无障碍永久拉黑（用户只能去安全中心手动解除）。
 * 我们只做两件事：组件缺失时补进启用列表、总开关为 0 时补 1；系统自己
 * 负责重绑服务实例。
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

    /** 强制重绑时「摘除」与「补回」之间的间隔：留给 AMS 的 ContentObserver 感知变更 */
    private const val REBIND_GAP_MS = 500L

    /** Provider 无障碍服务类名（包名运行时取 context.packageName，勿硬编码） */
    private const val A11Y_CLASS = "com.phoneagent.provider.a11y.ProviderAccessibilityService"

    /** 当前应用的 Provider 无障碍组件（按运行时包名构造，debug 变体也不会错） */
    private fun a11yComponent(context: Context): ComponentName =
        ComponentName(context.packageName, A11Y_CLASS)

    data class Config(
        val enabled: Boolean,
        val autoRepairA11y: Boolean,
        val autoRepairShizuku: Boolean,
    )

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            // 默认开启：Provider 是无界面应用，没有前台服务锚点时进程只是"空进程"，
            // 小米 HyperOS / vivo 等 ROM 会在数十秒内冻结并杀掉它——无障碍服务随之死亡，
            // 系统把开关留在列表里却标记「此服务出现故障无法运行」。
            // 保活是通道可用的前提，所以默认开，用户可在主应用里关闭
            enabled = p.getBoolean(KEY_ENABLED, true),
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

    /** 同一条提示每天只记一次（用于持续未解除的状态性提醒，避免每 10s 刷屏） */
    fun recordRepairOncePerDay(context: Context, message: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / 86_400_000L
        if (p.getString(KEY_LAST_REPAIR_MSG, "") == message &&
            p.getLong(KEY_LAST_REPAIR_AT, 0L) / 86_400_000L == today
        ) return
        recordRepair(context, message)
    }

    private fun repairCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_REPAIR_COUNT, 0)

    /** 当前进程是否已持有 WRITE_SECURE_SETTINGS（server attach 时由 shell uid pm grant 授予） */
    fun canRepairA11y(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 归一化比对启用列表条目与目标组件。
     *
     * 系统设置手动开启通常写全量形式（pkg/pkg.cls），但部分 ROM、备份恢复或第三方
     * 工具会写短形式（pkg/.cls）。ComponentName.unflattenFromString 不展开短形式
     * （".cls" 原样保留），直接 equals 会漏判——短形式要展开成全限定名再比。
     * 旧代码在这里的漏判直接导致"开关明明开着却检测为关"→ 看门狗反复追加写入。
     */
    private fun entryMatches(entry: String, pkg: String, cls: String): Boolean {
        val trimmed = entry.trim()
        val cn = ComponentName.unflattenFromString(trimmed) ?: return false
        var entryPkg = cn.packageName
        var entryCls = cn.className
        if (entryCls.startsWith(".")) {
            // 短形式展开：pkg/.a.b.C → pkg.a.b.C
            entryCls = entryPkg + entryCls
        } else if (!entryCls.contains('.')) {
            entryCls = "$entryPkg.$entryCls"
        }
        return entryPkg.equals(pkg, ignoreCase = true) && entryCls.equals(cls, ignoreCase = true)
    }

    /** 目标组件是否已在启用列表里（归一化比对，全量/短形式都能匹配上） */
    fun isInA11yList(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val pkg = context.packageName
        return enabled.split(':').any { entryMatches(it, pkg, A11Y_CLASS) }
    }

    /** 无障碍总开关（accessibility_enabled）当前是否开启 */
    fun a11yMasterEnabled(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    /**
     * 无障碍自动修复（只补缺，不摘除）：
     * ① 服务组件不在启用列表 → 追加（保留其它应用的辅助服务）；
     * ② 总开关为 0 → 补 1。
     * 返回是否实际执行了写回（false = 状态已正常 / 无权限）。
     */
    fun repairAccessibility(context: Context): Boolean {
        if (!canRepairA11y(context)) return false
        var changed = false
        if (!isInA11yList(context)) changed = addComponentToA11yList(context)
        if (!a11yMasterEnabled(context)) {
            changed = Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            ) || changed
        }
        return changed
    }

    /**
     * 强制重绑：把组件从启用列表摘掉后【补回】，等效用户手动关再开一次。
     *
     * 仅用于「开关在列、总开关为 1，但服务实例始终未连接」的故障态——
     * 此时 AMS 已把该服务标记为崩溃/故障，光重写相同值不会触发重新 bind，
     * 必须让列表真正发生变化。调用方必须做次数与冷却限制（见 WatchdogService），
     * 否则会和 ROM 的无障碍防护形成拉锯，几轮后被永久拉黑。
     *
     * 两次写入之间必须留出间隔：AMS 通过 secure settings 的 ContentObserver
     * 异步感知变化，紧挨着写「先删后加」会被合并成一次通知、最终值与旧值相同，
     * AMS 判断"没有变化"便不会重新 bind——这正是此前重置无效的原因。
     */
    suspend fun forceRebindA11y(context: Context): Boolean {
        if (!canRepairA11y(context)) return false
        val resolver = context.contentResolver
        val pkg = context.packageName
        val flat = a11yComponent(context).flattenToString()
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val without = current.split(':')
            .filter { it.isNotBlank() && !entryMatches(it, pkg, A11Y_CLASS) }
            .joinToString(":")
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
        // 等 AMS 处理完「摘除」这一次变更，再加回去（三次重试总耗时仍在毫秒级之外，可忽略）
        delay(REBIND_GAP_MS)
        val restored = if (without.isBlank()) flat else "$without:$flat"
        val ok = Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            restored,
        )
        if (!a11yMasterEnabled(context)) {
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        return ok
    }

    /** 把 Provider 无障碍组件追加进启用列表（不覆盖、不摘除其它条目） */
    fun addComponentToA11yList(context: Context): Boolean {
        if (!canRepairA11y(context)) return false
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        // 先归一化查重：短形式已在列时再追加全量形式会产生重复条目，
        // 部分 ROM 对重复/反复变动的启用列表会触发防护性停用
        if (isInA11yList(context)) return false
        val flat = a11yComponent(context).flattenToString()
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
            put("enabled", p.getBoolean(KEY_ENABLED, true))
            put("autoRepairA11y", p.getBoolean(KEY_AUTO_A11Y, true))
            put("autoRepairShizuku", p.getBoolean(KEY_AUTO_SHIZUKU, false))
            put("running", WatchdogService.isRunning)
            put("a11yConnected", A11yBridge.isConnected())
            put("a11yEnabledInList", isInA11yList(context))
            put("a11yMasterEnabled", a11yMasterEnabled(context))
            put("shizukuState", ShellBridge.shizukuState())
            put("canRepairA11y", canRepairA11y(context))
            put("repairCount", p.getInt(KEY_REPAIR_COUNT, 0))
            put("lastRepairMessage", p.getString(KEY_LAST_REPAIR_MSG, "") ?: "")
            put("lastRepairAtMs", p.getLong(KEY_LAST_REPAIR_AT, 0L))
            // 崩溃兜底现场：无障碍"自己关掉"的根因排查入口（ProviderApplication 记录）
            put("crashCount", p.getInt(ProviderApplication.KEY_CRASH_COUNT, 0))
            put("lastCrashMessage", p.getString(ProviderApplication.KEY_LAST_CRASH_MSG, "") ?: "")
            put("lastCrashAtMs", p.getLong(ProviderApplication.KEY_LAST_CRASH_AT, 0L))
            put("lastCrashThread", p.getString(ProviderApplication.KEY_LAST_CRASH_THREAD, "") ?: "")
        }.toString()
    }

    /** 配置开启时启动看门狗（幂等；已运行则前台通知仍在，服务 onCreate 不会重复） */
    fun startIfEnabled(context: Context) {
        if (load(context).enabled && !WatchdogService.isRunning) {
            // Android 12+ 后台启动 FGS 受限：Provider 一般因被前台主应用绑定而豁免，
            // 但边缘时序（主应用刚退后台才触发绑定回调）会抛异常——吞掉不致命，
            // 下一轮主应用绑定/开机广播仍会重试启动
            runCatching {
                context.startForegroundService(Intent(context, WatchdogService::class.java))
            }.onFailure { android.util.Log.w("WatchdogConfig", "FGS 启动受限（后台限制），已跳过：${it.message}") }
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

    /** Provider 无障碍服务的扁平组件名（Shizuku settings 命令兜底时写入用，按运行时包名构造） */
    fun a11yFlat(context: Context): String = a11yComponent(context).flattenToString()

    /** 上次经 Shizuku 修复无障碍的退避锚点（进程内状态，不持久化） */
    @Volatile
    var a11yNextAttemptAt: Long = 0L

    /** now 是否已过无障碍 Shizuku 修复退避期 */
    fun a11yBackoffElapsed(): Boolean = SystemClock.elapsedRealtime() >= a11yNextAttemptAt
}
