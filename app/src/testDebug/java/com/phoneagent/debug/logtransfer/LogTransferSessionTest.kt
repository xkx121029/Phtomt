package com.phoneagent.debug.logtransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单连接会话：序号编排、确认、缺口重传与超时代替。 */
class LogTransferSessionTest {

    private val events = mutableListOf<WireEvent>()
    private var now = 1_000L

    private fun rec(i: Int) = LogRecord(
        timestamp = 1_000L + i,
        level = "INFO",
        message = "m$i",
    )

    private fun session(
        retransmitAfterMs: Long = 600L,
        maxRetries: Int = 4,
        window: Int = LogTransferSession.DEFAULT_WINDOW,
    ) = LogTransferSession(
        sid = "test-sid",
        device = LogTransferSession.DeviceInfo("test-device", 33),
        send = { events += it },
        clock = { now },
        retransmitAfterMs = retransmitAfterMs,
        maxRetries = maxRetries,
        window = window,
    )

    private fun logEvents() = events.filterIsInstance<WireEvent.LogEntry>()

    @Test
    fun `历史按 seq 从 1 递增并追加 hist_end`() {
        val session = session()
        session.begin(listOf(rec(0), rec(1), rec(2)))
        assertEquals(3L, session.totalHistory)
        assertEquals(1, events.size)
        assertEquals(WireEvent.Hello::class.java, events[0].javaClass)

        assertTrue(session.tick(now))

        val logs = logEvents()
        assertEquals(listOf(1L, 2L, 3L), logs.map { it.seq })
        assertEquals(
            listOf(LogTransferSession.PHASE_HISTORY, LogTransferSession.PHASE_HISTORY, LogTransferSession.PHASE_HISTORY),
            logs.map { it.phase },
        )
        assertEquals(
            WireEvent.HistoryEnd(3L, 3L),
            events.filterIsInstance<WireEvent.HistoryEnd>().single(),
        )
        assertTrue(session.historyDispatched)
    }

    @Test
    fun `实时日志从历史末尾续号`() {
        val session = session()
        session.begin(listOf(rec(0), rec(1)))
        session.tick(now)
        session.pushLive(rec(9))
        session.tick(now)

        val logs = logEvents()
        assertEquals(listOf(1L, 2L, 3L), logs.map { it.seq })
        assertEquals(LogTransferSession.PHASE_LIVE, logs.last().phase)
        assertEquals("m9", logs.last().record.message)
    }

    @Test
    fun `累计确认后历史判定完成并释放窗口`() {
        val session = session()
        session.begin((0 until 10).map(::rec))
        session.tick(now)
        assertEquals(10, session.pendingCount())

        session.onAck(4)
        assertEquals(4L, session.ackedSeq)
        assertEquals(6, session.pendingCount())
        assertFalse(session.historyAcked)

        session.onAck(10)
        assertEquals(0, session.pendingCount())
        assertTrue(session.historyAcked)
    }

    @Test
    fun `nak 从缺口处起重发且不改序号`() {
        val session = session()
        session.begin((0 until 5).map(::rec))
        session.tick(now)
        val firstRound = logEvents().map { it.seq }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), firstRound)

        session.onNak(3)
        val resent = logEvents().drop(5).map { it.seq }
        assertEquals(listOf(3L, 4L, 5L), resent)
        assertEquals(3L, session.retransmitCount)
    }

    @Test
    fun `超时未确认的包会重发`() {
        val session = session(retransmitAfterMs = 100)
        session.begin(listOf(rec(0)))
        session.tick(now)
        val before = logEvents().size

        now += 150
        session.tick(now)
        assertEquals(before + 1, logEvents().size)
        assertEquals(1L, session.retransmitCount)
    }

    @Test
    fun `重试次数用尽后 tick 返回 false`() {
        val session = session(retransmitAfterMs = 100, maxRetries = 2)
        session.begin(listOf(rec(0)))
        assertTrue(session.tick(now))

        now += 150
        assertTrue(session.tick(now))
        now += 150
        assertTrue(session.tick(now))
        now += 150
        assertFalse(session.tick(now))
    }

    @Test
    fun `窗口限制未确认包数量`() {
        val session = session(window = 4)
        session.begin((0 until 10).map(::rec))
        session.tick(now)
        assertEquals(4, session.pendingCount())
        assertEquals(4, logEvents().size)

        session.onAck(2)
        session.tick(now)
        assertEquals(4, session.pendingCount())
        assertEquals(6, logEvents().size)
    }

    @Test
    fun `关闭时发送 bye 携带发送与确认计数`() {
        val session = session()
        session.begin((0 until 2).map(::rec))
        session.tick(now)
        session.onAck(2)
        session.close()

        val bye = events.filterIsInstance<WireEvent.Bye>().single()
        assertEquals(2L, bye.sent)
        assertEquals(2L, bye.acked)
    }
}
