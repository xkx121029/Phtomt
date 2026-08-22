package com.phoneagent.ai

import com.phoneagent.model.AgentAction

/**
 * AI 一次决策的结果：动作 + 性能指标。
 */
data class AiDecision(
    val action: AgentAction,
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