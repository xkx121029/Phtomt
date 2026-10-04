package com.phoneagent.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlin.coroutines.coroutineContext

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
     * 枚举 / 探测专用 client：继承主 client 的连接池，只把 callTimeout 压到 20s。
     * 主 client 的 readTimeout 是 120s，一次能力探测等不起。
     */
    private val probeClient by lazy { client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }

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

        /** 内网/本机常见后缀：mDNS、企业内网与家用路由器命名，未写协议时按 http 处理。 */
        private val LOCAL_HOST_SUFFIXES = listOf(
            ".local", ".localhost", ".lan", ".home", ".internal", ".intranet", ".corp", ".localdomain",
        )

        /**
         * 规范化 API 基地址：补协议、去末尾斜杠。
         *
         * 自建推理服务（Ollama、LM Studio、one-api、vLLM 等）常被填成 `192.168.1.5:8000/v1`
         * 或 `localhost:11434/v1`；而 OkHttp 的 `Request.url()` 要求带完整协议头，缺协议会直接抛异常。
         * 因此未写协议时按主机推断：本机/内网补 `http://`，公网域名补 `https://`；
         * 已写 `http://` 的地址原样保留 —— 明文流量是否放行由清单的 usesCleartextTraffic 决定。
         */
        internal fun normalizeBaseUrl(raw: String): String {
            val base = raw.trim().trimEnd('/')
            if (base.isEmpty()) return base
            if (base.startsWith("http://", true) || base.startsWith("https://", true)) return base
            return if (isLocalHost(base)) "http://$base" else "https://$base"
        }

        /** 从 URL 取出主机名，判断是否属于「本机/内网」：localhost、私有网段、单段主机名、内网后缀。 */
        private fun isLocalHost(url: String): Boolean {
            val authority = url.substringBefore('/').substringAfter('@')
            val host = (
                if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
                else authority.substringBefore(':')
                ).lowercase()
            if (host.isEmpty()) return false
            // IPv6 字面量：仅回环 / 链路本地 / ULA 视为内网
            if (host.contains(':')) {
                return host == "::1" || host.startsWith("fe80:") ||
                    host.startsWith("fc") || host.startsWith("fd")
            }
            if (!host.contains('.')) return true    // 单段主机名，如 nas、myserver
            privateIpv4(host)?.let { return it }
            return LOCAL_HOST_SUFFIXES.any { host.endsWith(it) }
        }

        /** 私有 IPv4 判定；非 IPv4 字面量返回 null，交由域名分支处理。 */
        private fun privateIpv4(host: String): Boolean? {
            val nums = host.split('.').map { it.toIntOrNull() ?: return null }
            if (nums.size != 4 || nums.any { it !in 0..255 }) return null
            val (a, b) = nums[0] to nums[1]
            return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
        }

        /**
         * 模型列表端点：与 [chatCompletionsUrl] 同样兼容各服务商填法差异 ——
         * 用户误把完整端点（`…/v1/chat/completions`）填进「API 地址」时先摘掉，再拼 `/models`。
         */
        internal fun modelsUrl(baseUrl: String): String {
            val base = normalizeBaseUrl(baseUrl).removeSuffix("/chat/completions")
            return if (base.endsWith("/models")) base else "$base/models"
        }

        /**
         * 单项探测判定（只看状态码，不做 image/tool 关键词启发式 —— 5xx 的错误正文会被误判成"不支持"）：
         * - 2xx → `true`：该能力可用
         * - 415 / 422 → `false`：服务端明确拒绝了这种请求体（媒体类型 / 字段不被支持）
         * - 401 / 403 → `null`：鉴权问题（调用方已在整体层面拦截，不会走到这里）
         * - 400 / 5xx / 其他 → `null`：未测出（字段不认识、网关改写、服务端异常都落这里）
         */
        internal fun probeVerdict(status: Int): Boolean? = when {
            status in 200..299 -> true
            status == 415 || status == 422 -> false
            else -> null
        }

        /** 模型名相关线索：命中即认为"模型名/地址不对"，整体失败（而不是记成"该能力不支持"）。 */
        private val MODEL_NAME_ERROR_HINTS = listOf(
            "model not found", "model_not_found", "unknown model", "invalid model",
            "model does not exist", "no such model", "unsupported model",
            "模型不存在", "无效的模型", "模型名", "不存在的模型",
        )

        /** 是否是"模型名/地址错"：404 一律算；400 需正文出现模型名线索（避免把"不支持图片"误判）。 */
        internal fun isModelNameError(status: Int, body: String): Boolean {
            if (status == 404) return true
            if (status != 400) return false
            val lower = body.lowercase()
            return MODEL_NAME_ERROR_HINTS.any { lower.contains(it) }
        }

        /**
         * AgentIntent 的标准 JSON Schema，用于约束模型输出（对齐 HPA动作执行逻辑优化文档 v2.1 三、意图 DSL）。
         *
         * intent 的 enum 由 [IntentType.ALL] 现场生成，**不手抄**：这份表曾因手抄而落后于意图全集
         * （缺 remember、device_query、say、show_agent、fetch、6 个 browse_*、shell、a11y），
         * 结果是"结构化回退"这条路上新意图被 schema 直接判非法。唯一定义点在 [IntentType.ALL]。
         */
        private val ACTION_SCHEMA = """
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
    }

    /**
     * 决策调用：流式生成，边生成边通过 [onDelta] 回调增量内容（用于悬浮窗/通知实时展示 AI 思考）。
     *
     * 流式不强制 response_format（避免 stream+json 兼容问题），完整正文累积后用 [parseAgentIntent] 解析；
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
        /** 流式中断准备重试时回调：上层可借此重置展示（如清空思考面板），避免重放内容重复累积 */
        onRetry: (() -> Unit)? = null,
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
                .url(chatCompletionsUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(streamBody)
                .build()

            val full = StringBuilder()
            var lastUsage: Usage? = null
            var sawReasoning = false
            var status = -1
            var lastErr: String? = null
            for (attempt in 0 until MAX_RETRIES) {
                if (attempt > 0) {
                    onRetry?.invoke()
                    if (!backoffSleep(status, attempt)) break
                }
                try {
                    full.setLength(0)
                    lastUsage = null
                    sawReasoning = false
                    var streamFailed = false
                    val call = client.newCall(request)
                    // 协程取消（任务停止/页面关闭）时同步取消 OkHttp 请求；请求结束后反注册回调
                    val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
                    try {
                        call.execute().use { resp ->
                            status = resp.code
                            if (!resp.isSuccessful) {
                                val body = resp.body?.string().orEmpty()
                                lastErr = httpErrorText(resp, body)
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
                    } finally {
                        handle?.dispose()
                    }
                    if (streamFailed) {
                        // 401/403/404 属鉴权/地址错误，重试不会变好，直接失败
                        // （对齐能力探测链路 probeBlocker 的"整体失败"口径）
                        if (status == 401 || status == 403 || status == 404) break
                        continue
                    }
                    val content = full.toString()
                    if (content.isBlank()) {
                        // 流式空正文（兼容问题）→ 回退非流式 + 结构化输出
                        lastErr = null
                        val fallbackBody = buildRequestBody(messages, screenshot, model, temperature)
                        val fallbackRequest = Request.Builder()
                            .url(chatCompletionsUrl(baseUrl))
                            .header("Authorization", "Bearer $apiKey")
                            .post(fallbackBody)
                            .build()
                        val fbContent = executeWithRetry(fallbackRequest)
                        return@runCatching AiDecision(
                            action = parseAgentIntent(fbContent),
                            promptTokens = 0,
                            completionTokens = 0,
                            totalTokens = 0,
                            elapsedMs = (System.nanoTime() - startNano) / 1_000_000,
                            rawContent = fbContent,
                            thinking = false,
                        )
                    }
                    return@runCatching AiDecision(
                        action = parseAgentIntent(content),
                        promptTokens = lastUsage?.promptTokens ?: 0,
                        completionTokens = lastUsage?.completionTokens ?: 0,
                        totalTokens = lastUsage?.totalTokens ?: 0,
                        elapsedMs = (System.nanoTime() - startNano) / 1_000_000,
                        rawContent = content,
                        thinking = sawReasoning,
                    )
                } catch (e: Exception) {
                    // 协程取消必须穿透重试循环向上传播，不能被当成普通失败吞掉
                    if (e is CancellationException) throw e
                    status = -1
                    lastErr = e.message
                }
            }
            error(lastErr ?: "AI 未返回内容")
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * 通用文本对话：返回模型原始输出（用于规划/澄清/验证等非动作场景）。
     * 历史版本曾强制 response_format=json_object，与"返回原始输出"的契约矛盾；
     * 现与 [chatText] 合并：不强制 JSON 模式，需要 JSON 的调用方自行从正文中提取解析。
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
    ): Result<String> = chatText(baseUrl, apiKey, model, messages, temperature)

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
                .url(chatCompletionsUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            executeWithRetry(request)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** 无结构化约束、无 JSON 模式的文本请求体 */
    private fun buildRawRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
    ): okhttp3.RequestBody = buildRequest(
        ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = 4096),
    )

    // ==================== 视觉模型（glm-4.6v-flash / 端侧 3B 之外的云端读图） ====================

    /** 视觉模型的一次回答：[text] 为原始回答正文，[x]/[y] 为（可选）某个目标的归一化中心坐标 */
    data class VisionAnswer(val text: String, val x: Float? = null, val y: Float? = null)

    /**
     * 统一的视觉提问入口 —— **上下文干净**：只有一条 user 消息（一句目的 + 这张图），不带任何对话历史。
     *
     * 旧实现是两个专用函数（visionDescribe 描述整屏 / visionLocate 定位单个目标），提示词都是引擎写死的
     * 通用话术，与"这一步到底想看图里的什么"无关：于是每次都整屏铺开、慢且贵，还答不到点上
     * （如「转盘指针指向哪个扇区」这类问题根本没人问）。改为目的由调用方现场给出一句话。
     *
     * @param purpose 这次为什么要看图，一句话（如「给出图中轮盘指针指向的扇区」）
     * @param target  要定位的具体目标；非空时要求返回该目标的归一化中心坐标
     */
    suspend fun visionAsk(
        baseUrl: String,
        apiKey: String,
        model: String,
        screenshot: Bitmap,
        purpose: String,
        target: String? = null,
        jsonMode: Boolean = false,
    ): Result<VisionAnswer> = withContext(Dispatchers.IO) {
        runCatching {
            val wantCoord = !target.isNullOrBlank() || jsonMode
            val prompt = if (wantCoord) {
                "这次看图的目的：$purpose\n" +
                    "请在截图中找到目标「${target ?: purpose}」，返回其中心点的比例坐标，" +
                    "格式严格为 JSON：{\"x\":0.0~1.0,\"y\":0.0~1.0}。只输出 JSON，不要任何其他文字。"
            } else {
                "这次看图的目的：$purpose\n" +
                    "请只围绕这个目的回答，用中文；列出与目的相关的元素及其显示的文字与大致位置" +
                    "（用 0~1 的比例坐标，x 为横向、y 为纵向）。与目的无关的内容不必赘述。"
            }
            // 上下文干净：单条 user 消息，不携带任何历史轮次
            val messages = listOf(
                ChatMessageDto(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = prompt),
                        ContentPart(type = "image_url", image_url = ImageUrl(base64Image(screenshot))),
                    ),
                ),
            )
            val content = postCompat(
                baseUrl, apiKey, model, messages, 0.1,
                if (wantCoord) ResponseFormat(type = "json_object") else null,
            )
            // 要坐标时解析失败不算整体失败：正文照旧带回去，让调用方按"没拿到坐标"降级处理
            val coord = if (wantCoord) runCatching { parseCoordinate(content) }.getOrNull() else null
            VisionAnswer(text = content, x = coord?.first, y = coord?.second)
        }.onFailure { if (it is CancellationException) throw it }
    }

    // ==================== 模型枚举与能力探测 ====================

    /** 单发请求的原始结果：状态码 + 正文 */
    private data class RawResponse(val status: Int, val body: String)

    /**
     * 拉取该端点可用的模型列表（`GET /models`）。
     *
     * 只写库、不做能力探测：网关常返回上百条模型，逐条探测等于几百次请求。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(modelsUrl(baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                probeClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 404) error("该服务未提供模型列表接口（HTTP 404），请手动填写模型名")
                    if (!resp.isSuccessful) error(httpErrorText(resp, body))
                    val parsed = runCatching { json.decodeFromString<ModelListResponse>(body) }.getOrNull()
                        ?: error("模型列表返回格式不认识（HTTP ${resp.code}）\n返回内容：${snippet(body)}")
                    parsed.data.mapNotNull { it.id?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()
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
    suspend fun probeAbility(baseUrl: String, apiKey: String, model: String): Result<ModelAbility> =
        withContext(Dispatchers.IO) {
            runCatching {
                val notes = mutableListOf<String>()

                // 1) 文本：这一步失败说明地址 / Key / 模型名有问题，整体失败
                val textCall = rawPost(
                    baseUrl, apiKey,
                    ChatRequest(
                        model = model,
                        messages = listOf(
                            ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请只回复：OK"))),
                        ),
                        max_tokens = 8,
                    ),
                )
                probeBlocker(textCall)?.let { error(it) }
                if (textCall.status !in 200..299) error("文本请求失败（HTTP ${textCall.status}）：${snippet(textCall.body)}")

                // 2) 识图：200 且正文非空才算测出支持
                val shot = probeImageBitmap()
                val visionCall = try {
                    rawPost(
                        baseUrl, apiKey,
                        ChatRequest(
                            model = model,
                            messages = listOf(
                                ChatMessageDto(
                                    role = "user",
                                    content = listOf(
                                        ContentPart(type = "text", text = "这张图是什么颜色？只回答颜色。"),
                                        ContentPart(type = "image_url", image_url = ImageUrl(base64Image(shot))),
                                    ),
                                ),
                            ),
                            max_tokens = 16,
                        ),
                    )
                } finally {
                    shot.recycle()
                }
                probeBlocker(visionCall)?.let { error(it) }
                val vision: Boolean? = when {
                    visionCall.status !in 200..299 -> probeVerdict(visionCall.status)
                    hasContent(visionCall) -> true
                    else -> null
                }
                if (vision == null) notes += "识图：${probeNote(visionCall)}"

                // 3) 工具调用：成功响应通常是 content=null + finish_reason=tool_calls，故只看状态码
                val toolsCall = rawPost(
                    baseUrl, apiKey,
                    ChatRequest(
                        model = model,
                        messages = listOf(
                            ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请调用 noop_ping 工具。"))),
                        ),
                        max_tokens = 64,
                        tools = listOf(
                            ToolSpec(
                                function = FunctionSpec(
                                    name = "noop_ping",
                                    description = "无副作用的连通性探测工具",
                                    parameters = json.parseToJsonElement("""{"type":"object","properties":{}}"""),
                                ),
                            ),
                        ),
                        tool_choice = "auto",
                    ),
                )
                probeBlocker(toolsCall)?.let { error(it) }
                val tools = probeVerdict(toolsCall.status)
                if (tools == null) notes += "工具：${probeNote(toolsCall)}"

                ModelAbility(vision = vision, tools = tools, note = notes.joinToString("；"))
            }
        }

    /** 单发 POST（无重试）：探测走这条，不做退避 */
    private fun rawPost(baseUrl: String, apiKey: String, body: ChatRequest): RawResponse {
        val request = Request.Builder()
            .url(chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .post(buildRequest(body))
            .build()
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
        isModelNameError(resp.status, resp.body) ->
            "模型或地址不可用（HTTP ${resp.status}），请确认模型名与 API 地址\n返回内容：${snippet(resp.body)}"
        else -> null
    }

    /** 200 但可能是空 choices（网关改写），进一步确认真的有正文 */
    private fun hasContent(resp: RawResponse): Boolean {
        val parsed = runCatching { json.decodeFromString<ChatResponse>(resp.body) }.getOrNull() ?: return false
        return !parsed.choices.firstOrNull()?.message?.content.isNullOrBlank()
    }

    private fun probeNote(resp: RawResponse): String = "HTTP ${resp.status} ${snippet(resp.body)}"

    private fun snippet(body: String): String =
        body.trim().take(200).ifEmpty { "（响应体为空）" }

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
            .url(chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        return executeWithRetry(request)
    }

    /**
     * 执行非流式请求并返回模型正文，带 429 退避重试。
     * - 429（限流）：指数退避 4s/8s/16s/32s，最多 5 次尝试
     * - 401/403/404（鉴权/地址错误）：不重试，直接失败（对齐能力探测链路 probeBlocker 的"整体失败"口径）
     * - 其他失败：快速重试 1 次（800ms）
     */
    private suspend fun executeWithRetry(request: Request): String {
        var status = -1
        var lastErr: String? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                if (!backoffSleep(status, attempt)) break
            }
            try {
                var failed = false
                val call = client.newCall(request)
                // 协程取消时同步取消 OkHttp 请求；请求结束后反注册回调
                val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { resp ->
                        status = resp.code
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastErr = httpErrorText(resp, body)
                            failed = true
                            return@use
                        }
                        val parsed = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull()
                        if (parsed == null) {
                            // 200 但正文不是预期 JSON（网关改写 / HTML 提示页）：保留原始返回，避免只剩解析异常
                            lastErr = httpErrorText(resp, body)
                            failed = true
                            return@use
                        }
                        if (parsed.error != null) {
                            lastErr = httpErrorText(resp, body)
                            failed = true
                            return@use
                        }
                        val content = parsed.choices.firstOrNull()?.message?.content?.trim()
                        if (content != null) return content
                        lastErr = "AI 返回空内容\n返回内容：${body.trim().ifEmpty { "（响应体为空）" }}"
                        failed = true
                    }
                } finally {
                    handle?.dispose()
                }
                if (failed) {
                    // 401/403/404 属鉴权/地址错误，重试不会变好，直接失败
                    if (status == 401 || status == 403 || status == 404) break
                    continue
                }
                error("AI 未返回内容")
            } catch (e: Exception) {
                // 协程取消必须向上传播，不能被当成普通失败吞掉
                if (e is CancellationException) throw e
                status = -1
                lastErr = e.message
            }
        }
        error(lastErr ?: "AI 未返回内容")
    }

    /**
     * 规范化对话补全端点，兼容各服务商填法差异，避免 404：
     * - 末尾多余斜杠（`https://api.deepseek.com/v1/`）
     * - 误把完整端点填进「API 地址」（`https://api.deepseek.com/v1/chat/completions`）
     * - 不带版本段（`https://api.deepseek.com`，DeepSeek 原生支持）
     * - 未写协议（`192.168.1.5:8000/v1`、`localhost:11434/v1`，见 [normalizeBaseUrl]）
     */
    private fun chatCompletionsUrl(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /**
     * 组装完整错误信息：状态码 + 请求地址 + 服务端原始返回正文。
     * 不再只截取 error.message —— 各服务商字段不一（有的把原因放在 error.type / code / 顶层 message），
     * 只取 message 会丢掉诊断依据，甚至完全取不到（非 JSON 响应如网关 HTML 错误页）。
     */
    private fun httpErrorText(resp: okhttp3.Response, body: String): String {
        val detail = body.trim().ifEmpty { "（响应体为空）" }
        return "HTTP ${resp.code}（${resp.request.url}）\n返回内容：$detail"
    }

    /**
     * 请求体构建辅助函数：接收 ChatRequest 构建器，序列化为 JSON 正文。
     * 消除 5 个 buildXxxRequestBody 方法中的重复逻辑。
     */
    private fun buildRequest(request: ChatRequest): okhttp3.RequestBody =
        json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonMediaType)

    /**
     * 退避等待：429 指数退避（4s/8s/16s/32s，随 attempt 递增），其他最多重试 1 次。
     * 返回 false 表示不再重试。用 suspend delay 而非 Thread.sleep，保证协程取消能即时生效。
     */
    private suspend fun backoffSleep(status: Int, attempt: Int): Boolean {
        return when {
            status == 429 -> { delay(BASE_429_DELAY_MS shl attempt); true }
            attempt >= MAX_NON_429_RETRIES -> false
            else -> { delay(INITIAL_DELAY_MS); true }
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
        /** 流式中断准备重试时回调：上层可借此重置展示，避免重放内容重复累积 */
        onRetry: (() -> Unit)? = null,
        thinking: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = buildStreamRequestBody(messages, model, temperature, thinking)
            val request = Request.Builder()
                .url(chatCompletionsUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val full = StringBuilder()
            var status = -1
            var lastErr: String? = null
            for (attempt in 0 until MAX_RETRIES) {
                if (attempt > 0) {
                    onRetry?.invoke()
                    if (!backoffSleep(status, attempt)) break
                }
                try {
                    // 每次尝试独立累积，避免流中断重试后新旧内容拼接导致重复
                    full.setLength(0)
                    var streamFailed = false
                    val call = client.newCall(request)
                    // 协程取消（任务停止/页面关闭）时同步取消 OkHttp 请求；请求结束后反注册回调
                    val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
                    try {
                        call.execute().use { resp ->
                            status = resp.code
                            if (!resp.isSuccessful) {
                                val body = resp.body?.string().orEmpty()
                                lastErr = httpErrorText(resp, body)
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
                    } finally {
                        handle?.dispose()
                    }
                    if (streamFailed) {
                        // 401/403/404 属鉴权/地址错误，重试不会变好，直接失败
                        // （对齐能力探测链路 probeBlocker 的"整体失败"口径）
                        if (status == 401 || status == 403 || status == 404) break
                        continue
                    }
                    return@runCatching full.toString()
                } catch (e: Exception) {
                    // 协程取消必须穿透重试循环向上传播，不能被当成普通失败吞掉
                    if (e is CancellationException) throw e
                    status = -1
                    lastErr = e.message
                }
            }
            error(lastErr ?: "AI 未返回内容")
        }.onFailure { if (it is CancellationException) throw it }
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

    /** 从模型输出中解析 AgentIntent，带多级兜底解析 + 日志 */
    private fun parseAgentIntent(content: String): AgentIntent {
        // Step 1：提取 JSON（支持 ```json 代码块包裹）后直接解析
        try {
            return json.decodeFromString(AgentIntent.serializer(), extractJson(content))
        } catch (e: Exception) {
            Log.w("AiClient", "Step1 parse failed: ${e.message}, content: ${content.take(200)}")
        }
        // Step 2：平衡花括号提取第一个完整 JSON 对象（支持嵌套）
        val balanced = extractBalancedJson(content)
        if (balanced != null) {
            try {
                return json.decodeFromString(AgentIntent.serializer(), balanced)
            } catch (e: Exception) {
                Log.w("AiClient", "Step2 balanced parse failed: ${e.message}, balanced: ${balanced.take(200)}")
            }
        }
        // Step 3：尝试 JSON 数组（意图合并）
        val arrayStart = content.indexOf('[')
        val arrayEnd = content.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            try {
                val arr = json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(AgentIntent.serializer()),
                    content.substring(arrayStart, arrayEnd + 1),
                )
                if (arr.isNotEmpty()) return arr.first()
            } catch (e: Exception) {
                Log.w("AiClient", "Step3 array parse failed: ${e.message}")
            }
        }
        // Step 4：全部失败 → 构造合法 give_up（换为「放弃」，避免误报完成）
        Log.e("AiClient", "All parse steps failed. Raw content: ${content.take(300)}")
        return AgentIntent(
            intent = "give_up",
            reason = "AI 输出无法解析为意图",
            reasoning = "解析失败",
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

    /**
     * 同一张截图的 base64 单槽缓存。
     *
     * 同一步里同一张图会被用到多次（每步自动描述、hint 定位、发主模型），而 scale+JPEG+Base64
     * 是纯 CPU 开销：不做缓存就是同一张图编码三遍。这里只留"最近一张"，因为决策链路天然是
     * 一张图走完（用完即换下一个截图对象），多槽反而要处理淘汰。
     */
    private var cachedImageSrc: Bitmap? = null
    private var cachedImageB64: String? = null

    /** 每步开头清一次：截图对象用完会被回收，避免跨步持有一份指向已回收位图的缓存 */
    fun clearImageCache() {
        cachedImageSrc = null
        cachedImageB64 = null
    }

    private fun base64Image(bitmap: Bitmap): String {
        // 引用相等而非 equals：只认"就是这张对象"，避免同尺寸不同内容的两张图互相串；
        // isRecycled 守卫：位图被回收后必须重新编码，不能把旧字符串再交出去
        if (cachedImageSrc === bitmap && !bitmap.isRecycled) {
            cachedImageB64?.let { return it }
        }
        val scaled = scaleBitmap(bitmap, 1024)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        val result = "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        // 若产生了缩放副本，用完即回收，避免原生内存泄漏
        if (scaled !== bitmap) scaled.recycle()
        cachedImageSrc = bitmap
        cachedImageB64 = result
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