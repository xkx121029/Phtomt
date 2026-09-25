package com.phoneagent.domain.rules

import com.phoneagent.domain.rules.ShellCommands.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * shell 通道动作形态反查单测。
 *
 * 光标有三形态，反查也必须分清三种：点击（input tap）、长按（起终点相同的 swipe）、
 * 滑动（起终点不同的 swipe）。按键、文本、raw 透传等非触摸命令返回 null。
 */
class ShellCommandsMotionTest {

    @Test
    fun `tap返回点击`() {
        assertEquals(Motion.Tap(500, 800), ShellCommands.parseMotion("input tap 500 800"))
    }

    @Test
    fun `双击取第一个点`() {
        assertEquals(
            Motion.Tap(100, 200),
            ShellCommands.parseMotion("input tap 100 200\ninput tap 100 200"),
        )
    }

    @Test
    fun `长按同点swipe返回长按`() {
        assertEquals(
            Motion.LongPress(300, 400, 1500),
            ShellCommands.parseMotion("input swipe 300 400 300 400 1500"),
        )
    }

    @Test
    fun `方向滑动返回起止点`() {
        assertEquals(
            Motion.Swipe(100, 200, 500, 600, 400),
            ShellCommands.parseMotion("input swipe 100 200 500 600 400"),
        )
    }

    @Test
    fun `非触摸命令返回null`() {
        assertNull(ShellCommands.parseMotion("input keyevent 4"))
        assertNull(ShellCommands.parseMotion("am broadcast -a x"))
        assertNull(ShellCommands.parseMotion(""))
        assertNull(ShellCommands.parseMotion("raw echo hello"))
    }

    @Test
    fun `非法数字返回null`() {
        assertNull(ShellCommands.parseMotion("input tap abc 800"))
        assertNull(ShellCommands.parseMotion("input swipe 100 200 500"))
    }
}