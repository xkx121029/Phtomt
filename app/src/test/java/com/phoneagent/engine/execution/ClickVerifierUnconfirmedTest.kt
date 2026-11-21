package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「读不到页面」的结构化判定。
 *
 * 这类结果过去一路返回 `success = true`：执行历史记 `verified_success`（UI 上是 ✅ 已确认）、
 * `recordProgress` / `saveCheckpoint` 照存、`consecutiveFailures` 每步清零，
 * 于是「连续 3 次失败请求用户介入」这条唯一的兜底永远不会触发。
 * 现在它是一个独立状态 [Delivery.DELIVERED_UNCONFIRMED]——既不是成功，也没有证据说失败。
 */
class ClickVerifierUnconfirmedTest {

    private fun page(toggled: Boolean) = ScreenSnapshot(
        packageName = "com.android.settings",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = listOf(switchWidget(toggled)),
    )

    private fun switchWidget(toggled: Boolean) = UiElement(
        index = 0,
        className = "android.widget.Switch",
        type = "Switch",
        text = "WLAN",
        isSelected = toggled,
        x = 540,
        y = 300,
        left = 100,
        top = 260,
        right = 980,
        bottom = 340,
        clickable = true,
        viewId = "android:id/switch_widget",
    )

    @Test
    fun `取树超时时_不得记成成功`() = runTest {
        val verifier = ClickVerifier(capture = { null })
        val r = verifier.confirm(page(false), switchWidget(false), "点击")
        assertFalse("读不到页面不该记为成功（既不确认生效，也没有证据说失败）", r.success)
        assertTrue("必须由结构化字段表达为未确认", r.unconfirmed)
        assertEquals(Delivery.DELIVERED_UNCONFIRMED, r.delivery)
        assertEquals(VerifyRules.STATUS_UNVERIFIED, VerifyRules.historyStatus(r.success))
        // 未确认不重试：动作已经下发，原样重放就是重复副作用
        assertFalse(VerifyRules.shouldRetry(ActionType.CLICK, r.delivery, r.structural, 1, true))
    }

    @Test
    fun `滑动输入链路取树超时时_同样不算成功`() = runTest {
        val executor = VerifiedClickExecutor(capture = { null })
        val r = executor.executeAndVerify(page(false), switchWidget(false)) { true }
        assertFalse(r.success)
        assertTrue(r.unconfirmed)
        assertEquals(Delivery.DELIVERED_UNCONFIRMED, r.delivery)
    }

    @Test
    fun `读得到页面且控件状态未变时_按未生效处理_不与未确认混为一谈`() = runTest {
        val verifier = ClickVerifier(capture = { page(false) })
        val r = verifier.confirm(page(false), switchWidget(false), "点击")
        assertFalse(r.success)
        assertFalse("读到了页面且看到「没变」，这是「无效果」而不是「未确认」", r.unconfirmed)
        assertEquals(Delivery.DELIVERED_NO_EFFECT, r.delivery)
    }
}
