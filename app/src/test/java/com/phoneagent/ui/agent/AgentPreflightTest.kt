package com.phoneagent.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送前能力预检的判据单测。
 *
 * 这条判据决定「用户能不能不发一言就起跑」：无障碍没开时一轮几乎注定失败，
 * 实测要空耗 40–60 秒。判据一旦放宽，用户又会回到「等一下才知道跑不起来」。
 */
class AgentPreflightTest {

    private fun needsConsent(
        a11yEnabled: Boolean,
        configProblem: String? = null,
        screenshotModeChosen: Boolean = false,
    ) = AgentPreflight.needsScreenshotConsent(
        a11yEnabled = a11yEnabled,
        configProblem = configProblem,
        screenshotModeChosen = screenshotModeChosen,
    )

    @Test
    fun `无障碍没开且没选过截图模式时必须先做一次明确选择`() {
        assertTrue(needsConsent(a11yEnabled = false))
    }

    @Test
    fun `无障碍可用时不再拦`() {
        assertFalse(needsConsent(a11yEnabled = true))
    }

    @Test
    fun `用户已明确选择截图模式后不再重复追问`() {
        assertFalse(needsConsent(a11yEnabled = false, screenshotModeChosen = true))
    }

    @Test
    fun `模型配置缺失时不问截图模式，交给引擎起跑前拦截`() {
        // 这一轮根本不会起跑（引擎在 TaskLifecycle.start 里直接给出配置失败卡），
        // 再问一次「要不要用截图模式」只会让用户先做一次无意义的选择
        assertFalse(needsConsent(a11yEnabled = false, configProblem = "API Key 未填写"))
    }
}
