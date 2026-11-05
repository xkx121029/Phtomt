package com.phoneagent.debug.logtransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连接触发：USB 调试连接与调试端接入的边沿语义。 */
class TransferTriggerTest {

    private val reasons = mutableListOf<String>()
    private val trigger = TransferTrigger { reasons += it }

    @Test
    fun `usb 调试连接的上升沿只触发一次`() {
        assertTrue(trigger.onUsbDebugChanged(true))
        assertEquals(listOf(TransferTrigger.REASON_USB), reasons)
        assertFalse(trigger.onUsbDebugChanged(true))
        assertEquals(1, reasons.size)
    }

    @Test
    fun `usb 断开后再次连接重新触发`() {
        assertTrue(trigger.onUsbDebugChanged(true))
        assertFalse(trigger.onUsbDebugChanged(false))
        assertTrue(trigger.onUsbDebugChanged(true))
        assertEquals(2, reasons.size)
    }

    @Test
    fun `首个调试端接入触发后续连接不再触发`() {
        assertTrue(trigger.onClientConnected())
        assertEquals(listOf(TransferTrigger.REASON_CLIENT), reasons)
        assertFalse(trigger.onClientConnected())
        assertEquals(1, reasons.size)
    }

    @Test
    fun `客户端全部断开后重新接入再次触发`() {
        assertTrue(trigger.onClientConnected())
        trigger.onClientDisconnected()
        assertEquals(0, trigger.activeClients())
        assertTrue(trigger.onClientConnected())
        assertEquals(2, reasons.size)
        assertEquals(1, trigger.activeClients())
    }

    @Test
    fun `两条信号独立且都能各自触发一次`() {
        assertTrue(trigger.onUsbDebugChanged(true))
        assertTrue(trigger.onClientConnected())
        assertEquals(
            listOf(TransferTrigger.REASON_USB, TransferTrigger.REASON_CLIENT),
            reasons,
        )
    }
}
