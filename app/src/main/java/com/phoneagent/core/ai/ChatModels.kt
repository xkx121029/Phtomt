package com.phoneagent.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI 兼容的 Chat Completions 请求/响应模型。
 * 智谱开放平台（GLM 系列）同样遵循该协议。
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double = 0.4,
    val max_tokens: Int = 4096,
    /** 是否流式输出（SSE） */
    val stream: Boolean = false,
    /** 结构化输出：约束 JSON 格式 */
    val response_format: ResponseFormat? = null,
    /** 深度思考参数（智谱 thinking），复杂任务（规划/重规划）时使用 */
    val thinking: ThinkingSpec? = null,
    /** 流式输出时是否返回用量统计（include_usage，部分厂商支持） */
    val stream_options: StreamOptions? = null,
    /** 可调用工具声明：仅用于「模型能力探测」，不参与 Agent 决策链 */
    val tools: List<ToolSpec>? = null,
    /** 工具选择策略（如 "auto"）；与 [tools] 同时出现才有意义 */
    val tool_choice: String? = null,
)

/** 工具声明（OpenAI tools 字段的单项） */
@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

@Serializable
data class StreamOptions(val include_usage: Boolean = true)

@Serializable
data class ResponseFormat(val type: String, val json_schema: JsonSchemaSpec? = null)

@Serializable
data class JsonSchemaSpec(val name: String, val strict: Boolean = true, val schema: JsonElement? = null)

@Serializable
data class ThinkingSpec(val type: String = "enabled", val effort: String = "high")

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: List<ContentPart>,
)

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    val image_url: ImageUrl? = null,
)

@Serializable
data class ImageUrl(val url: String)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ResponseMessage? = null,
    val finish_reason: String? = null,
)

@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

/** GET /models 响应（OpenAI 兼容） */
@Serializable
data class ModelListResponse(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(val id: String? = null)

/** SSE 流式响应块 */
@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta? = null,
)

@Serializable
data class StreamDelta(
    val content: String? = null,
    /** 增量思考内容（深度思考模式下先于 content 输出） */
    val reasoning_content: String? = null,
)