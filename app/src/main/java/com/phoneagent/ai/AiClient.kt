package com.phoneagent.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
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

    companion object {
        private const val INITIAL_DELAY_MS = 800L
        private const val BASE_429_DELAY_MS = 2000L
        private const val MAX_RETRIES = 5
        private const val MAX_NON_429_RETRIES = 2

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

        /** AgentAction 的标准 JSON Schema，用于约束模型输出（对齐 HPA 提示词 v2.0 动作契约） */
        private val ACTION_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "type": { "type": "string", "enum": ["tap","long_press","swipe","type","key","wait","launch","scroll_to","task_complete","abort","shell","click","long_click","swipe_up","swipe_down","swipe_left","swipe_right","back","home","recents","scroll","task_done","refresh"] },
            "target": {
              "type": "object",
              "properties": {
                "method": { "type": "string", "enum": ["id","label","coordinate"] },
                "value": { "type": "string" }
              },
              "required": ["method","value"],
              "additionalProperties": false
            },
            "x": { "type": "integer" },
            "y": { "type": "integer" },
            "endX": { "type": "integer" },
            "endY": { "type": "integer" },
            "text": { "type": "string" },
            "elementIndex": { "type": "integer" },
            "durationMs": { "type": "integer" },
            "summary": { "type": "string" },
            "reason": { "type": "string" },
            "reasoning": { "type": "string" },
            "expected": { "type": "string" },
            "confidence": { "type": "number" },
            "direction": { "type": "string", "enum": ["up","down","left","right"] },
            "keycode": { "type": "string", "enum": ["BACK","HOME","ENTER","RECENT","RECENTS"] },
            "packageName": { "type": "string" },
            "distancePx": { "type": "integer" },
            "pageFingerprint": { "type": "string" },
            "needsUserConfirmation": { "type": "boolean" },
            "command": { "type": "string" },
            "timeout_ms": { "type": "integer" }
          },
          "required": ["type"],
          "additionalProperties": false
        }
        """.trimIndent()
    }

    /**
     * 决策调用：流式生成，边生成边通过 [onDelta] 回调增量内容（用于悬浮窗/通知实时展示 AI 思考）。
     *
     * 流式不强制 response_format（避免 stream+json 兼容问题），完整正文累积后用 [parseAgentAction] 解析；
     * 若流式返回空正文（兼容性问题），回退到非流式 + 结构化输出，保证决策可靠性。
     * 末块若携带 usage（stream_options.include_usage）则解析用于指标统计。
     */
    suspend fun chatForAction(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        screenshot: Bitmap?,
        temperature: Double,
        onDelta: (String) -> Unit = {},
    ): Result<AiDecision> = withContext(Dispatchers.IO) {
        runCatching {
            val startNano = System.nanoTime()
            // 若携带截图，把图片拼到末尾消息（主模型决策一般文本即可，这里保留能力）
            val finalMessages = if (screenshot != null) {
                val imagePart = ContentPart(type = "image_url", image_url = ImageUrl(base64Image(screenshot)))
                val merged = messages.last().content.toMutableList() + imagePart
                messages.toMutableList().also { it[it.lastIndex] = it.last().copy(content = merged) }
            } else messages
            val streamBody = buildStreamRequestBody(finalMessages, model, temperature, thinking = false, streamOptions = StreamOptions())
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(streamBody)
                .build()

            val full = StringBuilder()
            var lastUsage: Usage? = null
            var sawReasoning = false
            var status = -1
            var lastErr: String? = null
            for (attempt in 0 until MAX_RETRIES) {
                if (attempt > 0) backoffSleep(status, attempt) ?: break
                try {
                        full.setLength(0)
                        lastUsage = null
                        sawReasoning = false
                        var streamFailed = false
                    client.newCall(request).execute().use { resp ->
                        status = resp.code
                        if (!resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            lastErr = runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                                .getOrNull() ?: "HTTP ${resp.code}"
                            streamFailed = true
                            return@use
                        }
                        resp.body?.source()?.use { source ->
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.isBlank() || !line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                val chunk = runCatching { json.decodeFromString<StreamChunk>(payload) }.getOrNull()
                                val delta = chunk?.choices?.firstOrNull()?.delta
                                val content = delta?.content.orEmpty()
                                val reasoning = delta?.reasoning_content.orEmpty()
                                if (reasoning.isNotEmpty()) sawReasoning = true
                                // 思考内容优先展示（边思考边输出）
                                val display = if (content.isNotEmpty()) content else reasoning
                                if (display.isNotEmpty()) onDelta(display)
                                // 完整正文只累积 content（用于最终解析 JSON）
                                if (content.isNotEmpty()) full.append(content)
                                // 末块可能携带 usage（stream_options.include_usage）
                                runCatching { json.decodeFromString<ChatResponse>(payload).usage }
                                    ?.getOrNull()?.let { lastUsage = it }
                            }
                        }
                    }
                    if (streamFailed) continue
                    val content = full.toString()
                    if (content.isBlank()) {
                        // 流式空正文（兼容问题）→ 回退非流式 + 结构化输出
                        lastErr = null
                        val fallbackBody = buildRequestBody(messages, screenshot, model, temperature)
                        val fallbackRequest = Request.Builder()
                            .url("$baseUrl/chat/completions")
                            .header("Authorization", "Bearer $apiKey")
                            .post(fallbackBody)
                            .build()
                        val fbContent = executeWithRetry(fallbackRequest)
                        return@runCatching AiDecision(
                            action = parseAgentAction(fbContent),
                            promptTokens = 0,
                            completionTokens = 0,
                            totalTokens = 0,
                            elapsedMs = (System.nanoTime() - startNano) / 1_000_000,
                            rawContent = fbContent,
                            thinking = false,
                        )
                    }
                    return@runCatching AiDecision(
                        action = parseAgentAction(content),
                        promptTokens = lastUsage?.promptTokens ?: 0,
                        completionTokens = lastUsage?.completionTokens ?: 0,
                        totalTokens = lastUsage?.totalTokens ?: 0,
                        elapsedMs = (System.nanoTime() - startNano) / 1_000_000,
                        rawContent = content,
                        thinking = sawReasoning,
                    )
                } catch (e: Exception) {
                    status = -1
                    lastErr = e.message
                }
            }
            error(lastErr ?: "AI 未返回内容")
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

            executeWithRetry(request)
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

            executeWithRetry(request)
        }
    }

    /** 无结构化约束、无 JSON 模式的文本请求体 */
    private fun buildRawRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = 4096),
    )

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
        return executeWithRetry(request)
    }

    /**
     * 执行非流式请求并返回模型正文，带 429 退避重试。
     * - 429（限流）：指数退避 2s/4s/8s/16s，最多 5 次尝试
     * - 其他失败：快速重试 1 次（800ms）
     */
    private fun executeWithRetry(request: Request): String {
        var status = -1
        var lastErr: String? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) backoffSleep(status, attempt) ?: break
            try {
                var failed = false
                client.newCall(request).execute().use { resp ->
                    status = resp.code
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastErr = runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                            .getOrNull() ?: "HTTP ${resp.code}"
                        failed = true
                        return@use
                    }
                    val parsed = json.decodeFromString<ChatResponse>(body)
                    parsed.error?.let { apiErr -> lastErr = apiErr.message; failed = true; return@use }
                    val content = parsed.choices.firstOrNull()?.message?.content?.trim()
                    if (content != null) return content
                    lastErr = "AI 返回空内容"
                    failed = true
                }
                if (failed) continue
                error("AI 未返回内容")
            } catch (e: Exception) {
                status = -1
                lastErr = e.message
            }
        }
        error(lastErr ?: "AI 未返回内容")
    }

    /**
     * 请求体构建辅助函数：接收 ChatRequest 构建器，序列化为 JSON 正文。
     * 消除 5 个 buildXxxRequestBody 方法中的重复逻辑。
     */
    private fun buildRequest(request: ChatRequest): okhttp3.RequestBody =
        json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)

    /** 退避等待：429 指数退避，其他最多重试 1 次；返回 false 表示不再重试 */
    private fun backoffSleep(status: Int, attempt: Int): Boolean {
        return when {
            status == 429 -> { Thread.sleep(BASE_429_DELAY_MS shl attempt); true }
            attempt >= MAX_NON_429_RETRIES -> false
            else -> { Thread.sleep(INITIAL_DELAY_MS); true }
        }
    }

    private fun buildCompatRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
        responseFormat: ResponseFormat?,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(messages = messages, model = model, temperature = temperature, max_tokens = 2048, response_format = responseFormat),
    )

    /** 从视觉模型输出中解析比例坐标：优先 JSON，其次 "x,y" 形式 */
    private fun parseCoordinate(content: String): Pair<Float, Float> {
        var trimmed = content.trim()
        // 去除 markdown 代码围栏（```json / ```），只保留括号内容
        if (trimmed.startsWith("```")) {
            val nl = trimmed.indexOf('\n')
            val end = trimmed.lastIndexOf("```")
            trimmed = if (nl >= 0 && end > nl) trimmed.substring(nl + 1, end) else {
                trimmed.removePrefix("```").removeSuffix("```")
            }
            trimmed = trimmed.trim()
        }
        runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val x = obj["x"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            val y = obj["y"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            if (x != null && y != null) return x to y
        }
        // 兜底：提取首个 { 到最后一个 } 之间的内容再按逗号拆分
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        val core = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
        val nums = core.replace("{", "").replace("}", "")
            .split(",").map { it.trim().toFloatOrNull() }
        if (nums.size >= 2 && nums[0] != null && nums[1] != null) return nums[0]!! to nums[1]!!
        error("无法解析坐标：$content")
    }

    /** 无结构化约束的文本请求体 */
    private fun buildTextRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = 4096, response_format = ResponseFormat(type = "json_object")),
    )

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
            var status = -1
            var lastErr: String? = null
            for (attempt in 0 until MAX_RETRIES) {
                if (attempt > 0) backoffSleep(status, attempt) ?: break
                try {
                    // 每次尝试独立累积，避免流中断重试后新旧内容拼接导致重复
                    full.setLength(0)
                    var streamFailed = false
                    client.newCall(request).execute().use { resp ->
                        status = resp.code
                        if (!resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            lastErr = runCatching { json.decodeFromString<ChatResponse>(body).error?.message }
                                .getOrNull() ?: "HTTP ${resp.code}"
                            streamFailed = true
                            return@use
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
                    if (streamFailed) continue
                    return@runCatching full.toString()
                } catch (e: Exception) {
                    status = -1
                    lastErr = e.message
                }
            }
            error(lastErr ?: "AI 未返回内容")
        }
    }

    /** 流式文本请求体（stream=true）；规划阶段用 extractJsonObject 解析，故不强制 response_format */
    private fun buildStreamRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
        thinking: Boolean = false,
        streamOptions: StreamOptions? = null,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = 4096, stream = true, thinking = if (thinking) ThinkingSpec() else null, stream_options = streamOptions),
    )

    /** 从模型输出中解析 AgentAction，带多级兜底解析 + 日志 */
    private fun parseAgentAction(content: String): AgentAction {
        // Step 1：提取 JSON（支持 ```json 代码块包裹）后直接解析
        try {
            return json.decodeFromString(AgentAction.serializer(), extractJson(content))
        } catch (e: Exception) {
            Log.w("AiClient", "Step1 parse failed: ${e.message}, content: ${content.take(200)}")
        }
        // Step 2：平衡花括号提取第一个完整 JSON 对象（支持嵌套）
        val balanced = extractBalancedJson(content)
        if (balanced != null) {
            try {
                return json.decodeFromString(AgentAction.serializer(), balanced)
            } catch (e: Exception) {
                Log.w("AiClient", "Step2 balanced parse failed: ${e.message}, balanced: ${balanced.take(200)}")
            }
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
            } catch (e: Exception) {
                Log.w("AiClient", "Step3 array parse failed: ${e.message}")
            }
        }
        // Step 4：全部失败 → 构造合法 abort
        Log.e("AiClient", "All parse steps failed. Raw content: ${content.take(300)}")
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

    /** 组装请求体：以结构化输出约束 JSON，并支持图片输入 */
    private fun buildRequestBody(
        messages: List<ChatMessageDto>,
        screenshot: Bitmap?,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody {
        val finalMessages = messages.toMutableList()
        if (screenshot != null) {
            val imagePart = ContentPart(type = "image_url", image_url = ImageUrl(base64Image(screenshot)))
            val merged = finalMessages.last().content.toMutableList() + imagePart
            finalMessages[finalMessages.lastIndex] = finalMessages.last().copy(content = merged)
        }
        return buildRequest(
            ChatRequest(
                model = model, messages = finalMessages, temperature = temperature, max_tokens = 4096,
                response_format = ResponseFormat(type = "json_object", json_schema = JsonSchemaSpec(name = "agent_action", strict = false, schema = json.parseToJsonElement(ACTION_SCHEMA))),
            ),
        )
    }

    /** 从模型输出中提取 JSON 对象（含反向搜索兜底） */
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
        // 反向搜索：从末尾 '}' 向前匹配最外层完整 JSON 对象
        val backward = extractJsonBackward(trimmed)
        if (backward != null) return backward
        // 兜底：提取第一个 { 到最后一个 }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    /** 从字符串末尾反向搜索最外层完整 JSON 对象 */
    private fun extractJsonBackward(text: String): String? {
        val lastBrace = text.lastIndexOf('}')
        if (lastBrace < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in lastBrace downTo 0) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '}') depth++
            else if (c == '{') {
                depth--
                if (depth == 0) {
                    val extracted = text.substring(i, lastBrace + 1).trim()
                    return if (extracted.length >= 2) extracted else null
                }
            }
        }
        return null
    }

    private fun base64Image(bitmap: Bitmap): String {
        val scaled = scaleBitmap(bitmap, 1024)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        val result = "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        // 若产生了缩放副本，用完即回收，避免原生内存泄漏
        if (scaled !== bitmap) scaled.recycle()
        return result
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
}