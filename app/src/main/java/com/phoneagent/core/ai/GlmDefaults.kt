package com.phoneagent.core.ai.GlmDefaults

/**
 * 智谱 GLM 模型默认配置。
 * 通过智谱开放平台 OpenAI 兼容接口调用，默认启用免费的 GLM-4.7-Flash。
 */
object GlmDefaults {
    /** 智谱开放平台 OpenAI 兼容接口 */
    const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    /** 默认免费模型 */
    const val MODEL = "glm-4.7-flash"
    /** 视觉模型：当主模型不支持图片输入时，用此模型描述截图并定位点击坐标 */
    const val VISION_MODEL = "glm-4.6v-flash"
    /** 思考模型：规划/重规划等复杂任务时使用（如开启 thinking 的 glm-4.7-flash） */
    const val REASON_MODEL = "glm-4.7-flash"
    /** 默认系统提示词 */
    const val DEFAULT_SYSTEM_PROMPT =
        "你是运行在安卓手机上的 AI 智能体助手。你会收到屏幕元素树（可交互元素的坐标、类型、文本标签）" +
            "以及可选截图，你需要根据用户的目标，一步步指挥手机完成操作。"
}