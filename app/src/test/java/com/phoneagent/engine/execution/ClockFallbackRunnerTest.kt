package com.phoneagent.engine.execution

import android.content.Context
import com.phoneagent.device.clock.ClockRequest
import com.phoneagent.device.clock.ClockRequests
import com.phoneagent.device.shell.ShizukuManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时钟降级链的**结论类型**：读不到回执必须与「看见了没设成」分开。
 *
 * 回归目标（P1-2 第三条路径）：`ClockEvidence.Unreadable` 本来就有，但到
 * `ClockOutcome.Done(verified: Boolean)` 被折平——`ExecutionCore` 于是只能给出
 * 「已交付、页面没有预期变化」，回注给 AI 的下一步提示就是「换入口/换控件」，
 * 对一个已经派发出去的闹钟就是引导重复设置。
 */
class ClockFallbackRunnerTest {

    private fun runner() = ClockFallbackRunner(
        context = mockk<Context>(relaxed = true),
        executor = null,
        shell = { ShizukuManager.ShellResult.Failure("无注入通道") },
        caps = ClockCapabilities(accessibility = false, shell = false),
        screen = { null },
        dispatcher = object : ClockIntentDispatcher {
            override fun dispatch(request: ClockRequest, skipUi: Boolean): String? = null
            override fun openPage(request: ClockRequest): Boolean = true
        },
    )

    @Test
    fun `派发后读不到时钟数据_结论是未确认而不是没生效`() = runTest {
        val outcome = runner().run(ClockRequests.alarm(7, 30, null, "起床"))

        val done = outcome as? ClockOutcome.Done ?: error("静默直达已交付，不该落到别的结论：$outcome")
        assertFalse("读不到回执不能算已确认", done.verified)
        assertTrue("必须由结构化字段表达为未确认", done.unconfirmed)
        assertEquals(Delivery.DELIVERED_UNCONFIRMED, done.delivery)
        // 文案仍然要如实说明「没有重复派发」，这是用户唯一能核对的东西
        assertTrue(done.message.contains("无法确认"))
    }
}
