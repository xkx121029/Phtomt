package com.phoneagent.core.ai

import android.graphics.Bitmap
import com.phoneagent.domain.model.IntentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 请求构造：把「模型 + 消息 + 采样参数」落成各家端点能吃的 HTTP 请求。
 *
 * 决策链路、文本链路、视觉链路与协议适配器（Claude / Gemini）的请求体只在这里成型；
 * 采样参数（max_tokens / temperature / thinking / stream_options）也只在这里决定，
 * 调用方不再各自拼 body。
 */
@Suppress("MaxLineLength")
internal class AiRequestBodies(private val json: Json, private val images: AiImageEncoder) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * AgentIntent 的标准 JSON Schema，用于约束模型输出（对齐 HPA动作执行逻辑优化文档 v2.1 三、意图 DSL）。
     *
     * intent 的 enum 由 [IntentType.ALL] 现场生成，**不手抄**：这份表曾因手抄而落后于意图全集
     * （缺 remember、device_query、say、show_agent、fetch、6 个 browse_*、shell、a11y），
     * 结果是"结构化回退"这条路上新意图被 schema 直接判非法。唯一定义点在 [IntentType.ALL]。
     */
    private val actionSchema = """
    {
      "type": "object",
      "properties": {
        "intent": { "type": "string", "enum": [${IntentType.ALL.joinToString(",") { "\"$it\"" }}] },
        "target": {
          "type": "object",
          "properties": {
            "by": { "type": "string", "enum": ["id","text","hint","coordinate"] },
            "value": { "type": "string" }
          },
          "required": ["by","value"],
          "additionalProperties": false
        },
        "app": { "type": "string" },
        "text": { "type": "string" },
        "direction": { "type": "string", "enum": ["up","down","left","right"] },
        "distance_px": { "type": "integer" },
        "key": { "type": "string" },
        "duration_ms": { "type": "integer" },
        "wait_ms": { "type": "integer" },
        "summary": { "type": "string" },
        "reason": { "type": "string" },
        "uri": { "type": "string" },
        "page": { "type": "string" },
        "reasoning": { "type": "string" },
        "expected": { "type": "string" },
        "confidence": { "type": "number" },
        "needs_confirmation": { "type": "boolean" },
        "page_fingerprint": { "type": "string" }
      },
      "required": ["intent"],
      "additionalProperties": false
    }
    """.trimIndent()

    /** [actionSchema] 只解析一次（每次请求都 parse 是纯浪费） */
    private val actionSchemaElement: kotlinx.serialization.json.JsonElement by lazy {
        json.parseToJsonElement(actionSchema)
    }

    /** 决策请求可选的顶层结构化字段；全空时请求体与历史版本一致（只有提示词约束） */
    internal data class ActionBodyExtras(
        val responseFormat: ResponseFormat? = null,
        val guidedJson: kotlinx.serialization.json.JsonElement? = null,
        val jsonSchema: kotlinx.serialization.json.JsonElement? = null,
    )

    /**
     * 决策请求的结构化输出附加字段。
     *
     * 三者互斥，按优先级只取一个（同时下发两套约束会被部分服务端直接判 400）：
     * 引导解码（手动档最高）> `response_format`（开关 + 探测）> 无（只靠提示词）。
     */
    internal fun decisionBodyExtras(structured: Boolean, guided: GuidedDecoding): ActionBodyExtras {
        val schema = actionSchemaElement
        return when (guided) {
            GuidedDecoding.VLLM -> ActionBodyExtras(guidedJson = schema)
            GuidedDecoding.SGLANG -> ActionBodyExtras(jsonSchema = schema)
            GuidedDecoding.OFF -> if (structured) {
                ActionBodyExtras(
                    responseFormat = ResponseFormat(
                        type = "json_schema",
                        json_schema = JsonSchemaSpec(name = "agent_action", strict = false, schema = schema),
                    ),
                )
            } else {
                ActionBodyExtras()
            }
        }
    }

    /**
     * 请求体构建辅助函数：接收 ChatRequest 构建器，序列化为 JSON 正文。
     */
    internal fun buildRequest(request: ChatRequest): okhttp3.RequestBody =
        json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)

    /** 无结构化约束、无 JSON 模式的文本请求体 */
    internal fun buildRawRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = 4096),
    )

    internal fun buildCompatRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
        responseFormat: ResponseFormat?,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(messages = messages, model = model, temperature = temperature, max_tokens = 2048,
            response_format = responseFormat),
    )

    /** 流式文本请求体（stream=true）；规划阶段用 extractJsonObject 解析，故不强制 response_format */
    @Suppress("LongParameterList")
    internal fun buildStreamRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
        thinking: Boolean = false,
        streamOptions: StreamOptions? = null,
        extras: ActionBodyExtras? = null,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = 4096, stream = true,
            thinking = if (thinking) ThinkingSpec() else null, stream_options = streamOptions,
            response_format = extras?.responseFormat, guidedJson = extras?.guidedJson,
            jsonSchema = extras?.jsonSchema),
    )

    /**
     * 组装请求体：以结构化输出约束 JSON，并支持图片输入。
     * [extras] 为 `null` 时保持历史行为（`json_object` + agent_action schema）。
     */
    internal fun buildRequestBody(
        messages: List<ChatMessageDto>,
        screenshot: Bitmap?,
        model: String,
        temperature: Double,
        extras: ActionBodyExtras? = null,
    ): okhttp3.RequestBody {
        val finalMessages = messages.toMutableList()
        if (screenshot != null) {
            val imagePart = ContentPart(type = "image_url", image_url = ImageUrl(images.base64Image(screenshot)))
            val merged = finalMessages.last().content.toMutableList() + imagePart
            finalMessages[finalMessages.lastIndex] = finalMessages.last().copy(content = merged)
        }
        val bodyExtras = extras ?: decisionBodyExtras(structured = true, guided = GuidedDecoding.OFF)
        return buildRequest(
            ChatRequest(
                model = model, messages = finalMessages, temperature = temperature, max_tokens = 4096,
                response_format = bodyExtras.responseFormat, guidedJson = bodyExtras.guidedJson,
                jsonSchema = bodyExtras.jsonSchema,
            ),
        )
    }

    /** 按协议适配器构建非流式请求（Claude / Gemini 原生端点） */
    @Suppress("LongParameterList")
    internal fun protocolRequest(
        adapter: ChatProtocolAdapter,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
    ): Request {
        val url = adapter.endpointUrl(baseUrl, model, stream)
        val body = adapter.body(model, messages, temperature, maxTokens, stream)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(url).post(body)
        adapter.headers(apiKey).forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    /** 按协议适配器构建流式请求（max_tokens 与决策链路对齐取 4096） */
    @Suppress("LongParameterList")
    internal fun protocolStreamRequest(
        adapter: ChatProtocolAdapter,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
    ): Request = protocolRequest(adapter, baseUrl, apiKey, model, messages, temperature, maxTokens = 4096, stream = true)
}
