package com.phoneagent.engine.loop

import com.phoneagent.domain.model.AgentState
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主循环壳的端到端回归（测试基建落地后的第一个可验证节点）。
 *
 * 用假 [TaskRunner.run] 的 step lambda 覆盖四条控制流：继续、正常返回、不计步重试、失败收尾，
 * 以及最大步数与用户取消两个边界。这里不需要 Android，也不需要 AgentEngine。
 */
class TaskRunnerTest {

    private class Harness(
        maxSteps: Int = 0,
        active: Boolean = true,
    ) {
        var maxSteps = maxSteps
        var active = active
        val startedSteps = mutableListOf<Int>()
        val rolledBack = mutableListOf<Int>()
        val fatal = mutableListOf<StepOutcome.Fatal>()
        var maxStepsHit = 0
        var resumeChecks = 0
        var lastStep = -1

        val runner = TaskRunner(
            awaitResume = { resumeChecks++ },
            isActive = { active },
            onStepStart = { startedSteps += it },
            onStepRolledBack = { rolledBack += it },
            onFatal = { fatal += it },
            onMaxSteps = { maxStepsHit++ },
        )
    }

    @Test
    fun `连续继续后正常返回`() = runTest {
        val h = Harness()
        val calls = mutableListOf<Int>()
        h.lastStep = h.runner.run(h.maxSteps) { step ->
            calls += step
            if (step < 3) StepOutcome.Continue else StepOutcome.Return
        }
        assertEquals(listOf(1, 2, 3), calls)
        assertEquals(listOf(1, 2, 3), h.startedSteps)
        assertEquals(3, h.lastStep)
        assertEquals(0, h.maxStepsHit)
        assertEquals(3, h.resumeChecks)
    }

    @Test
    fun `重试会回退步数并延时`() = runTest {
        val h = Harness()
        val calls = mutableListOf<Int>()
        val outcomes = ArrayDeque(
            listOf<StepOutcome>(
                StepOutcome.Retry(delayMs = 1_500),
                StepOutcome.Continue,
                StepOutcome.Return,
            ),
        )
        h.lastStep = h.runner.run(h.maxSteps) { step ->
            calls += step
            outcomes.removeFirst()
        }
        // 第一次决策异常回退到 0，下一轮仍从第 1 步继续
        assertEquals(listOf(1, 1, 2), calls)
        assertEquals(listOf(0), h.rolledBack)
        assertTrue("重试必须消耗 1500ms 虚拟时间", currentTime >= 1_500)
        assertEquals(2, h.lastStep)
    }

    @Test
    fun `失败收尾立即退出且不再跑下一步`() = runTest {
        val h = Harness()
        val calls = mutableListOf<Int>()
        h.lastStep = h.runner.run(h.maxSteps) { step ->
            calls += step
            if (step == 1) StepOutcome.Fatal("boom", AgentState.FailureKind.DECISION) else StepOutcome.Continue
        }
        assertEquals(listOf(1), calls)
        assertEquals(1, h.fatal.size)
        assertEquals("boom", h.fatal.first().reason)
        assertEquals(AgentState.FailureKind.DECISION, h.fatal.first().failureKind)
        assertEquals(0, h.maxStepsHit)
    }

    @Test
    fun `达到最大步数触发收尾`() = runTest {
        val h = Harness(maxSteps = 3)
        h.lastStep = h.runner.run(h.maxSteps) { StepOutcome.Continue }
        assertEquals(listOf(1, 2, 3), h.startedSteps)
        assertEquals(1, h.maxStepsHit)
        assertEquals(3, h.lastStep)
    }

    @Test
    fun `用户取消时不触发最大步数收尾`() = runTest {
        val h = Harness(maxSteps = 1, active = false)
        h.lastStep = h.runner.run(h.maxSteps) { StepOutcome.Continue }
        assertTrue(h.startedSteps.isEmpty())
        assertEquals(0, h.maxStepsHit)
        assertEquals(0, h.lastStep)
    }

    @Test
    fun `maxSteps 为零表示不设上限`() = runTest {
        val h = Harness(maxSteps = 0)
        val calls = mutableListOf<Int>()
        h.lastStep = h.runner.run(h.maxSteps) { step ->
            calls += step
            if (step < 5) StepOutcome.Continue else StepOutcome.Return
        }
        assertEquals(5, calls.size)
        assertEquals(0, h.maxStepsHit)
    }
}
