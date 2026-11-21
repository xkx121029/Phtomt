package com.phoneagent.engine.runtime

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.IntentType
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自写命令确认门（HAPP-32）。
 *
 * 闸门原本只认 `ActionType.SHELL + aiAuthored`，而 `terminal_op op=exec` 执行的是
 * **同一条注入通道上的任意命令**（command 甚至连必填都不是），却完全不经过它——
 * 换一个意图名就能把「AI 自写命令要不要用户点头」这道门绕过去。
 *
 * 这里的判据落在动作结构（endpoint/op）上，而不是模型/转译层自报的标记：
 * 自报标记只能是补充证据，不能是唯一判据。
 */
class ShellApprovalGateTest {

    private fun gate(store: EngineStore): UserGate = UserGate(
        store = store,
        ui = mockk(relaxed = true),
        mutateTaskMemory = { },
        actionLabel = { it },
    )

    private fun terminalExec(command: String) = AgentAction(
        type = ActionType.DEVICE_OPS,
        endpoint = IntentType.TERMINAL_OP,
        op = "exec",
        args = mapOf("command" to command),
    )

    @Test
    fun `terminal_op exec 首次执行前必须经过用户确认`() = runTest {
        val store = EngineStore()
        val gate = gate(store)

        val approval = async { gate.ensureShellApproval(terminalExec("rm -rf /sdcard/DCIM")) }
        advanceUntilIdle()

        assertEquals(
            "命令原文必须摆到确认面板上，否则用户不知道在批准什么",
            "rm -rf /sdcard/DCIM",
            store.pendingShellCommand.value,
        )

        gate.resolveShellApproval(false)
        advanceUntilIdle()

        assertFalse("用户拒绝后不得执行", approval.await())
        assertEquals("拒绝要按任务记住，不能下次又问一遍", false, store.shellApprovedForTask)
    }

    @Test
    fun `terminal_op exec 用户批准后放行`() = runTest {
        val store = EngineStore()
        val gate = gate(store)

        val approval = async { gate.ensureShellApproval(terminalExec("ls /sdcard")) }
        advanceUntilIdle()
        gate.resolveShellApproval(true)
        advanceUntilIdle()

        assertTrue(approval.await())
        assertEquals(true, store.shellApprovedForTask)
    }

    @Test
    fun `terminal_op 的只读探测不需要确认`() = runTest {
        val store = EngineStore()
        val gate = gate(store)

        val allowed = gate.ensureShellApproval(
            AgentAction(type = ActionType.DEVICE_OPS, endpoint = IntentType.TERMINAL_OP, op = "check_root"),
        )

        assertTrue("check_root 只读，不该打断用户", allowed)
        assertEquals(null, store.pendingShellCommand.value)
    }

    @Test
    fun `端侧自己编排的 shell 命令不需要确认`() = runTest {
        val store = EngineStore()
        val gate = gate(store)

        val allowed = gate.ensureShellApproval(
            AgentAction(type = ActionType.SHELL, command = "curl -s https://example.com", aiAuthored = false),
        )

        assertTrue("fetch 落到 Termux 的命令由端侧拼装，不走自写命令确认", allowed)
        assertEquals(null, store.pendingShellCommand.value)
    }

    @Test
    fun `AI 自写 shell 命令仍然要确认`() = runTest {
        val store = EngineStore()
        val gate = gate(store)

        val approval = async {
            gate.ensureShellApproval(AgentAction(type = ActionType.SHELL, command = "pm list packages", aiAuthored = true))
        }
        advanceUntilIdle()

        assertEquals("pm list packages", store.pendingShellCommand.value)
        gate.resolveShellApproval(false)
        advanceUntilIdle()
        assertFalse(approval.await())
    }

    @Test
    fun `批准一次后本任务内不再重复确认`() = runTest {
        val store = EngineStore()
        val gate = gate(store)

        val first = async { gate.ensureShellApproval(terminalExec("ls /sdcard")) }
        advanceUntilIdle()
        gate.resolveShellApproval(true)
        advanceUntilIdle()
        assertTrue(first.await())

        val second = gate.ensureShellApproval(terminalExec("ls /sdcard/Music"))
        assertTrue("同一任务内第二次自写命令不再问", second)
        assertEquals(null, store.pendingShellCommand.value)
    }
}
