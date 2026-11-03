package com.phoneagent.device.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时钟直达通道的纯函数测试（不启动 Android）：挑选优先级、请求字段归一化、失败文案。
 * ClockChannel 本身要 Context/PackageManager，无法在纯 JVM 里跑；能剥出来的判定逻辑都剥到这里。
 */
class ClockChannelTest {

    @Test
    fun 挑选_按优先表命中第一个可解析的() {
        val picked = ClockTargetPicker.pick(
            listOf(
                "com.oplus.alarmclock" to false,
                "com.coloros.alarmclock" to true,
                "com.android.deskclock" to true,
            ),
        )
        assertEquals("com.coloros.alarmclock", picked)
    }

    @Test
    fun 挑选_无任何可解析_没有时钟应用() {
        assertNull(ClockTargetPicker.pick(listOf("com.miui.clock" to false)))
        assertNull(ClockTargetPicker.pick(emptyList()))
        // 失败文案必须明确说"没有可用的时钟应用"，不能静默失败
        assertTrue(ClockPackages.NO_CLOCK_MESSAGE.contains("没有可用的时钟应用"))
        assertTrue(ClockPackages.NO_CLOCK_MESSAGE.contains("open_app"))
    }

    @Test
    fun 优先表_覆盖主流厂商且无重复() {
        assertEquals(ClockPackages.PREFERRED.size, ClockPackages.PREFERRED.toSet().size)
        assertTrue("com.coloros.alarmclock" in ClockPackages.PREFERRED)
        assertTrue("com.android.deskclock" in ClockPackages.PREFERRED)
    }

    @Test
    fun 闹钟请求_空标签与空重复归一化为null() {
        val req = ClockRequests.alarm(7, 30, emptyList(), "   ")
        assertNull(req.label)
        assertNull(req.repeatDays)
        assertEquals(7, req.hour)
        assertEquals(30, req.minute)
    }

    @Test
    fun 倒计时请求_保留标签() {
        val req = ClockRequests.timer(1800, "煮面")
        assertEquals(1800L, req.seconds)
        assertEquals("煮面", req.label)
    }
}
