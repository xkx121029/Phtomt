package com.phoneagent.engine.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码任务引导块必须说真话（HAPP-32）。
 *
 * 旧文案告诉模型「任务会被委托给设备端独立的编码 agent 执行」，但委托链路
 * `CodingAgentBridge.prompt()` 全仓没有调用方，`IntentRouter` 只有 AGENT/CHAT 两条路，
 * 编码是由入口页区分的。于是模型会在 Agent 页假装转达、假报进度，用户却什么都没发生。
 * 现在的口径：本页不写代码，用 say 引导用户到编码页。
 */
class CodingPromptTruthTest {

    @Test
    fun `编码提示词不再承诺会把任务委托出去`() {
        assertFalse(
            "委托链路没有调用方，不能这样承诺：${PromptBodies.SYS_CODING_CN}",
            PromptBodies.SYS_CODING_CN.contains("委托给"),
        )
        assertFalse(
            "no delegation link exists: ${PromptBodies.SYS_CODING_EN}",
            PromptBodies.SYS_CODING_EN.contains("is delegated to"),
        )
    }

    @Test
    fun `编码提示词把用户引到编码页`() {
        assertTrue(
            "必须明确告诉用户去哪儿下达：${PromptBodies.SYS_CODING_CN}",
            PromptBodies.SYS_CODING_CN.contains("「编码」页"),
        )
        assertTrue(
            "must name the destination: ${PromptBodies.SYS_CODING_EN}",
            PromptBodies.SYS_CODING_EN.contains("Coding page"),
        )
    }

    @Test
    fun `编码提示词仍然禁止用手机操控去假装写代码`() {
        assertTrue(
            "否则模型会去点屏幕假装实现：${PromptBodies.SYS_CODING_CN}",
            PromptBodies.SYS_CODING_CN.contains("不要为了"),
        )
    }
}
