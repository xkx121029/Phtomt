package com.phoneagent.provider.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无线调试状态机与「shell 能力」的口径回归。
 *
 * 回归目标（R2 审计 P1-3）：状态机曾在"会话连上"与"server 拉起失败"两种情况下都返回
 * [AdbPhase.READY]（文案「可直接执行 shell」「引导会话仍可用」）。主应用侧把 READY 当作
 * "shell 可执行"，于是把能力快照报成 SHELL_ADB=AVAILABLE、把授权模式升到 SHIZUKU，
 * 命令最后派进只走 Shizuku binder 的 execShizuku 再失败——用户看到"已就绪"加一串错误。
 *
 * 契约：READY 只表示**内置 Shizuku 已就绪**，这是唯一能执行 shell 的阶段。
 * 会话连上 / 拉起失败都是"没有可执行 shell"，不能再出现在 READY 上。
 */
class WirelessAdbStateMachineTest {

    @Test
    fun `会话连上不等于可执行-shell`() {
        val machine = WirelessAdbStateMachine()

        machine.onPairingSuccess()
        machine.onAdbConnected()

        assertEquals(AdbPhase.PAIRED, machine.current().phase)
        assertFalse("会话连上不能报 READY", machine.isReady())
        assertFalse(machine.current().message.contains("执行 shell"))
    }

    @Test
    fun `拉起失败如实报失败而不是回到就绪`() {
        val machine = WirelessAdbStateMachine()

        machine.onBootStart()
        machine.onBootFailed("shizuku server 启动超时")

        assertEquals(AdbPhase.FAILED, machine.current().phase)
        assertFalse(machine.isReady())
        assertTrue(
            "原因要点名 shell 不可用：${machine.current().message}",
            machine.current().message.contains("shell 命令不可用"),
        )
    }

    @Test
    fun `只有-shizuku-就绪才进入-ready`() {
        val direct = WirelessAdbStateMachine()
        direct.onShizukuDirectReady()
        assertEquals(AdbPhase.READY, direct.current().phase)
        assertTrue(direct.isReady())

        val booted = WirelessAdbStateMachine()
        booted.onBootStart()
        booted.onBootSuccess()
        assertEquals(AdbPhase.READY, booted.current().phase)
        assertTrue(booted.isReady())
    }

    @Test
    fun `除-shizuku-就绪外的任何事件都进不了-ready`() {
        // 把"READY = 可执行 shell"钉成全集判据：只要还有一个非 Shizuku 就绪的事件能走到 READY，
        // 主应用那边就会把它读成 shell 能力（能力快照 / 授权模式 / 通道候选全跟着错）
        val nonShizukuEvents = listOf<Pair<String, WirelessAdbStateMachine.() -> Unit>>(
            "无线调试未开启" to { onWirelessDebugDisabled() },
            "发现待配对" to { onDiscoverNeedsCode() },
            "配对中" to { onPairingStart() },
            "配对成功" to { onPairingSuccess() },
            "会话已连接" to { onAdbConnected() },
            "正在拉起" to { onBootStart() },
            "拉起失败" to { onBootFailed("启动超时") },
            "配对端口未开" to { onPairingPortOff() },
            "配对失败" to { onPairingFailed("码错误") },
            "会话断开" to { onAdbDisconnected() },
        )
        for ((label, event) in nonShizukuEvents) {
            val machine = WirelessAdbStateMachine()
            machine.event()
            assertFalse("「$label」之后不能是 READY", machine.isReady())
        }
    }
}
