package com.phoneagent.engine.runtime

import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 不可逆动作确认门的**去重**契约（[UserGate.awaitActionConfirmation]）。
 *
 * 背景：确认门按动作计，于是"确认过之后动作验证失败、模型重发同一下"也会再弹一次；
 * 但跨页面绝不能去重——结算页 `checkout_btn` → 下一页 `confirm_btn` 是两次独立的不可逆动作，
 * 页面变了就必须重新问。
 *
 * 键 =（意图类，页面指纹），窗口 60s：意图类含动作类型 + endpoint/op + 目标文字 + 命令原文，
 * 所以同一页面上删第一条、再删第二条仍是两次确认。
 */
class UserGateTest {

    private lateinit var store: EngineStore
    private lateinit var ui: UiBridge
    private lateinit var gate: UserGate
    private var nowMs = 1_000_000L

    @Before
    fun setUp() {
        store = EngineStore()
        ui = mockk(relaxed = true)
        gate = UserGate(
            store = store,
            ui = ui,
            mutateTaskMemory = { it },
            actionLabel = { "动作" },
            now = { nowMs },
        )
    }

    private fun tap(label: String, page: String? = "page-1") = AgentAction(
        type = ActionType.TAP,
        target = ActionTarget(method = "label", value = label),
        pageFingerprint = page,
        needsUserConfirmation = true,
    )

    /** 走一次"需要用户作答"的确认：确认框出现后由测试点允许/拒绝 */
    private suspend fun TestScope.askAndAnswer(
        action: AgentAction,
        allow: Boolean,
    ): Boolean {
        val deferred = async { gate.awaitActionConfirmation(action) }
        runCurrent()
        gate.respondActionConfirmation(allow)
        return deferred.await()
    }

    @Test
    fun `同一页面上的同一动作_第二次不再询问`() = runTest {
        val action = tap("删除")
        assertTrue(askAndAnswer(action, allow = true))

        nowMs += 5_000
        assertTrue("刚刚允许过同页同动作，不该再弹一次", gate.awaitActionConfirmation(action))
        // 只弹过一次确认框：第二次是直接放行，没有走"等待用户确认"那条路
        verify(exactly = 1) { ui.pushFloating("等待确认操作", "THINKING") }
    }

    @Test
    fun `页面变了_同一动作仍要重新询问`() = runTest {
        assertTrue(askAndAnswer(tap("删除", page = "page-1"), allow = true))

        nowMs += 5_000
        // 结算页 结算→确认支付 就是这一类：跨页面免确认等于替用户把钱付了，必须照旧问
        assertTrue(askAndAnswer(tap("删除", page = "page-2"), allow = true))
        verify(exactly = 2) { ui.pushFloating("等待确认操作", "THINKING") }
    }

    @Test
    fun `同一页面上不同目标_各自询问`() = runTest {
        assertTrue(askAndAnswer(tap("删除", page = "page-1"), allow = true))

        nowMs += 5_000
        // 列表里删第一条再删第二条：同页但目标不同，仍是两次独立动作
        assertTrue(askAndAnswer(tap("清空", page = "page-1"), allow = true))
        verify(exactly = 2) { ui.pushFloating("等待确认操作", "THINKING") }
    }

    @Test
    fun `窗口过期后重新询问`() = runTest {
        assertTrue(askAndAnswer(tap("删除"), allow = true))

        nowMs += UserGate.ACTION_CONFIRM_DEDUPE_MS + 1
        assertTrue(askAndAnswer(tap("删除"), allow = true))
        verify(exactly = 2) { ui.pushFloating("等待确认操作", "THINKING") }
    }

    @Test
    fun `用户拒绝不缓存_同一动作再问一次`() = runTest {
        assertFalse(askAndAnswer(tap("删除"), allow = false))

        nowMs += 5_000
        // 拒绝后记成"已授权"等于替用户点头，必须再问
        assertTrue(askAndAnswer(tap("删除"), allow = true))
        verify(exactly = 2) { ui.pushFloating("等待确认操作", "THINKING") }
    }

    @Test
    fun `没有页面指纹时不去重`() = runTest {
        assertTrue(askAndAnswer(tap("删除", page = null), allow = true))

        nowMs += 5_000
        // 不知道页面变没变时宁可多问一次，也不能替用户点头
        assertTrue(askAndAnswer(tap("删除", page = null), allow = true))
        verify(exactly = 2) { ui.pushFloating("等待确认操作", "THINKING") }
    }
}
