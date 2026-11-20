package com.phoneagent.engine.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 兜底的定性判据：「已交付」不等于「已确认生效」。
 *
 * 回归目标（真机可复现）：无障碍掉线后通道阶梯切到系统命令，`input tap` 返回退出码 0，
 * 旧实现据此无条件返回 `success = true` + `Delivered(confirmed = true)`——执行历史里这一步是 ✅、
 * 进度与检查点都当它是前进的一格，而这一下可能打在别的控件上或空处，事后没有任何回读。
 * 下面的用例把「没有页面证据就不许算成功」钉成契约。
 */
class ShellFallbackPolicyTest {

    @Test
    fun `命令退出码0但页面没变_不得判定生效`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", "fp-before")

        assertFalse("退出码 0 只代表事件被注入，不能当生效：$verdict", verdict.success)
        assertFalse(verdict.confirmed)
        assertTrue("原因里必须写明未确认：${verdict.suffix}", verdict.suffix.contains("未确认"))
        // 已交付：命令确实下发过，外层不能因此换通道把同一个动作再执行一遍
        assertTrue(verdict.dispatched)
    }

    @Test
    fun `命令退出码0且回读到页面变化_才算确认生效`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", "fp-after")

        assertTrue(verdict.success)
        assertTrue(verdict.confirmed)
        assertEquals("fp-before", verdict.beforeFingerprint)
        assertEquals("fp-after", verdict.afterFingerprint)
    }

    @Test
    fun `兜底后读不到页面_按未确认回报而不是成功`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", null)

        assertFalse("读不到页面就没有证据：$verdict", verdict.success)
        assertTrue(verdict.dispatched)
        assertTrue("原因里要点名读不到页面：${verdict.suffix}", verdict.suffix.contains("读不到页面"))
        assertEquals("fp-before", verdict.beforeFingerprint)
    }

    @Test
    fun `兜底前读不到页面_拿不到基准就不得判成功`() {
        val verdict = ShellFallbackPolicy.judge(null, "fp-after")

        assertFalse("没有基准就没有可比对的证据：$verdict", verdict.success)
        assertTrue(verdict.suffix.contains("无法比对"))
    }

    @Test
    fun `空白指纹与读不到等价`() {
        assertFalse(ShellFallbackPolicy.judge("   ", "fp-after").success)
        assertFalse(ShellFallbackPolicy.judge("fp-before", "  ").success)
    }
}
