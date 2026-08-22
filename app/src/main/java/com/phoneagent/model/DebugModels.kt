package com.phoneagent.model

import android.graphics.Bitmap

/**
 * 当前任务"最新一步"的执行留档：截图 + 执行说明。
 * 每步执行完成后更新；新任务开始即被覆盖清空。
 * 仅用于本地调试板块展示，不做持久化。
 */
data class StepShot(
    /** 步骤序号 */
    val step: Int = 0,
    /** 动作类型 */
    val actionType: String = "",
    /** 执行说明（AI 的 reasoning/reason + 验证结果） */
    val description: String = "",
    /** 该步是否已验证生效 */
    val verified: Boolean = false,
    /** 执行完成后的屏幕截图 */
    val screenshot: Bitmap? = null,
    /** 经本地视觉模型框选标注后的"识别截图" */
    val annotatedScreenshot: Bitmap? = null,
)

/**
 * 每步 AI 决策的详细追踪（供 Debug「按任务分类」页面展示）。
 * 由 AgentEngine 在每一步云端决策后记录。
 */
data class StepTrace(
    /** 所属任务 ID（-1 表示无归属） */
    val taskId: Long = -1,
    /** 所属任务描述 */
    val taskName: String? = null,
    /** 步骤序号 */
    val step: Int = 0,
    /** 发送给主模型的完整决策文本（观察 + 视觉描述 + 计划） */
    val sentText: String = "",
    /** 主模型原始返回正文（含思考前的 reasoning 之外的 content） */
    val receivedText: String = "",
    /** Token 消耗 */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    /** 本次请求耗时（毫秒） */
    val latencyMs: Long = 0,
    /** 本轮是否使用了视觉模型：外挂3B / 云端 / 本地OCR / 无 */
    val visionSource: String = "无",
    /** 实际视觉模型名称 */
    val visionModel: String = "",
    /** AI 对当前截图生成的视觉描述（外挂/云端/本地 OCR 输出） */
    val visionDescription: String = "",
    /** 该步是否思考（reasoning_content 非空） */
    val thinking: Boolean = false,
    /** 该步决策用的截图 */
    val screenshot: android.graphics.Bitmap? = null,
    /** 用外挂视觉画框后的结果带控件列表（临时，用于 Debug 画框展示） */
    val boxes: List<com.phoneagent.vision.DetectedControl> = emptyList(),
    val annotated: android.graphics.Bitmap? = null,
)

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
)