package com.phoneagent.agent

import com.phoneagent.model.AgentAction
import com.phoneagent.model.AgentIntent
import com.phoneagent.model.AgentIntentTarget
import com.phoneagent.model.ActionTarget
import com.phoneagent.model.IntentType
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentEngine 拆出的纯逻辑规则测试：幂等保护、滑动端点、动作标签、JSON 提取、中文占比。
 */
class EngineRulesTest {

    // ---- isFinalSubmit ----

    private fun action(type: String, value: String? = null, text: String? = null) = AgentAction(
        type = type,
        target = value?.let { ActionTarget(method = "label", value = it) },
        text = text,
    )

    @Test
    fun isFinalSubmit_点击发送_判为副作用操作() {
        assertTrue(EngineRules.isFinalSubmit(action("tap", "发送"), "tap"))
    }

    @Test
    fun isFinalSubmit_普通点击_不判为副作用() {
        assertFalse(EngineRules.isFinalSubmit(action("tap", "下一页"), "tap"))
    }

    @Test
    fun isFinalSubmit_非点击类动作_直接排除() {
        assertFalse(EngineRules.isFinalSubmit(action("swipe_up", "发送"), "swipe_up"))
    }

    // ---- idempotencyDone ----

    private fun snapshotWith(text: String?) = ScreenSnapshot(
        packageName = "com.test",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(
            UiElement(
                index = 0, className = "android.widget.TextView", type = "Text",
                x = 50, y = 50, left = 0, top = 0, right = 100, bottom = 100, text = text,
            ),
        ),
    )

    @Test
    fun idempotencyDone_页面含提交成功_返回true() {
        assertTrue(EngineRules.idempotencyDone(snapshotWith("订单已提交成功")))
    }

    @Test
    fun idempotencyDone_页面无完成证据_返回false() {
        assertFalse(EngineRules.idempotencyDone(snapshotWith("填写订单信息")))
    }

    // ---- swipeEndpoints ----

    @Test
    fun swipeEndpoints_向上滑动_钳制到0() {
        val (ex, ey) = EngineRules.swipeEndpoints(100, 50, "up", null, 1080, 2400)
        assertEquals(100, ex)
        assertEquals(0, ey)
    }

    @Test
    fun swipeEndpoints_向下滑动_钳制到屏高() {
        val (ex, ey) = EngineRules.swipeEndpoints(100, 2300, "down", 200, 1080, 2400)
        assertEquals(100, ex)
        assertEquals(2400, ey)
    }

    @Test
    fun swipeEndpoints_未知方向_原地() {
        assertEquals(100 to 100, EngineRules.swipeEndpoints(100, 100, "diag", null, 1080, 2400))
    }

    // ---- actionLabel ----

    @Test
    fun actionLabel_常见类型返回中文() {
        assertEquals("点按", EngineRules.actionLabel("tap"))
        assertEquals("输入文本", EngineRules.actionLabel("type"))
        assertEquals("回到桌面", EngineRules.actionLabel("home"))
    }

    @Test
    fun actionLabel_未知类型原样返回() {
        assertEquals("weird", EngineRules.actionLabel("weird"))
    }

    // ---- extractJsonObject / extractJsonBackward ----

    @Test
    fun extractJsonObject_纯JSON直接提取() {
        assertEquals("""{"a":1}""", EngineRules.extractJsonObject("""{"a":1}"""))
    }

    @Test
    fun extractJsonObject_带解释文字_反向定位() {
        val raw = "好的，我来处理。\n{" +
            "\"action\":\"tap\",\"target\":{\"by\":\"text\",\"value\":\"确认\"}}"
        val out = EngineRules.extractJsonObject(raw)
        assertTrue(out.startsWith("{"))
        assertTrue(out.contains("\"action\""))
        assertTrue(out.endsWith("}"))
    }

    @Test
    fun extractJsonObject_代码块包裹() {
        val out = EngineRules.extractJsonObject("```json\n{\"a\":1}\n```")
        assertEquals("{\"a\":1}", out)
    }

    @Test
    fun extractJsonObject_字符串内括号不被计数() {
        // "确认{删除}" 字符串内的括号不应影响括号匹配
        val raw = "前文 {" +
            "\"action\":\"tap\",\"value\":\"确认{删除}\"}"
        val out = EngineRules.extractJsonObject(raw)
        assertTrue(out.startsWith("{"))
        assertTrue(out.endsWith("}"))
    }

    @Test
    fun extractJsonBackward_无右括号返回null() {
        assertNull(EngineRules.extractJsonBackward("no brace here"))
    }

    // ---- isMostlyChinese ----

    @Test
    fun isMostlyChinese_中文为主_返回true() {
        assertTrue(EngineRules.isMostlyChinese("这是一段中文字符"))
    }

    @Test
    fun isMostlyChinese_英文为主_返回false() {
        assertFalse(EngineRules.isMostlyChinese("hello world API request"))
    }

    @Test
    fun isMostlyChinese_空串_返回false() {
        assertFalse(EngineRules.isMostlyChinese(""))
    }

    // ---- decisionTemperature ----

    @Test
    fun decisionTemperature_失败少于3次_使用稳定温度() {
        assertEquals(0.1, EngineRules.decisionTemperature(0), 1e-9)
        assertEquals(0.1, EngineRules.decisionTemperature(2), 1e-9)
    }

    @Test
    fun decisionTemperature_失败达到3次_使用重规划温度() {
        assertEquals(0.5, EngineRules.decisionTemperature(3), 1e-9)
        assertEquals(0.5, EngineRules.decisionTemperature(6), 1e-9)
    }

    // ---- needsReviewIntent ----

    private fun intent(intent: String, target: AgentIntentTarget? = null) =
        AgentIntent(intent = intent, target = target)

    @Test
    fun needsReviewIntent_完成或放弃_需审核() {
        assertTrue(EngineRules.needsReviewIntent(intent(IntentType.FINISH), snapshotWith("x")))
        assertTrue(EngineRules.needsReviewIntent(intent(IntentType.GIVE_UP), snapshotWith("x")))
    }

    @Test
    fun needsReviewIntent_点击有元素证据_不需审核() {
        val t = intent(IntentType.TAP, AgentIntentTarget(by = "text", value = "确认"))
        assertFalse(EngineRules.needsReviewIntent(t, snapshotWith("x")))
    }

    @Test
    fun needsReviewIntent_点击无目标或hint_需审核() {
        assertTrue(EngineRules.needsReviewIntent(intent(IntentType.TAP), snapshotWith("x")))
        assertTrue(EngineRules.needsReviewIntent(
            intent(IntentType.INPUT, AgentIntentTarget(by = "hint", value = "搜索框")), snapshotWith("x")),
        )
    }

    @Test
    fun needsReviewIntent_滑动无目标_需审核() {
        assertTrue(EngineRules.needsReviewIntent(intent(IntentType.SWIPE), snapshotWith("x")))
    }

    @Test
    fun needsReviewIntent_打开应用等待等_不审核() {
        assertFalse(EngineRules.needsReviewIntent(intent(IntentType.OPEN_APP), snapshotWith("x")))
        assertFalse(EngineRules.needsReviewIntent(intent(IntentType.WAIT), snapshotWith("x")))
        assertFalse(EngineRules.needsReviewIntent(intent(IntentType.WRITE_DOC), snapshotWith("x")))
    }
}