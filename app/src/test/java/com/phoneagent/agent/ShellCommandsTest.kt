package com.phoneagent.agent

import com.phoneagent.domain.rules.ShellCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AI 友好命令解析器 ShellCommands 的单元测试。
 * 覆盖：各命令 resolve、坐标三种格式（像素/百分比/比例）、键盘按键、
 * 转义、亮度范围校验、裸包名补全 launch、以及 parse/coord/coords 辅助方法。
 */
class ShellCommandsTest {

    // ---- resolve: 空/边界输入 ----

    @Test
    fun resolve_空命令_返回null() {
        assertNull(ShellCommands.resolve(""))
        assertNull(ShellCommands.resolve("   "))
    }

    @Test
    fun resolve_raw透传() {
        assertEquals("input swipe 1 2 3 4", ShellCommands.resolve("raw input swipe 1 2 3 4"))
        assertEquals("screencap -p /sdcard/x.png", ShellCommands.resolve("raw screencap -p /sdcard/x.png"))
    }

    @Test
    fun resolve_raw仅前缀_返回null() {
        assertNull(ShellCommands.resolve("raw"))
        assertNull(ShellCommands.resolve("raw  "))
    }

    @Test
    fun resolve_未知命令_返回null() {
        assertNull(ShellCommands.resolve("nonsense args here"))
    }

    // ---- resolve: 屏幕尺寸兜底 ----

    @Test
    fun resolve_坐标像素_屏幕尺寸兜底() {
        // 屏幕宽高 <= 0 时回退 1080x2400
        assertEquals("input tap 500 800", ShellCommands.resolve("tap 500 800", 0, 0))
    }

    // ---- resolve: 点击 ----

    @Test
    fun resolve_tap像素坐标() {
        assertEquals("input tap 500 800", ShellCommands.resolve("tap 500 800"))
    }

    @Test
    fun resolve_tap百分比坐标() {
        assertEquals("input tap 43 1200", ShellCommands.resolve("tap 4% 50%"))
    }

    @Test
    fun resolve_tap比例坐标() {
        assertEquals("input tap 43 1200", ShellCommands.resolve("tap 0.04 0.5"))
    }

    @Test
    fun resolve_tap单位置参数_返回null() {
        assertNull(ShellCommands.resolve("tap 500"))
    }

    @Test
    fun resolve_tap越界坐标_钳制到屏幕() {
        // 越界坐标钳制到有效像素上界 size-1（2026-09-14 修复：原为 coerceIn(0,dim)，会越界 1px）
        assertEquals("input tap 1079 2399", ShellCommands.resolve("tap 5000 9999"))
    }

    @Test
    fun resolve_tap坐标等于1_按像素处理() {
        // x=1 不应被误判为全屏比例
        assertEquals("input tap 1 1", ShellCommands.resolve("tap 1 1"))
    }

    // ---- resolve: 长按 / 双击 ----

    @Test
    fun resolve_lp长按() {
        assertEquals("input swipe 100 200 100 200 1500", ShellCommands.resolve("lp 100 200"))
    }

    @Test
    fun resolve_long_press别名() {
        assertEquals("input swipe 100 200 100 200 1500", ShellCommands.resolve("long_press 100 200"))
    }

    @Test
    fun resolve_dt双击() {
        assertEquals("input tap 100 200\ninput tap 100 200", ShellCommands.resolve("dt 100 200"))
    }

    // ---- resolve: 滑动 ----

    @Test
    fun resolve_sw滑动四坐标() {
        assertEquals("input swipe 10 20 30 40 400", ShellCommands.resolve("sw 10 20 30 40"))
    }

    @Test
    fun resolve_sw不足四坐标_返回null() {
        assertNull(ShellCommands.resolve("sw 10 20 30"))
    }

    @Test
    fun resolve_su上滑() {
        // h=2400，dist=600；y=800 → 800-600=200
        assertEquals("input swipe 100 800 100 200 400", ShellCommands.resolve("su 100 800"))
    }

    @Test
    fun resolve_sd下滑() {
        assertEquals("input swipe 100 800 100 1400 400", ShellCommands.resolve("sd 100 800"))
    }

    @Test
    fun resolve_sl左滑() {
        // w=1080，dist=270；x=500 → 500-270=230
        assertEquals("input swipe 500 100 230 100 400", ShellCommands.resolve("sl 500 100"))
    }

    @Test
    fun resolve_sr右滑() {
        assertEquals("input swipe 500 100 770 100 400", ShellCommands.resolve("sr 500 100"))
    }

    // ---- resolve: 按键 ----

