package com.phoneagent.core.ai

/**
 * 内置服务商预设：一键填充 API 地址与模型名，省去逐个查文档。
 *
 * 全部为 OpenAI 兼容端点，可直接复用现有 Chat Completions 协议（含 SSE 流式与图片输入），
 * 因此切换服务商无需改动任何请求代码。
 *
 * 注意：模型名随厂商迭代下线速度较快（如 Kimi 的 moonshot-v1 系列已于 2026-08-31 下线、
 * 腾讯混元旧平台已于 2026-06-22 下线迁至 TokenHub），若调用报「模型不存在」，以厂商控制台当前列表为准替换即可。
 */
data class ProviderPreset(
    val name: String,
    /** OpenAI 兼容端点根路径，不含 /chat/completions */
    val baseUrl: String,
    /** 主模型：每步执行决策 */
    val model: String,
    /** 视觉模型：截图描述与坐标定位。为空表示该服务商无云端视觉模型，需依赖本地 OCR / 端侧 3B */
    val visionModel: String = "",
    /** 思考模型：规划与重规划。为空表示与主模型同款 */
    val reasonModel: String = "",
)

object ProviderPresets {

    /** 智谱 GLM（项目默认，主模型免费） */
    val GLM = ProviderPreset(
        name = "智谱 GLM",
        baseUrl = GlmDefaults.BASE_URL,
        model = GlmDefaults.MODEL,
        visionModel = GlmDefaults.VISION_MODEL,
        reasonModel = GlmDefaults.REASON_MODEL,
    )

    /** 全部预设，顺序即界面展示顺序 */
    val all: List<ProviderPreset> = listOf(
        GLM,
        ProviderPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            // V4.1 Flash 起统一模型名，旧的 deepseek-chat / deepseek-reasoner 已下线
            model = "deepseek-flash",
            // V4.1 Flash 原生支持图像理解，可同时充当视觉模型
            visionModel = "deepseek-flash",
            reasonModel = "deepseek-flash",
        ),
        ProviderPreset(
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            model = "kimi-k3",
            // kimi-k3 原生支持视觉理解
            visionModel = "kimi-k3",
            reasonModel = "kimi-k3",
        ),
        ProviderPreset(
            name = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-plus",
            visionModel = "qwen-vl-plus",
            reasonModel = "qwen-plus",
        ),
        ProviderPreset(
            name = "火山方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            model = "doubao-seed-2-1-pro-260628",
            visionModel = "doubao-seed-2-1-pro-260628",
            reasonModel = "doubao-seed-2-1-pro-260628",
        ),
        ProviderPreset(
            name = "腾讯混元",
            baseUrl = "https://tokenhub.tencentmaas.com/v1",
            model = "hy3-preview",
            visionModel = "",
            reasonModel = "hy3-preview",
        ),
        ProviderPreset(
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "Qwen/Qwen3-32B",
            visionModel = "Qwen/Qwen3-VL-8B-Instruct",
            reasonModel = "Qwen/Qwen3-32B",
        ),
    )
}
