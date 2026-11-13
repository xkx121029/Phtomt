package com.phoneagent.domain.model

/**
 * 运行日志条目。
 */
data class AgentLog(
    val timestamp: Long,
    val level: Level,
    val message: String,
    /** 可展开的详细内容（如完整 API 请求/响应） */
    val detail: String? = null,
    /** 所属任务 ID：分任务查看与导出的依据（-1 表示任务无关的系统日志） */
    val taskId: Long = -1,
    /** 所属任务描述 */
    val taskName: String? = null,
) {
    enum class Level { INFO, WARN, ERROR, AI, API }
}

/**
 * 与 AI 的对话消息（用于调试页展示）。
 */
data class ConversationMessage(
    val role: String,
    val content: String,
    val timestamp: Long,
    val hasImage: Boolean = false,
)

/**
 * AI 性能指标。
 */
data class AgentMetrics(
    val totalSteps: Int = 0,
    val totalTokens: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 最近一次请求耗时（毫秒） */
    val lastLatencyMs: Long = 0,
    /** 平均请求耗时（毫秒） */
    val avgLatencyMs: Long = 0,
    /** 平均生成速度（tokens/秒） */
    val tokensPerSec: Double = 0.0,
    val requestCount: Int = 0,
    /** 视觉环节调用次数与总耗时（端侧3B/云端/本地识别 + 定位） */
    val visionCount: Int = 0,
    val visionTotalMs: Long = 0,
    /** 执行环节执行次数与总耗时（无障碍点击/输入/滑动等） */
    val execCount: Int = 0,
    val execTotalMs: Long = 0,
) {
    /** 平均视觉耗时（毫秒） */
    val avgVisionMs: Long get() = if (visionCount > 0) visionTotalMs / visionCount else 0
    /** 平均执行耗时（毫秒） */
    val avgExecMs: Long get() = if (execCount > 0) execTotalMs / execCount else 0
}