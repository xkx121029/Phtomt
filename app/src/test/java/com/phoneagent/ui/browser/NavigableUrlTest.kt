package com.phoneagent.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地址栏输入的解析（UX-13 / UI-05）。
 *
 * 这条判据决定「手动打开的浏览器」能不能真的用起来：完整网址、省略协议的域名、
 * 想搜的词三种输入都要落到一个可加载的地址上；不认识的 scheme 一律不能当网址执行。
 */
class NavigableUrlTest {

    @Test
    fun `空输入不发起请求`() {
        assertNull(navigableUrl(""))
        assertNull(navigableUrl("   "))
    }

    @Test
    fun `省略协议的域名补 https`() {
        assertEquals("https://example.com", navigableUrl("example.com"))
        assertEquals("https://www.bing.com/search?q=1", navigableUrl("www.bing.com/search?q=1"))
    }

    @Test
    fun `完整网址原样使用并忽略首尾空白`() {
        assertEquals("https://example.com/a?b=1", navigableUrl("  https://example.com/a?b=1 "))
        assertEquals("http://192.168.1.1:8080", navigableUrl("http://192.168.1.1:8080"))
    }

    @Test
    fun `带空格的输入当搜索词`() {
        assertEquals(
            "https://www.bing.com/search?q=%E6%80%8E%E4%B9%88+%E5%85%B3%E9%97%AD%E9%80%9A%E7%9F%A5",
            navigableUrl("怎么 关闭通知"),
        )
    }

    /** 安全边界：地址栏是用户手打的入口，javascript: 之类不能被当成网址执行 */
    @Test
    fun `非 http scheme 落回搜索而不是执行`() {
        val result = navigableUrl("javascript:alert(1)")
        assertEquals(true, result!!.startsWith("https://www.bing.com/search?q="))
    }

    @Test
    fun `纯词条当搜索词`() {
        assertEquals("https://www.bing.com/search?q=%E6%96%B0%E9%97%BB", navigableUrl("新闻"))
    }
}
