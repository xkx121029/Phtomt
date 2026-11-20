package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入闭环判据：把"填了"和"交了"分开。
 *
 * 对应真机失败：正文输入答题框后直接 finish，从未点提交，判分读到空答案。
 */
class InputCommitRulesTest {

    private fun typeAction(text: String = "42", label: String = "答题框") = AgentAction(
        type = ActionType.TYPE_TEXT,
        text = text,
        target = ActionTarget(method = "label", value = label),
    )

    private fun tapAction(label: String) = AgentAction(
        type = ActionType.CLICK,
        target = ActionTarget(method = "label", value = label),
    )

    @Test
    fun 输入生效后挂上待提交状态() {
        val pending = InputCommitRules.nextPending(
            current = null,
            action = typeAction(),
            intentType = IntentType.INPUT,
            verified = true,
            step = 3,
        )
        assertEquals("答题框", pending?.label)
        assertEquals("42", pending?.preview)
        assertEquals(3, pending?.step)
    }

    @Test
    fun 输入未生效不挂账() {
        val pending = InputCommitRules.nextPending(
            current = null,
            action = typeAction(),
            intentType = IntentType.INPUT,
            verified = false,
            step = 3,
        )
        assertNull(pending)
    }

    @Test
    fun 提交类控件销账() {
        val current = PendingInput("答题框", "42", 3)
        val pending = InputCommitRules.nextPending(
            current = current,
            action = tapAction("提交答案"),
            intentType = IntentType.TAP,
            verified = true,
            step = 4,
        )
        assertNull(pending)
    }

    @Test
    fun 发送意图销账() {
        val current = PendingInput("聊天框", "你好", 3)
        val pending = InputCommitRules.nextPending(
            current = current,
            action = tapAction("发送"),
            intentType = IntentType.SEND,
            verified = true,
            step = 4,
        )
        assertNull(pending)
    }

    @Test
    fun 回车销账() {
        val current = PendingInput("搜索框", "天气", 3)
        val pending = InputCommitRules.nextPending(
            current = current,
            action = AgentAction(type = ActionType.KEY, keycode = "ENTER"),
            intentType = null,
            verified = true,
            step = 4,
        )
        assertNull(pending)
    }

    @Test
    fun 点旁处不清账() {
        val current = PendingInput("答题框", "42", 3)
        val pending = InputCommitRules.nextPending(
            current = current,
            action = tapAction("查看题目"),
            intentType = IntentType.TAP,
            verified = true,
            step = 4,
        )
        assertEquals(current, pending)
    }

    @Test
    fun 离开当前页销账() {
        val current = PendingInput("答题框", "42", 3)
        val pending = InputCommitRules.nextPending(
            current = current,
            action = AgentAction(type = ActionType.BACK),
            intentType = IntentType.BACK,
            verified = true,
            step = 4,
        )
        assertNull(pending)
    }

    @Test
    fun 超过保鲜步数自动过期() {
        val current = PendingInput("搜索框", "天气", 3)
        val step = 3 + InputCommitRules.PENDING_INPUT_TTL_STEPS + 1
        val pending = InputCommitRules.nextPending(
            current = current,
            action = tapAction("结果条目"),
            intentType = IntentType.TAP,
            verified = true,
            step = step,
        )
        assertNull(pending)
    }

    @Test
    fun 未生效的提交动作不销账() {
        val current = PendingInput("答题框", "42", 3)
        val pending = InputCommitRules.nextPending(
            current = current,
            action = tapAction("提交"),
            intentType = IntentType.SEND,
            verified = false,
            step = 4,
        )
        assertEquals(current, pending)
    }

    @Test
    fun 空白输入不覆盖已有待提交内容() {
        val current = PendingInput("答题框", "42", 3)
        val pending = InputCommitRules.nextPending(
            current = current,
            action = typeAction(text = "   "),
            intentType = IntentType.INPUT,
            verified = true,
            step = 4,
        )
        assertEquals(current, pending)
    }

    @Test
    fun 只有文本输入动作算填了() {
        assertTrue(InputCommitRules.isTextInput(ActionType.TYPE_TEXT))
        assertFalse(InputCommitRules.isTextInput(ActionType.CLICK))
        assertFalse(InputCommitRules.isTextInput(ActionType.KEY))
    }
}
