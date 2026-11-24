package com.phoneagent.coding.agent

import com.phoneagent.coding.api.CodingConnection
import com.phoneagent.coding.api.CodingMessageView
import com.phoneagent.coding.api.MappedStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编码页状态归属（HAPP-82）：页面看到的整页快照由切片的投影产出。
 *
 * 这些断言钉的是**页面会渲染、会落盘、会据此改行为**的字段，
 * 不是投影函数的实现：任何一条字段丢掉，编码页都会安静地少一块（耗时不显示、
 * 审批卡看不到要批准的命令、计时器不走、停止键不锁），而不会报错。
 */
class CodingConversationProjectionTest {

    private val toolMessage = ChatMessage(
        key = "t_1",
        role = "tool",
        toolName = "run_command",
        toolOk = true,
        toolCallId = "call_7",
        durationMs = 1_500L,
        outputPreview = "构建成功",
    )

    private val approvalMessage = ChatMessage(
        key = "a_1",
        role = "approval",
        text = "执行命令",
        approvalCallId = "call_8",
        approvalResolved = null,
        approvalCommand = "rm -rf build",
        approvalRisk = "执行命令",
        approvalDanger = "删除目录",
    )

    private val subagentMessage = ChatMessage(
        key = "s_1",
        role = "subagent",
        text = "正在写文件",
        subagentId = "s1",
        subagentLabel = "模块A",
        subagentPersona = "实现者",
    )

    @Test
    fun `投影保留每条消息的渲染字段`() {
        val state = AgentUiState(messages = listOf(toolMessage, approvalMessage, subagentMessage))

        val views = state.toConversation().messages

        assertEquals(listOf("t_1", "a_1", "s_1"), views.map { it.key })
        assertEquals(
            CodingMessageView(
                key = "t_1",
                role = "tool",
                toolName = "run_command",
                toolOk = true,
                durationMs = 1_500L,
                outputPreview = "构建成功",
            ),
            views[0],
        )
        assertEquals(
            CodingMessageView(
                key = "a_1",
                role = "approval",
                text = "执行命令",
                approvalCallId = "call_8",
                approvalCommand = "rm -rf build",
                approvalRisk = "执行命令",
                approvalDanger = "删除目录",
            ),
            views[1],
        )
        assertEquals(
            CodingMessageView(
                key = "s_1",
                role = "subagent",
                text = "正在写文件",
                subagentLabel = "模块A",
                subagentPersona = "实现者",
            ),
            views[2],
        )
    }

    @Test
    fun `投影保留连接与运行进度`() {
        val state = AgentUiState(
            conn = ConnState.CONNECTED,
            busy = true,
            error = "编码出错",
            phase = "执行工具",
            iteration = 3,
            runStartedAt = 1_700_000_000_000L,
        )

        val snapshot = state.toConversation()

        assertEquals(CodingConnection.CONNECTED, snapshot.connection)
        assertEquals(true, snapshot.busy)
        assertEquals("编码出错", snapshot.error)
        assertEquals("执行工具", snapshot.phase)
        assertEquals(3, snapshot.iteration)
        assertEquals(1_700_000_000_000L, snapshot.runStartedAt)
    }

    @Test
    fun `装回会话只丢归约匹配键`() {
        val view = approvalMessage.toView()

        val restored = view.toChatMessage()

        assertEquals(view, restored.toView())
        // toolCallId / subagentId 是事件归约时的匹配键，页面既不渲染也不落盘：
        // 装回来的消息本来就没有它们（改造前 loadMessages 直接造 ChatMessage 也是空的）。
        assertNull(restored.toolCallId)
        assertNull(restored.subagentId)
        assertEquals("rm -rf build", restored.approvalCommand)
        assertEquals("删除目录", restored.approvalDanger)
    }

    @Test
    fun `页面快照无需订阅即跟随内部状态`() {
        val source = MutableStateFlow(AgentUiState())
        val conversation = MappedStateFlow(source) { it.toConversation() }

        assertEquals(false, conversation.value.busy)

        source.value = AgentUiState(busy = true, iteration = 2)

        assertEquals(true, conversation.value.busy)
        assertEquals(2, conversation.value.iteration)
    }
}
