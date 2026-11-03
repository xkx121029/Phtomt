package com.phoneagent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用清单/显示名任务级缓存单测（性能改造：先量后改）。
 *
 * 锁的是"每任务只打一次 PackageManager"的口径：
 * 改造前清单与显示名都在决策热路径上反复查询，一旦缓存被绕过（或任务边界没清缓存），
 * 就会退回到每步一次跨进程扫描——这类退化不会报错，只会让任务整体变慢，所以必须用单测锁住。
 */
class AppInventoryTest {

    private class FakePm(private val apps: List<String>) {
        var appsCalls = 0
        var labelCalls = 0
        val labels = linkedMapOf("com.tencent.mm" to "微信", "com.android.settings" to "设置")

        fun fetchApps(): List<String> {
            appsCalls++
            return apps
        }

        fun fetchLabel(pkg: String): String {
            labelCalls++
            return labels[pkg].orEmpty()
        }
    }

    private val apps = listOf("微信(com.tencent.mm)", "设置(com.android.settings)")
    private val pm = FakePm(apps)
    private val inventory = AppInventory(pm::fetchApps, pm::fetchLabel)

    @Test
    fun `同一任务内多次取清单_只查询一次`() {
        repeat(5) { assertEquals(apps, inventory.apps()) }

        assertEquals(1, pm.appsCalls)
        assertEquals(1, inventory.appQueryCount)
    }

    @Test
    fun `复用同一份清单实例_不重复拼装`() {
        // 清单本身是不可变 List，直接复用实例（调用方拿到的是同一份数据，不需要每次重新拼）
        val first = inventory.apps()
        val second = inventory.apps()
        assertSame(first, second)
    }

    @Test
    fun `计数与清单共用一次查询`() {
        inventory.count()
        inventory.apps()
        inventory.count()

        assertEquals(1, pm.appsCalls)
    }

    @Test
    fun `同一包名多次取显示名_只查询一次`() {
        repeat(3) { assertEquals("微信", inventory.labelOf("com.tencent.mm")) }

        assertEquals(1, pm.labelCalls)
        assertEquals(1, inventory.labelQueryCount)
    }

    @Test
    fun `取不到显示名也缓存_空串不重复查询`() {
        // "查不到"再查一次还是查不到，重复 IPC 没有意义；缓存空串让调用方照旧按"名字不可用"处理
        assertEquals("", inventory.labelOf("com.unknown.app"))
        assertEquals("", inventory.labelOf("com.unknown.app"))

        assertEquals(1, pm.labelCalls)
    }

    @Test
    fun `空白包名不触发查询`() {
        assertEquals("", inventory.labelOf(""))
        assertEquals("", inventory.labelOf("   "))

        assertEquals(0, pm.labelCalls)
    }

    @Test
    fun `任务切换清缓存_重新查询`() {
        inventory.apps()
        inventory.labelOf("com.tencent.mm")

        inventory.reset()

        // 应用可能在两次任务之间被装上或卸掉：清缓存后必须重新取真实清单
        assertEquals(0, inventory.appQueryCount)
        assertEquals(0, inventory.labelQueryCount)
        inventory.apps()
        inventory.labelOf("com.tencent.mm")
        assertEquals(2, pm.appsCalls)
        assertEquals(2, pm.labelCalls)
        assertEquals(1, inventory.appQueryCount)
        assertEquals(1, inventory.labelQueryCount)
    }

    @Test
    fun `十二步任务_清单与显示名各只查一次_耗时远低于逐次查询`() {
        // 用 20ms 的假 PackageManager 模拟一次真实跨进程查询，对比"每步重查"与"任务级缓存"
        val slowPm = FakePm(apps)
        val slowFetchApps: () -> List<String> = { Thread.sleep(20); slowPm.fetchApps() }
        val slowFetchLabel: (String) -> String = { pkg -> Thread.sleep(20); slowPm.fetchLabel(pkg) }
        val slowInventory = AppInventory(fetchApps = slowFetchApps, fetchLabel = slowFetchLabel)
        val steps = 12

        // 改造前：每步决策都要清单（计数）与前台应用显示名
        val beforeT0 = System.nanoTime()
        repeat(steps) {
            slowFetchApps()
            slowFetchLabel("com.tencent.mm")
        }
        val beforeMs = (System.nanoTime() - beforeT0) / 1_000_000

        // 改造后：同一任务内命中缓存
        val afterT0 = System.nanoTime()
        repeat(steps) {
            slowInventory.count()
            slowInventory.labelOf("com.tencent.mm")
        }
        val afterMs = (System.nanoTime() - afterT0) / 1_000_000

        assertEquals(1, slowInventory.appQueryCount)
        assertEquals(1, slowInventory.labelQueryCount)
        println("模拟 12 步任务（每次跨进程查询 20ms）：改前 ${beforeMs}ms / 改后 ${afterMs}ms")
        // 缓存路径只剩 2 次真实查询（清单 + 显示名），必然远低于逐次查询
        assertTrue("改后 ${afterMs}ms 应远低于改前 ${beforeMs}ms", afterMs < beforeMs / 4)
    }
}
