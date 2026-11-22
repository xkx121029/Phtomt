package com.phoneagent.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 模型枚举与能力探测：`GET /models` 拉列表，再用真实请求分别探文本 / 识图 / 工具 / 结构化输出。
 *
 * 探测走独立的短超时 client（见 [AiClient] 的 probeClient），不复用带退避的重试执行器——
 * 那会把一次探测拖到分钟级。能力值 `null` 表示「未测出」，与「不支持」（`false`）区分开。
 */
internal class AiProbe(
    private val probeClient: OkHttpClient,
    private val json: Json,
    private val bodies: AiRequestBodies,
    private val images: AiImageEncoder,
) {

    /** 单发请求的原始结果：状态码 + 正文 */
    private data class RawResponse(val status: Int, val body: String)

    /**
     * 拉取该端点可用的模型列表（`GET /models`）。
     *
     * 只写库、不做能力探测：网关常返回上百条模型，逐条探测等于几百次请求。
     */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val adapter = ChatProtocols.of(protocol)
                val url = adapter?.modelsUrl(baseUrl) ?: AiEndpoints.modelsUrl(baseUrl)
                val request = Request.Builder().url(url).get().apply {
                    if (adapter != null) {
                        adapter.headers(apiKey).forEach { (k, v) -> header(k, v) }
                    } else {
                        header("Authorization", "Bearer $apiKey")
                    }
                }.build()
                probeClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 404) error("该服务未提供模型列表接口（HTTP 404），请手动填写模型名")
                    if (!resp.isSuccessful) error(AiRetryPolicy.httpErrorText(resp, body))
                    val models = adapter?.parseModels(body) ?: runCatching {
                        json.decodeFromString<ModelListResponse>(body).data
                            .mapNotNull { it.id?.trim()?.takeIf(String::isNotEmpty) }
                    }.getOrElse { error("模型列表返回格式不认识（HTTP ${resp.code}）\n返回内容：${AiRetryPolicy.snippet(body)}") }
                    models.distinct().sorted()
                }
            }
        }

    /**
     * 真实请求探测单个模型的能力：文本 → 识图 → 工具，前一步整体失败即短路。
     *
     * 不复用 [executeWithRetry]：其 429 退避（4/8/16/32s）会把探测拖到分钟级，且会把
     * 「`finish_reason=tool_calls` + `content=null`」误判成"AI 返回空内容"。
     * 能力值 `null` = 未测出（服务端 400/5xx、网关改写、200 但空正文），与"不支持"（`false`）区分开。
     */
    suspend fun probeAbility(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<ModelAbility> =
        withContext(Dispatchers.IO) {
            runCatching {
                val adapter = ChatProtocols.of(protocol)
                val notes = mutableListOf<String>()

                // 1) 文本：这一步失败说明地址 / Key / 模型名有问题，整体失败
                probeText(baseUrl, apiKey, model, adapter)

                // 2) 识图：200 且正文非空才算测出支持
                val vision = probeVision(baseUrl, apiKey, model, adapter, notes)

                // 3) 工具调用：仅 OpenAI 兼容协议可探测
                val tools = probeTools(baseUrl, apiKey, model, adapter, notes)

                // 4) 结构化输出：仅 OpenAI 兼容协议可探测
                val jsonSchema = probeJsonSchema(baseUrl, apiKey, model, adapter, notes)

                ModelAbility(vision = vision, tools = tools, jsonSchema = jsonSchema,
                    note = notes.joinToString("；"))
            }
        }

    /** 第 1 步：文本连通性。整步失败说明地址 / Key / 模型名本身不对，直接让整个探测失败 */
    private fun probeText(baseUrl: String, apiKey: String, model: String, adapter: ChatProtocolAdapter?) {
        val textMsgs = listOf(
            ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请只回复：OK"))),
        )
        val textCall = rawPost(baseUrl, apiKey, model, textMsgs, null, adapter)
        probeBlocker(textCall)?.let { error(it) }
        if (textCall.status !in 200..299) error("文本请求失败（HTTP ${textCall.status}）：${AiRetryPolicy.snippet(textCall.body)}")
    }

    /** 第 2 步：识图。200 且正文非空才算测出支持；未测出时把原因记进 [notes] */
    private fun probeVision(
        baseUrl: String,
        apiKey: String,
        model: String,
        adapter: ChatProtocolAdapter?,
        notes: MutableList<String>,
    ): Boolean? {
        val shot = probeImageBitmap()
        val visionMsgs = listOf(
            ChatMessageDto(
                role = "user",
                content = listOf(
                    ContentPart(type = "text", text = "这张图是什么颜色？只回答颜色。"),
                    ContentPart(type = "image_url", image_url = ImageUrl(images.base64Image(shot))),
                ),
            ),
        )
        val visionCall = try {
            rawPost(baseUrl, apiKey, model, visionMsgs, null, adapter)
        } finally {
            shot.recycle()
        }
        probeBlocker(visionCall)?.let { error(it) }
        val extract = adapter?.let { a: ChatProtocolAdapter -> a::extractText }
        val vision: Boolean? = when {
            visionCall.status !in 200..299 -> AiEndpoints.probeVerdict(visionCall.status)
            hasContent(visionCall, extract) -> true
            else -> null
        }
        if (vision == null) notes += "识图：${probeNote(visionCall)}"
        return vision
    }

    /** 第 3 步：工具调用。Claude/Gemini 原生协议的工具线格式不同，不做探测 */
    private fun probeTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        adapter: ChatProtocolAdapter?,
        notes: MutableList<String>,
    ): Boolean? {
        if (adapter != null) {
            notes += "工具：该协议未探测（真实可用性以实际调用为准）"
            return null
        }
        val toolsCall = rawPost(
            baseUrl, apiKey, model,
            listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text",
                text = "请调用 noop_ping 工具。")))),
            listOf(
                ToolSpec(
                    function = FunctionSpec(
                        name = "noop_ping",
                        description = "无副作用的连通性探测工具",
                        parameters = json.parseToJsonElement("""{"type":"object","properties":{}}"""),
                    ),
                ),
            ),
            adapter,
        )
        probeBlocker(toolsCall)?.let { error(it) }
        val tools = AiEndpoints.probeVerdict(toolsCall.status)
        if (tools == null) notes += "工具：${probeNote(toolsCall)}"
        return tools
    }

    /**
     * 第 4 步：结构化输出（`response_format=json_schema`）。
     * 多数服务端用 400/415/422 拒绝，probeVerdict 对 400 返回 null（未测出），
     * 于是 AUTO 档不会贸然下发一个可能直接 400 的字段 —— 与旧行为一致。
     */
    private fun probeJsonSchema(
        baseUrl: String,
        apiKey: String,
        model: String,
        adapter: ChatProtocolAdapter?,
        notes: MutableList<String>,
    ): Boolean? {
        if (adapter != null) {
            notes += "结构化输出：该协议未探测"
            return null
        }
        val minimalSchema = json.parseToJsonElement(
            """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}""",
        )
        val schemaCall = rawPost(
            baseUrl, apiKey, model,
            listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text",
                text = "请只输出 JSON：{\"ok\":true}")))),
            null,
            adapter,
            ResponseFormat(
                type = "json_schema",
                json_schema = JsonSchemaSpec(name = "probe_ok", strict = false, schema = minimalSchema),
            ),
        )
        probeBlocker(schemaCall)?.let { error(it) }
        val jsonSchema = AiEndpoints.probeVerdict(schemaCall.status)
        if (jsonSchema == null) notes += "结构化输出：${probeNote(schemaCall)}"
        return jsonSchema
    }

    /**
     * 单发 POST（无重试）：探测走这条，不做退避。
     * [adapter] 为 null 时按 OpenAI ChatRequest 构建（[tools]/[responseFormat] 仅此路径有意义）；
     * 非 null 时由协议适配器构建线格式。
     */
    private fun rawPost(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        tools: List<ToolSpec>?,
        adapter: ChatProtocolAdapter?,
        responseFormat: ResponseFormat? = null,
    ): RawResponse {
        val request = if (adapter != null) {
            bodies.protocolRequest(adapter, baseUrl, apiKey, model, messages, temperature = 0.4, maxTokens = 64,
                stream = false)
        } else {
            Request.Builder()
                .url(AiEndpoints.chatCompletionsUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(
                    bodies.buildRequest(
                        ChatRequest(
                            model = model, messages = messages, temperature = 0.4, max_tokens = 64,
                            tools = tools, tool_choice = if (tools != null) "auto" else null,
                            response_format = responseFormat,
                        ),
                    ),
                )
                .build()
        }
        return try {
            probeClient.newCall(request).execute().use { resp ->
                RawResponse(resp.code, resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            throw IllegalStateException("请求未送达：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 探测用极小图片（64×64 纯红）：够模型解码，又不产生可审查内容 */
    private fun probeImageBitmap(): Bitmap =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    /**
     * 整体性失败判定：鉴权与模型名问题说明地址 / Key / 模型名本身不对，
     * 此时把能力记成"不支持"会误导用户，直接让整个探测失败。
     */
    private fun probeBlocker(resp: RawResponse): String? = when {
        resp.status == 401 || resp.status == 403 ->
            "鉴权失败（HTTP ${resp.status}），请检查 API Key"
        AiEndpoints.isModelNameError(resp.status, resp.body) ->
            "模型或地址不可用（HTTP ${resp.status}），请确认模型名与 API 地址\n返回内容：${AiRetryPolicy.snippet(resp.body)}"
        else -> null
    }

    /** 200 但可能是空 choices（网关改写），进一步确认真的有正文 */
    private fun hasContent(resp: RawResponse, extractText: ((String) -> String?)? = null): Boolean {
        val body = resp.body
        if (extractText != null) return !extractText(body).isNullOrBlank()
        val parsed = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull() ?: return false
        return !parsed.choices.firstOrNull()?.message?.content.isNullOrBlank()
    }

    private fun probeNote(resp: RawResponse): String = "HTTP ${resp.status} ${AiRetryPolicy.snippet(resp.body)}"
}
