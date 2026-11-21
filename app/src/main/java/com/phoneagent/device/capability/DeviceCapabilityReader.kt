package com.phoneagent.device.capability

import android.content.Context
import android.os.Build
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.perception.ForegroundProbe
import com.phoneagent.feature.notify.NotificationFeed

/**
 * 设备能力快照读取器（Android 侧取数收口）。
 *
 * 判定逻辑全部在 [CapabilityProbe] 里（可在 JVM 单测里跑真值表），本类只负责把
 * 各种系统事实一次性取齐：无障碍代理、Shizuku、无线调试、Termux 安装与授权、
 * 通知监听、使用情况访问权、系统版本。
 *
 * 取数走**短 TTL 缓存**：引擎同一步里执行层会多次问"现在能做什么"，
 * 每次都查 `Settings.Secure` / `AppOps` 既慢又容易在动作前后读到不同结果。
 * 800ms 足够覆盖一次动作编排，又不会让"刚连上无障碍"被长期误判为不可用。
 */
class DeviceCapabilityReader(
    private val context: Context,
    /** 内置 Shizuku binder 是否可用（由持有方注入，避免本类耦合 ShizukuManager 生命周期） */
    private val shizukuReady: () -> Boolean,
    /** 无线调试引导会话是否已连接 */
    private val adbConnected: () -> Boolean,
    /**
     * Termux 是否"已安装且已授予 RUN_COMMAND"。
     *
     * 必须由**持有 Termux 权限的那个主体**回答：`com.termux.permission.RUN_COMMAND` 由服务组件
     * （`com.phoneagent.provider`）声明并申请，主应用自己的清单里没有这条权限，
     * 所以主应用 `checkSelfPermission(RUN_COMMAND)` 恒为 DENIED——此前这里正是这么查的，
     * 结果是本机装了 Termux 也永远判"不可用"，把一条能跑的命令通道报成不存在。
     * 运行期真正决定"能不能走 Termux"的是同一个来源（`TermuxBridge.isAvailable()`，读 Provider 同步来的状态），
     * 快照与执行因此不会各说一套。
     */
    private val termuxAvailable: () -> Boolean,
) {

    companion object {
        /** 快照有效期：一次动作编排内的多次查询复用同一份事实 */
        const val CACHE_TTL_MS = 800L
    }

    @Volatile
    private var cached: DeviceCapabilitySnapshot? = null

    /**
     * 当前能力快照。
     * @param force 忽略缓存（任务开始、通道刚建立/断开、失败后重试前使用）
     */
    fun snapshot(force: Boolean = false, nowMillis: Long = System.currentTimeMillis()): DeviceCapabilitySnapshot {
        val hit = cached
        if (!force && hit != null && nowMillis - hit.capturedAtMillis <= CACHE_TTL_MS) return hit
        val fresh = CapabilityProbe.capture(
            a11yConnected = AgentAccessibilityService.instance != null,
            // 服务实例不在线时再看系统开关：区分"没开"与"开了但没连上"，后者给用户的指引不同
            a11yEnabled = AgentAccessibilityService.isServiceEnabled(context),
            shizukuReady = shizukuReady(),
            adbConnected = adbConnected(),
            termuxAvailable = termuxAvailable(),
            sdkInt = Build.VERSION.SDK_INT,
            notificationAccess = NotificationFeed.connected,
            usageAccess = ForegroundProbe(context).hasUsageAccess(),
            // 副屏当前实现依赖 Shizuku 的 shell 权限；未来若换实现，这里改判定即可
            virtualDisplaySupported = shizukuReady(),
            voiceAssistantDetected = null,
            nowMillis = nowMillis,
        )
        cached = fresh
        return fresh
    }

    /** 清空缓存（通道状态刚变化时由调用方主动调用） */
    fun invalidate() {
        cached = null
    }
}
