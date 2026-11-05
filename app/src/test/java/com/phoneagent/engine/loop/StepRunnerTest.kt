package com.phoneagent.engine.loop

import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.engine.PromptLang
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单步编排器的短路语义回归。
 *
 * 迁移前的 `continue` 是「本步到此为止」；拆成阶段后必须保证任一阶段返回非 Continue 时，
 * 后续阶段不再执行，且返回值原样上抛。
 */
class StepRunnerTest {

    private fun session() = StepSession(
        task = "测试任务",
        plan = null,
        step = 1,
        settingsVal = AppSettings.Settings(),
        lang = PromptLang.CN,
        messages = mutableListOf(),
    )

    @Test
    fun `观察阶段返回后决定不再执行决策和执行`() = runTest {
        val calls = mutableListOf<String>()
        val runner = StepRunner(
            observe = { calls += "observe"; StepOutcome.Return },
            decide = { calls += "decide"; StepOutcome.Continue },
            execute = { calls += "execute"; StepOutcome.Continue },
        )
        val outcome = runner.run(session())
        assertEquals(StepOutcome.Return, outcome)
        assertEquals(listOf("observe"), calls)
    }

    @Test
    fun `决策阶段返回重试后不再执行执行阶段`() = runTest {
        val calls = mutableListOf<String>()
        val retry = StepOutcome.Retry(1_500)
        val runner = StepRunner(
            observe = { calls += "observe"; StepOutcome.Continue },
            decide = { calls += "decide"; retry },
            execute = { calls += "execute"; StepOutcome.Continue },
        )
        val outcome = runner.run(session())
        assertEquals(retry, outcome)
        assertEquals(listOf("observe", "decide"), calls)
    }

    @Test
    fun `三段都继续时按顺序执行并返回执行结果`() = runTest {
        val calls = mutableListOf<String>()
        val runner = StepRunner(
            observe = { calls += "observe"; StepOutcome.Continue },
            decide = { calls += "decide"; StepOutcome.Continue },
            execute = { calls += "execute"; StepOutcome.Continue },
        )
        assertEquals(StepOutcome.Continue, runner.run(session()))
        assertEquals(listOf("observe", "decide", "execute"), calls)
    }

    @Test
    fun `阶段产物通过会话交接`() = runTest {
        val s = session()
        val runner = StepRunner(
            observe = { session ->
                session.pageMemoryHint = "hint"
                StepOutcome.Continue
            },
            decide = { session ->
                assertTrue(session.pageMemoryHint == "hint")
                StepOutcome.Continue
            },
            execute = { session ->
                assertFalse(session.fromLocal)
                StepOutcome.Continue
            },
        )
        runner.run(s)
        assertEquals("hint", s.pageMemoryHint)
    }
}
