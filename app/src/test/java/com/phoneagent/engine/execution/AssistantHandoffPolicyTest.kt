package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音助手传话的结论。
 *
 * 回归目标（P1-4）：Provider 的说明里明确写着「已填入文字但未找到发送按钮」，
 * `ExecutionCore` 过去无条件 `VerifyResult(true, desc)`——「填进去」被记成「发出去并确认」，
 * 而完成门（[com.phoneagent.domain.rules.InputCommitRules]）只认 `TYPE_TEXT`，看不见这条路。
 *
 * 这里钉住两件事：成败只由结构证据决定（输入框里还留着那句话 / 页面有没有变化），
 * 文案里写什么都不能改变结论。
 */
class AssistantHandoffPolicyTest {

    private fun inputBox(text: String?) = ScreenSnapshot(
        packageName = "com.vendor.assistant",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = listOf(
            UiElement(
                index = 0,
                className = "android.widget.EditText",
                type = "Input",
                text = text,
                x = 540,
                y = 1600,
                left = 40,
                top = 1560,
                right = 1040,
                bottom = 1640,
                editable = true,
            ),
        ),
    )

    private fun chatBubble(text: String) = ScreenSnapshot(
        packageName = "com.vendor.assistant",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = listOf(
            UiElement(
                index = 0,
                className = "android.widget.TextView",
                type = "Text",
                text = text,
                x = 540,
                y = 600,
                left = 100,
                top = 560,
                right = 980,
                bottom = 640,
            ),
        ),
    )

    @Test
    fun `输入框里还留着这句话_就是没发出去`() {
        assertTrue(AssistantHandoffPolicy.stillInInputBox(inputBox("定一个七点的闹钟"), "定一个七点的闹钟"))
    }

    @Test
    fun `聊天气泡回显同一句话不算没发出去`() {
        assertFalse(
            "非输入框（聊天气泡/结果卡片）里出现同一句话，不能当成「没发出去」",
            AssistantHandoffPolicy.stillInInputBox(chatBubble("定一个七点的闹钟"), "定一个七点的闹钟"),
        )
    }

    @Test
    fun `空传话内容_不产生否决证据`() {
        assertFalse(AssistantHandoffPolicy.stillInInputBox(inputBox(null), null))
        assertFalse(AssistantHandoffPolicy.stillInInputBox(inputBox(""), "   "))
    }

    @Test
    fun `填进去了但没发出去_不得报成功_哪怕说明里写着已转交`() {
        val r = AssistantHandoffPolicy.verdict(
            handoff = "已唤醒语音助手，并把「定一个七点的闹钟」转交给了它",
            typedNotSent = true,
            evidence = ShellFallbackPolicy.judge("fp-before", "fp-after"),
        )
        assertFalse("文案写着已转交也不许报成功：成败只认结构证据", r.success)
        assertFalse(r.unconfirmed)
        assertEquals(Delivery.DELIVERED_NO_EFFECT, r.delivery)
        assertTrue(r.reason.contains("没有发出去"))
    }

    @Test
    fun `读不到页面时_传话按未确认回报`() {
        val r = AssistantHandoffPolicy.verdict(
            handoff = "已唤醒语音助手，已填入文字但未找到发送按钮，请在助手界面确认",
            typedNotSent = false,
            evidence = ShellFallbackPolicy.judge("fp-before", null),
        )
        assertFalse(r.success)
        assertTrue("读不到页面 = 未确认，不是「没生效」", r.unconfirmed)
        assertEquals(Delivery.DELIVERED_UNCONFIRMED, r.delivery)
    }

    @Test
    fun `页面已确认变化且输入框已清空_才算成功`() {
        val r = AssistantHandoffPolicy.verdict(
            handoff = "已唤醒语音助手，并把「定一个七点的闹钟」转交给了它",
            typedNotSent = false,
            evidence = ShellFallbackPolicy.judge("fp-before", "fp-after"),
        )
        assertTrue(r.success)
        assertEquals(Delivery.EFFECTIVE, r.delivery)
        assertFalse("这一下已经交付，外层不许再重跑一遍", r.notDelivered)
    }

    @Test
    fun `传话失败不重试_已交付的动作重放就是重复副作用`() {
        val r = AssistantHandoffPolicy.verdict(
            handoff = "已唤醒语音助手，已填入文字但未找到发送按钮",
            typedNotSent = false,
            evidence = ShellFallbackPolicy.judge("fp-before", "fp-before"),
        )
        assertFalse(r.success)
        assertFalse(VerifyRules.shouldRetry(ActionType.LAUNCH_ASSISTANT, r.delivery, r.structural, 1, true))
    }
}
