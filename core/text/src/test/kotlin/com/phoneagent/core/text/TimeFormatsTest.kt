package com.phoneagent.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [TimeFormats] 单测：钉住两件它自己声称的事。
 *
 * 1. **输出跟着 pattern 走**——缓存的是格式化器，不能把上一次的结果连时间戳一起缓存住；
 * 2. **Locale 参与缓存键**——运行时切换语言之后不能拿到旧 Locale 的实例。
 *
 * 断言刻意不写死具体年月日：格式化用默认时区，写死 "1970-01-01" 在东八区与 UTC 下会得到
 * 不同的日期，那种测试会在别的机器上红，而不是在为行为负责。
 */
class TimeFormatsTest {

    private val stamp = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    @Test
    fun `输出遵循给定的pattern`() {
        assertTrue(
            TimeFormats.format("yyyy/MM/dd HH:mm", stamp),
            Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}""").matches(TimeFormats.format("yyyy/MM/dd HH:mm", stamp)),
        )
        assertEquals(4, TimeFormats.format("yyyy", stamp).length)
    }

    @Test
    fun `重复调用同一pattern不会把上一次的时间戳缓存住`() {
        val first = TimeFormats.format("yyyy-MM-dd HH:mm:ss", stamp)
        val later = TimeFormats.format("yyyy-MM-dd HH:mm:ss", stamp + 86_400_000L)
        val again = TimeFormats.format("yyyy-MM-dd HH:mm:ss", stamp)
        assertNotEquals("相差一天却算出同一串，说明缓存住了结果而不是格式化器", first, later)
        assertEquals("同一输入两次调用必须一致", first, again)
    }

    @Test
    fun `不同locale拿到各自的文案而不是缓存的旧实例`() {
        val en = TimeFormats.format("MMMM", stamp, Locale.US)
        val zh = TimeFormats.format("MMMM", stamp, Locale.SIMPLIFIED_CHINESE)
        assertNotEquals("Locale 必须参与缓存键，否则切语言后仍是旧语言", en, zh)
        assertEquals("切回英文仍要是英文（缓存按 locale 分槽）", en, TimeFormats.format("MMMM", stamp, Locale.US))
    }
}
