package com.phoneagent.engine

/**
 * 引擎决策用的模型端点配置（重构批次 4 自 AgentEngine 私有嵌套类提为 internal 顶层，
 * 供 PlanningFlow / DecisionLoop 等协作类共用；字段与语义不变）。
 */

/** 视觉模型配置（截图描述 + 点击坐标定位） */
internal data class VisionConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

/** 思考模型配置（规划/重规划等复杂任务，开启深度思考） */
internal data class ReasoningConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)
