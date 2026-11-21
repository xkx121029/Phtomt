package com.phoneagent.engine.execution

import android.content.Context
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.shell.ShizukuManager

/**
 * 当前授权模式检测与缓存（对应 HPA动作执行逻辑优化文档 v2.1 四、转译层 4.2）。
 *
 * 转译层依据该模式把同一意图转译为不同通道的命令：
 * - SHIZUKU：内置 Shizuku binder 可用，可执行 shell（input tap / monkey 等）。
 *   无线调试会话不算：它只用于把 Shizuku server 拉起来，本身不实现命令执行
 * - ACCESSIBILITY：仅有无障碍服务，使用 performAction / dispatchGesture
 * - READONLY：两者都不可用，仅能分析/给出建议，不能自动执行
 *
 * AI 完全感知不到该模式；模式变化（开关 Shizuku / 无线调试会话拉起 server）对 AI 透明。
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

    /**
     * 当前授权模式。每次调用实时检测（Shizuku 可能动态掉线/恢复）。
     *
     * [adbConnectedProvider] 的语义是"ADB 引导通路是否已经给出可执行的 shell"（即 Shizuku server
     * 已就绪），而不是"无线调试会话是否连上"——后者本身执行不了任何命令，把它当权限会让转译层
     * 以为有 shell 能力。
     */
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