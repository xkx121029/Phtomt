package com.phoneagent.device.capability

/**
 * 设备能力清单（系统级集成层的**唯一定义点**）。
 *
 * 为什么要单独一张表：
 * - 过去"这台机器现在能做什么"散落在转译层、执行层、提示词与各 UI 里各查各的
 *   （`AgentAccessibilityService.instance`、`shizukuManager.isAvailable()`、`NotificationFeed.connected`…），
 *   同一步决策里可能读到不同时刻的状态，失败原因也各写一套；
 * - 这里把能力收敛成稳定编号（[code]）+ 中文名（[label]），一次探测得到一份快照，
 *   执行通道排序、失败原因回注、日志排查都读同一份事实。
 *
 * 与 `CapabilityManager.Mode` 的分工：Mode 只回答"当前是哪种授权档位"（用于转译层二分），
 * 本表回答**每一项能力各自可用到什么程度**（可细到"无障碍开关已开但服务未连接"）。
 */
enum class DeviceCapability(val code: String, val label: String) {
    /** 无障碍服务（读屏 + 手势 + 节点操作） */
    ACCESSIBILITY("ACCESSIBILITY_UNAVAILABLE", "无障碍服务"),

    /** 无障碍截图（API 30+ 的 takeScreenshot；元素树之外的像素级观察） */
    SCREEN_CAPTURE("SCREEN_CAPTURE_UNAVAILABLE", "屏幕截图"),

    /** 真实 shell 通道（下面三项任一可用即视为可用） */
    SHELL("SHELL_UNAVAILABLE", "命令行通道"),

    /** 内置 Shizuku（binder 可用） */
    SHELL_SHIZUKU("SHIZUKU_UNAVAILABLE", "内置 Shizuku"),

    /** 无线调试引导会话（内置 Shizuku 的启动机制） */
    SHELL_ADB("ADB_UNAVAILABLE", "无线调试会话"),

    /** Termux（普通应用权限的 Linux 环境，只能跑工具链命令） */
    SHELL_TERMUX("TERMUX_UNAVAILABLE", "Termux 命令环境"),

    /** 通知使用权（通知监听服务在线） */
    NOTIFICATION_ACCESS("NOTIFICATION_ACCESS_REQUIRED", "通知使用权"),

    /** 使用情况访问权（前台应用交叉验证） */
    USAGE_ACCESS("APP_USAGE_ACCESS_REQUIRED", "使用情况访问权"),

    /** 副屏（虚拟显示，依赖 Shizuku） */
    VIRTUAL_DISPLAY("VIRTUAL_DISPLAY_UNAVAILABLE", "副屏"),

    /** 系统语音助手（探测结果；未探测时为未知） */
    VOICE_ASSISTANT("VOICE_ASSISTANT_NOT_FOUND", "系统语音助手"),
}

/**
 * 单项能力的可用程度。
 *
 * [DEGRADED] 是刻意保留的中间态：能力存在但**只能做子集**（例如 shell 只剩 Termux，
 * 系统命令 `am/pm/settings` 走不通）。把"有"与"够用"分开，才能在执行前就选对通道，
 * 而不是等命令失败再回退。
 */
enum class CapabilityState {
    /** 完整可用 */
    AVAILABLE,

    /** 部分可用（能力在，但功能受子集限制） */
    DEGRADED,

    /** 不可用（未授权 / 未安装 / 系统版本不支持） */
    UNAVAILABLE,

    /** 尚未探测（不阻塞决策，按"未知"处理） */
    UNKNOWN;

    /** 是否足以支撑依赖该能力的动作（未知按可用处理，避免无谓拦截） */
    val usable: Boolean get() = this == AVAILABLE || this == DEGRADED || this == UNKNOWN
}

/**
 * 一次探测得到的设备能力快照（不可变）。
 *
 * 快照属于"某个时刻"：引擎每步取一次，同一步内所有决策读同一份，避免半路状态漂移
 * （典型场景：Shizuku 在这条命令执行期间掉线，前后两次 `isAvailable()` 结论不同）。
 */
