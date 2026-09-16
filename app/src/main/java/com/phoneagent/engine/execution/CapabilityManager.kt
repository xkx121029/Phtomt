package com.phoneagent.execution

import android.content.Context
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.shell.ShizukuManager

/**
 * 当前授权模式检测与缓存（对应 HPA动作执行逻辑优化文档 v2.1 四、转译层 4.2）。
 *
 * 转译层依据该模式把同一意图转译为不同通道的命令：
 * - SHIZUKU：拥有 Shizuku 或无线 ADB 权限，可执行 shell（input tap / monkey 等）
 * - ACCESSIBILITY：仅有无障碍服务，使用 performAction / dispatchGesture
 * - READONLY：两者都不可用，仅能分析/给出建议，不能自动执行
 *
 * AI 完全感知不到该模式；模式变化（开关 Shizuku/接入无线 ADB）对 AI 透明。
 */
class CapabilityManager(
    private val context: Context,
    private val shizukuManager: ShizukuManager?,
    private val adbConnectedProvider: () -> Boolean = { false },
) {

    enum class Mode {
        SHIZUKU,
        ACCESSIBILITY,
        READONLY,
    }

    /** 当前授权模式。每次调用实时检测（Shizuku/无线 ADB 状态可能动态变化） */
    fun currentMode(): Mode = when {
        shizukuManager?.isAvailable() == true || adbConnectedProvider() -> Mode.SHIZUKU
        AgentAccessibilityService.instance != null || AgentAccessibilityService.isServiceEnabled(context) -> Mode.ACCESSIBILITY
        else -> Mode.READONLY
    }

    /** 是否拥有 Shizuku 权限（仅供外部开关状态展示） */
    fun hasShizuku(): Boolean = shizukuManager?.isAvailable() == true

    /** 是否拥有无障碍服务（on 服务可用，off 仅系统开关打开） */
    fun hasAccessibility(): Boolean =
        AgentAccessibilityService.instance != null || AgentAccessibilityService.isServiceEnabled(context)
}