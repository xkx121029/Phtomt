package com.phoneagent.core.ai

/**
 * 主模型「要不要在决策请求里带结构化输出约束（`response_format=json_schema`）」的三态。
 *
 * 背景：决策链路一直是「流式 + 自己解析 JSON」，`response_format` 只在流式空正文回退到非流式时才出现。
 * 但很多服务端对 `stream=true` 与 `response_format` 同时出现会直接 400，而另一些服务端
 * （vLLM / SGLang / 新版 OpenAI）反而靠它把"吐不出合法 JSON"的概率压到很低。
 *
 * 所以不猜、也不一刀切：
 * - [AUTO]：只信模型库里的**真实请求探测**结果（[CatalogModel.jsonSchema]），没测出就不发（与旧行为一致）；
 * - [ON] / [OFF]：用户的明确覆盖，探测不参与。
 */
enum class StructuredOutputMode(val key: String) {
    /** 按模型库的探测结果决定 */
    AUTO("AUTO"),

    /** 强制带上结构化输出约束 */
    ON("ON"),

    /** 强制不带（只靠提示词 + 本地解析） */
    OFF("OFF"),
}

/**
 * 端点级引导解码（手动档，不自动探测）。
 *
 * 自建推理服务普遍支持"把 schema 直接交给采样器"的强约束，但字段名与位置各不相同：
 * - vLLM：顶层 `guided_json`；
 * - SGLang：顶层 `json_schema`。
 *
 * 这是**用户显式选择**的档位：开关打开时会替代 `response_format`，避免两套约束同时下发互相打架。
 */
enum class GuidedDecoding(val key: String, val label: String) {
    OFF("OFF", "关闭"),
    VLLM("VLLM", "vLLM"),
    SGLANG("SGLANG", "SGLang");

    companion object {
        /** 脏数据（空串/历史值/手工改坏）一律回落 [OFF]，不让设置读坏就把请求改坏 */
        fun fromKey(key: String?): GuidedDecoding =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: OFF
    }
}

object StructuredOutputRouting {

    /** 脏数据一律回落 [StructuredOutputMode.AUTO] */
    fun modeFromKey(key: String?): StructuredOutputMode =
        StructuredOutputMode.entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
            ?: StructuredOutputMode.AUTO

    /**
     * 三态合流：[mode] 为 AUTO 时听探测结果；ON/OFF 时探测不参与。
     *
     * [probed] 为 `null` 表示"没测过 / 没测出来"（400/5xx/网关改写），AUTO 下等同"不发"：
     * 宁可少发，也不要对着不认识该字段的服务端换来一次 400。
     */
    fun resolve(mode: StructuredOutputMode, probed: Boolean?): Boolean = when (mode) {
        StructuredOutputMode.AUTO -> probed == true
        StructuredOutputMode.ON -> true
        StructuredOutputMode.OFF -> false
    }

    /**
     * 从模型库里找出"当前主模型（地址 + 模型名）"的结构化输出探测结果。
     * 地址先归一化再比对，口径与 [VisionRouting.probedVisionOf] 一致。
     */
    fun probedOf(catalog: List<CatalogModel>, baseUrl: String, model: String): Boolean? {
        val name = model.trim()
        if (name.isBlank()) return null
        val id = ModelCatalogCodec.endpointId(baseUrl)
        return catalog.firstOrNull { it.endpointId == id && it.name == name }?.jsonSchema
    }

    /** 当前主模型所在端点的引导解码档位；端点未入库（旧三槽配置）按关闭处理 */
    fun guidedOf(endpoints: List<Endpoint>, baseUrl: String): GuidedDecoding =
        GuidedDecoding.fromKey(
            endpoints.firstOrNull { it.id == ModelCatalogCodec.endpointId(baseUrl) }?.guidedDecoding,
        )

    /** 服务端是否在正文里明确抱怨了结构化输出字段（用于流式 400/422 的一次性降级重试） */
    fun isStructuredRejection(status: Int, body: String?): Boolean {
        if (status != 400 && status != 422) return false
        val lower = body.orEmpty().lowercase()
        return STRUCTURED_HINTS.any { lower.contains(it) }
    }

    /** 各服务商的报错措辞差异很大，这里只认"指名字段"的线索，避免把无关的 400 也当成可降级 */
    private val STRUCTURED_HINTS = listOf(
        "response_format", "json_schema", "json schema", "structured", "guided_json", "guided decoding",
    )
}
