package com.phoneagent.shizuku.adb

import com.phoneagent.device.shell.AdbPhase
import com.phoneagent.device.shell.WirelessAdbStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbStateMachineTest {

    @Test
    fun 全流程状态迁移() {
        val m = WirelessAdbStateMachine()
        assertEquals(AdbPhase.UNPAIRED, m.current().phase)

        m.onDiscoverNeedsCode()
        assertEquals(AdbPhase.WAITING_CODE, m.current().phase)

        m.onPairingStart()
        assertEquals(AdbPhase.PAIRING, m.current().phase)

        m.onPairingSuccess()
        assertEquals(AdbPhase.PAIRED, m.current().phase)

        m.onAdbConnected()
        m.onBootStart()
        assertEquals(AdbPhase.BOOTING, m.current().phase)

        m.onBootSuccess()
        assertTrue(m.isReady())
        assertEquals(AdbPhase.READY, m.current().phase)
    }

    @Test
    fun 无线调试未开启直接失败且需用户操作() {
        val m = WirelessAdbStateMachine()
        m.onWirelessDebugDisabled()
        assertTrue(m.isFailed())
        assertTrue(m.current().isUserActionRequired)
    }

    @Test
    fun 断线标记为失败() {
        val m = WirelessAdbStateMachine()
        m.onAdbConnected()
        m.onAdbDisconnected()
        assertTrue(m.isFailed())
        assertTrue(m.current().message.isNotBlank())
    }

    @Test
    fun 配对失败复位后可重来() {
        val m = WirelessAdbStateMachine()
        m.onPairingFailed("码错误")
        assertTrue(m.isFailed())
        assertTrue(m.current().isUserActionRequired)

        m.reset()
        assertEquals(AdbPhase.UNPAIRED, m.current().phase)
        assertFalse(m.isFailed())
    }

    @Test
    fun 无线ADB连接即就绪可直接执行shell() {
        val m = WirelessAdbStateMachine()
        m.onAdbConnected()
        m.onAdbReady()
        assertTrue(m.isReady())
        assertEquals(AdbPhase.READY, m.current().phase)
        assertFalse(m.isFailed())
    }

    @Test
    fun Shizuku可选启动失败回到就绪不阻塞() {
        val m = WirelessAdbStateMachine()
        m.onAdbConnected()
        m.onAdbReady()
        m.onBootFailed("Shizuku 未安装")
        // Shizuku 可选：失败回到 READY，不置 FAILED
        assertTrue(m.isReady())
        assertEquals(AdbPhase.READY, m.current().phase)
        assertFalse(m.isFailed())
        assertFalse(m.current().isUserActionRequired)
    }

    @Test
    fun 启动失败不要求用户操作() {
        val m = WirelessAdbStateMachine()
        m.onBootFailed("超时")
        // 无线 ADB 通路下 Shizuku 启动失败仍视为可用（回 READY），不要求用户操作
        assertTrue(m.isReady())
        assertFalse(m.current().isUserActionRequired)
    }
}
