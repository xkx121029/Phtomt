package com.phoneagent.device.capability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.perception.ForegroundProbe
import com.phoneagent.device.shell.TermuxBridge
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

    /**
     * Termux 是否"已安装且已授予 RUN_COMMAND"。
     *
     * 这里直接查包管理器与自身权限，而不是读 Provider 的缓存：快照应反映本机事实，
     * 不能依赖另一条 IPC 是否恰好刷新过。
     */
    private fun termuxAvailable(): Boolean {
        val installed = runCatching {
            context.packageManager.getPackageInfo(TermuxBridge.TERMUX_PACKAGE, 0)
        }.isSuccess
        if (!installed) return false
        return ContextCompat.checkSelfPermission(
            context,
            TermuxBridge.RUN_COMMAND_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
