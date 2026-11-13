// 本文件里的数字多为上限、超时与截断长度，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.engine.network

import android.content.Context
import android.os.Build
import com.phoneagent.BuildConfig
import com.phoneagent.core.log.AppLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.engine.TaskSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 任务执行数据的云端反馈上报（默认关闭，由设置里的 [com.phoneagent.data.prefs.AppSettings.Settings.feedbackUploadEnabled] 控制）。
 *
 * 隐私边界：只上报「动作类型 / 成败 / 耗时 / token 用量」这类结构性指标。
 * 任务文本、页面元素文字、输入内容、坐标、截图、应用包名、设备型号与任何账号标识都不采集；
 * 设备侧只保留一个随机生成的安装 ID，用于把同一台设备的多次任务串起来，不含系统标识。
 *
 * 可靠性：任务结束先落一条本地 outbox（进程被杀也不丢），再异步逐条上传；
 * 网络失败保留下次任务再试，服务端明确拒绝（4xx）则丢弃该条，避免坏数据永久堵队列。
 */
@Suppress("TooManyFunctions")
internal class FeedbackReporter(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * 本地 outbox。`filesDir` 在极少数情况下会缺失（存储异常、或单元测试里 Android 框架未初始化），
     * 此时退化为「不落盘只上传」：宁可丢队列持久化，也不能让引擎构造阶段崩在这里。
     *
     * 注意这里取的是 `path` 而不是 `File` 本身：mock 出来的 File 可能 path 为 null，
     * 直接 `File(filesDir, "...")` 会在 File 构造函数里读 parent.path 抛 NPE。
     */
    private val outbox: File? = context.filesDir?.path
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it, "feedback/outbox.jsonl") }

    /** outbox 的文件读写与上传流程各自串行：任务收尾可能并发触发多次上报 */
    private val fileLock = Any()
    private val flushLock = Mutex()

    private val prefs by lazy { context.getSharedPreferences("feedback", Context.MODE_PRIVATE) }

    /** 随机安装 ID：仅用于关联同一安装的多次任务，不含 ANDROID_ID / IMEI 等系统标识 */
    private val installId: String by lazy {
        prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
    }

    /**
     * 上报一次已结束的任务。
     *
     * 调用方负责先判断开关；这里再做一次兜底（关闭时不落盘、不联网）。
     * 该方法立即返回，文件写入很小，网络上传在独立 IO 协程完成。
     */
    fun submit(
        baseUrl: String,
        session: TaskSession?,
        records: List<StepRecord>,
        metrics: AgentMetrics,
    ) {
        val endpoint = baseUrl.trim().trimEnd('/')
        if (session == null || endpoint.isBlank()) return
        val payload = runCatching { buildPayload(session, records, metrics).toString() }.getOrNull()
        if (payload == null) {
            AppLog.w(TAG, "反馈数据序列化失败，已跳过本次上报")
            return
        }
        synchronized(fileLock) { enqueue(payload) }
        scope.launch { flush(endpoint) }
    }

    // ==================== 上报体 ====================

    private fun buildPayload(
        session: TaskSession,
        records: List<StepRecord>,
        metrics: AgentMetrics,
    ): JsonObject {
        val steps = records.takeLast(MAX_STEPS).map(::buildStep)
        val actionCounts = records.groupingBy { normalizeAction(it.action?.type) }.eachCount()

        return buildJsonObject {
            put("schema", SCHEMA_VERSION)
            put("event", "task_finished")
            put("installId", installId)
            putJsonObject("app") {
                put("version", BuildConfig.VERSION_NAME.take(32))
                put("build", BuildConfig.VERSION_CODE)
                put("android", Build.VERSION.SDK_INT)
            }
            putJsonObject("task") {
                put("status", session.status.name.lowercase())
                // 绝对起止时间不上报：只留时长，减少可关联的时间指纹
                put("durationMs", clampMs(session.endedAt - session.startedAt))
                put("steps", session.steps.coerceIn(0, MAX_COUNT))
                put("okSteps", session.okSteps.coerceIn(0, MAX_COUNT))
                put("sendFailed", records.count { it.sendResult == SEND_FAILED }.coerceIn(0, MAX_COUNT))
                put("verifyFailed", records.count { it.verificationResult == VERIFY_FAILED }.coerceIn(0, MAX_COUNT))
                put("unverified", records.count { it.verificationResult == VERIFY_UNVERIFIED }.coerceIn(0, MAX_COUNT))
                putJsonObject("actionCounts") {
                    actionCounts.entries.sortedByDescending { it.value }.take(MAX_ACTION_KINDS).forEach { (k, v) ->
                        put(k, v.coerceIn(0, MAX_COUNT))
                    }
                }
                put("stepTrace", buildJsonArray {
                    steps.forEach { step ->
                        addJsonObject { step.forEach { (key, value) -> put(key, value) } }
                    }
                })
            }
            putJsonObject("metrics") {
                put("totalTokens", metrics.totalTokens.coerceIn(0, MAX_TOKENS))
                put("promptTokens", metrics.promptTokens.coerceIn(0, MAX_TOKENS))
                put("completionTokens", metrics.completionTokens.coerceIn(0, MAX_TOKENS))
                put("requestCount", metrics.requestCount.coerceIn(0, MAX_COUNT))
                put("avgLatencyMs", clampMs(metrics.avgLatencyMs))
                put("visionCount", metrics.visionCount.coerceIn(0, MAX_COUNT))
                put("visionTotalMs", clampMs(metrics.visionTotalMs))
                put("execCount", metrics.execCount.coerceIn(0, MAX_COUNT))
                put("execTotalMs", clampMs(metrics.execTotalMs))
            }
        }
    }

    /** 单步只映射白名单字段：动作类型、发送/验证结果、是否确认、耗时 */
    private fun buildStep(record: StepRecord): Map<String, JsonElement> = mapOf(
        "type" to JsonPrimitive(normalizeAction(record.action?.type)),
        "send" to JsonPrimitive(normalizeSend(record.sendResult)),
        "verify" to JsonPrimitive(normalizeVerify(record.verificationResult)),
        "ok" to JsonPrimitive(record.isConfirmed),
        "ms" to JsonPrimitive(clampMs(record.durationMs)),
    )

    /** 动作类型来自模型输出，只保留字母/数字/下划线，长度受限，避免把任意文本带上去 */
    private fun normalizeAction(raw: String?): String {
        val value = raw.orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        return value.take(MAX_ACTION_LEN).ifBlank { "unknown" }
    }

    private fun normalizeSend(raw: String?): String = when (raw) {
        "sent" -> "sent"
        SEND_FAILED -> SEND_FAILED
        else -> "unknown"
    }

    private fun normalizeVerify(raw: String?): String = when (raw) {
        VERIFY_SUCCESS, VERIFY_UNVERIFIED, VERIFY_FAILED -> raw
        else -> "unknown"
    }

    private fun clampMs(value: Long): Long = value.coerceIn(0, MAX_DURATION_MS)

    // ==================== 本地 outbox ====================

    private fun enqueue(payload: String) {
        runCatching {
            val file = outbox ?: return@runCatching
            file.parentFile?.mkdirs()
            val pending = readLines()
            val next = (pending + payload).takeLast(MAX_PENDING)
            file.writeText(next.joinToString("\n", postfix = "\n"))
        }.onFailure { AppLog.w(TAG, "反馈记录写入本地队列失败：${it.message}") }
    }

    private fun readLines(): List<String> {
        val file = outbox ?: return emptyList()
        return if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    // ==================== 上传 ====================

    private suspend fun flush(baseUrl: String) = flushLock.withLock {
        val url = "$baseUrl/api/feedback"
        while (true) {
            val first = synchronized(fileLock) { readLines().firstOrNull() } ?: return@withLock
            when (postJson(url, first)) {
                PostResult.OK, PostResult.DROP -> {
                    // 成功或服务端明确拒绝都移除队首：拒绝说明这条数据本身不可接受，重试只会永久堵住队列
                    synchronized(fileLock) {
                        val file = outbox ?: return@synchronized
                        file.writeText(readLines().drop(1).joinToString("\n", postfix = "\n"))
                    }
                }
                PostResult.RETRY -> return@withLock
            }
        }
    }

    private fun postJson(url: String, body: String): PostResult = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", JSON_MEDIA.toString())
            .header("User-Agent", "HPA-Feedback/${BuildConfig.VERSION_NAME}")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> PostResult.OK
                response.code in 400..499 && response.code != 429 -> PostResult.DROP
                else -> PostResult.RETRY
            }
        }
    }.getOrElse {
        AppLog.d(TAG, "反馈上传暂未成功，已保留待下次重试：${it.message}")
        PostResult.RETRY
    }

    private enum class PostResult { OK, DROP, RETRY }

    private companion object {
        const val TAG = "FeedbackReporter"
        const val SCHEMA_VERSION = 1
        const val MAX_PENDING = 20
        const val MAX_STEPS = 120
        const val MAX_COUNT = 100_000
        const val MAX_TOKENS = 100_000_000
        const val MAX_DURATION_MS = 3_600_000L
        const val MAX_ACTION_KINDS = 16
        const val MAX_ACTION_LEN = 24
        const val KEY_INSTALL_ID = "install_id"
        const val SEND_FAILED = "send_failed"
        const val VERIFY_SUCCESS = "verified_success"
        const val VERIFY_UNVERIFIED = "unverified"
        const val VERIFY_FAILED = "failed"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
