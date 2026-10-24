package com.phoneagent.coding.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodingEventReducer] 纯逻辑单测（Superagent 融合 Phase 7）：
 * JSON-lines 事件 → ChatMessage 列表变更的每个分支与连续增量拼接。
 * 输入是已解析的基本类型字段（org.json 在 android.jar stub 下不可 new，故 Reducer 不收 JSONObject）。
 */
class CodingEventReducerTest {

    private var seq = 0
    private fun nextId(): String = "n${++seq}"

    private fun apply(
        type: String,
        messages: List<ChatMessage> = emptyList(),
        assistantKey: String? = null,
        delta: String = "",
        name: String = "",
        ok: Boolean = false,
        approved: Boolean = true,
        why: String = "",
        callId: String = "",
        tool: String = "",
        code: Int = 0,
        argsPreview: String = "",
    ): CodingEventReducer.Result = CodingEventReducer.apply(
        type, messages, assistantKey, ::nextId,
        delta, name, ok, approved, why, callId, tool, code, argsPreview,
    )

    private fun toolCard(name: String, text: String = "", toolOk: Boolean? = null) =
        ChatMessage(key = "t_${name}", role = "tool", toolName = name, text = text, toolOk = toolOk)

    // ==================== text 增量 ====================

    @Test
    fun `text事件在空列表上新建助手消息`() {
        val r = apply("text", delta = "你好")
        assertEquals(1, r.messages.size)
        val m = r.messages.single()
        assertEquals("assistant", m.role)
        assertEquals("你好", m.text)
        assertTrue("分配出的 key 回传给调用方延续后续增量", r.assistantKey != null)
        assertEquals(m.key, r.assistantKey)
        assertFalse(r.hostExited)
        assertNull("text 事件不改 busy", r.busy)
    }

    @Test
    fun `连续text增量拼接进同一条消息`() {
        val first = apply("text", delta = "你好")
        val second = apply("text", messages = first.messages, assistantKey = first.assistantKey, delta = "，世界")
        assertEquals(1, second.messages.size)
        assertEquals("你好，世界", second.messages.single().text)
        assertEquals(first.assistantKey, second.assistantKey)
    }

    @Test
    fun `tool_start重置助手key后text再开新消息`() {
        val first = apply("text", delta = "先说一句")
        val start = apply("tool_start", messages = first.messages, assistantKey = first.assistantKey, name = "ls")
        assertEquals(null, start.assistantKey)
        val second = apply("text", messages = start.messages, assistantKey = start.assistantKey, delta = "再说一句")
        assertEquals(3, second.messages.size)
        assertEquals("先说一句", second.messages[0].text)
        assertEquals("再说一句", second.messages[2].text)
    }

    // ==================== 工具卡 ====================

    @Test
    fun `tool_start追加工具卡并重置助手key`() {
        val r = apply("tool_start", name = "write_file", argsPreview = "path=src/app.js")
        val card = r.messages.single()
        assertEquals("tool", card.role)
        assertEquals("write_file", card.toolName)
        assertEquals("path=src/app.js", card.text)
        assertNull("未结束时无成败结论", card.toolOk)
        assertNull("工具回合开始后 assistant 在写消息作废", r.assistantKey)
    }

    @Test
    fun `tool_end按名字置最后一张未结卡`() {
        val msgs = listOf(
            toolCard("write_file", toolOk = true),
            toolCard("write_file"),
        )
        val r = apply("tool_end", messages = msgs, name = "write_file", ok = true)
        assertNull("前面已结的卡不受影响", r.messages[0].toolOk.takeIf { it != true })
        assertEquals(true, r.messages[1].toolOk)
    }

    @Test
    fun `tool_end失败同样置位`() {
        val msgs = listOf(toolCard("run_cmd"))
        val r = apply("tool_end", messages = msgs, name = "run_cmd", ok = false)
        assertEquals(false, r.messages.single().toolOk)
    }

    @Test
    fun `tool_end无匹配卡不改消息`() {
        val msgs = listOf(toolCard("other"))
        val r = apply("tool_end", messages = msgs, name = "write_file", ok = true)
        assertEquals(msgs, r.messages)
    }

    @Test
    fun `tool_end不碰审批卡`() {
        val approval = ChatMessage(key = "p_c1", role = "approval", approvalCallId = "c1", text = "write_file")
        val r = apply("tool_end", messages = listOf(approval), name = "write_file", ok = true)
        assertEquals(listOf(approval), r.messages)
    }

    // ==================== 审批 ====================

