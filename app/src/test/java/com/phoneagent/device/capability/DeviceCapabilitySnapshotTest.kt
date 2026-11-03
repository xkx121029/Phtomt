package com.phoneagent.device.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备能力快照的真值表回归。
 *
 * 这张表的判错代价很直接：
 * - 把"开关已开但服务未连接"当成可用 → 执行层继续走无障碍，动作全部落空；
 * - 把"只有 Termux"当成完整 shell → 系统命令被丢进必然失败的通道；
 * - 截图能力漏判系统版本 → 低版本机型上每步都撞异常。
 */
class DeviceCapabilitySnapshotTest {

    private fun snap(
        a11yConnected: Boolean = true,
        a11yEnabled: Boolean = true,
        shizuku: Boolean = false,
        adb: Boolean = false,
        termux: Boolean = false,
        sdk: Int = 34,
        notification: Boolean = false,
        usage: Boolean = false,
        virtualDisplay: Boolean = false,
    ): DeviceCapabilitySnapshot = CapabilityProbe.capture(
        a11yConnected = a11yConnected,
        a11yEnabled = a11yEnabled,
        shizukuReady = shizuku,
        adbConnected = adb,
        termuxAvailable = termux,
        sdkInt = sdk,
        notificationAccess = notification,
        usageAccess = usage,
        virtualDisplaySupported = virtualDisplay,
        nowMillis = 0L,
    )

    @Test
    fun 无障碍在线时截图能力可用() {
        val s = snap()
        assertEquals(CapabilityState.AVAILABLE, s.stateOf(DeviceCapability.ACCESSIBILITY))
        assertEquals(CapabilityState.AVAILABLE, s.stateOf(DeviceCapability.SCREEN_CAPTURE))
        assertTrue(s.isUsable(DeviceCapability.SCREEN_CAPTURE))
    }

    @Test
    fun 开关已开但服务未连接判为降级而不是可用() {
        val s = snap(a11yConnected = false, a11yEnabled = true)
        assertEquals(CapabilityState.DEGRADED, s.stateOf(DeviceCapability.ACCESSIBILITY))
        // 服务不在线时截图一定不可用（不能把"开关开着"当成能截图）
        assertEquals(CapabilityState.UNAVAILABLE, s.stateOf(DeviceCapability.SCREEN_CAPTURE))
        assertTrue(s.reasonOf(DeviceCapability.ACCESSIBILITY).contains("服务未连接"))
    }

    @Test
    fun 开关未开时给出未开启文案() {
        val s = snap(a11yConnected = false, a11yEnabled = false)
        assertEquals(CapabilityState.UNAVAILABLE, s.stateOf(DeviceCapability.ACCESSIBILITY))
        assertEquals("无障碍服务未开启", s.reasonOf(DeviceCapability.ACCESSIBILITY))
    }

    @Test
    fun 低版本系统截图判为降级() {
        val s = snap(sdk = 28)
        assertEquals(CapabilityState.DEGRADED, s.stateOf(DeviceCapability.SCREEN_CAPTURE))
        assertTrue(s.reasonOf(DeviceCapability.SCREEN_CAPTURE).contains("系统版本"))
    }

    @Test
    fun shell可用性按Shizuku与Termux分层() {
        assertEquals(CapabilityState.UNAVAILABLE, snap().stateOf(DeviceCapability.SHELL))
        assertEquals(CapabilityState.DEGRADED, snap(termux = true).stateOf(DeviceCapability.SHELL))
        assertEquals(CapabilityState.AVAILABLE, snap(shizuku = true).stateOf(DeviceCapability.SHELL))
        assertEquals(CapabilityState.AVAILABLE, snap(adb = true).stateOf(DeviceCapability.SHELL))
        assertTrue(snap(termux = true).reasonOf(DeviceCapability.SHELL).contains("Shizuku"))
    }

    @Test
    fun 通知使用权与使用情况访问权独立判定() {
        val s = snap(notification = true, usage = false)
        assertEquals(CapabilityState.AVAILABLE, s.stateOf(DeviceCapability.NOTIFICATION_ACCESS))
        assertEquals(CapabilityState.UNAVAILABLE, s.stateOf(DeviceCapability.USAGE_ACCESS))
        assertEquals("使用情况访问权未开启", s.reasonOf(DeviceCapability.USAGE_ACCESS))
    }

    @Test
    fun 副屏依赖shell通道() {
        assertEquals(CapabilityState.UNAVAILABLE, snap().stateOf(DeviceCapability.VIRTUAL_DISPLAY))
        assertEquals(
            CapabilityState.AVAILABLE,
            snap(shizuku = true, virtualDisplay = true).stateOf(DeviceCapability.VIRTUAL_DISPLAY),
        )
    }

    @Test
    fun 未探测的语音助手为未知且不阻塞决策() {
        val s = snap()
        assertEquals(CapabilityState.UNKNOWN, s.stateOf(DeviceCapability.VOICE_ASSISTANT))
        assertTrue(s.isUsable(DeviceCapability.VOICE_ASSISTANT))
        assertEquals("", s.reasonOf(DeviceCapability.VOICE_ASSISTANT))
    }

    @Test
    fun 摘要包含全部能力且顺序稳定() {
        val s = snap(shizuku = true)
        val summary = s.summary()
        DeviceCapability.entries.forEach { capability ->
            assertTrue("摘要缺少 ${capability.label}", summary.contains(capability.label))
        }
        assertTrue(summary.contains("无障碍服务=可用"))
        assertTrue(summary.contains("Termux 命令环境=不可用"))
    }

    @Test
    fun 未登记能力按未知处理且不误报原因() {
        val s = DeviceCapabilitySnapshot(emptyMap(), 0L)
        assertEquals(CapabilityState.UNKNOWN, s.stateOf(DeviceCapability.ACCESSIBILITY))
        assertEquals("", s.reasonOf(DeviceCapability.ACCESSIBILITY))
        assertFalse(s.isFullyAvailable(DeviceCapability.ACCESSIBILITY))
    }
}
