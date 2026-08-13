package com.phoneagent.model

/**
 * Agent 运行状态，用于 UI 展示与日志。
 */
data class AgentState(
    val isRunning: Boolean = false,
    val task: String = "",
    /** 当前阶段：idle / observing / thinking / acting / done / error */
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val stepCount: Int = 0,
    val lastAction: AgentAction? = null,
    val hasAccessibility: Boolean = false,
    val hasScreenshot: Boolean = false,
) {
    enum class Phase {
        IDLE, OBSERVING, THINKING, ACTING, DONE, ERROR
    }
}