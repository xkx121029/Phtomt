package com.phoneagent.engine.perf

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 阶段耗时聚合单测。
 *
 * 这里锁的是"账要记得对、任务边界要分得开"两件事：统计错了不会让任务失败，
 * 但会让后续优化照着假数据走，所以口径必须可验证。
 */
class StageTimingTest {

    @Before
    fun setUp() = StageTiming.reset()

    @After
    fun tearDown() = StageTiming.reset()

    @Test
    fun `同一阶段多次记账_次数与总耗时累加`() {
        StageTiming.measure(taskId = 1L, stage = "环境事实") { Thread.sleep(2) }
        StageTiming.measure(taskId = 1L, stage = "环境事实") { Thread.sleep(2) }

        val sample = StageTiming.samples().single()
        assertEquals("环境事实", sample.stage)
        assertEquals(2, sample.count)
        assertTrue("总耗时应覆盖两次休眠", sample.totalMs >= 4)
        assertEquals(sample.totalMs / 2, sample.avgMs)
    }

    @Test
    fun `不同阶段分开记账`() {
        StageTiming.measure(1L, "环境事实") { Thread.sleep(1) }
        StageTiming.measure(1L, "记忆与规则") { Thread.sleep(1) }

        assertEquals(listOf("环境事实", "记忆与规则"), StageTiming.samples().map { it.stage })
    }

    @Test
    fun `抛异常也要记账`() {
        // 失败路径"等了多久才失败"同样是决策延迟的一部分，不能只统计成功分支
        runCatching { StageTiming.measure(1L, "通道回退") { error("模拟执行异常") } }

        assertEquals(1, StageTiming.samples().single().count)
    }

    @Test
    fun `任务切换清空上一个任务的样本`() {
        StageTiming.measure(1L, "环境事实") { Thread.sleep(1) }
        StageTiming.measure(2L, "记忆与规则") { Thread.sleep(1) }

        // 换任务后只剩新任务的阶段：跨任务混算会把均值搅乱
        assertEquals(listOf("记忆与规则"), StageTiming.samples().map { it.stage })
        assertTrue(StageTiming.summaryText().contains("记忆与规则"))
        assertFalse(StageTiming.summaryText().contains("环境事实"))
    }

    @Test
    fun `汇总文本包含次数与均值`() {
        StageTiming.measure(1L, "应用清单") { Thread.sleep(2) }

        val text = StageTiming.summaryText()
        assertTrue(text, text.contains("应用清单 1 次"))
        assertTrue(text, text.contains("均"))
    }

    @Test
    fun `未记账时汇总为空`() {
        assertEquals("", StageTiming.summaryText())
        assertTrue(StageTiming.samples().isEmpty())
    }
}