    @Test
    fun resolve_key常见键() {
        assertEquals("input keyevent 4", ShellCommands.resolve("key BACK"))
        assertEquals("input keyevent 66", ShellCommands.resolve("key ENTER"))
        assertEquals("input keyevent 187", ShellCommands.resolve("key RECENT"))
        assertEquals("input keyevent 26", ShellCommands.resolve("key POWER"))
    }

    @Test
    fun resolve_key数字码() {
        assertEquals("input keyevent 66", ShellCommands.resolve("key 66"))
    }

    @Test
    fun resolve_key未知键_返回null() {
        assertNull(ShellCommands.resolve("key NOT_A_KEY"))
    }

    @Test
    fun resolve_back_home_recents() {
        assertEquals("input keyevent 4", ShellCommands.resolve("back"))
        assertEquals("input keyevent 3", ShellCommands.resolve("home"))
        assertEquals("input keyevent 187", ShellCommands.resolve("recents"))
    }

    @Test
    fun resolve_key空参数_返回null() {
        assertNull(ShellCommands.resolve("key"))
    }

    // ---- resolve: 文字 ----

    @Test
    fun resolve_text输入_去引号并转义() {
        assertEquals("input text hello\\ world", ShellCommands.resolve("text \"hello world\""))
    }

    @Test
    fun resolve_type别名() {
        assertEquals("input text 你好", ShellCommands.resolve("type 你好"))
    }

    @Test
    fun resolve_text空内容_返回null() {
        assertNull(ShellCommands.resolve("text \"\""))
    }

    // ---- resolve: 应用 / 系统 ----

    @Test
    fun resolve_launch启动应用() {
        assertEquals("monkey -p com.tencent.mm 1", ShellCommands.resolve("launch com.tencent.mm"))
    }

    @Test
    fun resolve_stop停止应用() {
        assertEquals("am force-stop com.tencent.mm", ShellCommands.resolve("stop com.tencent.mm"))
    }

    @Test
    fun resolve_am启动activity() {
        assertEquals("am start -n com.test/.MainActivity", ShellCommands.resolve("am com.test/.MainActivity"))
    }

    @Test
    fun resolve_brightness合法范围() {
        assertEquals("settings put system screen_brightness 128", ShellCommands.resolve("brightness 128"))
    }

    @Test
    fun resolve_brightness越界_返回null() {
        assertNull(ShellCommands.resolve("brightness 300"))
        assertNull(ShellCommands.resolve("brightness -1"))
    }

    @Test
    fun resolve_screenshot以及无参命令() {
        assertEquals("screencap -p /sdcard/hpa_screenshot.png", ShellCommands.resolve("screenshot"))
        assertEquals("svc wifi enable", ShellCommands.resolve("wifi_on"))
    }

    // ---- resolve: 裸包名补全 launch ----

    @Test
    fun resolve_裸包名_补全launch() {
        assertEquals("monkey -p com.tencent.mm 1", ShellCommands.resolve("com.tencent.mm"))
    }

    @Test
    fun resolve_带空格的不视为裸包名_返回null() {
        assertNull(ShellCommands.resolve("com.tencent.mm some args"))
    }

    // ---- parse ----

    @Test
    fun parse_命令与参数分离() {
        assertEquals("tap" to "500 800", ShellCommands.parse("tap 500 800"))
    }

    @Test
    fun parse_raw透传_返回null() {
        assertNull(ShellCommands.parse("raw input tap 1 2"))
    }

    @Test
    fun parse_空命令_返回null() {
        assertNull(ShellCommands.parse(""))
    }

    // ---- coord 单个双坐标 ----

    @Test
    fun coord_像素坐标() {
        assertEquals(100 to 200, ShellCommands.coord("100 200", 1080, 2400))
    }

    @Test
    fun coord_比例坐标() {
        assertEquals(108 to 1200, ShellCommands.coord("0.1 0.5", 1080, 2400))
    }

    @Test
    fun coord_参数不足_返回null() {
        assertNull(ShellCommands.coord("100", 1080, 2400))
    }

    // ---- coords 四坐标 ----

    @Test
    fun coords_四坐标像素() {
        assertEquals(listOf(10, 20, 30, 40), ShellCommands.coords("10 20 30 40", 1080, 2400))
    }

    @Test
    fun coords_混合比例坐标() {
        // x 用 w=1080，y 用 h=2400
        assertEquals(listOf(108, 240, 270, 480), ShellCommands.coords("0.1 0.1 0.25 0.2", 1080, 2400))
    }

    @Test
    fun coords_参数不足_返回null() {
        assertNull(ShellCommands.coords("10 20 30", 1080, 2400))
    }

    @Test
    fun coords_非法坐标_返回null() {
        assertNull(ShellCommands.coords("a b c d", 1080, 2400))
    }
}
