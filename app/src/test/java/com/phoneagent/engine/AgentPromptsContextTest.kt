package com.phoneagent.engine

import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.ActionPolicy
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

    // ---- 动作模式（授权范围）提示词 ----

    @Test
    fun `保守模式列出低风险清单并声明其余被拒`() {
        val text = AgentPrompts.actionModeSection(PromptLang.CN, ActionMode.CONSERVATIVE)
        assertTrue(text.contains("保守"))
        assertTrue("应列出低风险意图", text.contains("refresh"))
        assertTrue("应提供换档指路", text.contains("均衡"))
        // 自由模式专属内容不该出现在保守档
        assertFalse(text.contains("intent=shell"))
        assertFalse(text.contains("click_node"))
    }

    @Test
    fun `均衡模式声明全意图可用但不含自由专属`() {
        val text = AgentPrompts.actionModeSection(PromptLang.CN, ActionMode.BALANCED)
        assertTrue(text.contains("全部意图"))
        assertTrue(text.contains("自由模式"))
        assertFalse(text.contains("intent=a11y"))
    }

    @Test
    fun `自由模式给出命令表与端点全表`() {
        val text = AgentPrompts.actionModeSection(PromptLang.CN, ActionMode.FREE)
        assertTrue("应带上友好命令表", text.contains("友好命令"))
        assertTrue("应说明首次确认", text.contains("确认"))
        // 端点表必须与白名单同源：逐个端点都应在提示词里出现
        ActionPolicy.a11yEndpoints.forEach {
            assertTrue("提示词缺少端点 ${it.name}", text.contains(it.name))
        }
    }

    @Test
    fun `自由模式铁律二放开自写命令_其余档位仍然禁止`() {
        val free = AgentPrompts.system(PromptLang.CN, "", hasVision = true, shizukuAvailable = true, actionMode = ActionMode.FREE)
        assertTrue("自由档应明确允许 shell 与 a11y", free.contains("intent=shell") && free.contains("intent=a11y"))

        val balanced = AgentPrompts.system(PromptLang.CN, "", hasVision = true, shizukuAvailable = true)
        assertTrue("默认档位仍禁止输出 shell 命令", balanced.contains("禁止输出 shell 命令"))
    }

    @Test
    fun `自定义提示词也照样追加授权范围一段`() {
        // 用户自定义系统提示不该成为"绕过门控说明书"的口子
        val text = AgentPrompts.system(
            PromptLang.CN, "自定义", hasVision = false, shizukuAvailable = false,
            actionMode = ActionMode.CONSERVATIVE,
        )
        assertTrue(text.startsWith("自定义"))
        assertTrue(text.contains("动作模式"))
    }

    @Test
    fun `英文档位提示词不混入中文`() {
        val conservative = AgentPrompts.actionModeSection(PromptLang.EN, ActionMode.CONSERVATIVE)
        assertTrue(conservative.contains("Action Mode"))
        assertTrue(conservative.contains("Conservative"))
        assertFalse(conservative.contains("动作模式"))

        // 自由档内容最多（命令表 + 端点全表 + 示例），是英文提示词最容易被污染的一档
        val free = AgentPrompts.actionModeSection(PromptLang.EN, ActionMode.FREE)
        assertTrue(free.contains("Free"))
        val cjk = free.filter { it.code in 0x4E00..0x9FFF }.take(20)
        assertFalse("英文提示词混入中文字符：$cjk", free.any { it.code in 0x4E00..0x9FFF })
    }
}