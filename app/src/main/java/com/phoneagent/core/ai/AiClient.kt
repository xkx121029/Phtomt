// 本文件只做调用编排：重试循环、流式回调、早停与纠错重发；数字类常量随实现搬去了各自文件
@file:Suppress("MagicNumber")
package com.phoneagent.core.ai

import android.graphics.Bitmap
import com.phoneagent.core.log.AppLog
import com.phoneagent.domain.model.AgentIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSource
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * OpenAI 兼容的 AI 客户端，用于调用 GLM 等模型。
 * 支持文本 + 截图（多模态）输入，并通过结构化输出约束 AgentAction 的 JSON 格式。
 *
 * 结构：本文件只留**调用编排**——重试循环、流式回调、早停与纠错重发；
 * 其余职责按块落在同包的这几个文件里，对外签名一律不变（调用方无需改动）：
 * - `AiEndpoints.kt` 端点适配：地址规整、明文泄露拦截、模型名错误判定；
 * - `AiRequestBodies.kt` 请求构造：各家端点的请求体与采样参数；
 * - `AiStreamPump.kt` 流式解析：SSE 帧 → 增量文本 / 累积正文 / 用量；
 * - `AiRetry.kt` 重试与错误映射：退避策略 + 非流式执行器；
 * - `AiProbe.kt` 模型枚举与能力探测；
 * - `AiIntentParsing.kt` 模型输出 → AgentIntent 的多级兜底解析；
 * - `AiImageEncoder.kt` 截图 base64 编码与单槽缓存。
 */
class AiClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /**
     * 枚举 / 探测专用 client：继承主 client 的连接池，只把 callTimeout 压到 20s。
     * 主 client 的 readTimeout 是 120s，一次能力探测等不起。
     */
    private val probeClient by lazy { client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }

    private val images = AiImageEncoder()
    private val bodies = AiRequestBodies(json, images)
    private val pump = AiStreamPump(json)
    private val transport = AiTransport(client, json)
    private val intents = AiIntentParser(json)
    private val probe by lazy { AiProbe(probeClient, json, bodies, images) }

    companion object {

        /** 用户打开「允许 http 端点」后的风险提示：设置页开关与任务日志共用同一句话 */
        const val CLEARTEXT_ALLOWED_NOTICE =
            "已允许 http 明文端点：API Key 与聊天内容会明文经过网络，仅建议用于本机或可信内网。"

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

        /** 端点规整与判定：实现见 [AiEndpoints]，这里只保留对外签名 */
        internal fun normalizeBaseUrl(raw: String): String = AiEndpoints.normalizeBaseUrl(raw)

        internal fun cleartextProblem(baseUrl: String): String? = AiEndpoints.cleartextProblem(baseUrl)

        internal fun modelsUrl(baseUrl: String): String = AiEndpoints.modelsUrl(baseUrl)

        internal fun probeVerdict(status: Int): Boolean? = AiEndpoints.probeVerdict(status)

        internal fun isModelNameError(status: Int, body: String): Boolean =
            AiEndpoints.isModelNameError(status, body)
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
        /**
         * 是否在本轮请求里带结构化输出约束（`response_format=json_schema`）。
         * 默认 `false` = 与历史行为一致（只靠提示词 + 本地解析）；由
         * [StructuredOutputRouting.resolve] 按「三态 + 模型库探测结果」算出后传入。
         */
        structured: Boolean = false,
        /** 端点级引导解码（手动档）；非 [GuidedDecoding.OFF] 时替代 `response_format` */
        guided: GuidedDecoding = GuidedDecoding.OFF,
    ): Result<AiDecision> = withContext(Dispatchers.IO) {
        val adapter = ChatProtocols.of(protocol)
        // 这两个都必须可改：服务端在流式里拒绝结构化字段时会就地降级重试一次
        var useStructured = structured
        var useGuided = guided
        // 显式标注泛型：while(true) 循环体里有面向 for 的 break，编译器把 lambda
        // 返回类型推成 AiDecision | Unit（= Any），这里收口回 AiDecision
        runCatching<AiDecision> {
            DecisionStreamStats.onCall()
            val startNano = System.nanoTime()
            // 若携带截图，把图片拼到末尾消息（主模型决策一般文本即可，这里保留能力）
            val finalMessages = if (screenshot != null) {
                val imagePart = ContentPart(type = "image_url", image_url = ImageUrl(images.base64Image(screenshot)))
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
                    bodies.protocolStreamRequest(adapter, baseUrl, apiKey, model, msgs, temperature)
                } else {
                    Request.Builder()
                        .url(AiEndpoints.chatCompletionsUrl(baseUrl))
                        .header("Authorization", "Bearer $apiKey")
                        .post(bodies.buildStreamRequestBody(msgs, model, temperature, thinking = false,
                            streamOptions = StreamOptions(),
                            extras = bodies.decisionBodyExtras(useStructured, useGuided)))
                        .build()
                }
            var request = actionRequest(activeMessages)

            val full = StringBuilder()
            var lastUsage: Usage? = null
            var sawReasoning = false
            var status = -1
            var lastErr: String? = null
            // 首 token 计时（Eta 对标结论之三）：把"等首字"与"生成"分开记账，
            // 才能在慢的时候分清是网络/服务排队（TTFT 大）还是模型吐字慢（生成段大），
            // 不用再去猜"是不是应用卡了"。只写应用内日志，不进提示词、不影响行为。
            var attemptStartNano = 0L
            var firstDeltaNano = 0L
            var firstDeltaSeen = false
            val timedDelta: (String) -> Unit = { delta ->
                if (!firstDeltaSeen && delta.isNotBlank()) {
                    firstDeltaSeen = true
                    firstDeltaNano = System.nanoTime()
                }
                onDelta(delta)
            }
            // 边收边解析（Eta 对标结论之二）：每帧之后扫一遍新正文，一旦第一个顶层 JSON 闭合
            // 就尝试解析；成功即停止消费，不必等服务端把流关闭（省掉结尾的网络尾巴）。
            // 只扫描 content（思考内容不进 full），因此不会把 reasoning 里的花括号当成 JSON。
            val incremental = IncrementalIntentParser()
            var earlyAction: AgentIntent? = null
            // 提前结束时复用这个已闭合对象，别再对整段正文做一次 toString 复制
            var earlyContent: String? = null
            val shouldStopStream: () -> Boolean = {
                if (earlyAction == null) {
                    val closed = incremental.nextObject(full)
                    if (closed != null) {
                        // 闭合对象直接按完整解析口径尝试（含一次本地修复）；
                        // 解析不出来就继续等完整正文，交给下面的纠错重发判定，行为与原来一致
                        earlyContent = closed
                        earlyAction = intents.parseAgentIntentOrNull(closed)
                    }
                }
                earlyAction != null
            }
            loop@ while (true) {
                for (attempt in 0 until AiRetryPolicy.MAX_RETRIES) {
                    if (attempt > 0) {
                        // 先算退避时长：算不出来说明已到上限，这一轮不再重试，也就不该先回调"重试中"
                        val waitMs = AiRetryPolicy.backoffDelayMs(status, attempt) ?: break
                        onRetry?.invoke(AiRetryPolicy.retryReason(status, lastErr), waitMs)
                        delay(waitMs)
                    }
                    try {
                        full.setLength(0)
                        incremental.reset()
                        earlyAction = null
                        earlyContent = null
                        lastUsage = null
                        sawReasoning = false
                        firstDeltaSeen = false
                        firstDeltaNano = 0L
                        attemptStartNano = System.nanoTime()
                        var streamFailed = false
                        val call = client.newCall(request)
                        // 协程取消（任务停止/页面关闭）时同步取消 OkHttp 请求；请求结束后反注册回调
                        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
                        try {
                            call.execute().use { resp ->
                                status = resp.code
                                if (!resp.isSuccessful) {
                                    val body = resp.body?.string().orEmpty()
                                    lastErr = AiRetryPolicy.httpErrorText(resp, body)
                                    streamFailed = true
                                    return@use
                                }
                                resp.body?.source()?.use { source ->
                                    sawReasoning = pump.pumpSse(
                                        source = source,
                                        adapter = adapter,
                                        full = full,
                                        onDelta = timedDelta,
                                        onUsage = { usage -> lastUsage = pump.combineUsage(lastUsage, usage) },
                                        shouldStop = shouldStopStream,
                                    )
                                }
                            }
                        } finally {
                            handle?.dispose()
                        }
                        if (streamFailed) {
                            // 401/403/404 属鉴权/地址错误，重试不会变好，直接失败
                            // （对齐能力探测链路 probeBlocker 的"整体失败"口径）
                            if (status == 401 || status == 403 || status == 404) break
                            // 服务端明确抱怨结构化字段：摘掉约束立刻重发一次（不占网络重试预算）。
                            // 引导解码同样属于"可能不被认识"的字段，一并摘掉，避免同一请求反复 400。
                            if ((useStructured || useGuided != GuidedDecoding.OFF) &&
                                StructuredOutputRouting.isStructuredRejection(status, lastErr)
                            ) {
                                useStructured = false
                                useGuided = GuidedDecoding.OFF
                                AppLog.w("AiClient", "服务端拒绝结构化输出字段（HTTP $status），已去掉约束重试一次")
                                onRetry?.invoke("服务端不支持结构化输出，已去掉约束重试", 0L)
                                request = actionRequest(activeMessages)
                                status = -1
                                lastErr = null
                                continue@loop
                            }
                            continue
                        }
                        // 增量解析已命中：JSON 已经闭合且可解析，不必再等服务端关闭流。
                        // usage 由末尾帧携带，提前结束时拿不到，按 0 记（只影响成本统计，不影响决策）。
                        // 早停路径直接用已闭合对象当 rawContent，避免对整段正文再做一次 toString 复制。
                        val early = earlyAction
                        if (early != null) {
                            val nowNano = System.nanoTime()
                            val raw = earlyContent ?: full.toString()
                            logStreamTiming(
                                "决策", attemptStartNano, firstDeltaNano, nowNano, full.length, attempt,
                                earlyStop = true,
                            )
                            DecisionStreamStats.onEarlyStop()
                            AppLog.i("AiClient", DecisionStreamStats.summaryText())
                            return@runCatching AiDecision(
                                action = early,
                                promptTokens = lastUsage?.promptTokens ?: 0,
                                completionTokens = lastUsage?.completionTokens ?: 0,
                                totalTokens = lastUsage?.totalTokens ?: 0,
                                elapsedMs = (nowNano - startNano) / 1_000_000,
                                rawContent = raw,
                                thinking = sawReasoning,
                            )
                        }
                        val content = full.toString()
                        if (content.isBlank()) {
                            // 流式空正文（兼容问题）→ 回退非流式 + 结构化输出
                            lastErr = null
                            val fallbackRequest = if (adapter != null) {
                                // 协议适配器路径：非流式请求（finalMessages 已含截图部件）
                                bodies.protocolRequest(
                                    adapter, baseUrl, apiKey, model, finalMessages, temperature,
                                    maxTokens = 4096, stream = false,
                                )
                            } else {
                                Request.Builder()
                                    .url(AiEndpoints.chatCompletionsUrl(baseUrl))
                                    .header("Authorization", "Bearer $apiKey")
                                    .post(bodies.buildRequestBody(messages, screenshot, model, temperature,
                                        extras = bodies.decisionBodyExtras(useStructured, useGuided)))
                                    .build()
                            }
                            @Suppress("MaxLineLength")
                            val fbContent = transport.executeWithRetry(fallbackRequest, adapter?.let { a -> a::extractText })
                            return@runCatching AiDecision(
                                action = intents.parseAgentIntent(fbContent),
                                promptTokens = 0,
                                completionTokens = 0,
                                totalTokens = 0,
                                elapsedMs = (System.nanoTime() - startNano) / 1_000_000,
                                rawContent = fbContent,
                                thinking = false,
                            )
                        }
                        // 只在拿到正文后记账：空正文会走下面的非流式回退，不参与首 token 统计
                        logStreamTiming("决策", attemptStartNano, firstDeltaNano, System.nanoTime(), content.length,
                            attempt)
                        // 先按「可空」口径解析：失败时不急着 give_up，先做一次纠错重发
                        val action = intents.parseAgentIntentOrNull(content)
                        if (action == null && !jsonRepairUsed) {
                            jsonRepairUsed = true
                            DecisionStreamStats.onResend()
                            AppLog.w("AiClient", "决策输出无法解析为标准 JSON，附上原文纠错重发（仅一次）")
                            // 重发前清空流式回显（与网络重试同一口径），避免界面上内容叠加两遍
                            onRetry?.invoke("返回格式无法解析，正在重新生成", 0L)
                            // 附上「上一轮信息无法识别」：assistant 带原文 + user 要求只输出标准 JSON
                            activeMessages = activeMessages + listOf(
                                ChatMessageDto(role = "assistant", content = listOf(ContentPart(type = "text",
                                    text = content))),
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
                        AppLog.i("AiClient", DecisionStreamStats.summaryText())
                        return@runCatching AiDecision(
                            // 纠错后仍解析失败 → 走既有 give_up 兜底（不无限循环）
                            action = action ?: intents.parseAgentIntent(content),
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
                bodies.protocolRequest(adapter, baseUrl, apiKey, model, messages, temperature, maxTokens = 4096,
                    stream = false)
            } else {
                Request.Builder()
                    .url(AiEndpoints.chatCompletionsUrl(baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .post(bodies.buildRawRequestBody(messages, model, temperature))
                    .build()
            }
            transport.executeWithRetry(request, adapter?.let { a -> a::extractText })
        }.onFailure { if (it is CancellationException) throw it }
    }

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
                        ContentPart(type = "image_url", image_url = ImageUrl(images.base64Image(screenshot))),
                    ),
                ),
            )
            val adapter = ChatProtocols.of(protocol)
            @Suppress("MaxLineLength")
            val content = if (adapter != null) {
                // 协议适配器路径：Claude/Gemini 无 OpenAI 的 response_format，坐标靠提示词约束 + 兜底解析
                transport.executeWithRetry(
                    bodies.protocolRequest(adapter, baseUrl, apiKey, model, messages, 0.1, maxTokens = 2048, stream = false),
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

    /** 通用 POST 请求：返回模型正文（支持图片与可选结构化输出） */
    private suspend fun postCompat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
        responseFormat: ResponseFormat?,
    ): String {
        val requestBody = bodies.buildCompatRequestBody(messages, model, temperature, responseFormat)
        val request = Request.Builder()
            .url(AiEndpoints.chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        return transport.executeWithRetry(request)
    }

    /**
     * 记录一次流式调用的分段耗时：**首 token（TTFT）**与**首 token 之后的生成段**分开记。
     *
     * 为什么要分开：总耗时只能告诉你"慢"，分不清是网络/服务端排队慢（请求已发出但首字迟迟不来），
     * 还是模型吐字慢（首字很快、后续拖很久），更分不清是不是端侧在等工具。分开后：
     * - 首 token 大 → 网络/服务排队/提示词过长（前缀缓存没命中）
     * - 生成段大 → 模型速度/输出长度
     * 日志只进应用内调试缓冲，不改变任何请求与提示词，也不暴露给 AI。
     */
    // 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
    @Suppress("LongParameterList")
    private fun logStreamTiming(
        scene: String,
        startNano: Long,
        firstDeltaNano: Long,
        endNano: Long,
        chars: Int,
        attempt: Int,
        earlyStop: Boolean = false,
    ) {
        if (startNano <= 0L) return
        val totalMs = (endNano - startNano) / 1_000_000
        val retryNote = if (attempt > 0) "（第 ${attempt + 1} 次尝试）" else ""
        val earlyNote = if (earlyStop) "（增量解析命中，提前结束）" else ""
        if (firstDeltaNano <= 0L) {
            // 有正文但没记到首字（如非流式回退路径），只报总量
            AppLog.i("AiClient", "$scene 流式$retryNote：未记到首 token，总耗时 ${totalMs}ms，${chars} 字")
            return
        }
        val ttftMs = (firstDeltaNano - startNano) / 1_000_000
        val genMs = (endNano - firstDeltaNano) / 1_000_000
        if (scene == "决策") DecisionStreamStats.onTiming(ttftMs, totalMs)
        AppLog.i(
            "AiClient",
            "$scene 流式$retryNote：首 token ${ttftMs}ms，生成 ${genMs}ms，合计 ${totalMs}ms，${chars} 字$earlyNote",
        )
    }

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
    // 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
    @Suppress("LongMethod", "CyclomaticComplexMethod")
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
                bodies.protocolStreamRequest(adapter, baseUrl, apiKey, model, messages, temperature)
            } else {
                Request.Builder()
                    .url(AiEndpoints.chatCompletionsUrl(baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .post(bodies.buildStreamRequestBody(messages, model, temperature, thinking))
                    .build()
            }

            val full = StringBuilder()
            var status = -1
            var lastErr: String? = null
            // 首 token 计时（与决策链路同一口径）：规划慢时区分"等首字"与"生成"
            var attemptStartNano = 0L
            var firstDeltaNano = 0L
            var firstDeltaSeen = false
            val timedDelta: (String) -> Unit = { delta ->
                if (!firstDeltaSeen && delta.isNotBlank()) {
                    firstDeltaSeen = true
                    firstDeltaNano = System.nanoTime()
                }
                onDelta(delta)
            }
            for (attempt in 0 until AiRetryPolicy.MAX_RETRIES) {
                if (attempt > 0) {
                    // 与 chatForAction 同一口径：先算退避，算不出来说明已到上限，不再回调"重试中"
                    val waitMs = AiRetryPolicy.backoffDelayMs(status, attempt) ?: break
                    onRetry?.invoke(AiRetryPolicy.retryReason(status, lastErr), waitMs)
                    delay(waitMs)
                }
                try {
                    // 每次尝试独立累积，避免流中断重试后新旧内容拼接导致重复
                    full.setLength(0)
                    firstDeltaSeen = false
                    firstDeltaNano = 0L
                    attemptStartNano = System.nanoTime()
                    var streamFailed = false
                    val call = client.newCall(request)
                    // 协程取消（任务停止/页面关闭）时同步取消 OkHttp 请求；请求结束后反注册回调
                    val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
                    try {
                        call.execute().use { resp ->
                            status = resp.code
                            if (!resp.isSuccessful) {
                                val body = resp.body?.string().orEmpty()
                                lastErr = AiRetryPolicy.httpErrorText(resp, body)
                                streamFailed = true
                                return@use
                            }
                            resp.body?.source()?.use { source ->
                                // 规划/思考链路不消费 usage，传 null 即跳过 usage 帧解析
                                pump.pumpSse(source, adapter, full, timedDelta, onUsage = null)
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
                    val text = full.toString()
                    logStreamTiming("规划", attemptStartNano, firstDeltaNano, System.nanoTime(), text.length, attempt)
                    return@runCatching text
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

    /** 模型枚举与能力探测：实现见 [AiProbe] */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<List<String>> = probe.listModels(baseUrl, apiKey, protocol)

    suspend fun probeAbility(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: ApiProtocol = ApiProtocol.OPENAI,
    ): Result<ModelAbility> = probe.probeAbility(baseUrl, apiKey, model, protocol)

    /** 流式解析入口：实现见 [AiStreamPump]，签名保持不变供测试与内部调用 */
    @Suppress("LongParameterList")
    internal fun pumpSse(
        source: BufferedSource,
        adapter: ChatProtocolAdapter?,
        full: StringBuilder,
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)? = null,
        shouldStop: () -> Boolean = { false },
    ): Boolean = pump.pumpSse(source, adapter, full, onDelta, onUsage, shouldStop)

    /** 请求构造入口：实现见 [AiRequestBodies] */
    @Suppress("LongParameterList")
    internal fun buildStreamRequestBody(
        messages: List<ChatMessageDto>,
        model: String,
        temperature: Double,
        thinking: Boolean = false,
        streamOptions: StreamOptions? = null,
        extras: AiRequestBodies.ActionBodyExtras? = null,
    ): RequestBody =
        bodies.buildStreamRequestBody(messages, model, temperature, thinking, streamOptions, extras)

    internal fun decisionBodyExtras(
        structured: Boolean,
        guided: GuidedDecoding,
    ): AiRequestBodies.ActionBodyExtras = bodies.decisionBodyExtras(structured, guided)

    /** 意图解析入口：实现见 [AiIntentParser] */
    internal fun parseAgentIntentOrNull(content: String, allowRepair: Boolean = true): AgentIntent? =
        intents.parseAgentIntentOrNull(content, allowRepair)

    /** 每步开头清一次：截图对象用完会被回收，避免跨步持有一份指向已回收位图的缓存 */
    fun clearImageCache() = images.clearImageCache()
}
