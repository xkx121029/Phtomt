package com.phoneagent.model

/**
 * 运行日志条目。
 */
data class AgentLog(
    val timestamp: Long,
    val level: Level,
    val message: String,
    /** 可展开的详细内容（如完整 API 请求/响应） */
    val detail: String? = null,
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
)