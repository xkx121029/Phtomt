package com.phoneagent.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.phoneagent.core.log.AppLog
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.rules.EngineRules
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
import okio.BufferedSource
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
        /**
         * 429（限流）最多尝试次数。
         *
         * 与决策看门狗（[com.phoneagent.engine.runtime.EngineLimits.WATCHDOG_DECIDE_MS] = 45s）对齐：
         * 旧实现按 `2000 shl attempt` 退避，4 次退避合计 60s，已经超过看门狗，
         * 结果「重试还没跑完就被看门狗打断 → 本步降级为 wait → 下一轮再重来」，
         * 用户看到的是任务原地卡住且没有任何提示。现在退避封顶 8s、429 最多 3 次，
         * 总预算（约 20s 退避 + 响应）稳定落在看门狗之内。
         */
        private const val MAX_429_RETRIES = 3

        /** 单次退避上限：指数退避不能无限增长，否则会把调用方的超时预算吃光 */
        private const val MAX_BACKOFF_MS = 8_000L

        /** HTTP 408 Request Timeout / 504 Gateway Timeout：都归为「等太久」 */
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_GATEWAY_TIMEOUT = 504
        /** 5xx 区间：服务端自身错误，值得重试 */
        private const val HTTP_SERVER_ERROR_MIN = 500
        private const val HTTP_SERVER_ERROR_MAX = 599
        /** 错误正文截断长度：够看清原因，又不至于把整段 HTML 错误页塞进界面 */
        private const val MAX_ERROR_SNIPPET = 60

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

        /**
         * 校验地址是否会把 API Key 明文发到公网：返回问题描述（null = 可以发）。
         *
         * 清单里 `usesCleartextTraffic=true` 是为了让自建服务可用，但它对公网域名一视同仁——
         * 用户把某个服务商地址误填成 `http://api.xxx.com/v1` 时，`Authorization: Bearer <key>`
         * 会整段明文上路，且不会有任何提示。这里在发请求前拦掉这类配置：
         * 内网/本机仍然允许 http（那正是明文放行的目的），公网一律要求 https。
         */
        internal fun cleartextProblem(baseUrl: String): String? {
            val base = baseUrl.trim()
            val plainHttp = base.startsWith("http://", ignoreCase = true)
            // 不是明文 http，或目标是本机/内网：都属于「明文放行」的本意，不拦
            if (!plainHttp || isLocalHost(base.substringAfter("://"))) return null
            val host = base.substringAfter("://").substringBefore('/').substringBefore(':')
            return "接口地址「$host」是公网地址却用了 http，API Key 会明文传输。" +
                "请把地址改成 https:// 开头；只有本机与内网服务才建议用 http。"
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
        /**
         * 流式中断准备重试时回调：上层可借此重置展示（如清空思考面板），避免重放内容重复累积。
         * 参数为本次重试的人话原因（如「服务端限流」）与等待时长，供 UI 明确告知用户
         * 「不是卡住，是在等 8 秒后重试」。
         */
        onRetry: ((reason: String, delayMs: Long) -> Unit)? = null,
        /** API 协议档位；非 OpenAI 兼容端点由对应协议适配器转译线格式 */
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<AiDecision> = withContext(Dispatchers.IO) {
        val adapter = ChatProtocols.of(protocol)
        // 显式标注泛型：while(true) 循环体里有面向 for 的 break，编译器把 lambda
        // 返回类型推成 AiDecision | Unit（= Any），这里收口回 AiDecision
        runCatching<AiDecision> {
            val startNano = System.nanoTime()
            // 若携带截图，把图片拼到末尾消息（主模型决策一般文本即可，这里保留能力）
            val finalMessages = if (screenshot != null) {
                val imagePart = ContentPart(type = "image_url", image_url = ImageUrl(base64Image(screenshot)))
                val merged = messages.last().content.toMutableList() + imagePart
                messages.toMutableList().also { it[it.lastIndex] = it.last().copy(content = merged) }
            } else messages
            // JSON 纠错重发（最多一次）：流式返回解析不出标准 JSON 时，
            // 附上「上一轮信息无法识别」+ 原文重新请求，要求只输出标准 JSON。
            // 上限一次 —— 纠错后仍解析不了就走既有 give_up 兜底，避免与模型死循环
            var activeMessages = finalMessages
            var jsonRepairUsed = false
            fun actionRequest(msgs: List<ChatMessageDto>): Request =
                if (adapter != null) {
                    protocolStreamRequest(adapter, baseUrl, apiKey, model, msgs, temperature)
                } else {
                    Request.Builder()
                        .url(chatCompletionsUrl(baseUrl))
                        .header("Authorization", "Bearer $apiKey")
                        .post(buildStreamRequestBody(msgs, model, temperature, thinking = false, streamOptions = StreamOptions()))
                        .build()
                }
            var request = actionRequest(activeMessages)

            val full = StringBuilder()
            var lastUsage: Usage? = null
            var sawReasoning = false
            var status = -1
            var lastErr: String? = null
            loop@ while (true) {
                for (attempt in 0 until MAX_RETRIES) {
                    if (attempt > 0) {
                        // 先算退避时长：算不出来说明已到上限，这一轮不再重试，也就不该先回调"重试中"
                        val waitMs = backoffDelayMs(status, attempt) ?: break
                        onRetry?.invoke(retryReason(status, lastErr), waitMs)
                        delay(waitMs)
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
                                    sawReasoning = pumpSse(source, adapter, full, onDelta) { usage ->
                                        lastUsage = combineUsage(lastUsage, usage)
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
                            val fallbackRequest = if (adapter != null) {
                                // 协议适配器路径：非流式请求（finalMessages 已含截图部件）
                                protocolRequest(
                                    adapter, baseUrl, apiKey, model, finalMessages, temperature,
                                    maxTokens = 4096, stream = false,
                                )
                            } else {
                                Request.Builder()
                                    .url(chatCompletionsUrl(baseUrl))
                                    .header("Authorization", "Bearer $apiKey")
                                    .post(buildRequestBody(messages, screenshot, model, temperature))
                                    .build()
                            }
                            val fbContent = executeWithRetry(fallbackRequest, adapter?.let { a -> a::extractText })
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
                        // 先按「可空」口径解析：失败时不急着 give_up，先做一次纠错重发
                        val action = parseAgentIntentOrNull(content)
                        if (action == null && !jsonRepairUsed) {
                            jsonRepairUsed = true
                            AppLog.w("AiClient", "决策输出无法解析为标准 JSON，附上原文纠错重发（仅一次）")
                            // 重发前清空流式回显（与网络重试同一口径），避免界面上内容叠加两遍
                            onRetry?.invoke("返回格式无法解析，正在重新生成", 0L)
                            // 附上「上一轮信息无法识别」：assistant 带原文 + user 要求只输出标准 JSON
                            activeMessages = activeMessages + listOf(
                                ChatMessageDto(role = "assistant", content = listOf(ContentPart(type = "text", text = content))),
                                ChatMessageDto(
                                    role = "user",
                                    content = listOf(
                                        ContentPart(
                                            type = "text",
                                            text = "你上一轮返回的信息无法识别（无法从中解析出标准 JSON 动作）。" +
                                                "请重新回答本轮请求：严格只输出一个标准 JSON 对象，" +
                                                "不要输出任何解释文字、Markdown 代码块围栏或 JSON 之外的内容；" +
                                                "字段与格式必须符合本轮系统提示中给定的动作 JSON 规范。",
                                        ),
                                    ),
                                ),
                            )
                            request = actionRequest(activeMessages)
                            status = -1
                            lastErr = null
                            continue@loop
                        }
                        return@runCatching AiDecision(
                            // 纠错后仍解析失败 → 走既有 give_up 兜底（不无限循环）
                            action = action ?: parseAgentIntent(content),
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
                // for 循环耗尽（网络重试上限/不可恢复错误）：error() 抛出结束整个调用，
                // while(true) 仅由纠错重发的 continue@loop 重新进入，不存在无限循环
                error(lastErr ?: "AI 未返回内容")
            }
            // 编译器把内层 for 的 break 粗粒度当成 while 可正常退出（尾表达式为 Unit），
            // 这里补一个不可达的 Nothing 收口类型；运行时确实到不了这里——
            // for 耗尽时上面 error() 已经抛出
            error("unreachable")
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
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<String> = chatText(baseUrl, apiKey, model, messages, temperature, protocol)

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
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = ChatProtocols.of(protocol)
            val request = if (adapter != null) {
                protocolRequest(adapter, baseUrl, apiKey, model, messages, temperature, maxTokens = 4096, stream = false)
            } else {
                Request.Builder()
                    .url(chatCompletionsUrl(baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .post(buildRawRequestBody(messages, model, temperature))
                    .build()
            }
            executeWithRetry(request, adapter?.let { a -> a::extractText })
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
        protocol: ApiProtocol = ApiProtocol.OPENAI,
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
            val adapter = ChatProtocols.of(protocol)
            val content = if (adapter != null) {
                // 协议适配器路径：Claude/Gemini 无 OpenAI 的 response_format，坐标靠提示词约束 + 兜底解析
                executeWithRetry(
                    protocolRequest(adapter, baseUrl, apiKey, model, messages, 0.1, maxTokens = 2048, stream = false),
                    adapter::extractText,
                )
            } else {
                postCompat(
                    baseUrl, apiKey, model, messages, 0.1,
                    if (wantCoord) ResponseFormat(type = "json_object") else null,
                )
            }
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
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val adapter = ChatProtocols.of(protocol)
                val url = adapter?.modelsUrl(baseUrl) ?: modelsUrl(baseUrl)
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
                    if (!resp.isSuccessful) error(httpErrorText(resp, body))
                    val models = adapter?.parseModels(body) ?: runCatching {
                        json.decodeFromString<ModelListResponse>(body).data
                            .mapNotNull { it.id?.trim()?.takeIf(String::isNotEmpty) }
                    }.getOrElse { error("模型列表返回格式不认识（HTTP ${resp.code}）\n返回内容：${snippet(body)}") }
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
                val textMsgs = listOf(
                    ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请只回复：OK"))),
                )
                val textCall = rawPost(baseUrl, apiKey, model, textMsgs, null, adapter)
                probeBlocker(textCall)?.let { error(it) }
                if (textCall.status !in 200..299) error("文本请求失败（HTTP ${textCall.status}）：${snippet(textCall.body)}")

                // 2) 识图：200 且正文非空才算测出支持
                val shot = probeImageBitmap()
                val visionMsgs = listOf(
                    ChatMessageDto(
                        role = "user",
                        content = listOf(
                            ContentPart(type = "text", text = "这张图是什么颜色？只回答颜色。"),
                            ContentPart(type = "image_url", image_url = ImageUrl(base64Image(shot))),
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
                    visionCall.status !in 200..299 -> probeVerdict(visionCall.status)
                    hasContent(visionCall, extract) -> true
                    else -> null
                }
                if (vision == null) notes += "识图：${probeNote(visionCall)}"

                // 3) 工具调用：仅 OpenAI 兼容协议可探测（Claude/Gemini 原生协议的工具线格式不同，不做探测）
                var tools: Boolean? = null
                if (adapter == null) {
                    val toolsCall = rawPost(
                        baseUrl, apiKey, model,
                        listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请调用 noop_ping 工具。")))),
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
                    tools = probeVerdict(toolsCall.status)
                    if (tools == null) notes += "工具：${probeNote(toolsCall)}"
                } else {
                    notes += "工具：该协议未探测（真实可用性以实际调用为准）"
                }

                ModelAbility(vision = vision, tools = tools, note = notes.joinToString("；"))
            }
        }

    /**
     * 单发 POST（无重试）：探测走这条，不做退避。
     * [adapter] 为 null 时按 OpenAI ChatRequest 构建（[tools] 仅此路径有意义）；
     * 非 null 时由协议适配器构建线格式。
     */
    private fun rawPost(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        tools: List<ToolSpec>?,
        adapter: ChatProtocolAdapter?,
    ): RawResponse {
        val request = if (adapter != null) {
            protocolRequest(adapter, baseUrl, apiKey, model, messages, temperature = 0.4, maxTokens = 64, stream = false)
        } else {
            Request.Builder()
                .url(chatCompletionsUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(
                    buildRequest(
                        ChatRequest(
                            model = model, messages = messages, temperature = 0.4, max_tokens = 64,
                            tools = tools, tool_choice = if (tools != null) "auto" else null,
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
        isModelNameError(resp.status, resp.body) ->
            "模型或地址不可用（HTTP ${resp.status}），请确认模型名与 API 地址\n返回内容：${snippet(resp.body)}"
        else -> null
    }

    /** 200 但可能是空 choices（网关改写），进一步确认真的有正文 */
    private fun hasContent(resp: RawResponse, extractText: ((String) -> String?)? = null): Boolean {
        val body = resp.body
        if (extractText != null) return !extractText(body).isNullOrBlank()
        val parsed = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull() ?: return false
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
     * 消费一段 SSE 流：增量文本交给 [onDelta]，正文累积到 [full]，[onUsage] 非空时解析 usage 帧。
     *
     * `chatForAction`（决策）与 `chatStream`（规划/思考）原先各写一份逐行解析，两处逐行逻辑完全同构，
     * 只在"是否解析 usage"上有差异。抽成一处后，SSE 帧格式（adapter 原生帧 / OpenAI data 帧）的
     * 解析口径只有一份；各调用点的重试、401/403/404 快速失败、空正文回退等策略仍留在调用点。
     *
     * @return 本次流是否出现过思考内容（reasoning_content）
     */
    internal fun pumpSse(
        source: BufferedSource,
        adapter: ChatProtocolAdapter?,
        full: StringBuilder,
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)? = null,
    ): Boolean {
        var sawReasoning = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank() || !line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (adapter != null) {
                // 协议适配器路径：Claude/Gemini 的 SSE 帧；终止帧由适配器自己判定
                if (pumpAdapterFrame(adapter, payload, full, onDelta, onUsage)) break
            } else if (pumpOpenAiFrame(payload, full, onDelta, onUsage)) {
                sawReasoning = true
            }
        }
        return sawReasoning
    }

    /** 协议适配器的 SSE 帧；返回 true 表示命中终止帧，调用方应停止消费 */
    private fun pumpAdapterFrame(
        adapter: ChatProtocolAdapter,
        payload: String,
        full: StringBuilder,
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)?,
    ): Boolean {
        if (adapter.isTerminal(payload)) return true
        val content = adapter.extractDelta(payload)
        if (content.isNotEmpty()) {
            onDelta(content)
            full.append(content)
        }
        if (onUsage != null) adapter.extractUsageFrame(payload)?.let(onUsage)
        return false
    }

    /** OpenAI data 帧；返回 true 表示本帧出现过 reasoning 内容 */
    private fun pumpOpenAiFrame(
        payload: String,
        full: StringBuilder,
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)?,
    ): Boolean {
        val chunk = runCatching { json.decodeFromString<StreamChunk>(payload) }.getOrNull()
        val delta = chunk?.choices?.firstOrNull()?.delta
        val content = delta?.content.orEmpty()
        val reasoning = delta?.reasoning_content.orEmpty()
        // 思考内容优先展示（边思考边输出）
        val display = if (content.isNotEmpty()) content else reasoning
        if (display.isNotEmpty()) onDelta(display)
        // 完整正文只累积 content（用于最终解析 JSON）
        if (content.isNotEmpty()) full.append(content)
        if (onUsage != null) {
            // 末块可能携带 usage（stream_options.include_usage）
            runCatching { json.decodeFromString<ChatResponse>(payload).usage }
                .getOrNull()?.let(onUsage)
        }
        return reasoning.isNotEmpty()
    }

    /**
     * 执行非流式请求并返回模型正文，带 429 退避重试。
     * - [extractText] 为 null 时按 OpenAI ChatResponse 解析；非空时交给协议适配器解析
     * - 429（限流）：指数退避 4s/8s/16s/32s，最多 5 次尝试
     * - 401/403/404（鉴权/地址错误）：不重试，直接失败（对齐能力探测链路 probeBlocker 的"整体失败"口径）
     * - 其他失败：快速重试 1 次（800ms）
     */
    private suspend fun executeWithRetry(request: Request, extractText: ((String) -> String?)? = null): String {
        var status = -1
        var lastErr: String? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                val waitMs = backoffDelayMs(status, attempt) ?: break
                delay(waitMs)
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
                        val content = if (extractText != null) {
                            // 协议适配器解析（Claude / Gemini）；解析不出按失败处理
                            extractText(body)
                        } else {
                            val parsed = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull()
                            if (parsed == null) {
                                // 200 但正文不是预期 JSON（网关改写 / HTML 提示页）：保留原始返回，避免只剩解析异常
                                lastErr = httpErrorText(resp, body)
                                failed = true
                                null
                            } else if (parsed.error != null) {
                                lastErr = httpErrorText(resp, body)
                                failed = true
                                null
                            } else {
                                parsed.choices.firstOrNull()?.message?.content?.trim()
                            }
                        }
                        if (failed) return@use
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

    // ==================== 协议适配器请求构建 ====================

    /** 按协议适配器构建非流式请求（Claude / Gemini 原生端点） */
    private fun protocolRequest(
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
    private fun protocolStreamRequest(
        adapter: ChatProtocolAdapter,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
    ): Request = protocolRequest(adapter, baseUrl, apiKey, model, messages, temperature, maxTokens = 4096, stream = true)

    /** 合并分帧到达的用量统计（如 Anthropic 的 message_start / message_delta 各带一半） */
    private fun combineUsage(a: Usage?, b: Usage): Usage {
        if (a == null) return b
        val prompt = maxOf(a.promptTokens, b.promptTokens)
        val completion = maxOf(a.completionTokens, b.completionTokens)
        return Usage(prompt, completion, prompt + completion)
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
     * 计算下一次重试的退避时长；返回 null 表示不再重试。
     *
     * 429 限流按 2s/4s/8s 递增并封顶 [MAX_BACKOFF_MS]，最多 [MAX_429_RETRIES] 次；
     * 其他错误最多 [MAX_NON_429_RETRIES] 次。用 suspend delay 而非 Thread.sleep，保证协程取消能即时生效。
     */
    private fun backoffDelayMs(status: Int, attempt: Int): Long? = when {
        status == 429 ->
            if (attempt >= MAX_429_RETRIES) null
            else (BASE_429_DELAY_MS shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(MAX_BACKOFF_MS)

        attempt >= MAX_NON_429_RETRIES -> null
        else -> INITIAL_DELAY_MS
    }

    /** 重试原因的人话描述：用户看到的应该是「被限流」而不是「正在思考」 */
    private fun retryReason(status: Int, err: String?): String = when {
        status == 429 -> "服务端限流"
        status == HTTP_REQUEST_TIMEOUT || status == HTTP_GATEWAY_TIMEOUT -> "请求超时"
        status in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> "服务端错误（HTTP $status）"
        status > 0 -> "请求失败（HTTP $status）"
        else -> err?.take(MAX_ERROR_SNIPPET)?.takeIf { it.isNotBlank() } ?: "网络连接失败"
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
            if (x != null && y != null) return clamp01(x) to clamp01(y)
        }
        // 兜底：提取首个 { 到最后一个 } 之间的内容再按逗号拆分
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        val core = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
        val nums = core.replace("{", "").replace("}", "")
            .split(",").map { it.trim().toFloatOrNull() }
        if (nums.size >= 2 && nums[0] != null && nums[1] != null) return clamp01(nums[0]!!) to clamp01(nums[1]!!)
        error("无法解析坐标：$content")
    }

    /** 比例坐标收敛到 0~1：模型偶尔吐出 >1 / 负值，不收敛会让点击落出屏幕（动作静默失效） */
    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

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
        /**
         * 流式中断准备重试时回调：上层可借此重置展示，避免重放内容重复累积。
         * 参数为本次重试的人话原因与等待时长（0 表示立即重发）。
         */
        onRetry: ((reason: String, delayMs: Long) -> Unit)? = null,
        thinking: Boolean = false,
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = ChatProtocols.of(protocol)
            val request = if (adapter != null) {
                protocolStreamRequest(adapter, baseUrl, apiKey, model, messages, temperature)
            } else {
                Request.Builder()
                    .url(chatCompletionsUrl(baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .post(buildStreamRequestBody(messages, model, temperature, thinking))
                    .build()
            }

            val full = StringBuilder()
            var status = -1
            var lastErr: String? = null
            for (attempt in 0 until MAX_RETRIES) {
                if (attempt > 0) {
                    // 与 chatForAction 同一口径：先算退避，算不出来说明已到上限，不再回调"重试中"
                    val waitMs = backoffDelayMs(status, attempt) ?: break
                    onRetry?.invoke(retryReason(status, lastErr), waitMs)
                    delay(waitMs)
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
                                // 规划/思考链路不消费 usage，传 null 即跳过 usage 帧解析
                                pumpSse(source, adapter, full, onDelta, onUsage = null)
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

    /**
     * 从模型输出中解析 AgentIntent，带多级兜底解析 + 日志。
     * 兜底全部失败时构造 give_up（换为「放弃」，避免误报完成）；
     * 需要在失败时做「纠错重发」的调用方改用 [parseAgentIntentOrNull]。
     */
    private fun parseAgentIntent(content: String): AgentIntent =
        parseAgentIntentOrNull(content) ?: AgentIntent(
            intent = "give_up",
            reason = "AI 输出无法解析为意图",
            reasoning = "解析失败",
        )

    /** 同 [parseAgentIntent]，但兜底全部失败时返回 null 而非 give_up（供纠错重发判定） */
    internal fun parseAgentIntentOrNull(content: String): AgentIntent? {
        // Step 1：提取 JSON（支持 ```json 代码块包裹）后直接解析
        try {
            return json.decodeFromString(AgentIntent.serializer(), EngineRules.extractJsonObject(content))
        } catch (e: Exception) {
            AppLog.w("AiClient", "Step1 parse failed: ${e.message}, content: ${content.take(200)}")
        }
        // Step 2：平衡花括号提取第一个完整 JSON 对象（支持嵌套）
        val balanced = extractBalancedJson(content)
        if (balanced != null) {
            try {
                return json.decodeFromString(AgentIntent.serializer(), balanced)
            } catch (e: Exception) {
                AppLog.w("AiClient", "Step2 balanced parse failed: ${e.message}, balanced: ${balanced.take(200)}")
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
                AppLog.w("AiClient", "Step3 array parse failed: ${e.message}")
            }
        }
        // Step 4：全部失败 → 返回 null，由调用方决定纠错重发或 give_up 兜底
        AppLog.e("AiClient", "All parse steps failed. Raw content: ${content.take(300)}")
        return null
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

    // JSON 提取逻辑已统一收敛到 domain.rules.EngineRules.extractJsonObject / extractJsonBackward，
    // 原先这里的两份私有副本与之一字不差，重复维护只会在两侧行为漂移。

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