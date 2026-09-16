package com.phoneagent.data.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.phoneagent.core.security.DataSanitizer
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.StepTrace
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志 / 诊断报告导出（自 MainViewModel 外迁，重构 7.5）。
 *
 * 仅搬动代码位置，导出的文案、字段、排版格式与迁移前完全一致。
 * 落盘统一走「下载/HappyPhoneAgent」目录（MediaStore，Android 10+ 无需存储权限）。
 */
object LogExporter {

    /** 把指定任务（或全部）的日志导出为文本文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存路径，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogs(context: Context, logs: List<AgentLog>, taskId: Long, taskName: String?): String {
        return runCatching {
            val entries = if (taskId < 0) logs else logs.filter { it.taskId == taskId }
            if (entries.isEmpty()) return@runCatching "ERR:没有可导出的日志"

            val sb = StringBuilder()
            sb.appendLine("Happy Phone Agent 运行日志")
            sb.appendLine("导出时间：${formatNow()}")
            sb.appendLine("任务：${taskName ?: "全部"}  ·  共 ${entries.size} 条")
            sb.appendLine("═".repeat(48))
            entries.forEach { e ->
                sb.append("[${levelTag(e.level)}] ${formatTimestamp(e.timestamp)} ${e.message}")
                e.detail?.let { sb.appendLine("\n$it") }
                sb.appendLine()
            }

            val fileName = "hpa_logs_${taskName?.take(12)?.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_") ?: "all"}_${fileStamp()}.txt"
            if (!writeToDownloads(context, downloadValues(fileName, "text/plain"), sb.toString().toByteArray(Charsets.UTF_8)))
                return@runCatching "ERR:无法写入导出文件（需 Android 10 及以上）"
            "已导出到 下载/HappyPhoneAgent/$fileName"
        }.getOrElse { "ERR:${it.message ?: "导出失败"}" }
    }

    /** 分任务批量导出：每个任务导出一个独立 JSON 文件，保存到「下载」目录（Android 10+ 无需存储权限）。
     *  @return 成功返回保存汇总，失败返回错误信息（以 "ERR:" 开头） */
    fun exportLogsJsonAll(context: Context, logs: List<AgentLog>): String {
        return runCatching {
            val all = logs
            val byTask = all.filter { it.taskId >= 0 }.groupBy { it.taskId }
            val groups = mutableListOf<Triple<Long, String?, List<AgentLog>>>()
            byTask.values.forEach { g -> groups += Triple(g.first().taskId, g.first().taskName, g) }
            val sys = all.filter { it.taskId < 0 }
            if (sys.isNotEmpty()) groups += Triple(-1L, "系统日志", sys)
            if (groups.isEmpty()) return@runCatching "ERR:没有可导出的日志"

            val stamp = fileStamp()
            val written = mutableListOf<String>()
            groups.forEach { (tid, name, list) ->
                val safeName = name?.take(12)?.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_") ?: "task"
                val fileName = if (tid < 0) "hpa_logs_system_$stamp.json" else "hpa_logs_${tid}_${safeName}_$stamp.json"
                val jsonObj = buildJsonObject {
                    put("app", "Happy Phone Agent")
                    put("exported_at", formatNow())
                    put("task_id", tid)
                    put("task_name", name ?: "")
                    put("log_count", list.size)
                    put("logs", buildJsonArray {
                        list.forEach { l ->
                            add(
                                buildJsonObject {
                                    put("ts", l.timestamp)
                                    put("time", formatTimestamp(l.timestamp))
                                    put("level", l.level.name)
                                    put("message", l.message)
                                    l.detail?.takeIf { it.isNotBlank() }?.let { put("detail", it) }
                                }
                            )
                        }
                    })
                }
                val json = jsonObj.toString()
                if (!writeToDownloads(context, downloadValues(fileName, "application/json"), json.toByteArray(Charsets.UTF_8)))
                    return@runCatching "ERR:无法写入导出文件（需 Android 10 及以上）"
                written += fileName
            }
            "已批量导出 ${written.size} 个任务 JSON 到 下载/HappyPhoneAgent/"
        }.getOrElse { "ERR:${it.message ?: "导出失败"}" }
    }

