package com.phoneagent.engine.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepBudgetTest {

    @Test
    fun `未超预算不触发`() {
        assertFalse(StepBudget.exceeded(0))
        assertFalse(StepBudget.exceeded(1_500))
        // 边界：刚好等于预算仍算正常，避免"卡在临界值"的抖动
        assertFalse(StepBudget.exceeded(StepBudget.BUDGET_MS))
    }

    @Test
    fun `超出预算触发`() {
        assertTrue(StepBudget.exceeded(StepBudget.BUDGET_MS + 1))
        assertTrue(StepBudget.exceeded(StepBudget.BUDGET_MS * 3))
    }

    @Test
    fun `提示里带步号与实际秒数`() {
        val hint = StepBudget.hint(step = 7, elapsedMs = 95_000)

        assertTrue(hint.contains("第 7 步"))
        assertTrue(hint.contains("95 秒"))
        assertTrue(hint.contains("超出单步预算"))
    }

    @Test
    fun `提示要求换入口而不是继续原地重复`() {
        val hint = StepBudget.hint(step = 2, elapsedMs = 80_000)

        assertTrue(hint.contains("不要在同一入口上继续重复"))
        // 不能把 AI 引向"再等一会"：原地等待正是整步超预算的常见原因
        assertFalse(hint.contains("wait"))
    }

    @Test
    fun `预算必须显著大于单次决策看门狗`() {
        // 否则普通一步（决策 3-10s）就会被误判为超预算，提示变成噪音
        assertTrue(StepBudget.BUDGET_MS > 45_000L)
    }
}
