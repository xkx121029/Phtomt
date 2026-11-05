package com.phoneagent.debug.logtransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 实时采集的配套逻辑：环形增量、跨来源去重、追加流水。 */
class LivePipelineTest {

    @Test
    fun `环形缓冲没有基准时整份都算新增`() {
        val list = listOf("a", "b", "c")
        assertEquals(list, EngineLogDelta.after(list, null) { it })
    }

    @Test
    fun `环形缓冲按上次最后一条反查增量`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "d"), EngineLogDelta.after(list, "b") { it })
        assertTrue(EngineLogDelta.after(list, "d") { it }.isEmpty())
    }

    @Test
    fun `上次那条已被挤出时返回整份缓冲`() {
        val list = listOf("x", "y", "z")
        assertEquals(list, EngineLogDelta.after(list, "a") { it })
        assertTrue(EngineLogDelta.after(emptyList<String>(), "a") { it }.isEmpty())
    }

    @Test
    fun `跨来源镜像只保留一份`() {
        var now = 100L
        val deduper = CrossSourceDeduper({ now }, windowMs = 3_000)
        assertTrue(deduper.accept("k1", "applog"))
        assertFalse(deduper.accept("k1", "engine"))
        // 同来源的重复（用户连续打两条相同日志）照常保留
        assertTrue(deduper.accept("k2", "applog"))
        assertTrue(deduper.accept("k2", "applog"))
    }

    @Test
    fun `窗口外的同 key 视为新日志`() {
        var now = 100L
        val deduper = CrossSourceDeduper({ now }, windowMs = 1_000)
        assertTrue(deduper.accept("same", "applog"))
        now += 2_000
        assertTrue(deduper.accept("same", "engine"))
    }

    @Test
    fun `追加流水跨读取保留完整顺序`() {
        val file = File.createTempFile("hpa-journal", ".jsonl")
        try {
            val journal = LogJournal(file)
            val records = (0 until 200).map {
                LogRecord(1_000L + it, "INFO", "line-$it", detail = "d$it", taskId = it.toLong())
            }
            records.forEach { journal.append(it) }
            val restored = journal.readAll()
            assertEquals(records, restored)
            assertTrue(journal.sizeBytes() > 0)
        } finally {
            file.delete()
        }
    }
}
