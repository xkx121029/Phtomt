package com.phoneagent.engine.runtime

import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.StepTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 环形缓冲并发写入测试。
 *
 * 回归目标：日志缓冲有多个并发写入方（引擎协程、AppLog 全局 sink 所在的 IO 线程、界面线程）。
 * 旧实现 `flow.value = (flow.value + entry).takeLast(cap)` 是读改写三步，两个线程同时追加时
 * 后写者会覆盖先写者，条目被静默吞掉。这里用"总量恰好等于上限"的方式让丢失无所遁形：
 * 一旦丢条目，最终条数必然小于上限（不会因为裁剪而掩盖）。
 */
class EngineStoreAppendTest {

    @Test
    fun `并发追加日志_总量不超过上限时一条都不丢`() {
        val store = EngineStore()
        val threads = 8
        val perThread = EngineLimits.MAX_LOGS / threads

        runConcurrently(threads, perThread) { t, i ->
            store.appendLog(log("t$t-$i"))
        }

        val logs = store.logs.value
        assertEquals(threads * perThread, logs.size)
        // 条数对但内容重复同样视为丢条目（说明某次追加覆盖了别人）
        assertEquals(threads * perThread, logs.map { it.message }.toSet().size)
    }

    @Test
    fun `并发追加对话_总量不超过上限时一条都不丢`() {
        val store = EngineStore()
        val threads = 8
        val perThread = EngineLimits.MAX_CONVERSATION / threads

        runConcurrently(threads, perThread) { t, i ->
            store.appendConversation(ConversationMessage("user", "t$t-$i", 1L))
        }

        val messages = store.conversation.value
        assertEquals(threads * perThread, messages.size)
        assertEquals(threads * perThread, messages.map { it.content }.toSet().size)
    }

    @Test
    fun `并发追加轨迹_总量不超过上限时一条都不丢`() {
        val store = EngineStore()
        val threads = 8
        val perThread = EngineLimits.MAX_TRACES / threads - 1

        runConcurrently(threads, perThread) { t, i ->
            store.appendTrace(StepTrace(step = t * perThread + i))
        }

        val traces = store.traces.value
        assertEquals(threads * perThread, traces.size)
        assertEquals(threads * perThread, traces.map { it.step }.toSet().size)
    }

    @Test
    fun `超过上限后只保留最新条目`() {
        val store = EngineStore()
        repeat(EngineLimits.MAX_LOGS + 5) { i -> store.appendLog(log("m$i")) }

        val logs = store.logs.value
        assertEquals(EngineLimits.MAX_LOGS, logs.size)
        assertEquals("m${EngineLimits.MAX_LOGS + 4}", logs.last().message)
        assertTrue(logs.none { it.message == "m0" })
    }

    private fun log(message: String) = AgentLog(
        timestamp = System.currentTimeMillis(),
        level = AgentLog.Level.INFO,
        message = message,
    )

    /** [threads] 个线程各追加 [perThread] 次，同时起跑以放大竞争窗口 */
    private fun runConcurrently(threads: Int, perThread: Int, append: (Int, Int) -> Unit) {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> append(t, i) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("并发追加超时", pool.awaitTermination(30, TimeUnit.SECONDS))
    }
}
