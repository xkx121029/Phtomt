package com.phoneagent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 待执行任务队列测试。
 *
 * 这里的并发用例是回归保护：队列原先直接对 MutableStateFlow 做「读—改—写」，
 * 多线程同时入队时后写的会覆盖先写的，任务被静默吞掉（提交了却不执行）。
 */
class PendingTaskQueueTest {

    @Test
    fun 入队后按先进先出取出() {
        val queue = PendingTaskQueue()
        queue.enqueue("任务A")
        queue.enqueue("任务B")

        assertEquals(listOf("任务A", "任务B"), queue.items.value)
        assertEquals("任务A", queue.poll())
        assertEquals(listOf("任务B"), queue.items.value)
        assertEquals("任务B", queue.poll())
    }

    @Test
    fun 空队列取出返回null且不报错() {
        val queue = PendingTaskQueue()
        assertNull(queue.poll())
        assertNull(queue.poll())
        assertFalse(queue.isNotEmpty())
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun 取空后队列为空() {
        val queue = PendingTaskQueue()
        queue.enqueue("唯一任务")
        assertTrue(queue.isNotEmpty())
        assertEquals("唯一任务", queue.poll())
        assertFalse(queue.isNotEmpty())
    }

    @Test
    fun 并发入队不丢任务() {
        val queue = PendingTaskQueue()
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> queue.enqueue("任务-$t-$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("并发入队未在超时内完成", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        // 全部入队成功：一条都不能少
        assertEquals(threads * perThread, queue.items.value.size)

        // 逐条取出：取出总数与入队一致，且互不重复
        val polled = mutableListOf<String>()
        while (true) {
            val next = queue.poll() ?: break
            polled.add(next)
        }
        assertEquals(threads * perThread, polled.size)
        assertEquals(threads * perThread, polled.toSet().size)
        assertFalse(queue.isNotEmpty())
    }

    @Test
    fun 边入队边取出不重复不丢任务() {
        val queue = PendingTaskQueue()
        val total = 500
        val producer = Thread { repeat(total) { queue.enqueue("任务-$it") } }
        val consumed = mutableListOf<String>()

        producer.start()
        // 消费者与生产者并发：在队列短暂为空时允许 poll 返回 null（退出条件以「生产者已结束且队列已空」为准）
        while (producer.isAlive || queue.isNotEmpty()) {
            queue.poll()?.let { consumed.add(it) }
        }
        producer.join()

        assertEquals(total, consumed.size)
        assertEquals(total, consumed.toSet().size)
    }
}