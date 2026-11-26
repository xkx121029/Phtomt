package com.phoneagent.coding.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Writer
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * JSON-lines 整行写入测试。
 *
 * 回归目标：`BufferedWriter` 的 write / newLine / flush 是三次独立同步，
 * 发送方来自不同线程时两行会拼成一行，host 侧 `JSON.parse` 报错。
 * [LineWriter] 用整行互斥保证每条消息边界完整。
 */
class LineWriterTest {

    @Test
    fun `并发写不会把两条消息拼成一行`() {
        val sink = ChaoticWriter()
        val writer = LineWriter(sink)
        val threads = 8
        val perThread = 50

        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread(start = true) {
                start.await()
                repeat(perThread) { writer.writeLine("T$t") }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        val lines = sink.content.split("\n").dropLast(1) // 每条消息都以 \n 结束
        assertEquals("消息条数必须等于写入次数", threads * perThread, lines.size)
        assertTrue("不能出现被拼在一起的脏行：$lines", lines.all { it.matches(Regex("T\\d+")) })
        (0 until threads).forEach { t ->
            assertEquals("T$t 的条数不对", perThread, lines.count { it == "T$t" })
        }
    }

    /**
     * 每次 write 先写一半、让出 CPU、再写另一半：不加整行互斥时这一步最容易暴露交错。
     */
    private class ChaoticWriter : Writer() {
        private val sb = StringBuilder()

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            val mid = len / 2
            synchronized(sb) { sb.append(cbuf, off, mid) }
            Thread.yield()
            synchronized(sb) { sb.append(cbuf, off + mid, len - mid) }
        }

        override fun flush() = Unit

        override fun close() = Unit

        val content: String
            get() = synchronized(sb) { sb.toString() }
    }
}
