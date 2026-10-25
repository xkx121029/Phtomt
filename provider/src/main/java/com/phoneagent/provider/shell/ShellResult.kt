package com.phoneagent.provider.shell

/**
 * shell 执行结果（顶层版本）：[TermuxBridge] 与 [ShellBridge] 共用的统一口径，
 * 字段与 [ShizukuManager.ShellResult] 同构——Shizuku 副本留在其类内是迁移遗留，
 * Termux 侧自会话恢复起直接用本顶层类型（ShellBridge 据此复用同一套映射）。
 */
sealed class ShellResult {
    data class Success(val output: String, val exitCode: Int = 0) : ShellResult()
    data class Failure(val reason: String, val exitCode: Int = -1) : ShellResult()
}
