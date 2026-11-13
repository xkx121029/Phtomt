package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码输入脱敏口径测试。
 *
 * 锁住的是「密码只用于这一次执行」：任何留档、回显、回注的入口都必须拿到占位符，
 * 而不是原文。这里只测纯字符串处理，不依赖 Android。
 */
class SensitiveInputTest {

    @Test
    fun masked_只保留位数() {
        assertEquals("已输入 6 位（内容不记录）", SensitiveInput.masked("abc123"))
        assertEquals("已输入 0 位（内容不记录）", SensitiveInput.masked(null))
    }

    @Test
    fun redactAction_只替换输入类动作的正文() {
        val typed = AgentAction(type = ActionType.TYPE_TEXT, text = "hunter2", reasoning = "输入密码")
        val redacted = SensitiveInput.redactAction(typed)

        assertEquals("已输入 7 位（内容不记录）", redacted.text)
        // 目标与理由保留：下一步判断还需要知道"对哪个框做了什么"
        assertEquals("输入密码", redacted.reasoning)
        assertTrue(redacted.type == ActionType.TYPE_TEXT)
    }

    @Test
    fun redactAction_非输入动作原样返回() {
        val action = AgentAction(type = ActionType.CLICK, text = "提交")
        assertEquals(action, SensitiveInput.redactAction(action))
    }

    @Test
    fun redactIntent_输入意图不保留正文() {
        val intent = AgentIntent(intent = IntentType.INPUT, text = "hunter2")
        val redacted = SensitiveInput.redactIntent(intent)

        assertFalse(redacted.toString().contains("hunter2"))
        assertTrue(redacted.toString().contains("已输入 7 位"))
    }

    @Test
    fun redactIntent_非输入意图原样返回() {
        val intent = AgentIntent(intent = IntentType.TAP, text = null)
        assertEquals(intent, SensitiveInput.redactIntent(intent))
    }

    @Test
    fun redactRawResponse_只替换text字段() {
        // 短密码如果直接 replace 整段 JSON，会把别的数字/字符一起换掉
        val raw = """{"intent":"input","target":{"by":"id","value":"pwd"},"text":"123456","hour":1}"""

        val out = SensitiveInput.redactRawResponse(raw, "123456")

        assertFalse(out.contains("123456"))
        assertTrue(out.contains("已输入 6 位（内容不记录）"))
        assertTrue(out.contains("\"hour\":1"))
        assertTrue(out.contains("\"value\":\"pwd\""))
    }

    @Test
    fun redactRawResponse_没有密码时不改原文() {
        val raw = """{"intent":"tap","text":"确定"}"""
        assertEquals(raw, SensitiveInput.redactRawResponse(raw, null))
        assertEquals(raw, SensitiveInput.redactRawResponse(raw, ""))
    }
}
