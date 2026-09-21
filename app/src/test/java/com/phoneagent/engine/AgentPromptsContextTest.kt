package com.phoneagent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境上下文与会话承接的提示词渲染单测（纯字符串，无 Android 依赖）。
 * 目标是：事实齐全时该出现的都在，缺项不产生空标签，没有历史任务时整段不注入（不给 AI 添负担）。
 */
class AgentPromptsContextTest {

    private val env = EnvFacts(
        dateTime = "2026-09-20 周六 15:04",
        network = "Wi-Fi",
        battery = "62%（充电中）",
        foreground = "微信(com.tencent.mm)",
        installedCount = 87,
    )

    @Test
    fun `中文环境上下文包含时间前台应用与设备事实`() {
        val text = AgentPrompts.environment(PromptLang.CN, env)
        assertTrue(text.contains("2026-09-20 周六 15:04"))
        assertTrue(text.contains("微信(com.tencent.mm)"))
        assertTrue(text.contains("Wi-Fi"))
        assertTrue(text.contains("62%（充电中）"))
        assertTrue(text.contains("87"))
        // 完整应用清单默认不给，必须指出自查渠道
        assertTrue(text.contains("device_query"))
    }

    @Test
    fun `英文环境上下文使用英文标签`() {
        val text = AgentPrompts.environment(PromptLang.EN, env)
        assertTrue(text.contains("Current time"))
        assertTrue(text.contains("Foreground app"))
        assertTrue(text.contains("Installed apps"))
        assertFalse(text.contains("当前时间"))
    }

    /** 规划阶段拿不到前台应用：缺项应整行省略，而不是留下一行空标签 */
    @Test
    fun `缺项时不渲染空标签`() {
        val text = AgentPrompts.environment(PromptLang.CN, EnvFacts(dateTime = "2026-09-20 周六 15:04"))
        assertFalse(text.contains("前台应用："))
        assertTrue(text.contains("网络：未知"))
    }

    @Test
    fun `没有历史任务时不注入会话承接`() {
        assertEquals("", AgentPrompts.sessionContext(PromptLang.CN, emptyList(), followUp = true))
    }

    @Test
    fun `追问时强调以上一轮任务为目标主体`() {
        val previous = listOf(
            PreviousTask(goal = "帮我在美团点一份黄焖鸡米饭", statusLabel = "已完成", conclusion = "已下单一份黄焖鸡米饭"),
            PreviousTask(goal = "查一下明天天气", statusLabel = "已完成"),
        )
        val text = AgentPrompts.sessionContext(PromptLang.CN, previous, followUp = true)
        assertTrue(text.contains("上一轮任务"))
        assertTrue(text.contains("帮我在美团点一份黄焖鸡米饭"))
        assertTrue(text.contains("结论：已下单一份黄焖鸡米饭"))
        assertTrue(text.contains("更早的任务1"))
        assertTrue(text.contains("追问"))
    }

    @Test
    fun `非追问时提示按相关性承接而不是硬套`() {
        val previous = listOf(PreviousTask(goal = "查一下明天天气", statusLabel = "已完成"))
        val text = AgentPrompts.sessionContext(PromptLang.CN, previous, followUp = false)
        assertFalse(text.contains("追问"))
        assertTrue(text.contains("独立任务"))
    }
}