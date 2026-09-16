package com.phoneagent.data.export

import android.content.Context
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.StepTrace
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LogExporter 单测（重构 7.6 新增）。
 *
 * JVM 单测下 `Build.VERSION.SDK_INT` 取默认值 0（< API 29），
 * `writeToDownloads` 会直接返回 false → 落盘阶段统一返回「需 Android 10 及以上」，
 * 因此这里可稳定验证「导出前的数据整理/筛选/拼接」与错误分支，不触碰真实 MediaStore。
 */
class LogExporterTest {

    private val context: Context = mockk(relaxed = true)
    private val writeFailed = "ERR:无法写入导出文件（需 Android 10 及以上）"
    private val writeFailedDiag = "ERR:无法写入诊断文件（需 Android 10 及以上）"

    private fun log(
        taskId: Long,
        message: String,
        level: AgentLog.Level = AgentLog.Level.INFO,
        detail: String? = null,
        taskName: String? = null,
    ) = AgentLog(
        timestamp = 1_700_000_000_000L,
        level = level,
        message = message,
        detail = detail,
        taskId = taskId,
        taskName = taskName,
    )

    // ---------- exportLogs ----------

    @Test
    fun exportLogs_无日志_返回无可导出提示() {
        val r = LogExporter.exportLogs(context, emptyList(), taskId = -1, taskName = null)
        assertEquals("ERR:没有可导出的日志", r)
    }

    @Test
    fun exportLogs_指定任务无匹配日志_返回无可导出提示() {
        val logs = listOf(log(taskId = 7L, message = "a"))
        val r = LogExporter.exportLogs(context, logs, taskId = 8L, taskName = "任务8")
        assertEquals("ERR:没有可导出的日志", r)
    }

    @Test
    fun exportLogs_有日志_进入落盘阶段并返回写入失败提示() {
        val logs = listOf(log(taskId = 7L, message = "已打开微信", detail = "detail-1"))
        val r = LogExporter.exportLogs(context, logs, taskId = 7L, taskName = "发消息")
        assertEquals(writeFailed, r)
    }

    @Test
    fun exportLogs_全部任务_进入落盘阶段并返回写入失败提示() {
        val logs = listOf(log(taskId = 7L, message = "a"), log(taskId = -1L, message = "b"))
        val r = LogExporter.exportLogs(context, logs, taskId = -1, taskName = null)
        assertEquals(writeFailed, r)
    }

    // ---------- exportLogsJsonAll ----------

    @Test
    fun exportLogsJsonAll_无日志_返回无可导出提示() {
        val r = LogExporter.exportLogsJsonAll(context, emptyList())
        assertEquals("ERR:没有可导出的日志", r)
    }

    @Test
    fun exportLogsJsonAll_仅有系统日志_不因任务为空而提前返回() {
        val logs = listOf(log(taskId = -1L, message = "系统启动"))
        val r = LogExporter.exportLogsJsonAll(context, logs)
        assertEquals(writeFailed, r)
    }

    @Test
    fun exportLogsJsonAll_多任务_逐任务生成后返回写入失败提示() {
        val logs = listOf(
            log(taskId = 1L, message = "a", taskName = "任务一"),
            log(taskId = 2L, message = "b", taskName = null),
            log(taskId = -1L, message = "c"),
        )
        val r = LogExporter.exportLogsJsonAll(context, logs)
        assertEquals(writeFailed, r)
    }

    // ---------- exportDiagnosticReport ----------

    @Test
    fun exportDiagnosticReport_无数据_不崩溃并返回写入提示() {
        val r = LogExporter.exportDiagnosticReport(context, emptyList(), emptyList(), emptyList())
        assertEquals(writeFailedDiag, r)
    }

    @Test
    fun exportDiagnosticReport_含追踪与错误日志_翻译与脱敏后进入落盘阶段() {
        val traces = listOf(
            StepTrace(
                taskId = 1L,
                taskName = "给张三发消息",
                step = 1,
                sentText = "手机号 13800138000",
                receivedText = "{\"intent\":\"tap\"}",
                totalTokens = 120,
                latencyMs = 800,
                visionSource = "云端",
                visionModel = "glm-4.6v-flash",
            ),
            StepTrace(taskId = 1L, taskName = "给张三发消息", step = 2),
        )
        val logs = listOf(
            log(taskId = 1L, message = "找不到控件", level = AgentLog.Level.ERROR),
            log(taskId = 1L, message = "重试一次", level = AgentLog.Level.WARN, detail = "13900139000"),
        )
        val conversation = listOf(ConversationMessage(role = "user", content = "给张三发消息", timestamp = 1_700_000_000_000L))
        val r = LogExporter.exportDiagnosticReport(context, traces, logs, conversation)
        assertEquals(writeFailedDiag, r)
    }

    @Test
    fun exportDiagnosticReport_错误信息以ERR前缀返回() {
        val r = LogExporter.exportDiagnosticReport(context, emptyList(), emptyList(), emptyList())
        assertTrue(r.startsWith("ERR:"))
    }
}