package com.phoneagent.engine.runtime

import com.phoneagent.engine.runtime.EngineLimits.NO_PROGRESS_MS
import com.phoneagent.engine.runtime.LoopGuard.RepeatEscalation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主循环护栏状态机（重构阶段 A）。
 *
 * 这些护栏对应真机上踩过的坑：广告点了还在、AI 在同一控件反复点、任务长时间无进展。
 * 阶段 B 把主循环外迁前，先用真值表锁住它们的阈值与升级时机，避免迁移动刀时悄悄改掉语义。
 */
class LoopGuardTest {

    /** 可注入的假时钟：看门狗按毫秒推进，测试里不睡真实时间 */
    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun guard(clock: FakeClock) = LoopGuard(clock)

    // ---- 广告自动点击护栏 ----

    @Test
    fun 广告首次命中允许自动点击() {
        val g = guard(FakeClock())
        assertTrue(g.shouldAutoClickAd("100:200"))
        assertEquals(1, g.adClickStreakValue)
    }

    @Test
    fun 广告同一目标连续命中即停止自动点击() {
        val g = guard(FakeClock())
        assertTrue(g.shouldAutoClickAd("100:200"))
        assertFalse(g.shouldAutoClickAd("100:200"))
        assertFalse(g.shouldAutoClickAd("100:200"))
        assertEquals(3, g.adClickStreakValue)
    }

    @Test
    fun 广告换目标重新允许点击() {
        val g = guard(FakeClock())
        assertTrue(g.shouldAutoClickAd("100:200"))
        assertFalse(g.shouldAutoClickAd("100:200"))
        assertTrue(g.shouldAutoClickAd("140:200"))
        assertEquals(1, g.adClickStreakValue)
    }

    @Test
    fun 广告护栏随任务复位() {
        val g = guard(FakeClock())
        assertTrue(g.shouldAutoClickAd("100:200"))
        assertFalse(g.shouldAutoClickAd("100:200"))
        g.reset()
        assertTrue(g.shouldAutoClickAd("100:200"))
    }

    // ---- 端侧决策节流（阶段 B2） ----

    @Test
    fun 端侧决策未到阈值不提醒() {
        val g = guard(FakeClock())
        repeat(LoopGuard.LOCAL_DECISION_REVIEW_STREAK - 1) {
            assertFalse(g.onLocalDecision(true))
        }
        assertEquals(LoopGuard.LOCAL_DECISION_REVIEW_STREAK - 1, g.localDecisionStreakValue)
    }

    @Test
    fun 端侧决策达到阈值只提醒一次() {
        val g = guard(FakeClock())
        repeat(LoopGuard.LOCAL_DECISION_REVIEW_STREAK - 1) { g.onLocalDecision(true) }
        assertTrue(g.onLocalDecision(true))
        assertFalse(g.onLocalDecision(true))
    }

    @Test
    fun 云端决策打断端侧连续计数() {
        val g = guard(FakeClock())
        repeat(3) { g.onLocalDecision(true) }
        assertFalse(g.onLocalDecision(false))
        assertEquals(0, g.localDecisionStreakValue)
        repeat(LoopGuard.LOCAL_DECISION_REVIEW_STREAK - 1) { assertFalse(g.onLocalDecision(true)) }
        assertTrue(g.onLocalDecision(true))
    }

    @Test
    fun 达到阈值后请求强制云端复核() {
        val g = guard(FakeClock())
        repeat(LoopGuard.LOCAL_DECISION_REVIEW_STREAK) {
            assertFalse(g.shouldForceCloudReview())
            g.onLocalDecision(true)
        }
        assertTrue(g.shouldForceCloudReview())
    }

    @Test
    fun 端侧决策计数随任务复位() {
        val g = guard(FakeClock())
        repeat(LoopGuard.LOCAL_DECISION_REVIEW_STREAK) { g.onLocalDecision(true) }
        g.reset()
        assertEquals(0, g.localDecisionStreakValue)
        assertFalse(g.shouldForceCloudReview())
    }

    // ---- 同签名动作熔断 ----

    @Test
    fun 同签名动作达到四步注入纠偏提示() {
        val g = guard(FakeClock())
        val sig = "TAP|确认|100,200||"
        assertEquals(RepeatEscalation.NONE, g.onVerifiedAction(sig))
        assertEquals(RepeatEscalation.NONE, g.onVerifiedAction(sig))
        assertEquals(RepeatEscalation.NONE, g.onVerifiedAction(sig))
        assertEquals(RepeatEscalation.SWITCH_APPROACH, g.onVerifiedAction(sig))
        assertEquals(4, g.sameSignatureStreak)
        // 第 5 步不重复提示，第 6 步才升级
        assertEquals(RepeatEscalation.NONE, g.onVerifiedAction(sig))
        assertEquals(RepeatEscalation.ASK_USER, g.onVerifiedAction(sig))
        assertEquals(6, g.sameSignatureStreak)
        assertEquals(RepeatEscalation.NONE, g.onVerifiedAction(sig))
    }

    @Test
    fun 换动作签名重新计数() {
        val g = guard(FakeClock())
        repeat(4) { g.onVerifiedAction("TAP|A|1,2||") }
        assertEquals(4, g.sameSignatureStreak)
        assertEquals(RepeatEscalation.NONE, g.onVerifiedAction("TAP|B|1,2||"))
        assertEquals(1, g.sameSignatureStreak)
    }

    @Test
    fun 不参与计数的动作复位熔断状态() {
        val g = guard(FakeClock())
        val sig = "TAP|确认|100,200||"
        repeat(4) { g.onVerifiedAction(sig) }
        assertEquals(4, g.sameSignatureStreak)
        // wait/say 等动作生效时传 null：只复位，不计入同签名链
        g.onVerifiedAction(null)
        assertEquals(0, g.sameSignatureStreak)
        assertEquals(RepeatEscalation.NONE, g.onVerifiedAction(sig))
        assertEquals(1, g.sameSignatureStreak)
    }

    // ---- 长时间无进展看门狗 ----

    @Test
    fun 还没有完成步骤时不提醒无进展() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start()
        clock.now += NO_PROGRESS_MS * 2
        assertFalse(g.shouldNotifyNoProgress(completedSteps = 0))
    }

    @Test
    fun 未超过阈值不提醒无进展() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start()
        clock.now += NO_PROGRESS_MS
        assertFalse(g.shouldNotifyNoProgress(completedSteps = 1))
    }

    @Test
    fun 超过阈值提醒一次且不重复() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start()
        clock.now += NO_PROGRESS_MS + 1
        assertTrue(g.shouldNotifyNoProgress(completedSteps = 1))
        // 每个任务只提醒一次，继续卡住也不刷屏
        clock.now += NO_PROGRESS_MS
        assertFalse(g.shouldNotifyNoProgress(completedSteps = 1))
    }

    @Test
    fun 出现新进展后重新允许提醒() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start()
        clock.now += NO_PROGRESS_MS + 1
        assertTrue(g.shouldNotifyNoProgress(completedSteps = 1))
        // 又完成一步：计时重置，提醒资格恢复
        g.onProgress()
        assertFalse(g.shouldNotifyNoProgress(completedSteps = 2))
        clock.now += NO_PROGRESS_MS + 1
        assertTrue(g.shouldNotifyNoProgress(completedSteps = 2))
    }

    @Test
    fun 复位后看门狗重新计时() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start()
        clock.now += NO_PROGRESS_MS + 1
        assertTrue(g.shouldNotifyNoProgress(completedSteps = 1))
        g.reset()
        assertFalse(g.shouldNotifyNoProgress(completedSteps = 1))
    }
}
