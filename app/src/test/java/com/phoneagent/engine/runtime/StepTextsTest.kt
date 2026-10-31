package com.phoneagent.engine.runtime

import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.engine.PromptLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策上下文文本回归。
 *
 * 这些字符串直接进入提示词：三态文案必须跟随语言（中文 ✅/⚠️/❌ 与英文 verified /
 * sent but unverified / failed 一一对应），任务记忆的进度与阶段切分也必须稳定。
 * 本轮把拼装逻辑从 AgentEngine 下沉到 [StepTexts]，用本测试钉住原口径。
 */
class StepTextsTest {

    private fun record(
        type: String = "tap",
        confirmed: Boolean = false,
        verification: String = "",
    ) = StepRecord(
        step = 1,
        action = AgentAction(type = type, reason = "点一下"),
        isConfirmed = confirmed,
        verificationResult = verification,
    )

    @Test
    fun `空历史返回空串`() {
        assertEquals("", StepTexts.lastStepResult(emptyList(), PromptLang.CN))
    }

    @Test
    fun `中文三态`() {
        assertTrue(StepTexts.lastStepResult(listOf(record(confirmed = true)), PromptLang.CN).startsWith("✅ 已确认成功"))
        assertTrue(StepTexts.lastStepResult(listOf(record(verification = "failed")), PromptLang.CN).startsWith("❌ 未生效"))
        assertTrue(StepTexts.lastStepResult(listOf(record()), PromptLang.CN).startsWith("⚠️ 已发送但未确认"))
    }

    @Test
    fun `英文三态跟随提示词语言`() {
        assertTrue(StepTexts.lastStepResult(listOf(record(confirmed = true)), PromptLang.EN).startsWith("✅ verified"))
        assertTrue(
            StepTexts.lastStepResult(listOf(record(verification = "failed")), PromptLang.EN)
                .startsWith("❌ failed"),
        )
        assertTrue(StepTexts.lastStepResult(listOf(record()), PromptLang.EN).startsWith("⚠️ sent but unverified"))
    }

    @Test
    fun `动作带 reason 时拼进文案`() {
        val text = StepTexts.lastStepResult(listOf(record(type = "click")), PromptLang.CN)
        assertTrue(text.contains("click(点一下)"))
    }

    @Test
    fun `任务记忆包含目标与用户要求`() {
        val mem = TaskMemoryEntry(
            taskId = 1L,
            taskName = "下单",
            goal = "帮我买一杯咖啡",
            requirements = listOf("少糖", "到店取"),
        )
        val text = StepTexts.taskMemory(mem, completedSteps = 0, totalPlannedSteps = 0)
        assertTrue(text.contains("目标：帮我买一杯咖啡"))
        assertTrue(text.contains("1. 少糖"))
        assertTrue(text.contains("2. 到店取"))
        // 尚未执行时不应出现进度段
        assertTrue(!text.contains("执行进度"))
    }

    @Test
    fun `任务记忆按阶段粒度报进度`() {
        val mem = TaskMemoryEntry(
            taskId = 1L,
            taskName = "下单",
            goal = "买咖啡",
            methods = listOf("搜索咖啡店"),
        )
        // 12 步规划 / STAGE_SIZE=6 → 两个阶段；已完成 7 步 → 第 2 阶段
        val text = StepTexts.taskMemory(mem, completedSteps = 7, totalPlannedSteps = 12)
        assertTrue(text.contains("已完成 7/12 步"))
        assertTrue(text.contains("阶段 2/2"))
        assertTrue(text.contains("1. 搜索咖啡店"))
    }

    @Test
    fun `无任务记忆时返回空串`() {
        assertEquals("", StepTexts.taskMemory(null, completedSteps = 3, totalPlannedSteps = 5))
    }
}
