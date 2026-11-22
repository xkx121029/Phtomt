package com.phoneagent.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送前能力预检的判据单测。
 *
 * 这条判据决定「用户能不能不发一言就起跑」：无障碍没开时一轮几乎注定失败，
 * 实测要空耗 40–60 秒。判据一旦放宽，用户又会回到「等一下才知道跑不起来」。
 *
 * 反过来把「模型配置可用」也当前提同样是错的：`configProblem` 在「上次连接测试
 * 失败」时同样非空，那正是首次配置必经的状态，闸门会在最该拦的时候整条失效
 * （HAPP-58 实测：无障碍没开、没选过截图模式，点发送却没有任何询问）。
 */
class AgentPreflightTest {

    private fun ask(
        a11yEnabled: Boolean,
        configProblem: String? = null,
        screenshotModeChosen: Boolean = false,
    ) = AgentPreflight.ask(
        a11yEnabled = a11yEnabled,
        configProblem = configProblem,
        screenshotModeChosen = screenshotModeChosen,
    )

    @Test
    fun `无障碍没开且没选过截图模式时必须先做一次明确选择`() {
        assertTrue(ask(a11yEnabled = false).consent)
    }

    @Test
    fun `无障碍可用时不再拦`() {
        assertFalse(ask(a11yEnabled = true).consent)
    }

    @Test
    fun `用户已明确选择截图模式后不再重复追问`() {
        assertFalse(ask(a11yEnabled = false, screenshotModeChosen = true).consent)
    }

    @Test
    fun `模型配置有问题时仍然要问，配置只作为附注出现`() {
        // 「上次连接测试失败」是最常见的首次配置状态。这条断言钉住的是：配置问题
        // 只能进 configNote，不能把 consent 压成 false（早先的实现就是那样，
        // 于是这次发送连一句询问都没有，直接落到失败终态卡）
        val problem = "上次连接测试失败：HTTP 401。请到「设置 → 云端模型」修好 Key / 地址后重新「测试连接」再下达任务。"
        val ask = ask(a11yEnabled = false, configProblem = problem)
        assertTrue("配置问题不能顶掉预检", ask.consent)
        assertEquals(problem, ask.configNote)
    }

    @Test
    fun `无障碍可用时配置问题也只是附注，不进预检`() {
        val ask = ask(a11yEnabled = true, configProblem = "API Key 未填写")
        assertFalse(ask.consent)
        assertEquals("API Key 未填写", ask.configNote)
    }

    @Test
    fun `无障碍可用且配置可用时没有附注`() {
        assertNull(ask(a11yEnabled = true).configNote)
    }
}
