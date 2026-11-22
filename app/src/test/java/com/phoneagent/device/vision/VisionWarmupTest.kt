package com.phoneagent.device.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3B 按需预热的闸门行为。
 *
 * 要钉住的对照是**预加载次数**：
 * 改前「任务启动时无条件预加载」⇒ 任何任务 1 次；
 * 改后「本任务第一次真的要走 3B 才预热」⇒ 用不到 3B 的任务 0 次、用得到的最多 1 次。
 */
class VisionWarmupTest {

    @Test
    fun 全程用不到3B的任务一次都不预热() {
        val gate = VisionWarmup()
        var started = 0
        repeat(20) { if (gate.shouldStart(need3b = false, alreadyLoaded = false)) started++ }
        assertEquals("旧口径：任务启动就加载一次；新口径：0 次", 0, started)
    }

    @Test
    fun 第一次需要3B时预热一次且只一次() {
        val gate = VisionWarmup()
        var started = 0
        repeat(10) { if (gate.shouldStart(need3b = true, alreadyLoaded = false)) started++ }
        assertEquals(1, started)
    }

    @Test
    fun 前几步不需要3B_后几步才需要_只在需要那一步预热() {
        val gate = VisionWarmup()
        var started = 0
        repeat(5) { i -> if (gate.shouldStart(need3b = i >= 3, alreadyLoaded = false)) started++ }
        assertEquals(1, started)
    }

    @Test
    fun 模型已加载时不再预热() {
        val gate = VisionWarmup()
        assertFalse(gate.shouldStart(need3b = true, alreadyLoaded = true))
    }

    @Test
    fun 新任务重新获得一次预热机会() {
        val gate = VisionWarmup()
        assertTrue(gate.shouldStart(need3b = true, alreadyLoaded = false))
        assertFalse(gate.shouldStart(need3b = true, alreadyLoaded = false))
        gate.reset()
        assertTrue(gate.shouldStart(need3b = true, alreadyLoaded = false))
    }
}
