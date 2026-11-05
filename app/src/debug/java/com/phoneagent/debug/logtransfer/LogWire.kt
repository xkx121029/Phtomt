package com.phoneagent.debug.logtransfer

/**
 * 设备 → 电脑 的线格式（每行一个 JSON 对象，UTF-8 + `\n`）。
 *
 * 行协议而不是纯二进制：PC 侧用标准库 json 就能解析，出问题时肉眼可读。
 * 字段表（电脑端脚本按同一份定义解析）：
 *
 *   {"t":"hello","sid":"…","total":N,"device":"…","sdk":33,"at":毫秒}
 *   {"t":"log","seq":n,"phase":"history|live","ts":…,"lvl":"INFO","msg":"…",
 *    "detail":…|null,"taskId":…,"taskName":…|null}
 *   {"t":"hist_end","seq":n,"total":N}
 *   {"t":"bye","sent":n,"acked":n}
 *
 * 电脑 → 设备：
 *
 *   {"t":"ack","seq":n}   累计确认（≤n 的都已收到）
 *   {"t":"nak","expect":n} 序号缺口，请从 n 起重传
 *   {"t":"bye"}            主动结束
 */
sealed class WireEvent {

    data class Hello(
        val sid: String,
        val total: Long,
        val device: String,
        val sdk: Int,
        val at: Long,
    ) : WireEvent()

    data class LogEntry(
        val seq: Long,
        /** history / live：历史日志先全量正序推送，随后才是实时流 */
        val phase: String,
        val record: LogRecord,
    ) : WireEvent()

    data class HistoryEnd(val seq: Long, val total: Long) : WireEvent()

    data class Bye(val sent: Long, val acked: Long) : WireEvent()
}

object LogWire {

    fun encode(event: WireEvent): String = when (event) {
        is WireEvent.Hello -> LogJson.obj(
            "t" to "hello", "sid" to event.sid, "total" to event.total,
            "device" to event.device, "sdk" to event.sdk, "at" to event.at,
        )
        is WireEvent.LogEntry -> LogJson.obj(
            "t" to "log", "seq" to event.seq, "phase" to event.phase,
            "ts" to event.record.timestamp, "lvl" to event.record.level,
            "msg" to event.record.message, "detail" to event.record.detail,
            "taskId" to event.record.taskId, "taskName" to event.record.taskName,
        )
        is WireEvent.HistoryEnd -> LogJson.obj(
            "t" to "hist_end", "seq" to event.seq, "total" to event.total,
        )
        is WireEvent.Bye -> LogJson.obj("t" to "bye", "sent" to event.sent, "acked" to event.acked)
    }

    /** 磁盘追加日志行（与线格式的 log 事件同字段，去掉 t/seq/phase 这些会话信息） */
    fun encodeRecord(record: LogRecord): String = LogJson.obj(
        "ts" to record.timestamp, "lvl" to record.level, "msg" to record.message,
        "detail" to record.detail, "taskId" to record.taskId, "taskName" to record.taskName,
    )

    fun decodeRecord(line: String): LogRecord? {
        val m = LogJson.parseFlatObject(line) ?: return null
        val ts = (m["ts"] as? Long) ?: return null
        val level = m["lvl"] as? String ?: return null
        val message = m["msg"] as? String ?: return null
        return LogRecord(
            timestamp = ts,
            level = level,
            message = message,
            detail = m["detail"] as? String,
            taskId = (m["taskId"] as? Long) ?: -1L,
            taskName = m["taskName"] as? String,
        )
    }

    /** 电脑端命令解析（设备侧读取 ack/nak/bye；认不出就忽略，不让坏行打断传输） */
    fun decodeCommand(line: String): WireCommand? {
        val m = LogJson.parseFlatObject(line) ?: return null
        return when (m["t"] as? String) {
            "ack" -> (m["seq"] as? Long)?.let { WireCommand.Ack(it) }
            "nak" -> (m["expect"] as? Long)?.let { WireCommand.Nak(it) }
            "bye" -> WireCommand.Bye
            else -> null
        }
    }
}

sealed class WireCommand {
    data class Ack(val seq: Long) : WireCommand()
    data class Nak(val expect: Long) : WireCommand()
    data object Bye : WireCommand()
}
