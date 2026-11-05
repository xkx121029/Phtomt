package com.phoneagent.engine.runtime

import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行段回注文案与动作签名（重构阶段 B3）。
 * 签名串与纠偏文案直接参与熔断/纠正，改错一个字都可能让护栏静默失效，因此先用测试锁住。
 */
class ExecutionFeedbackTest {

    @Test
    fun 动作签名包含类型目标坐标键值与命令() {
        val action = AgentAction(
            type = ActionType.CLICK,
            target = ActionTarget(method = "id", value = "btn_send"),
            x = 120, y = 340,
            keycode = "BACK",
            command = "tap 0.5 0.5",
        )
        assertEquals("click|btn_send|120,340|BACK|tap 0.5 0.5", ExecutionFeedback.actionSignature(action))
    }

    @Test
    fun 动作签名缺省字段按空串拼接() {
        val action = AgentAction(type = ActionType.WAIT)
        assertEquals("wait||null,null||", ExecutionFeedback.actionSignature(action))
    }

    @Test
    fun shell首次无效提示不禁止shell() {
        val hint = ExecutionFeedback.structuralErrorHint(ActionType.SHELL, "unknown command", 1)
        assertTrue(hint.contains("你发出的 shell 命令无效"))
        assertTrue(!hint.contains("禁止再使用"))
    }

    @Test
    fun shell连续两次无效升级为禁止shell() {
        val hint = ExecutionFeedback.structuralErrorHint(ActionType.SHELL, "unknown command", 2)
        assertTrue(hint.contains("连续 2 次"))
        assertTrue(hint.contains("禁止再使用 type=\"shell\""))
    }

    @Test
    fun 非shell意图提示带出意图名与原因() {
        val hint = ExecutionFeedback.structuralErrorHint("open_app", "缺少应用名", 1)
        assertTrue(hint.contains("意图「open_app」执行无效"))
        assertTrue(hint.contains("缺少应用名"))
    }
}
