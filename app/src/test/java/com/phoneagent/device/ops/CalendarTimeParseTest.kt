package com.phoneagent.device.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日历时间解析。
 *
 * 这段逻辑原本内联在 [DeviceOpsChannel] 里，纯函数却没有测试，而它直接决定
 * 「用户说『明天九点』到底写成哪个时间戳」——一旦解析错，日程会建在错误的日子上。
 * 抽出 [CalendarOps.parseTime] 后在这里钉住支持的写法和边界。
 */
class CalendarTimeParseTest {

    private val ops = CalendarOps(shell = { null }, channelAdvice = { "" })

    @Test
    fun `纯数字按毫秒时间戳处理`() {
        assertEquals(1793817324000L, ops.parseTime("1793817324000"))
    }

    @Test
    fun `标准完整格式`() {
        // 2026-11-05 09:00 是本地时区，用同一套 SimpleDateFormat 反算，避免硬编码时区差异
        val ms = ops.parseTime("2026-11-05 09:00")
        assertNotNull(ms)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        assertEquals("2026-11-05 09:00", fmt.format(java.util.Date(ms!!)))
    }

    @Test
    fun `带秒的格式也能解析`() {
        val ms = ops.parseTime("2026-11-05 09:00:30")
        assertNotNull(ms)
    }

    @Test
    fun `只有日期时按当天零点`() {
        val ms = ops.parseTime("2026-11-05")
        assertNotNull(ms)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        assertEquals("2026-11-05 00:00", fmt.format(java.util.Date(ms!!)))
    }

    @Test
    fun `中文月日写法`() {
        val ms = ops.parseTime("11月5日 09:00")
        assertNotNull(ms)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms!! }
        assertEquals(10, cal.get(java.util.Calendar.MONTH)) // 0-based，11 月 = 10
        assertEquals(5, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `无法识别的文本返回null而不是抛异常`() {
        assertNull(ops.parseTime("下周三下午"))
        assertNull(ops.parseTime(""))
    }

    @Test
    fun `只给HHmm时必须是今天或明天_不会落到过去`() {
        val ms = ops.parseTime("09:00")
        assertNotNull(ms)
        // 结果必须晚于"现在减去一分钟"，允许跨天顺延
        val now = System.currentTimeMillis()
        org.junit.Assert.assertTrue("解析结果不应是过去的时刻", ms!! > now - 60_000L)
    }
}
