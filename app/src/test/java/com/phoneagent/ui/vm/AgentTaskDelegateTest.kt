package com.phoneagent.ui.vm

import com.phoneagent.engine.AgentEngine
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 执行域里少数不依赖引擎对象图的状态逻辑：
 * - `startAgent` 的空白守卫（空白任务不应起一次空跑）；
 * - 翻译缓存的上限与 LRU 淘汰（滚出视野再滚回来要能首帧命中，且长会话不能无限驻留）。
 *
 * 引擎用惰性 provider：空白守卫路径根本不应触达引擎，这里用计数断言把它钉住。
 */
class AgentTaskDelegateTest {

    private var engineBuilt = 0

    private fun delegate(engine: AgentEngine = mockk(relaxed = true)): AgentTaskDelegate =
        AgentTaskDelegate({ engineBuilt++; engine }, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `空白任务直接忽略_不拉起引擎`() {
        val engine = mockk<AgentEngine>(relaxed = true)
        val d = delegate(engine)

        d.startAgent("")
        d.startAgent("   ")
        d.startAgent("\n\t ")

        assertEquals("空白任务不应构建引擎", 0, engineBuilt)
        verify(exactly = 0) { engine.start(any()) }
    }

    @Test
    fun `非空任务原样透传给引擎`() {
        val engine = mockk<AgentEngine>(relaxed = true)
        val d = delegate(engine)

        d.startAgent("  打开设置  ")

        verify(exactly = 1) { engine.start("  打开设置  ") }
    }

    @Test
    fun `命中返回译文_未命中返回 null`() {
        val d = delegate()

        assertNull(d.cachedTranslation("hello"))
        d.putTranslation("hello", "你好")
        assertEquals("你好", d.cachedTranslation("hello"))
    }

    @Test
    fun `缓存上限 32 条_按 LRU 淘汰最久未访问的`() {
        val d = delegate()
        (1..32).forEach { d.putTranslation("k$it", "v$it") }
        (1..32).forEach { assertEquals("v$it", d.cachedTranslation("k$it")) }

        // 再读一次 k1，把它提到最近使用；此刻最久未访问的是 k2
        assertEquals("v1", d.cachedTranslation("k1"))
        d.putTranslation("k33", "v33")

        assertNull("最久未访问的 k2 应被淘汰", d.cachedTranslation("k2"))
        assertEquals("v1", d.cachedTranslation("k1"))
        assertEquals("v33", d.cachedTranslation("k33"))
    }

    @Test
    fun `超过上限时只保留最新 32 条`() {
        val d = delegate()
        (1..40).forEach { d.putTranslation("k$it", "v$it") }

        (1..8).forEach { assertNull("k$it 应已被淘汰", d.cachedTranslation("k$it")) }
        (9..40).forEach { assertEquals("v$it", d.cachedTranslation("k$it")) }
    }
}
