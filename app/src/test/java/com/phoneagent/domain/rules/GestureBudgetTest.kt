package com.phoneagent.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势时长预算：AI 传的 duration_ms 不可信，太短触发不了长按，太长会把整步卡住。
 */
class GestureBudgetTest {

    @Test
    fun 未指定时长走默认() {
        assertEquals(GestureBudget.DEFAULT_LONG_PRESS_MS, GestureBudget.holdMs(null))
        assertEquals(GestureBudget.DEFAULT_SWIPE_MS, GestureBudget.swipeMs(null))
    }

    @Test
    fun 非正数按时长走默认() {
        assertEquals(GestureBudget.DEFAULT_LONG_PRESS_MS, GestureBudget.holdMs(0))
        assertEquals(GestureBudget.DEFAULT_LONG_PRESS_MS, GestureBudget.holdMs(-5))
        assertEquals(GestureBudget.DEFAULT_SWIPE_MS, GestureBudget.swipeMs(0))
    }

    @Test
    fun 过短的长按抬到下限() {
        assertEquals(GestureBudget.MIN_LONG_PRESS_MS, GestureBudget.holdMs(200))
        assertEquals(GestureBudget.MIN_SWIPE_MS, GestureBudget.swipeMs(50))
    }

    @Test
    fun 抬到下限后必须跨过系统长按阈值() {
        // 系统长按阈值 ViewConfiguration.getLongPressTimeout() 默认 500ms。下限要是低于它，
        // 所有 <500ms 的请求都会被抬到一个仍然触发不了长按的时长，系统判成普通点击——
        // 表现是「长按了但只是点了一下」。副屏 input swipe x y x y <ms> 走同一条预算。
        assertTrue(GestureBudget.MIN_LONG_PRESS_MS >= SYSTEM_LONG_PRESS_TIMEOUT_MS)
        assertTrue(GestureBudget.holdMs(1) >= SYSTEM_LONG_PRESS_TIMEOUT_MS)
        // 这条在旧实现（下限 400）上必然红：400ms 的请求被"抬"成 400ms，一步没跨过阈值
        assertTrue(GestureBudget.holdMs(400) >= SYSTEM_LONG_PRESS_TIMEOUT_MS)
        assertEquals(GestureBudget.MIN_LONG_PRESS_MS, GestureBudget.holdMs(400))
        // 留余量吸收 ROM 差异与计时抖动
        assertEquals(600L, GestureBudget.MIN_LONG_PRESS_MS)
    }

    @Test
    fun 过长的时长压到上限() {
        // 长按 60 秒会让执行链 60 秒不观察页面，期间状态早已过期
        assertEquals(GestureBudget.MAX_LONG_PRESS_MS, GestureBudget.holdMs(60_000))
        assertEquals(GestureBudget.MAX_SWIPE_MS, GestureBudget.swipeMs(60_000))
    }

    @Test
    fun 合理区间内原样透传() {
        assertEquals(800L, GestureBudget.holdMs(800))
        assertEquals(1500L, GestureBudget.holdMs(1500))
        assertEquals(500L, GestureBudget.swipeMs(500))
    }

    private companion object {
        /** 系统长按判定阈值（`ViewConfiguration.getLongPressTimeout()` 默认值） */
        const val SYSTEM_LONG_PRESS_TIMEOUT_MS = 500L
    }
}
