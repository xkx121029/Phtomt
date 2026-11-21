package com.phoneagent.domain.rules

import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.domain.rules.EngineRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun isFinalSubmit_无障碍端点点击发送_判为副作用操作() {
        // 自由模式 a11y_call 的 click_node 同样可能触发提交/发送，必须纳入幂等保护，避免重复副作用
        assertTrue(EngineRules.isFinalSubmit(action("a11y_call", "发送"), "a11y_call"))
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

    private fun named(
        index: Int, className: String, type: String, text: String,
        left: Int, top: Int, right: Int, bottom: Int,
    ) = UiElement(
        index = index, className = className, type = type, text = text,
        x = (left + right) / 2, y = (top + bottom) / 2,
        left = left, top = top, right = right, bottom = bottom,
    )

    /** 待办/工单详情页：标题 + 状态标签 + 3 个按钮（5 个元素，正落在旧实现「≤6 元素」的稀疏守卫内） */
    private fun todoDetailPage(status: String) = ScreenSnapshot(
        packageName = "com.test.todo",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = listOf(
            named(0, "android.widget.TextView", "Text", "买牛奶", 40, 40, 900, 160),
            named(1, "android.widget.TextView", "Text", status, 40, 220, 300, 320),
            named(2, "android.widget.Button", "Button", "编辑", 40, 1600, 300, 1700),
            named(3, "android.widget.Button", "Button", "删除", 780, 1600, 1040, 1700),
            named(4, "android.widget.Button", "Button", "返回", 40, 80, 160, 180),
        ),
    )

    private fun deleteAction() = AgentAction(
        type = ActionType.TAP,
        target = ActionTarget(method = "label", value = "删除"),
    )

    @Test
    fun `待办详情页的已完成标签不构成完成证据`() {
        // P0-1（HAPP-5）：旧实现「元素 ≤6 + 含『已完成』」就判"已做过"，待办详情页的状态标签
        // 让整条删除被跳过、动作根本没下发却记成 ✅。状态标签不是完成证据。
        assertFalse(EngineRules.idempotencyDone(todoDetailPage("已完成")))
    }

    @Test
    fun `操作成功与已提交这类状态提示不构成完成证据`() {
        assertFalse(EngineRules.idempotencyDone(snapshotWith("操作成功")))
        assertFalse(EngineRules.idempotencyDone(snapshotWith("已提交")))
    }

    @Test
    fun `带宾语的确认句仍算完成证据`() {
        assertTrue(EngineRules.idempotencyDone(snapshotWith("删除成功")))
        assertTrue(EngineRules.idempotencyDone(snapshotWith("支付成功")))
    }

    @Test
    fun `页面文字加删除意图但本任务没做过_要求用户确认而不是跳过`() {
        val verdict = EngineRules.idempotencyVerdict(emptyList(), deleteAction(), "tap", todoDetailPage("已完成"))
        assertTrue("历史为空时不得判跳过，实际：$verdict", verdict is EngineRules.IdempotencyVerdict.NeedsConfirm)
    }

    @Test
    fun `本任务内已下发过同一动作且页面仍是完成态_判跳过`() {
        val key = EngineRules.idempotencyKey(deleteAction(), "tap")
        val verdict = EngineRules.idempotencyVerdict(
            listOf(EngineRules.CommittedEffect(key, 3)),
            deleteAction(), "tap", todoDetailPage("删除成功"),
        )
        assertEquals(EngineRules.IdempotencyVerdict.Skip(3), verdict)
    }

    @Test
    fun `本任务内没有执行记录时_页面出现提交成功也不跳过`() {
        // 「发送」不在不可逆词表：没有执行记录就直接执行，绝不拿页面文字当"做过"
        val verdict = EngineRules.idempotencyVerdict(
            emptyList(), action("tap", "发送"), "tap", snapshotWith("提交成功"),
        )
        assertEquals(EngineRules.IdempotencyVerdict.Proceed, verdict)
    }

    @Test
    fun `幂等键按语义目标归一化_不同类型或目标不同键不同`() {
        assertEquals(
            EngineRules.idempotencyKey(deleteAction(), "tap"),
            EngineRules.idempotencyKey(deleteAction(), ActionType.CLICK),
        )
        assertNotEquals(
            EngineRules.idempotencyKey(deleteAction(), "tap"),
            EngineRules.idempotencyKey(action("tap", "移除"), "tap"),
        )
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
        // 终点钳制到有效像素上界 size-1（2026-09-14 修复：原为 coerceAtMost(size)，会越界 1px）
        assertEquals(2399, ey)
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

    @Test
    fun actionLabel_内置浏览器六类意图都有中文标签() {
        // browse_* 不经转译层、不产生 ActionType，标签按意图名直查，六类必须齐全（缺一个跑马灯就露英文）
        assertEquals("打开网页", EngineRules.actionLabel(IntentType.BROWSE_OPEN))
        assertEquals("抓取网页内容", EngineRules.actionLabel(IntentType.BROWSE_READ))
        assertEquals("点击网页元素", EngineRules.actionLabel(IntentType.BROWSE_CLICK))
        assertEquals("填写网页表单", EngineRules.actionLabel(IntentType.BROWSE_INPUT))
        assertEquals("滚动网页", EngineRules.actionLabel(IntentType.BROWSE_SCROLL))
        assertEquals("网页后退", EngineRules.actionLabel(IntentType.BROWSE_BACK))
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

    // ---- shouldFallbackReset ----

    @Test
    fun shouldFallbackReset_任务id一致且非终态_允许复位() {
        assertTrue(EngineRules.shouldFallbackReset(100L, 100L, AgentState.Phase.ERROR))
        assertTrue(EngineRules.shouldFallbackReset(100L, 100L, AgentState.Phase.ACTING))
    }

    @Test
    fun shouldFallbackReset_已完成终态_不复位() {
        // DONE 是正常完成的终态，兜底复位不能把「任务完成」洗成「空闲」
        assertFalse(EngineRules.shouldFallbackReset(100L, 100L, AgentState.Phase.DONE))
    }

    @Test
    fun shouldFallbackReset_任务id不一致_不复位() {
        // stop() 后收尾是异步跑的：旧任务（id=100）的收尾不能复位用户刚发起的新任务（id=200）
        assertFalse(EngineRules.shouldFallbackReset(100L, 200L, AgentState.Phase.ERROR))
        assertFalse(EngineRules.shouldFallbackReset(100L, 200L, AgentState.Phase.ACTING))
    }

    @Test
    fun shouldFallbackReset_无归属id_只靠终态守卫() {
        // taskId 为 null 表示任务记忆尚未建立（无归属信息），此时只按终态判定
        assertTrue(EngineRules.shouldFallbackReset(null, 200L, AgentState.Phase.ERROR))
        assertFalse(EngineRules.shouldFallbackReset(null, 200L, AgentState.Phase.DONE))
    }
}
