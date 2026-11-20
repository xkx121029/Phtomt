package com.phoneagent.domain.rules

import org.junit.Assert.assertEquals
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
}
