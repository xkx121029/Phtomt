package com.phoneagent.debug.logtransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史日志：三源合并、去重、正序、不截断，以及线格式的编解码。 */
class LogHistoryTest {

    private fun rec(ts: Long, msg: String, level: String = "INFO") =
        LogRecord(timestamp = ts, level = level, message = msg)

    @Test
    fun `三源合并去重并按时间戳正序`() {
        val journal = listOf(rec(100, "a"), rec(300, "c"))
        val memory = listOf(rec(200, "b"), rec(300, "c"), rec(350, "d"))
        val persisted = listOf(rec(50, "z"), rec(100, "a"))

        val merged = LogHistoryAssembler.assemble(journal, memory, persisted)

        assertEquals(listOf(50L, 100L, 200L, 300L, 350L), merged.map { it.timestamp })
        assertEquals(listOf("z", "a", "b", "c", "d"), merged.map { it.message })
    }

    @Test
    fun `五千条历史完整保留不截断`() {
        val records = (0 until 5000).map { rec(1_000L + it, "entry-$it") }
        val merged = LogHistoryAssembler.assemble(records, emptyList(), emptyList())
        assertEquals(5000, merged.size)
        assertEquals(1_000L, merged.first().timestamp)
        assertEquals(5_999L, merged.last().timestamp)
        assertEquals("entry-4999", merged.last().message)
    }

    @Test
    fun `同时间戳保持来源内的原有顺序`() {
        val first = listOf(rec(7, "first-1"), rec(7, "first-2"))
        val second = listOf(rec(7, "second-1"))
        val merged = LogHistoryAssembler.assemble(first, second)
        assertEquals(listOf("first-1", "first-2", "second-1"), merged.map { it.message })
    }

    @Test
    fun `同来源重复条目去重`() {
        val duplicated = listOf(rec(10, "same"), rec(10, "same"))
        assertEquals(1, LogHistoryAssembler.assemble(duplicated).size)
    }

    @Test
    fun `线格式与磁盘行的 JSON 往返`() {
        val record = LogRecord(
            timestamp = 1_700_000_000_123L,
            level = "ERROR",
            message = "第一行\n第二行 \"引号\" \\反斜杠\t制表",
            detail = "detail\u2028分段",
            taskId = 42L,
            taskName = "演示任务",
        )

        val wire = LogWire.encode(WireEvent.LogEntry(7, LogTransferSession.PHASE_LIVE, record))
        val parsed = LogJson.parseFlatObject(wire)!!
        assertEquals("log", parsed["t"])
        assertEquals(7L, parsed["seq"])
        assertEquals("live", parsed["phase"])
        assertEquals(record.timestamp, parsed["ts"])
        assertEquals("ERROR", parsed["lvl"])
        assertEquals(record.message, parsed["msg"])
        assertEquals(record.detail, parsed["detail"])
        assertEquals(42L, parsed["taskId"])
        assertEquals("演示任务", parsed["taskName"])

        val line = LogWire.encodeRecord(record)
        val restored = LogWire.decodeRecord(line)!!
        assertEquals(record, restored)
    }

    @Test
    fun `坏行解析返回 null`() {
        assertNull(LogWire.decodeRecord("not json"))
        assertNull(LogWire.decodeRecord("""{"ts":1,"lvl":"INFO"}"""))
        assertNull(LogWire.decodeRecord("""{"ts":1,"lvl":"INFO","msg":"x","nested":{"a":1}}"""))
        assertTrue(LogWire.decodeRecord("""{"ts":1,"lvl":"INFO","msg":"x"}""") != null)
    }

    @Test
    fun `电脑端命令解析`() {
        assertEquals(WireCommand.Ack(12), LogWire.decodeCommand("""{"t":"ack","seq":12}"""))
        assertEquals(WireCommand.Nak(3), LogWire.decodeCommand("""{"t":"nak","expect":3}"""))
        assertEquals(WireCommand.Bye, LogWire.decodeCommand("""{"t":"bye"}"""))
        assertNull(LogWire.decodeCommand("""{"t":"ack"}"""))
        assertNull(LogWire.decodeCommand("garbage"))
    }
}
