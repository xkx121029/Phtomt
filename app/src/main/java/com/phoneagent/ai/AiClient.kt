package com.phoneagent.ai

import android.graphics.Bitmap
import android.util.Base64
import com.phoneagent.model.AgentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的 AI 客户端，用于调用 GLM 等模型。
 * 支持文本 + 截图（多模态）输入，并通过结构化输出约束 AgentAction 的 JSON 格式。
 */
class AiClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 发送对话并返回 AgentAction 决策。
     *
     * @param baseUrl API 基础地址（不含 /chat/completions）
     * @param apiKey API Key
     * @param model 模型名
     * @param messages 历史+当前消息
     * @param screenshot 可选截图（缩放后 base64），用于视觉理解
     * @param temperature 温度
     */
    suspend fun chatForAction(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        screenshot: Bitmap?,
        temperature: Double,
    ): Result<AiDecision> = withContext(Dispatchers.IO) {
        runCatching {
            val startNano = System.nanoTime()
            val requestBody = buildRequestBody(messages, screenshot, model, temperature)
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            // 失败重试 1 次
            var lastContent: String? = null
            var lastErr: String? = null
            repeat(2) { attempt ->
                if (attempt > 0) {
                    Thread.sleep(800)
                }
                try {
                    client.newCall(request).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastErr = runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                                .getOrNull() ?: "HTTP ${resp.code}"
                            return@repeat
                        }
                        val parsed = json.decodeFromString<ChatResponse>(body)
                        parsed.error?.let { apiErr -> lastErr = apiErr.message; return@repeat }
                        lastContent = parsed.choices.firstOrNull()?.message?.content ?: return@repeat
                    }
                } catch (e: Exception) {
                    lastErr = e.message
                }
            }

            val content = lastContent ?: error(lastErr ?: "AI 未返回内容")
            val action = parseAgentAction(content)
            val usage = extractUsage(content)
            AiDecision(
                action = action,
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens ?: 0,
                totalTokens = usage?.totalTokens ?: 0,
                elapsedMs = (System.nanoTime() - startNano) / 1_000_000,
                rawContent = content,
            )
        }
    }

    /**
     * 通用文本对话：返回模型原始输出（用于规划/澄清/验证等非动作场景）。
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = buildTextRequestBody(messages, model, temperature)
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            var lastContent: String? = null
            var lastErr: String? = null
            repeat(2) { attempt ->
                if (attempt > 0) Thread.sleep(800)
                try {
                    client.newCall(request).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastErr = runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                                .getOrNull() ?: "HTTP ${resp.code}"
                            return@repeat
                        }
                        val parsed = json.decodeFromString<ChatResponse>(body)
                        parsed.error?.let { apiErr -> lastErr = apiErr.message; return@repeat }
                        lastContent = parsed.choices.firstOrNull()?.message?.content ?: return@repeat
                    }
                } catch (e: Exception) {
                    lastErr = e.message
                }
            }
            lastContent ?: error(lastErr ?: "AI 未返回内容")
        }
    }

    /**
     * 普通文本对话（不强制 json_object）：返回模型原始输出。
     * 用于翻译等需要自然语言结果的场景。
     */
    suspend fun chatText(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = buildRawRequestBody(messages, model, temperature)
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            var lastContent: String? = null
            var lastErr: String? = null
            repeat(2) { attempt ->
                if (attempt > 0) Thread.sleep(800)
                try {
                    client.newCall(request).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastErr = runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                                .getOrNull() ?: "HTTP ${resp.code}"
                            return@repeat
                        }
                        val parsed = json.decodeFromString<ChatResponse>(body)
                        parsed.error?.let { apiErr -> lastErr = apiErr.message; return@repeat }
                        lastContent = parsed.choices.firstOrNull()?.message?.content?.trim() ?: return@repeat
                    }
                } catch (e: Exception) {
                    lastErr = e.message
                }
            }
            lastContent ?: error(lastErr ?: "AI 未返回内容")
        }
    }

    /** 无结构化约束、无 JSON 模式的文本请求体 */
    private fun buildRawRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody {
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            max_tokens = 4096,
        )
        return json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)
    }

    // ==================== 视觉模型（glm-4.6v-flash） ====================

    /**
     * 用视觉模型描述截图：把图片转成可交互元素的文本描述（含大致位置）。
     * 用于主模型不支持图片输入时，将截图“翻译”成文本。
     */
    suspend fun visionDescribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        screenshot: Bitmap,
        task: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = "请仔细观察这张手机截图。列出页面上所有可交互元素（按钮、输入框、列表项、开关等）" +
                "以及它们显示的文字，并给出每个元素在屏幕上的大致位置（用 0~1 的比例坐标，x 为横向、y 为纵向）。" +
                "用户当前的目标是：$task。请用中文回答，聚焦可用于点击/操作的目标。"
            val messages = listOf(
                ChatMessageDto(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = prompt),
                        ContentPart(type = "image_url", image_url = ImageUrl(base64Image(screenshot))),
                    ),
                ),
            )
            postCompat(baseUrl, apiKey, model, messages, 0.1, null)
        }
    }

    /**
     * 用视觉模型定位目标：给定目标文字，返回其在截图上的比例坐标（0~1）。
     * 返回 JSON 形如 {"x":0.5,"y":0.3}。
     */
    suspend fun visionLocate(
        baseUrl: String,
        apiKey: String,
        model: String,
        screenshot: Bitmap,
        targetText: String,
    ): Result<Pair<Float, Float>> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = "请在这张手机截图上找到目标元素“$targetText”。" +
                "返回该元素中心点的比例坐标，格式严格为 JSON：{\"x\":0.0~1.0,\"y\":0.0~1.0}。" +
                "只输出 JSON，不要任何其他文字。"
            val messages = listOf(
                ChatMessageDto(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = prompt),
                        ContentPart(type = "image_url", image_url = ImageUrl(base64Image(screenshot))),
                    ),
                ),
            )
            val content = postCompat(baseUrl, apiKey, model, messages, 0.1, ResponseFormat(type = "json_object"))
            parseCoordinate(content)
        }
    }

    /** 通用 POST 请求：返回模型正文（支持图片与可选结构化输出） */
    private suspend fun postCompat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
        responseFormat: ResponseFormat?,
    ): String {
        val requestBody = buildCompatRequestBody(messages, model, temperature, responseFormat)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        var lastContent: String? = null
        var lastErr: String? = null
        repeat(2) { attempt ->
            if (attempt > 0) Thread.sleep(800)
            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastErr = runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                            .getOrNull() ?: "HTTP ${resp.code}"
                        return@repeat
                    }
                    val parsed = json.decodeFromString<ChatResponse>(body)
                    parsed.error?.let { apiErr -> lastErr = apiErr.message; return@repeat }
                    lastContent = parsed.choices.firstOrNull()?.message?.content?.trim() ?: return@repeat
                }
            } catch (e: Exception) {
                lastErr = e.message
            }
        }
        return lastContent ?: error(lastErr ?: "AI 未返回内容")
    }

    private fun buildCompatRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
        responseFormat: ResponseFormat?,
    ): okhttp3.RequestBody {
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            max_tokens = 2048,
            response_format = responseFormat,
        )
        return json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)
    }

    /** 从视觉模型输出中解析比例坐标：优先 JSON，其次 "x,y" 形式 */
    private fun parseCoordinate(content: String): Pair<Float, Float> {
        val trimmed = content.trim().removePrefix("```").removeSuffix("```").trim()
        runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val x = obj["x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            val y = obj["y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            if (x != null && y != null) return x to y
        }
        val nums = trimmed.replace("{", "").replace("}", "")
            .split(",").map { it.trim().toFloatOrNull() }
        if (nums.size >= 2 && nums[0] != null && nums[1] != null) return nums[0]!! to nums[1]!!
        error("无法解析坐标：$content")
    }

    /** 无结构化约束的文本请求体 */
    private fun buildTextRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody {
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            max_tokens = 4096,
            response_format = ResponseFormat(type = "json_object"),
        )
        return json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)
    }

    /**
     * 流式对话（SSE）：边生成边通过 onDelta 回调增量文本，返回完整正文。
     * 用于规划/思考阶段，避免用户长时间空等。
     */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
        onDelta: (String) -> Unit,
        thinking: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = buildStreamRequestBody(messages, model, temperature, thinking)
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val full = StringBuilder()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    error(
                        runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                            .getOrNull() ?: "HTTP ${resp.code}",
                    )
                }
                resp.body?.source()?.use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank() || !line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val delta = runCatching {
                            json.decodeFromString<StreamChunk>(payload).choices.firstOrNull()?.delta
                        }.getOrNull()
                        val content = delta?.content.orEmpty()
                        val reasoning = delta?.reasoning_content.orEmpty()
                        // 思考内容优先展示（边思考边输出）
                        val display = if (content.isNotEmpty()) content else reasoning
                        if (display.isNotEmpty()) onDelta(display)
                        // 完整正文只累积 content（用于最终解析 JSON）
                        if (content.isNotEmpty()) full.append(content)
                    }
                }
            }
            full.toString()
        }
    }

    /** 流式文本请求体（stream=true）；规划阶段用 extractJsonObject 解析，故不强制 response_format */
    private fun buildStreamRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
        thinking: Boolean = false,
    ): okhttp3.RequestBody {
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            max_tokens = 4096,
            stream = true,
            thinking = if (thinking) ThinkingSpec() else null,
        )
        return json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)
    }

    /** 从模型输出中解析 AgentAction，带多级兜底解析 */
    private fun parseAgentAction(content: String): AgentAction {
        // Step 1：提取 JSON（支持 ```json 代码块包裹）后直接解析
        try {
            return json.decodeFromString(AgentAction.serializer(), extractJson(content))
        } catch (_: Exception) {}
        // Step 2：平衡花括号提取第一个完整 JSON 对象（支持嵌套）
        val balanced = extractBalancedJson(content)
        if (balanced != null) {
            try {
                return json.decodeFromString(AgentAction.serializer(), balanced)
            } catch (_: Exception) {}
        }
        // Step 3：尝试 JSON 数组（动作合并）
        val arrayStart = content.indexOf('[')
        val arrayEnd = content.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            try {
                val arr = json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(AgentAction.serializer()),
                    content.substring(arrayStart, arrayEnd + 1),
                )
                if (arr.isNotEmpty()) return arr.first()
            } catch (_: Exception) {}
        }
        // Step 4：全部失败 → 构造合法 abort
        return AgentAction(
            type = "task_done",
            summary = "AI 输出无法解析为动作",
            reason = "JSON parse failed",
        )
    }

    /** 平衡花括号提取第一个完整 JSON 对象（支持嵌套） */
    private fun extractBalancedJson(content: String): String? {
        val start = content.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until content.length) {
            val c = content[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return content.substring(start, i + 1) }
            }
        }
        return null
    }

    /** 若响应内嵌 usage 结构，尝试提取（兜底） */
    private fun extractUsage(content: String): Usage? {
        return try {
            val parser = json
            val obj = parser.parseToJsonElement(content).jsonObject
            if ("usage" in obj) parser.decodeFromJsonElement(Usage.serializer(), obj["usage"]!!) else null
        } catch (_: Exception) { null }
    }

    /** 组装请求体：以结构化输出约束 JSON，并支持图片输入 */
    private fun buildRequestBody(
        messages: List<ChatMessageDto>,
        screenshot: Bitmap?,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody {
        val finalMessages = messages.toMutableList()
        // 将截图作为最后一个 user 消息的多模态输入追加
        if (screenshot != null) {
            val imagePart = ContentPart(type = "image_url", image_url = ImageUrl(base64Image(screenshot)))
            val merged = finalMessages.last().content.toMutableList() + imagePart
            finalMessages[finalMessages.size - 1] =
                finalMessages.last().copy(content = merged)
        }

        val request = ChatRequest(
            model = model,
            messages = finalMessages,
            temperature = temperature,
            max_tokens = 4096,
            response_format = ResponseFormat(
                type = "json_object",
                json_schema = JsonSchemaSpec(
                    name = "agent_action",
                    strict = true,
                    schema = json.parseToJsonElement(ACTION_SCHEMA),
                ),
            ),
        )
        return json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)
    }

    /** 从模型输出中提取第一个 JSON 对象（剥离可能的 markdown 代码块包裹） */
    private fun extractJson(content: String): String {
        val trimmed = content.trim()
        // 处理 ```json ... ``` 或 ``` ... ``` 代码块包裹
        if (trimmed.startsWith("```")) {
            val firstNewline = trimmed.indexOf("\n")
            val lastFence = trimmed.lastIndexOf("```")
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim()
            }
        }
        // 处理中间出现代码块的情况（前有文字+```json）
        val codeBlockStart = trimmed.indexOf("```json")
        if (codeBlockStart >= 0) {
            val afterMarker = trimmed.indexOf("\n", codeBlockStart)
            val blockEnd = trimmed.indexOf("```", afterMarker)
            if (afterMarker > 0 && blockEnd > afterMarker) {
                return trimmed.substring(afterMarker + 1, blockEnd).trim()
            }
        }
        // 兜底：提取第一个 { 到最后一个 }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun base64Image(bitmap: Bitmap): String {
        val scaled = scaleBitmap(bitmap, 1024)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    /** 等比缩放截图，避免超出模型限制 */
    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val max = maxOf(w, h)
        if (max <= maxDim) return src
        val scale = maxDim.toFloat() / max
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    companion object Factory {
        fun create(): AiClient {
            val http = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            val json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
            return AiClient(http, json)
        }

        /** AgentAction 的标准 JSON Schema，用于约束模型输出 */
        private val ACTION_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "type": { "type": "string", "enum": ["click","long_click","swipe","swipe_up","swipe_down","swipe_left","swipe_right","type","back","home","recents","scroll","wait","task_done","refresh"] },
            "x": { "type": "integer" },
            "y": { "type": "integer" },
            "endX": { "type": "integer" },
            "endY": { "type": "integer" },
            "text": { "type": "string" },
            "elementIndex": { "type": "integer" },
            "durationMs": { "type": "integer" },
            "summary": { "type": "string" },
            "reason": { "type": "string" }
          },
          "required": ["type"],
          "additionalProperties": false
        }
        """.trimIndent()
    }
}