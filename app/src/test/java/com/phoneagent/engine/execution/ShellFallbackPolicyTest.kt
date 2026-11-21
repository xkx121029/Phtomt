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

        assertFalse("退出码 0 只代表事件被注入，不能当生效：$verdict", verdict.confirmed)
        assertTrue("原因里必须写明未能确认：${verdict.suffix}", verdict.suffix.contains("未能确认"))
    }

    @Test
    fun `命令退出码0且回读到页面变化_才算确认生效`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", "fp-after")

        assertTrue(verdict.confirmed)
        assertEquals("fp-before", verdict.beforeFingerprint)
        assertEquals("fp-after", verdict.afterFingerprint)
    }

    @Test
    fun `兜底后读不到页面_按未确认回报而不是成功`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", null)

        assertFalse("读不到页面就没有证据：$verdict", verdict.confirmed)
        assertTrue("原因里要点名读不到页面：${verdict.suffix}", verdict.suffix.contains("读不到页面"))
        assertEquals("fp-before", verdict.beforeFingerprint)
    }

    @Test
    fun `兜底前读不到页面_拿不到基准就不得判成功`() {
        val verdict = ShellFallbackPolicy.judge(null, "fp-after")

        assertFalse("没有基准就没有可比对的证据：$verdict", verdict.confirmed)
        assertTrue(verdict.suffix.contains("无法比对"))
    }

    @Test
    fun `空白指纹与读不到等价`() {
        assertFalse(ShellFallbackPolicy.judge("   ", "fp-after").confirmed)
        assertFalse(ShellFallbackPolicy.judge("fp-before", "  ").confirmed)
    }

    @Test
    fun `兜底后读不到页面_结构上标为未确认`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", null)

        assertFalse(verdict.confirmed)
        assertTrue("「没看见」必须与「看见了没变化」在结构上分开：$verdict", verdict.unconfirmed)
    }

    @Test
    fun `兜底前读不到页面_同样按未确认处理`() {
        val verdict = ShellFallbackPolicy.judge(null, "fp-after")

        assertTrue("没有基准不等于没生效，只是没资格下结论：$verdict", verdict.unconfirmed)
    }

    @Test
    fun `页面没变化_是看到了没生效而不是未确认`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", "fp-before")

        assertFalse(verdict.confirmed)
        assertFalse("读到了页面就有证据，不能标成未确认：$verdict", verdict.unconfirmed)
    }

    @Test
    fun `确认生效时_既不是未确认也不带未确认后缀`() {
        val verdict = ShellFallbackPolicy.judge("fp-before", "fp-after")

        assertTrue(verdict.confirmed)
        assertFalse(verdict.unconfirmed)
        assertFalse(verdict.suffix.contains("未能确认"))
    }

    // ---- 多命令序列（TYPE_TEXT = input tap + input text）的交付定性 ----

    private val tapAndType = listOf("input tap 540 1200", "input text hello")

    @Test
    fun `序列全部下发_交给页面回读判生效`() {
        val outcome = ShellFallbackPolicy.sequenceOutcome(tapAndType, tapAndType, null)
        assertEquals(ShellFallbackPolicy.SequenceOutcome.Issued, outcome)
    }

    @Test
    fun `序列第一条就失败_判未交付可以换通道`() {
        val outcome = ShellFallbackPolicy.sequenceOutcome(tapAndType, emptyList(), "shell 通道无响应")
        assertTrue("一条都没出去才允许重试：$outcome", outcome is ShellFallbackPolicy.SequenceOutcome.NothingIssued)
    }

    @Test
    fun `序列中途失败_判部分交付而不是未交付`() {
        // 回归目标（真机可复现）：`input tap` 成功、`input text` 失败时，旧实现报 notDelivered=true，
        // 外层于是原样重跑整条序列——已经点下去的那一次被反复执行，输入框焦点被打乱。
        val outcome = ShellFallbackPolicy.sequenceOutcome(
            tapAndType, listOf("input tap 540 1200"), "input: command not found",
        )
        val partial = outcome as? ShellFallbackPolicy.SequenceOutcome.Partial
            ?: return org.junit.Assert.fail("中途失败必须判为部分交付：$outcome")
        assertEquals("已下发的那一条要如实记下来（重试时不得再执行）", listOf("input tap 540 1200"), partial.issued)
        assertTrue("原因里要点名已执行的部分：${partial.reason}", partial.reason.contains("input tap 540 1200"))
        assertTrue("原因里要点名失败的部分：${partial.reason}", partial.reason.contains("input text hello"))
        assertTrue("原因里要点名失败原因：${partial.reason}", partial.reason.contains("command not found"))
        assertTrue("不得再重试整条序列：${partial.reason}", partial.reason.contains("不再重试"))
    }
}
