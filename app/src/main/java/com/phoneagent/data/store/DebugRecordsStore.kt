package com.phoneagent.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.domain.model.StepTrace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 调试记录的本地持久化。这些调试数据原本全部是 AgentEngine 的内存 StateFlow，重启即丢。
 * 本类将日志 / 步骤轨迹 / 执行历史 / 对话写入 app 内部目录，AgentEngine 启动时回载，实现"调试记录持久化"。
 *
 * 截图为降内存与磁盘体积统一压缩为小尺寸缩略图落盘；[thumb] 也被 AgentEngine 用于入内存前缩放，
 * 缓解整幅全分辨率截图长期驻留导致的内存溢出闪退。
 */
object DebugRecordsStore {

    private const val DIR_NAME = "hpa_debug_hist"
    private const val BUNDLE_FILE = "records.json"
    /** 缩略图最大宽度（px）：Debug 页复显同一份数据，显著降低单步截图占用 */
    const val THUMB_MAX_WIDTH = 360

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class LeanLog(
        val timestamp: Long, val level: String, val message: String, val detail: String?,
        val taskId: Long, val taskName: String?,
    )

    @Serializable
    data class LeanTrace(
        val taskId: Long, val taskName: String?, val step: Int,
        val sentText: String, val receivedText: String,
        val promptTokens: Int, val completionTokens: Int, val totalTokens: Int, val latencyMs: Long,
        val visionSource: String, val visionModel: String, val visionDescription: String, val thinking: Boolean,
        val shotFile: String?,
    )

    @Serializable
    data class LeanStepRecord(
        val step: Int, val action: AgentAction?, val sendResult: String, val verificationResult: String,
        val beforeFingerprint: String, val afterFingerprint: String, val durationMs: Long, val isConfirmed: Boolean,
    )

    @Serializable
    data class LeanMessage(val role: String, val content: String, val timestamp: Long, val hasImage: Boolean)

    @Serializable
    data class Bundle(
        val logs: List<LeanLog> = emptyList(),
        val traces: List<LeanTrace> = emptyList(),
        val history: List<LeanStepRecord> = emptyList(),
        val conversation: List<LeanMessage> = emptyList(),
    )

    /** 回载结果，供 AgentEngine 一次性写回内存 StateFlow */
    data class Persisted(
        val logs: List<AgentLog>,
        val traces: List<StepTrace>,
        val history: List<StepRecord>,
        val conversation: List<ConversationMessage>,
    )

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }

    /** 等比缩放到宽度不超过 [maxWidth] 的【新】缩略图；不回收原图（原图可能仍被其它引用持有）。
     *  原图已较小或为 null / 已回收时原样返回，避免无谓拷贝与重复回收。 */
    fun thumb(src: Bitmap?, maxWidth: Int = THUMB_MAX_WIDTH): Bitmap? {
        if (src == null || src.isRecycled) return null
        if (src.width <= maxWidth) return src
        val scale = maxWidth.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, maxWidth, (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    fun save(
        ctx: Context,
        logs: List<AgentLog>,
        traces: List<StepTrace>,
        history: List<StepRecord>,
        conversation: List<ConversationMessage>,
    ) {
        runCatching {
            val dir = dir(ctx)
            dir.listFiles { f -> f.name.endsWith(".png") }?.forEach { it.delete() }
            val leanTraces = traces.mapIndexed { i, t ->
                var shotFile: String? = null
                if (t.screenshot != null) {
                    val thumb = thumb(t.screenshot) ?: t.screenshot
                    val file = File(dir, "shot_$i.png")
                    file.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    shotFile = file.name
                }
                LeanTrace(
                    t.taskId, t.taskName, t.step, t.sentText, t.receivedText,
                    t.promptTokens, t.completionTokens, t.totalTokens, t.latencyMs,
                    t.visionSource, t.visionModel, t.visionDescription, t.thinking, shotFile,
                )
            }
            val bundle = Bundle(
                logs = logs.map { LeanLog(it.timestamp, it.level.name, it.message, it.detail, it.taskId, it.taskName) },
                traces = leanTraces,
                history = history.map {
                    LeanStepRecord(
                        it.step, it.action, it.sendResult, it.verificationResult,
                        it.beforeFingerprint, it.afterFingerprint, it.durationMs, it.isConfirmed,
                    )
                },
                conversation = conversation.map { LeanMessage(it.role, it.content, it.timestamp, it.hasImage) },
            )
            File(dir, BUNDLE_FILE).writeText(json.encodeToString(Bundle.serializer(), bundle))
        }
    }

    fun load(ctx: Context): Persisted? = runCatching {
        val dir = dir(ctx)
        val file = File(dir, BUNDLE_FILE)
        if (!file.exists()) return null
        val bundle = json.decodeFromString(Bundle.serializer(), file.readText())
        val traces = bundle.traces.map { t ->
            val shot = t.shotFile?.let { name -> BitmapFactory.decodeFile(File(dir, name).absolutePath) }
            StepTrace(
                taskId = t.taskId, taskName = t.taskName, step = t.step,
                sentText = t.sentText, receivedText = t.receivedText,
                promptTokens = t.promptTokens, completionTokens = t.completionTokens, totalTokens = t.totalTokens,
                latencyMs = t.latencyMs, visionSource = t.visionSource, visionModel = t.visionModel,
                visionDescription = t.visionDescription, thinking = t.thinking, screenshot = shot,
            )
        }
        Persisted(
            logs = bundle.logs.map {
                AgentLog(it.timestamp, AgentLog.Level.valueOf(it.level), it.message, it.detail, it.taskId, it.taskName)
            },
            traces = traces,
            history = bundle.history.map {
                StepRecord(
                    it.step, it.action, it.sendResult, it.verificationResult,
                    it.beforeFingerprint, it.afterFingerprint, it.durationMs, it.isConfirmed,
                )
            },
            conversation = bundle.conversation.map { ConversationMessage(it.role, it.content, it.timestamp, it.hasImage) },
        )
    }.getOrNull()

    /** 清空持久化目录（配合调试页"清空"按钮，避免重启后旧记录又被回载） */
    fun clear(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}