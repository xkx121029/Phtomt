package com.phoneagent.device.vision

import android.graphics.Bitmap

/**
 * 每步执行的留档（截图 + 说明），仅用于本地调试板块展示，不做持久化。
 *
 * 之所以放在 `device.vision` 而不是 `domain.model`：它持有 [Bitmap] 与
 * [DetectedControl]，两者都是 Android 运行时/设备层类型。留在 domain 会让
 * 「纯领域模型」这一层在单测里被迫依赖 Android stub。
 */

/**
 * 当前任务"最新一步"的执行留档：截图 + 执行说明。
 * 每步执行完成后更新；新任务开始即被覆盖清空。
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
    /** 本轮是否使用了视觉模型：端侧3B / 云端 / 本地OCR / 无 */
    val visionSource: String = "无",
    /** 实际视觉模型名称 */
    val visionModel: String = "",
    /** AI 对当前截图生成的视觉描述（端侧/云端/本地 OCR 输出） */
    val visionDescription: String = "",
    /** 该步是否思考（reasoning_content 非空） */
    val thinking: Boolean = false,
    /** 该步决策用的截图 */
    val screenshot: Bitmap? = null,
    /** 用端侧视觉画框后的结果带控件列表（临时，用于 Debug 画框展示） */
    val boxes: List<DetectedControl> = emptyList(),
    val annotated: Bitmap? = null,
)
