package com.phoneagent.engine.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 应用候选挑选单测（[pickPreferredPackage]）。
 *
 * 用户偏好「打开软件优先用系统软件」：AI 说"打开浏览器"这类**泛指类目**时，机器上往往
 * 同时装着系统浏览器与第三方浏览器，按安装顺序取第一个结果随机，因此候选里先挑系统应用。
 * 真实解析依赖 PackageManager 无法在纯 JVM 跑，故把这一段抽成纯函数单测。
 */
class AppNameResolverPickTest {

    @Test
    fun `候选挑选_优先系统自带应用`() {
        // 第三方排在前面，系统应用仍应胜出
        assertEquals(
            "com.android.browser",
            pickPreferredPackage(listOf("com.quark.browser" to false, "com.android.browser" to true)),
        )
    }

    @Test
    fun `候选挑选_无系统应用时取第一个`() {
        assertEquals(
            "com.tencent.mm",
            pickPreferredPackage(listOf("com.tencent.mm" to false, "com.tencent.mobileqq" to false)),
        )
    }

    @Test
    fun `候选挑选_多个系统应用取第一个`() {
        assertEquals(
            "com.android.chrome",
            pickPreferredPackage(listOf("com.android.chrome" to true, "org.mozilla.firefox" to true)),
        )
    }

    @Test
    fun `候选挑选_空列表返回null`() {
        assertNull(pickPreferredPackage(emptyList()))
    }
}