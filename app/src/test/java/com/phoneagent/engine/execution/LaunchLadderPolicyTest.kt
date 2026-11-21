package com.phoneagent.engine.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动阶梯的三态定性。
 *
 * 回归目标（真机可复现，第五/第六条假成功）：无障碍拉起后 `awaitLaunched` 读不到页面
 * （无障碍断开 / Provider 取树超时），旧实现报 `VerifyResult(success = true, …)`，而同一个结果里
 * `ChannelStepResult.Delivered(confirmed = false)` 让降级文案说「未确认生效」。
 * 于是执行历史记 ✅、进度与检查点照存、失败计数每步清零，
 * 「连续 3 次失败请求用户介入」这条唯一的兜底永远不会触发。
 */
class LaunchLadderPolicyTest {

    private fun verdict(observed: Boolean?) = LaunchLadderPolicy.verdict(
        observed = observed,
        confirmedReason = "已启动应用: com.demo",
        unconfirmedReason = "已启动应用: com.demo（已交付，但读不到页面，未能确认前台；以下一步观察为准）",
        notDeliveredReason = "启动指令已发出但目标应用未到前台",
    )

    @Test
    fun `读不到页面时_不得报成功_但仍是已交付`() {
        val v = verdict(null)
        val result = requireNotNull(v.result) { "读不到页面不是「没交付」，必须给出结果" }
        assertFalse("读不到页面就不能声称已启动：${result.reason}", result.success)
        assertTrue("必须由结构化字段表达为未确认", result.unconfirmed)
        assertFalse("动作确实已下发，标成未交付会让下一级通道把应用再拉一次", result.notDelivered)
        assertEquals(Delivery.DELIVERED_UNCONFIRMED, result.delivery)
        // 下游：不清失败计数、不记成功进度（historyStatus 落 ⚠️ 而不是 ✅）
        assertEquals(VerifyRules.STATUS_UNVERIFIED, VerifyRules.historyStatus(result.delivery))
    }

    @Test
    fun `读不到页面时_通道阶梯也按已交付未确认处理`() {
        val step = verdict(null).step
        assertTrue("读不到页面不能升级到下一级通道：应用可能已经被拉起", step is ChannelStepResult.Delivered)
        assertFalse("没拿到页面证据就不是「已确认」", (step as ChannelStepResult.Delivered).confirmed)
    }

    @Test
    fun `确认到前台才算成功`() {
        val v = verdict(true)
        val result = requireNotNull(v.result)
        assertTrue(result.success)
        assertEquals(Delivery.EFFECTIVE, result.delivery)
        assertTrue((v.step as ChannelStepResult.Delivered).confirmed)
    }

    @Test
    fun `观察窗内没见到前台_是没交付_交给下一级通道`() {
        val v = verdict(false)
        assertNull("没交付不该产出验证结果，否则阶梯会以为这一级已经交付", v.result)
        assertTrue(v.step is ChannelStepResult.NotDelivered)
    }
}
