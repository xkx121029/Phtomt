package com.phoneagent.device.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 可启动应用解析规则单测（不依赖 Android 框架）。
 *
 * 这套「按显示名找包名」的规则原先在 DeviceOpsChannel（只做精确匹配）与
 * AppNameResolver（精确 + 模糊）各写一份，两处对同一句话可能给出不同结论；
 * 现在统一收口到 [LauncherCatalog.resolveByNameIn]。这里锁住三点既有语义：
 * 精确优先、模糊兜底、同分优先系统应用。
 */
class LauncherAppResolveTest {

    private val apps = listOf(
        LauncherApp("浏览器", "com.quark.browser", isSystem = false),
        LauncherApp("浏览器", "com.android.browser", isSystem = true),
        LauncherApp("微信", "com.tencent.mm", isSystem = false),
        LauncherApp("设置", "com.android.settings", isSystem = true),
    )

    @Test
    fun `精确匹配忽略首尾空白`() {
        assertEquals("com.tencent.mm", LauncherCatalog.resolveByNameIn(apps, "微信"))
        assertEquals("com.tencent.mm", LauncherCatalog.resolveByNameIn(apps, " 微信 "))
    }

    @Test
    fun `同名候选优先系统应用`() {
        // 同时装着夸克与系统浏览器：仍然优先系统应用
        assertEquals("com.android.browser", LauncherCatalog.resolveByNameIn(apps, "浏览器"))
    }

    @Test
    fun `精确未命中时做去空白的模糊匹配`() {
        assertEquals("com.tencent.mm", LauncherCatalog.resolveByNameIn(apps, "微 信"))
    }

    @Test
    fun `精确优先于模糊`() {
        // 「设置」精确命中系统设置，不会被更短的模糊候选抢走
        assertEquals("com.android.settings", LauncherCatalog.resolveByNameIn(apps, "设置"))
    }

    @Test
    fun `找不到返回空`() {
        assertNull(LauncherCatalog.resolveByNameIn(apps, "不存在的应用"))
        assertNull(LauncherCatalog.resolveByNameIn(apps, ""))
        assertNull(LauncherCatalog.resolveByNameIn(apps, "   "))
        assertNull(LauncherCatalog.resolveByNameIn(emptyList(), "微信"))
    }
}
