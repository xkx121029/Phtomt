package com.phoneagent.debug.logtransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** 真 socket 端到端：历史完整性、实时延迟、多连接与断线重连。 */
class LogServerCoreTest {

    private class TestFeed(private val history: List<LogRecord>) : LogFeed {
        private val listeners = CopyOnWriteArrayList<(LogRecord) -> Unit>()

        override fun historySnapshot(): List<LogRecord> = history

        override fun addListener(listener: (LogRecord) -> Unit): Closeable {
            listeners += listener
            return Closeable { listeners -= listener }
        }

        fun emit(record: LogRecord) {
            listeners.forEach { it(record) }
        }
    }

    private class WireClient(port: Int) : Closeable {
        private val socket = Socket().apply {
            soTimeout = 5_000
            connect(InetSocketAddress(LogServerCore.LOOPBACK, port), 3_000)
        }
        private val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

        fun next(): Map<String, Any?> {
            val line = reader.readLine() ?: throw AssertionError("设备端提前关闭连接")
            return LogJson.parseFlatObject(line) ?: throw AssertionError("无法解析：$line")
        }

        fun ack(seq: Long) = send("""{"t":"ack","seq":$seq}""")

        fun send(line: String) {
            synchronized(writer) {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class Harness(feed: LogFeed) {
        val statuses = CopyOnWriteArrayList<String>()
        val server = LogServerCore(
            port = 0,
            feed = feed,
            status = { statuses += it },
            deviceLabel = { "test-device" },
            sdk = 33,
            retransmitAfterMs = 2_000L,
        )

        init {
            assertTrue("服务端启动失败", server.start())
        }

        val port: Int get() = server.localPort

        fun awaitStatus(part: String, timeoutMs: Long = 2_000L): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (statuses.any { it.contains(part) }) return true
                Thread.sleep(50)
            }
            return false
        }

        fun close() = server.close()
    }

    private fun rec(i: Int) = LogRecord(1_000L + i, "INFO", "entry-$i")

    /** 读到 hist_end 为止；返回收到的历史条数与最大序号。重复包按重传忽略。 */
    private fun drainHistory(client: WireClient, expectedTotal: Long): Long {
        var expected = 1L
        var received = 0L
        while (true) {
            val ev = client.next()
            when (ev["t"]) {
                "log" -> {
                    val seq = ev["seq"] as Long
                    when {
                        seq < expected -> Unit // 重传的重复包
                        seq > expected -> fail("历史序号不连续：期望 $expected 实收 $seq")
                        else -> {
                            assertEquals("history", ev["phase"])
                            assertEquals("entry-${seq - 1}", ev["msg"])
                            expected++
                            received++
                            client.ack(seq)
                        }
                    }
                }
                "hist_end" -> {
                    assertEquals(expectedTotal, ev["total"])
                    assertEquals(expectedTotal, received)
                    return received
                }
                else -> fail("意外事件：${ev["t"]}")
            }
        }
    }

    private fun nextLog(client: WireClient): Map<String, Any?> {
        while (true) {
            val ev = client.next()
            if (ev["t"] == "log") return ev
            if (ev["t"] != "hist_end") fail("意外事件：${ev["t"]}")
        }
    }

    @Test
    fun `历史日志五千条按序号完整传输并给出完成提示`() {
        val feed = TestFeed((0 until 5000).map(::rec))
        val harness = Harness(feed)
        try {
            WireClient(harness.port).use { client ->
                val hello = client.next()
                assertEquals("hello", hello["t"])
                assertEquals(5000L, hello["total"])
                assertEquals("test-device", hello["device"])
                assertEquals(5000L, drainHistory(client, 5000L))
                // 保持连接等待设备侧打印完成提示（断开后主循环可能已退出）
                assertTrue(
                    "未输出历史完成提示：${harness.statuses}",
                    harness.awaitStatus("历史日志传输完成"),
                )
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `实时日志从产生到收到的延迟低于 100ms`() {
        val feed = TestFeed((0 until 20).map(::rec))
        val harness = Harness(feed)
        try {
            WireClient(harness.port).use { client ->
                client.next() // hello
                drainHistory(client, 20L)

                val live = LogRecord(System.currentTimeMillis(), "INFO", "live-1")
                val startedAt = System.currentTimeMillis()
                feed.emit(live)

                val ev = nextLog(client)
                val latency = System.currentTimeMillis() - startedAt
                assertEquals("live", ev["phase"])
                assertEquals(21L, ev["seq"])
                assertEquals("live-1", ev["msg"])
                assertTrue("实时延迟 ${latency}ms 超过 100ms", latency < 100L)
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `断线重连后重新拿到从 seq 1 开始的完整历史`() {
        val feed = TestFeed((0 until 100).map(::rec))
        val harness = Harness(feed)
        try {
            WireClient(harness.port).use { first ->
                val hello = first.next()
                assertEquals(100L, hello["total"])
                val ev = first.next()
                assertEquals(1L, ev["seq"])
                assertEquals("entry-0", ev["msg"])
                // 不确认直接断开：模拟传输中断线
            }
            Thread.sleep(100)

            WireClient(harness.port).use { second ->
                val hello = second.next()
                assertEquals(100L, hello["total"])
                assertEquals(100L, drainHistory(second, 100L))
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `两个调试端同时连接各自收到完整历史与实时日志`() {
        val feed = TestFeed((0 until 50).map(::rec))
        val harness = Harness(feed)
        val first = WireClient(harness.port)
        val second = WireClient(harness.port)
        try {
            // 读历史时两个会话各自独立推进，先读完 first 再读 second 不会互相影响
            assertEquals("hello", first.next()["t"])
            assertEquals("hello", second.next()["t"])
            assertEquals(50L, drainHistory(first, 50L))
            assertEquals(50L, drainHistory(second, 50L))

            feed.emit(LogRecord(System.currentTimeMillis(), "WARN", "broadcast"))
            val a = nextLog(first)
            val b = nextLog(second)
            assertEquals(51L, a["seq"])
            assertEquals(51L, b["seq"])
            assertEquals("broadcast", a["msg"])
            assertEquals("broadcast", b["msg"])
        } finally {
            first.close()
            second.close()
            harness.close()
        }
    }

    @Test
    fun `连接与断开都会更新客户端计数并输出状态`() {
        val feed = TestFeed(emptyList())
        val harness = Harness(feed)
        try {
            WireClient(harness.port).use { client ->
                assertEquals("hello", client.next()["t"])
                assertTrue(harness.awaitStatus("调试端已连接"))
                assertEquals(1, harness.server.activeClients)
            }
            assertTrue(harness.awaitStatus("传输结束"))
        } finally {
            harness.close()
        }
    }
}
