package com.phoneagent.engine.execution

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 应用名解析器缓存单测（性能改造：先量后改）。
 *
 * 真实 `queryIntentActivities` 是一次跨进程全量扫描，改造前每个 open_app 动作都要扫一遍。
 * 这里用可计数的假清单验证三件事：命中后不再查询、清单变了能自己刷新、
 * 以及"同名优先系统应用"的既有语义不能被缓存改坏。
 */
class AppNameResolverCacheTest {

    private class FakeQuery {
        var calls = 0
        val apps = mutableListOf(
            InstalledApp("浏览器", "com.quark.browser", isSystem = false),
            InstalledApp("浏览器", "com.android.browser", isSystem = true),
            InstalledApp("微信", "com.tencent.mm", isSystem = false),
        )

        fun query(): List<InstalledApp> {
            calls++
            return apps.toList()
        }
    }

    private val fake = FakeQuery()

    // context 只在默认查询里用到，这里注入了假查询，传一个宽松 mock 即可
    private val resolver = AppNameResolver(context = mockk<Context>(relaxed = true), queryInstalled = fake::query)

    @Test
    fun `同名应用重复解析_只查询一次清单`() {
        repeat(3) { assertEquals("com.tencent.mm", resolver.resolve("微信")) }

        assertEquals(1, fake.calls)
        assertEquals(1, resolver.queryCount)
    }

    @Test
    fun `同名候选优先系统应用_缓存不改变语义`() {
        // "浏览器"同时装着夸克与系统浏览器：仍然优先系统应用
        assertEquals("com.android.browser", resolver.resolve("浏览器"))
        assertEquals("com.android.browser", resolver.resolve("浏览器"))
    }

    @Test
    fun `包名直传不触发清单查询`() {
        assertEquals("com.tencent.mm", resolver.resolve("com.tencent.mm"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `首次未命中只查一次_不重复刷新`() {
        assertNull(resolver.resolve("不存在的应用"))

        assertEquals(1, fake.calls)
    }

    @Test
    fun `已有清单但未命中_刷新一次以覆盖任务中刚装的应用`() {
        // 先解析一个已装应用，把清单读进缓存
        assertEquals("com.tencent.mm", resolver.resolve("微信"))
        assertEquals(1, fake.calls)

        // 模拟"任务进行中刚装上"：清单变了，但缓存还是旧的
        fake.apps += InstalledApp("新装应用", "com.example.newapp", isSystem = false)

        assertEquals("com.example.newapp", resolver.resolve("新装应用"))
        // 第一次未命中触发一次刷新；刷新后才找到
        assertEquals(2, fake.calls)
    }

    @Test
    fun `模糊匹配仍然生效`() {
        assertEquals("com.tencent.mm", resolver.resolve("微 信"))
    }

    @Test
    fun `空白入参直接返回空`() {
        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("   "))
        assertNull(resolver.resolve(null))
        assertEquals(0, fake.calls)
    }
}
