package com.phoneagent.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StartupTrace.span` 的契约：它是包在冷启动关键路径上的计时代码，
 * **只许打点，不许改变被包代码的行为**。
 *
 * 为什么这里必须有测试（不写就是空跑）：`:core:perf` 是从 `:app` 切出来的模块，
 * 如果测试留在 `:app`，`:core:perf:testDebugUnitTest` 会是 NO-SOURCE ——
 * 任务成功退出但一条用例都没跑，CI 看起来绿。
 */
class StartupTraceTest {

    @Test
    fun `span 原样返回被包代码的结果`() {
        val result = StartupTrace.span("测试段落") { 6 * 7 }
        assertEquals(42, result)
    }

    @Test
    fun `span 不吞异常`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            StartupTrace.span("会抛的段落") { throw IllegalStateException("boom") }
        }
        assertEquals("boom", thrown.message)
    }

    /** 打点本身不能产生累积副作用：连续调用后时间仍是单调不减的 */
    @Test
    fun `elapsed 单调不减`() {
        val first = StartupTrace.elapsed()
        val second = StartupTrace.elapsed()
        assertTrue("elapsed 必须单调不减（$first → $second）", second >= first)
    }

    @Test
    fun `mark 与 threadName 可以直接调用`() {
        StartupTrace.mark("测试点")
        assertTrue(StartupTrace.threadName().isNotEmpty())
    }
}
