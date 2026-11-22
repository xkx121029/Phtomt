// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * 重试策略与错误映射：退避时长、重试次数、以及「失败原因怎么说人话」。
 *
 * 纯函数，不碰网络，可以单独单测。**这里的数字是产品行为**：429 退避封顶 8s、
 * 最多 3 次，是为了整体预算落在决策看门狗（45s）之内；非 429 只快速重试 1 次。
 */
internal object AiRetryPolicy {

    private const val INITIAL_DELAY_MS = 800L
    private const val BASE_429_DELAY_MS = 2000L
    internal const val MAX_RETRIES = 5
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

    /**
     * 计算下一次重试的退避时长；返回 null 表示不再重试。
     *
     * 429 限流按 2s/4s/8s 递增并封顶 [MAX_BACKOFF_MS]，最多 [MAX_429_RETRIES] 次；
     * 其他错误最多 [MAX_NON_429_RETRIES] 次。用 suspend delay 而非 Thread.sleep，保证协程取消能即时生效。
     */
    internal fun backoffDelayMs(status: Int, attempt: Int): Long? = when {
        status == 429 ->
            if (attempt >= MAX_429_RETRIES) null
            else (BASE_429_DELAY_MS shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(MAX_BACKOFF_MS)

        attempt >= MAX_NON_429_RETRIES -> null
        else -> INITIAL_DELAY_MS
    }

    /** 重试原因的人话描述：用户看到的应该是「被限流」而不是「正在思考」 */
    internal fun retryReason(status: Int, err: String?): String = when {
        status == 429 -> "服务端限流"
        status == HTTP_REQUEST_TIMEOUT || status == HTTP_GATEWAY_TIMEOUT -> "请求超时"
        status in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> "服务端错误（HTTP $status）"
        status > 0 -> "请求失败（HTTP $status）"
        else -> err?.take(MAX_ERROR_SNIPPET)?.takeIf { it.isNotBlank() } ?: "网络连接失败"
    }

    /**
     * 组装完整错误信息：状态码 + 请求地址 + 服务端原始返回正文。
     * 不再只截取 error.message —— 各服务商字段不一（有的把原因放在 error.type / code / 顶层 message），
     * 只取 message 会丢掉诊断依据，甚至完全取不到（非 JSON 响应如网关 HTML 错误页）。
     */
    internal fun httpErrorText(resp: okhttp3.Response, body: String): String {
        val detail = body.trim().ifEmpty { "（响应体为空）" }
        return "HTTP ${resp.code}（${resp.request.url}）\n返回内容：$detail"
    }

    internal fun snippet(body: String): String =
        body.trim().take(200).ifEmpty { "（响应体为空）" }
}

/** 非流式请求执行器：一次请求 + 退避重试 + 正文解析，401/403/404 不重试直接失败。 */
internal class AiTransport(private val client: OkHttpClient, private val json: Json) {

    /**
     * 执行非流式请求并返回模型正文，带 429 退避重试。
     * - [extractText] 为 null 时按 OpenAI ChatResponse 解析；非空时交给协议适配器解析
     * - 429（限流）：指数退避 4s/8s/16s/32s，最多 5 次尝试
     * - 401/403/404（鉴权/地址错误）：不重试，直接失败（对齐能力探测链路 probeBlocker 的"整体失败"口径）
     * - 其他失败：快速重试 1 次（800ms）
     */
    @Suppress(
        "CyclomaticComplexMethod",
        "InstanceOfCheckForException",
        "LoopWithTooManyJumpStatements",
        "NestedBlockDepth",
        "TooGenericExceptionCaught",
    )
    internal suspend fun executeWithRetry(request: Request, extractText: ((String) -> String?)? = null): String {
        var status = -1
        var lastErr: String? = null
        for (attempt in 0 until AiRetryPolicy.MAX_RETRIES) {
            if (attempt > 0) {
                val waitMs = AiRetryPolicy.backoffDelayMs(status, attempt) ?: break
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
                            lastErr = AiRetryPolicy.httpErrorText(resp, body)
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
                                lastErr = AiRetryPolicy.httpErrorText(resp, body)
                                failed = true
                                null
                            } else if (parsed.error != null) {
                                lastErr = AiRetryPolicy.httpErrorText(resp, body)
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
}
