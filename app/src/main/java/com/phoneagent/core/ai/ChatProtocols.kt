package com.phoneagent.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * API 协议档位：端点除 OpenAI 兼容协议外，还支持 Claude / Gemini 原生协议。
 *
 * 设计对齐 Operit 的「薄 Provider」思路 —— 每种协议一个适配器（见 [ChatProtocolAdapter]），
 * 请求/响应的线格式差异全部收口在适配器内；[AiClient] 只在 URL、请求头、正文、SSE 解析
 * 四个切面按协议分流，主流程（重试、纠错重发、JSON 提取）对协议无感。
 */
enum class ApiProtocol(val id: String, val label: String) {
    OPENAI("openai", "OpenAI 兼容"),
    ANTHROPIC("anthropic", "Claude 原生"),
    GEMINI("gemini", "Gemini 原生"),
    ;

    companion object {
        /** 脏数据 / 旧数据（无 protocol 字段）一律回落 OpenAI 兼容 */
        fun fromId(id: String?): ApiProtocol = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

/**
 * 协议适配器契约：把统一的内部消息格式（[ChatMessageDto]，OpenAI 风格）转译成各协议线格式，
 * 并把各协议的响应/SSE 帧解析回统一口径。
 *
 * 实现只需覆盖三件事：
 * 1. 端点 URL 与请求头（各协议的地址拼法与鉴权头不同）；
 * 2. 请求体（系统消息、图片部件的角色与字段名不同）；
 * 3. 响应与 SSE 帧的增量/用量提取。
 */
internal interface ChatProtocolAdapter {

    /** 请求端点 URL（流式与否可能不同，如 Gemini 的 generateContent / streamGenerateContent） */
    fun endpointUrl(baseUrl: String, model: String, stream: Boolean): String

    /** 额外请求头；Authorization 由调用方按需追加（适配器返回空即完全自管鉴权） */
    fun headers(apiKey: String): List<Pair<String, String>>

    /** 非流式请求体 */
    fun body(model: String, messages: List<ChatMessageDto>, temperature: Double, maxTokens: Int, stream: Boolean): String

    /** 非流式响应 → 模型正文；解析不出返回 null */
    fun extractText(responseBody: String): String?

    /** SSE 数据帧（已剥掉 "data:" 前缀）→ 增量正文；无增量返回空串 */
    fun extractDelta(ssePayload: String): String

    /** SSE 数据帧是否为终止帧（如 OpenAI 的 [DONE]、Anthropic 的 message_stop） */
    fun isTerminal(ssePayload: String): Boolean

    /** 从响应体提取 token 用量 */
    fun extractUsageBody(responseBody: String): Usage?

    /** 从 SSE 帧提取 token 用量（帧可能只带其中一部分，调用方按字段合并） */
    fun extractUsageFrame(ssePayload: String): Usage?

    /** 模型列表端点 */
    fun modelsUrl(baseUrl: String): String

    /** 解析模型列表响应 → 模型名列表 */
    fun parseModels(responseBody: String): List<String>
}

/** 协议适配器注册表：[ApiProtocol] → 适配器实例（无状态，单例复用） */
internal object ChatProtocols {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun of(protocol: ApiProtocol): ChatProtocolAdapter? = when (protocol) {
        ApiProtocol.OPENAI -> null // OpenAI 兼容走 AiClient 既有路径，无需适配器
        ApiProtocol.ANTHROPIC -> AnthropicAdapter(json)
        ApiProtocol.GEMINI -> GeminiAdapter(json)
    }

    // ==================== 适配器共用的小工具 ====================

    /** 取消息里全部文本（拼接各 text 部件） */
    internal fun textOf(content: List<ContentPart>): String =
        content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")

    /** 取首个图片部件的 base64 数据与 MIME 类型（data URL 剥头）；无图返回 null */
    internal fun imageOf(content: List<ContentPart>): Pair<String, String>? {
        val url = content.firstOrNull { it.type == "image_url" }?.image_url?.url ?: return null
        // 形如 data:image/jpeg;base64,XXXX
        val marker = ";base64,"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val mime = url.substringBefore(";base64,").substringAfter("data:").ifBlank { "image/jpeg" }
        val data = url.substring(idx + marker.length)
        return data to mime
    }
}

/** 安全取 JSON 字段（缺失/类型不符返回 null）。声明为 JsonObject 的顶层扩展：适配器各解析点都作用在对象节点上 */
internal fun JsonObject.str(key: String): String? =
    runCatching { this@str[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

internal fun JsonObject.obj(key: String): JsonObject? =
    runCatching { this@obj[key]?.jsonObject }.getOrNull()

internal fun JsonObject.arr(key: String): JsonArray? =
    runCatching { this@arr[key]?.jsonArray }.getOrNull()

/**
 * Claude 原生协议（Anthropic Messages API）。
 *
 * 线格式要点：
 * - 端点 `{base}/v1/messages`，鉴权 `x-api-key` + `anthropic-version`；
 * - system 消息独立成 `system` 字段，messages 里只允许 user / assistant；
 * - 图片为 `{type:"image", source:{type:"base64", media_type, data}}`；
 * - max_tokens 必填；响应正文在 `content[].text`；
 * - SSE：content_block_delta 带 text_delta 增量，message_start 带输入用量，message_delta 带输出用量。
 */
internal class AnthropicAdapter(private val json: Json) : ChatProtocolAdapter {

    override fun endpointUrl(baseUrl: String, model: String, stream: Boolean): String {
        val base = AiClient.normalizeBaseUrl(baseUrl)
        return when {
            base.endsWith("/v1/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
    }

    override fun headers(apiKey: String): List<Pair<String, String>> = listOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
    )

    override fun body(
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
    ): String {
        // system 消息独立成顶层字段；其余按 user/assistant 透传
        val system = messages.filter { it.role == "system" }.map { ChatProtocols.textOf(it.content) }
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val chat = messages.filter { it.role != "system" }
        val body = buildString {
            append("{\"model\":${JsonPrimitive.quote(model)}")
            append(",\"max_tokens\":$maxTokens")
            append(",\"temperature\":$temperature")
            if (system.isNotBlank()) append(",\"system\":${JsonPrimitive.quote(system)}")
            append(",\"stream\":$stream")
            append(",\"messages\":[")
            chat.forEachIndexed { i, msg ->
                if (i > 0) append(",")
                val role = if (msg.role == "assistant") "assistant" else "user"
                append("{\"role\":\"$role\",\"content\":[")
                val img = ChatProtocols.imageOf(msg.content)
                if (img != null) {
                    append("{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":")
                    append(JsonPrimitive.quote(img.second))
                    append(",\"data\":")
                    append(JsonPrimitive.quote(img.first))
                    append("}}")
                }
                val text = ChatProtocols.textOf(msg.content)
                if (text.isNotBlank()) {
                    if (img != null) append(",")
                    append("{\"type\":\"text\",\"text\":")
                    append(JsonPrimitive.quote(text))
                    append("}")
                }
                if (img == null && text.isBlank()) append("{\"type\":\"text\",\"text\":\"\"}")
                append("]}")
            }
            append("]}")
        }
        return body
    }

    override fun extractText(responseBody: String): String? = runCatching {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        obj["error"]?.let { return null }
        obj.arr("content")?.mapNotNull { block ->
            val b = runCatching { block.jsonObject }.getOrNull() ?: return@mapNotNull null
            if (b.str("type") == "text") b.str("text") else null
        }?.joinToString("")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun extractDelta(ssePayload: String): String = runCatching {
        val obj = json.parseToJsonElement(ssePayload).jsonObject
        if (obj.str("type") != "content_block_delta") return ""
        val delta = obj.obj("delta") ?: return ""
        when (delta.str("type")) {
            "text_delta" -> delta.str("text").orEmpty()
            else -> ""
        }
    }.getOrDefault("")

    override fun isTerminal(ssePayload: String): Boolean =
        ssePayload.contains("\"type\":\"message_stop\"")

    override fun extractUsageBody(responseBody: String): Usage? = runCatching {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        val usage = obj.obj("usage") ?: return null
        Usage(
            promptTokens = usage.str("input_tokens")?.toIntOrNull() ?: 0,
            completionTokens = usage.str("output_tokens")?.toIntOrNull() ?: 0,
            totalTokens = (usage.str("input_tokens")?.toIntOrNull() ?: 0) + (usage.str("output_tokens")?.toIntOrNull() ?: 0),
        )
    }.getOrNull()

    override fun extractUsageFrame(ssePayload: String): Usage? = runCatching {
        val obj = json.parseToJsonElement(ssePayload).jsonObject
        when (obj.str("type")) {
            "message_start" -> {
                val usage = obj.obj("message")?.obj("usage") ?: return null
                val input = usage.str("input_tokens")?.toIntOrNull() ?: 0
                Usage(promptTokens = input, completionTokens = 0, totalTokens = input)
            }
            "message_delta" -> {
                val usage = obj.obj("usage") ?: return null
                val output = usage.str("output_tokens")?.toIntOrNull() ?: 0
                Usage(promptTokens = 0, completionTokens = output, totalTokens = output)
            }
            else -> null
        }
    }.getOrNull()

    override fun modelsUrl(baseUrl: String): String {
        val base = AiClient.normalizeBaseUrl(baseUrl)
        return if (base.endsWith("/v1/models")) base
        else if (base.endsWith("/v1")) "$base/models"
        else "$base/v1/models"
    }

    override fun parseModels(responseBody: String): List<String> = runCatching {
        json.parseToJsonElement(responseBody).jsonObject
            .arr("data").orEmpty()
            .mapNotNull { runCatching { it.jsonObject.str("id") }.getOrNull() }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}

/**
 * Gemini 原生协议（generativelanguage generateContent）。
 *
 * 线格式要点：
 * - 端点 `{base}/v1beta/models/{model}:generateContent`，流式为 `:streamGenerateContent?alt=sse`；
 * - 鉴权 `x-goog-api-key` 请求头；
 * - system 消息进 `systemInstruction`，assistant 角色名映射为 "model"；
 * - 图片为 `inlineData:{mimeType, data}`；正文在 `candidates[0].content.parts[].text`；
 * - 用量在 `usageMetadata`（promptTokenCount / candidatesTokenCount / totalTokenCount）。
 */
internal class GeminiAdapter(private val json: Json) : ChatProtocolAdapter {

    override fun endpointUrl(baseUrl: String, model: String, stream: Boolean): String {
        val base = AiClient.normalizeBaseUrl(baseUrl)
        val root = if (base.contains("/v1")) base else "$base/v1beta"
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "$root/models/${model.trim()}:$action"
    }

    override fun headers(apiKey: String): List<Pair<String, String>> = listOf(
        "x-goog-api-key" to apiKey,
    )

    override fun body(
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
    ): String {
        val system = messages.filter { it.role == "system" }.map { ChatProtocols.textOf(it.content) }
            .filter { it.isNotBlank() }.joinToString("\n\n")
        // assistant → model；连续同角色合并（Gemini 对连续同角色消息兼容性差）
        data class Turn(val role: String, val parts: StringBuilder)
        val turns = mutableListOf<Turn>()
        for (msg in messages.filter { it.role != "system" }) {
            val role = if (msg.role == "assistant") "model" else "user"
            val last = turns.lastOrNull()
            if (last != null && last.role == role) {
                appendParts(last.parts, msg.content)
            } else {
                Turn(role, StringBuilder().also { appendParts(it, msg.content) }).also { turns.add(it) }
            }
        }
        return buildString {
            append("{\"contents\":[")
            turns.forEachIndexed { i, t ->
                if (i > 0) append(",")
                append("{\"role\":\"${t.role}\",\"parts\":[${t.parts}]}")
            }
            append("]")
            if (system.isNotBlank()) {
                append(",\"systemInstruction\":{\"parts\":[{\"text\":")
                append(JsonPrimitive.quote(system))
                append("}]}")
            }
            append(",\"generationConfig\":{\"temperature\":$temperature,\"maxOutputTokens\":$maxTokens}")
            append("}")
        }
    }

    private fun appendParts(sb: StringBuilder, content: List<ContentPart>) {
        val img = ChatProtocols.imageOf(content)
        if (img != null) {
            if (sb.isNotEmpty()) sb.append(",")
            sb.append("{\"inlineData\":{\"mimeType\":")
            sb.append(JsonPrimitive.quote(img.second))
            sb.append(",\"data\":")
            sb.append(JsonPrimitive.quote(img.first))
            sb.append("}}")
        }
        val text = ChatProtocols.textOf(content)
        if (text.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append(",")
            sb.append("{\"text\":")
            sb.append(JsonPrimitive.quote(text))
            sb.append("}")
        }
        if (sb.isEmpty()) sb.append("{\"text\":\"\"}")
    }

    override fun extractText(responseBody: String): String? = runCatching {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        obj["error"]?.let { return null }
        val cand = obj.arr("candidates")?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return null
        val parts = cand.obj("content")?.arr("parts") ?: return null
        parts.mapNotNull { p ->
            runCatching { p.jsonObject }.getOrNull()?.str("text")
        }.joinToString("").takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun extractDelta(ssePayload: String): String = runCatching {
        val obj = json.parseToJsonElement(ssePayload).jsonObject
        val cand = obj.arr("candidates")?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return ""
        val parts = cand.obj("content")?.arr("parts") ?: return ""
        parts.mapNotNull { p -> runCatching { p.jsonObject }.getOrNull()?.str("text") }.joinToString("")
    }.getOrDefault("")

    override fun isTerminal(ssePayload: String): Boolean = false

    override fun extractUsageBody(responseBody: String): Usage? = runCatching {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        val usage = obj.obj("usageMetadata") ?: return null
        val prompt = usage.str("promptTokenCount")?.toIntOrNull() ?: 0
        val completion = usage.str("candidatesTokenCount")?.toIntOrNull() ?: 0
        Usage(prompt, completion, usage.str("totalTokenCount")?.toIntOrNull() ?: (prompt + completion))
    }.getOrNull()

    override fun extractUsageFrame(ssePayload: String): Usage? = runCatching {
        // Gemini 的用量只挂在末帧的 usageMetadata 上，多数帧无此字段
        val usage = json.parseToJsonElement(ssePayload).jsonObject.obj("usageMetadata") ?: return null
        val prompt = usage.str("promptTokenCount")?.toIntOrNull() ?: 0
        val completion = usage.str("candidatesTokenCount")?.toIntOrNull() ?: 0
        Usage(prompt, completion, usage.str("totalTokenCount")?.toIntOrNull() ?: (prompt + completion))
    }.getOrNull()

    override fun modelsUrl(baseUrl: String): String {
        val base = AiClient.normalizeBaseUrl(baseUrl)
        val root = if (base.contains("/v1")) base else "$base/v1beta"
        return "$root/models"
    }

    override fun parseModels(responseBody: String): List<String> = runCatching {
        json.parseToJsonElement(responseBody).jsonObject
            .arr("models").orEmpty()
            .mapNotNull { m ->
                runCatching {
                    val o = m.jsonObject
                    // 只留支持 generateContent 的模型；名字形如 "models/xxx"，剥掉前缀
                    val methods = o.arr("supportedGenerationMethods")
                        ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                        .orEmpty()
                    if (methods.isNotEmpty() && methods.none { it == "generateContent" }) return@mapNotNull null
                    o.str("name")?.removePrefix("models/")
                }.getOrNull()
            }
            .filter { !it.isNullOrBlank() }
            .map { it!! }
    }.getOrDefault(emptyList())
}

/** 手写 JSON 字符串字面量的最小工具（转义反斜杠/引号/控制字符） */
internal object JsonPrimitive {
    fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        sb.append('"')
        for (c in raw) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