    @Test
    fun `tool_approval被拒写原因并置灰`() {
        val msgs = listOf(toolCard("run_cmd"))
        val r = apply("tool_approval", messages = msgs, name = "run_cmd", approved = false, why = "危险命令")
        val card = r.messages.single()
        assertEquals("⛔ 危险命令", card.text)
        assertEquals(false, card.toolOk)
    }

    @Test
    fun `tool_approval被拒无原因时写默认文案`() {
        val msgs = listOf(toolCard("run_cmd"))
        val r = apply("tool_approval", messages = msgs, name = "run_cmd", approved = false, why = "")
        assertEquals("⛔ 被拒绝", r.messages.single().text)
    }

    @Test
    fun `tool_approval通过不改消息`() {
        val msgs = listOf(toolCard("run_cmd"))
        val r = apply("tool_approval", messages = msgs, name = "run_cmd", approved = true)
        assertEquals(msgs, r.messages)
    }

    @Test
    fun `approval_request追加待审批卡`() {
        val r = apply("approval_request", callId = "call-1", tool = "write_file")
        val card = r.messages.single()
        assertEquals("approval", card.role)
        assertEquals("p_call-1", card.key)
        assertEquals("call-1", card.approvalCallId)
        assertEquals("write_file", card.text)
        assertNull("未解决审批卡保持待定（置灰交给 UI 响应动作）", card.approvalResolved)
        assertNull("审批请求不改 busy", r.busy)
    }

    // ==================== 回合收束 ====================

    @Test
    fun `done置闲并重置助手key`() {
        val first = apply("text", delta = "hi")
        val r = apply("done", messages = first.messages, assistantKey = first.assistantKey)
        assertEquals(first.messages, r.messages)
        assertEquals(false, r.busy)
        assertNull(r.assistantKey)
        assertFalse(r.hostExited)
    }

    @Test
    fun `cancelled置闲并重置助手key`() {
        val r = apply("cancelled", assistantKey = "a_1")
        assertEquals(false, r.busy)
        assertNull(r.assistantKey)
    }

    @Test
    fun `host_exited置断连与错误并标记重连`() {
        val r = apply("host_exited", code = 7, assistantKey = "a_1")
        assertEquals(ConnState.DISCONNECTED, r.conn)
        assertEquals(false, r.busy)
        assertNull(r.assistantKey)
        assertTrue("错误文案含退出码与重连提示", r.error!!.contains("code 7") && r.error!!.contains("正在重连"))
        assertTrue("AgentClient 据此调度退避重连", r.hostExited)
    }

    @Test
    fun `未知事件不改任何状态`() {
        val msgs = listOf(toolCard("ls", toolOk = true))
        val r = apply("something_new", messages = msgs, assistantKey = "a_1")
        assertEquals(msgs, r.messages)
        assertEquals("a_1", r.assistantKey)
        assertNull(r.busy)
        assertNull(r.conn)
        assertNull(r.error)
        assertFalse(r.hostExited)
    }

    // ==================== 组合回合 ====================

    @Test
    fun `完整回合串联_文本到工具到收尾`() {
        var r = apply("text", delta = "我来算一下")
        r = apply("tool_start", messages = r.messages, assistantKey = r.assistantKey, name = "run_cmd", argsPreview = "cmd=python fib.py")
        r = apply("approval_request", messages = r.messages, assistantKey = r.assistantKey, callId = "c9", tool = "run_cmd")
        r = apply("tool_approval", messages = r.messages, assistantKey = r.assistantKey, name = "run_cmd", approved = false, why = "用户拒绝")
        r = apply("tool_end", messages = r.messages, assistantKey = r.assistantKey, name = "run_cmd", ok = false)
        r = apply("text", messages = r.messages, assistantKey = r.assistantKey, delta = "那我换个方式")
        r = apply("done", messages = r.messages, assistantKey = r.assistantKey)

        assertEquals(4, r.messages.size)
        assertEquals("我来算一下", r.messages[0].text)               // assistant 首条
        assertEquals(false, r.messages[1].toolOk)                   // 工具卡：被拒置灰
        assertEquals("⛔ 用户拒绝", r.messages[1].text)
        assertEquals("c9", r.messages[2].approvalCallId)            // 审批卡：保持待定
        assertNull(r.messages[2].approvalResolved)
        assertEquals("那我换个方式", r.messages[3].text)             // tool_start 后新开的 assistant 消息
        assertEquals(false, r.busy)                                 // done 收束
        assertNull(r.assistantKey)
    }
}