data class DeviceCapabilitySnapshot(
    val states: Map<DeviceCapability, CapabilityState>,
    val capturedAtMillis: Long,
) {

    /** 取单项能力状态；未登记的一律视为未知 */
    fun stateOf(capability: DeviceCapability): CapabilityState =
        states[capability] ?: CapabilityState.UNKNOWN

    /** 是否足以支撑依赖该能力的动作 */
    fun isUsable(capability: DeviceCapability): Boolean = stateOf(capability).usable

    /** 是否完整可用（不含降级/未知） */
    fun isFullyAvailable(capability: DeviceCapability): Boolean =
        stateOf(capability) == CapabilityState.AVAILABLE

    /**
     * 给人看的中文原因（用于失败回注与日志，不用于提示词的能力宣称）。
     * 可用时返回空串。
     */
    fun reasonOf(capability: DeviceCapability): String {
        val state = stateOf(capability)
        if (state == CapabilityState.AVAILABLE || state == CapabilityState.UNKNOWN) return ""
        return when (capability) {
            DeviceCapability.ACCESSIBILITY ->
                if (state == CapabilityState.DEGRADED) {
                    "无障碍开关已开启，但服务未连接（可下拉通知栏或重进本应用后重试）"
                } else {
                    "无障碍服务未开启"
                }
            DeviceCapability.SCREEN_CAPTURE ->
                if (state == CapabilityState.DEGRADED) {
                    "当前系统版本不支持无障碍截图"
                } else {
                    "无障碍截图不可用（需无障碍服务在线且系统为 Android 11+）"
                }
            DeviceCapability.SHELL -> "无可用命令行通道（内置 Shizuku 未就绪，Termux 也不可用）"
            DeviceCapability.SHELL_SHIZUKU -> "内置 Shizuku 未就绪"
            DeviceCapability.SHELL_ADB -> "无线调试会话未连接"
            DeviceCapability.SHELL_TERMUX -> "Termux 不可用（未安装或未授予 RUN_COMMAND 权限）"
            DeviceCapability.NOTIFICATION_ACCESS -> "通知使用权未开启"
            DeviceCapability.USAGE_ACCESS -> "使用情况访问权未开启"
            DeviceCapability.VIRTUAL_DISPLAY -> "副屏不可用（需要 Shizuku 通道）"
            DeviceCapability.VOICE_ASSISTANT -> "未探测到系统语音助手"
        }
    }

    /**
     * 一行中文摘要（按枚举顺序稳定输出，便于日志比对两次快照的差异）。
     * 仅用于日志/排查，不进提示词——执行通道对 AI 保持透明。
     */
    fun summary(): String = DeviceCapability.entries.joinToString("；") { capability ->
        val state = stateOf(capability)
        val word = when (state) {
            CapabilityState.AVAILABLE -> "可用"
            CapabilityState.DEGRADED -> "降级"
            CapabilityState.UNAVAILABLE -> "不可用"
            CapabilityState.UNKNOWN -> "未知"
        }
        "${capability.label}=$word"
    }
}

/**
 * 能力探测：**纯函数**，所有外部事实由调用方注入。
 *
 * 这样做的理由很实际：探测逻辑要在 JVM 单测里验证真值表，不能依赖 Android 服务
 * （无障碍单例、PackageManager、AppOps 在单测环境里都拿不到）。Android 侧的取数统一收口在
 * `CapabilityManager.snapshot()`，这里只做"事实 → 状态"的判定。
 */
object CapabilityProbe {

    /**
     * @param a11yConnected 无障碍服务实例是否在线（能真正读屏/操作）
     * @param a11yEnabled 系统设置里的无障碍开关是否已开（服务未连上时为降级态）
     * @param shizukuReady 内置 Shizuku binder 是否可用
     * @param adbConnected 无线调试引导会话是否连接
     * @param termuxAvailable Termux 是否已安装且已授予 RUN_COMMAND
     * @param sdkInt 系统版本（截图能力要求 30+）
     * @param notificationAccess 通知监听服务是否在线
     * @param usageAccess 是否已授予使用情况访问权
     * @param virtualDisplaySupported 副屏是否受支持（当前实现要求 Shizuku 可用）
     * @param voiceAssistantDetected 是否探测到系统语音助手；null 表示尚未探测
     */
    @Suppress("LongParameterList")
    fun capture(
        a11yConnected: Boolean,
        a11yEnabled: Boolean,
        shizukuReady: Boolean,
        adbConnected: Boolean,
        termuxAvailable: Boolean,
        sdkInt: Int,
        notificationAccess: Boolean,
        usageAccess: Boolean,
        virtualDisplaySupported: Boolean,
        voiceAssistantDetected: Boolean? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): DeviceCapabilitySnapshot {
        val accessibility = when {
            a11yConnected -> CapabilityState.AVAILABLE
            a11yEnabled -> CapabilityState.DEGRADED
            else -> CapabilityState.UNAVAILABLE
        }
        val screenCapture = when {
            !a11yConnected -> CapabilityState.UNAVAILABLE
            sdkInt < 30 -> CapabilityState.DEGRADED
            else -> CapabilityState.AVAILABLE
        }
        val shell = when {
            shizukuReady || adbConnected -> CapabilityState.AVAILABLE
            termuxAvailable -> CapabilityState.DEGRADED
            else -> CapabilityState.UNAVAILABLE
        }
        return DeviceCapabilitySnapshot(
            states = mapOf(
                DeviceCapability.ACCESSIBILITY to accessibility,
                DeviceCapability.SCREEN_CAPTURE to screenCapture,
                DeviceCapability.SHELL to shell,
                DeviceCapability.SHELL_SHIZUKU to bool(shizukuReady),
                DeviceCapability.SHELL_ADB to bool(adbConnected),
                DeviceCapability.SHELL_TERMUX to bool(termuxAvailable),
                DeviceCapability.NOTIFICATION_ACCESS to bool(notificationAccess),
                DeviceCapability.USAGE_ACCESS to bool(usageAccess),
                DeviceCapability.VIRTUAL_DISPLAY to bool(virtualDisplaySupported),
                DeviceCapability.VOICE_ASSISTANT to when (voiceAssistantDetected) {
                    true -> CapabilityState.AVAILABLE
                    false -> CapabilityState.UNAVAILABLE
                    null -> CapabilityState.UNKNOWN
                },
            ),
            capturedAtMillis = nowMillis,
        )
    }

    private fun bool(value: Boolean): CapabilityState =
        if (value) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE
}