    /** 导出诊断报告：人话摘要区 + 原始数据区，导出前自动脱敏。
     *  @return 保存路径或错误信息（以 "ERR:" 开头） */
    fun exportDiagnosticReport(
        context: Context,
        traces: List<StepTrace>,
        logs: List<AgentLog>,
        conversation: List<ConversationMessage>,
    ): String {
        return runCatching {
            val sb = StringBuilder()
            sb.appendLine("Happy Phone Agent 诊断报告")
            sb.appendLine("导出时间：${formatNow()}")
            sb.appendLine("═".repeat(48))

            sb.appendLine("\n━━━ 一、人话摘要区（用户视角）━━━")
            if (traces.isEmpty()) sb.appendLine("暂无已执行任务步骤。")
            traces.groupBy { it.taskId }.forEach { (tid, list) ->
                val sorted = list.sortedBy { it.step }
                val name = sorted.first().taskName ?: "任务 #$tid"
                sb.appendLine("\n■ 任务：$name（${sorted.size} 步）")
                sorted.forEach { tr ->
                    val human = HumanTranslator.summarizeDecision(tr.receivedText)
                    sb.appendLine("  · 第${tr.step}步 ${if (human.isNotBlank()) human else ""}${if (tr.visionModel.isNotBlank()) "（视觉:${tr.visionSource}）" else ""} · ${tr.latencyMs}ms · ${tr.totalTokens}token")
                }
            }
            val issues = logs.filter { it.level == AgentLog.Level.ERROR || it.level == AgentLog.Level.WARN }
            if (issues.isNotEmpty()) {
                sb.appendLine("\n■ 遇到的问题（已翻译成人话）：")
                issues.forEach { l ->
                    val human = HumanTranslator.translateError(l.message)
                    sb.appendLine("  - ${human}")
                }
            }

            sb.appendLine("\n\n━━━ 二、原始数据区（开发者视角，已脱敏）━━━")
            sb.appendLine("\n-- 执行追踪 (Trace) --")
            traces.groupBy { it.taskId }.forEach { (tid, list) ->
                sb.appendLine("\n[任务 $tid] ${list.first().taskName ?: ""}")
                list.sortedBy { it.step }.forEach { tr ->
                    sb.appendLine("· 步骤 ${tr.step} | 视觉=${tr.visionSource}(${tr.visionModel}) | 思考=${tr.thinking} | token=${tr.totalTokens} | ${tr.latencyMs}ms")
                    sb.appendLine("  SENT: ${DataSanitizer.sanitize(tr.sentText)}")
                    sb.appendLine("  GOT:  ${DataSanitizer.sanitize(tr.receivedText)}")
                }
            }
            sb.appendLine("\n-- 系统日志 (Logs，含 API) --")
            logs.forEach { l ->
                sb.appendLine("[${formatTimestamp(l.timestamp)}][${l.level.name}] ${DataSanitizer.sanitize(l.message)}")
                l.detail?.takeIf { it.isNotBlank() }?.let { sb.appendLine("    ${DataSanitizer.sanitize(it)}") }
            }
            sb.appendLine("\n-- 对话 (Conversation) --")
            conversation.forEach { c -> sb.appendLine("[${c.role}] ${DataSanitizer.sanitize(c.content).take(500)}") }

            val fileName = "hpa_diagnostic_${fileStamp()}.txt"
            if (!writeToDownloads(context, downloadValues(fileName, "text/plain"), sb.toString().toByteArray(Charsets.UTF_8)))
                return@runCatching "ERR:无法写入诊断文件（需 Android 10 及以上）"
            "已导出诊断报告：下载/HappyPhoneAgent/$fileName"
        }.getOrElse { "ERR:${it.message ?: "导出失败"}" }
    }

    // ---- 以下为原 MainViewModel 内的私有实现，随导出逻辑一并外迁 ----

    private fun formatNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun formatTimestamp(t: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(t))

    /** 组装 MediaStore 写入参数（统一下载目录 HappyPhoneAgent） */
    private fun downloadValues(fileName: String, mimeType: String): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HappyPhoneAgent")
        }

    /** 把内容写入「下载/HappyPhoneAgent」目录。
     *  MediaStore.Downloads.EXTERNAL_CONTENT_URI 仅 Android 10(API29)+ 可用；低版本设备返回 false，
     *  避免在 API<29 上引用该字段导致崩溃（导出功能主面向 Android 10+）。 */
    private fun writeToDownloads(context: Context, values: ContentValues, payload: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return resolver.openOutputStream(uri)?.use { it.write(payload) } != null
    }

    private fun levelTag(lvl: AgentLog.Level): String = when (lvl) {
        AgentLog.Level.ERROR -> "错误"
        AgentLog.Level.WARN -> "警告"
        AgentLog.Level.AI -> "AI"
        AgentLog.Level.INFO -> "信息"
        AgentLog.Level.API -> "API"
    }
}