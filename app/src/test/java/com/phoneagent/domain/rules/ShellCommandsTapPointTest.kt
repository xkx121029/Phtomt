package com.phoneagent.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 点击光标坐标反查单测。
 * 光标只在"点"上显示：input tap（点击/双击）与同点 swipe（长按）应返回坐标，
 * 真正的滑动、文本处理、raw 透传命令应返回 null。
 */
class ShellCommandsTapPointTest {

    @Test
    fun `tap返回坐标`() {
        assertEquals(500 to 800, ShellCommands.parseTapPoint("input tap 500 800"))
    }

    @Test
    fun `双击取第一个点`() {
        assertEquals(
            100 to 200,
            ShellCommands.parseTapPoint("input tap 100 200\ninput tap 100 200"),
        )
    }

    @Test
    fun `长按同点swipe返回坐标`() {
        assertEquals(300 to 400, ShellCommands.parseTapPoint("input swipe 300 400 300 400 1500"))
    }

    @Test
    fun `方向滑动返回null`() {
        assertNull(ShellCommands.parseTapPoint("input swipe 100 200 500 600 400"))
    }

    @Test
    fun `非点击命令返回null`() {
        assertNull(ShellCommands.parseTapPoint("input keyevent 4"))
        assertNull(ShellCommands.parseTapPoint("am broadcast -a x"))
        assertNull(ShellCommands.parseTapPoint(""))
        assertNull(ShellCommands.parseTapPoint("raw echo hello"))
    }

    @Test
    fun `非法数字返回null`() {
        assertNull(ShellCommands.parseTapPoint("input tap abc 800"))
    }
}