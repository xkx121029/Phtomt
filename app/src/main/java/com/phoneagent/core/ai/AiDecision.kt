package com.phoneagent.core.ai.AiDecision

import com.phoneagent.domain.model.AgentIntent

/**
 * AI 一次决策的结果：意图 + 性能指标。
 * AI 输出的是"意图"（做什么），具体执行命令由端侧转译层依据授权模式生成。
 */
data class AiDecision(
    val action: AgentIntent,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    /** 本次请求耗时（毫秒） */
    val elapsedMs: Long = 0,
    /** 模型原始输出正文（供调试日志展示完整响应） */
    val rawContent: String = "",
    /** 本次是否产出思考内容（reasoning_content 非空，用于调试页标记想了哪一步） */
    val thinking: Boolean = false,
)