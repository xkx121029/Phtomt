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
    /** 单步墙钟（观察+决策+执行）次数与总耗时：与 execTotalMs 分开，用来发现"动作很快但整步很慢" */
    val stepCount: Int = 0,
    val stepTotalMs: Long = 0,
    /** 超出单步预算的步数：长任务里持续出现说明链路（网页/通道/页面加载）有问题 */
    val overBudgetSteps: Int = 0,
    /** 交付分类失败计数：二者分开才能判断该修通道（没送达）还是该修策略（送达未生效） */
    val notDeliveredCount: Int = 0,
    val noEffectCount: Int = 0,
    /**
     * 「已交付但读不到页面」的步数：既不是没送达，也不是确认没生效。
     *
     * 单独计数（而不是折进上面两桶）的理由很实际：这条链路正在被重构，
     * 没有这个数字就看不出「读不到页面」在真机上占多少、是在变好还是变坏——
     * 折进 [noEffectCount] 会把"没看见"记成"看见了没效果"，两个数字同时失真。
     */
    val unconfirmedCount: Int = 0,
) {
    /** 平均视觉耗时（毫秒） */
    val avgVisionMs: Long get() = if (visionCount > 0) visionTotalMs / visionCount else 0
    /** 平均执行耗时（毫秒） */
    val avgExecMs: Long get() = if (execCount > 0) execTotalMs / execCount else 0
    /** 平均单步耗时（毫秒） */
    val avgStepMs: Long get() = if (stepCount > 0) stepTotalMs / stepCount else 0
}