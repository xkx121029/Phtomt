package com.phoneagent.device.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏四态展示口径（设置页开关下面那行字）：
 * 未激活 Shizuku / 系统不支持 / 已启用 display=N / 失败原因。
 *
 * 这些文案直接决定用户"点开关之后能不能看懂发生了什么"，所以按态逐条钉住。
 */
class VirtualDisplayStatusTest {

    @Test
    fun 未激活Shizuku_优先于其它状态() {
        // 即使上一次是"系统不支持"，Shizuku 掉线时也要先提示通道问题，否则用户会去查设备
        val p = VirtualDisplayStatus.Unsupported("创建超时").present(shizukuReady = false)
        assertEquals("未激活 Shizuku", p.title)
        assertTrue(p.isFailure)
    }

    @Test
    fun 有活动副屏_显示具体displayId() {
        val p = VirtualDisplayStatus.Active(2).present(shizukuReady = true)
        assertEquals("已启用 display=2", p.title)
        assertFalse(p.isFailure)
    }

    @Test
    fun 系统不支持_展示原因可诊断() {
        val p = VirtualDisplayStatus.Unsupported("模拟副屏创建超时").present(shizukuReady = true)
        assertEquals("系统不支持", p.title)
        assertEquals("模拟副屏创建超时", p.detail)
        assertTrue(p.isFailure)
    }

    @Test
    fun 创建失败_展示失败原因() {
        val p = VirtualDisplayStatus.Failed("写入模拟显示设置失败：permission denied").present(shizukuReady = true)
        assertEquals("创建失败", p.title)
        assertTrue(p.detail.contains("permission denied"))
        assertTrue(p.isFailure)
    }

    @Test
    fun 就绪但还没创建_不是失败态() {
        val p = VirtualDisplayStatus.Idle.present(shizukuReady = true)
        assertEquals("已就绪", p.title)
        assertFalse(p.isFailure)
    }
}
