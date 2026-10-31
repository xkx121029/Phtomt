package com.phoneagent.domain.rules

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页面路径边动作文案回归。
 *
 * 这些文案会落进页面记忆的路径边并在提示里复用：同一控件用 id 或文字定位必须得到同一句文案，
 * 否则「上页 —[动作]→ 本页」会碎成多条。本轮把内联在 PageMemoryRecorder 的 when 下沉为
 * [PageActionLabels]，用本测试钉住原有措辞。
 */
class PageActionLabelsTest {

    private fun intent(
        type: String,
        target: AgentIntentTarget? = null,
        app: String? = null,
        direction: String? = null,
        key: String? = null,
    ) = AgentIntent(intent = type, target = target, app = app, direction = direction, key = key)

    @Test
    fun `点击类意图统一为点击文案`() {
        val labels = listOf(
            intent(IntentType.TAP, AgentIntentTarget("text", "立即购买")),
            intent(IntentType.SEND, AgentIntentTarget("text", "发送")),
            intent(IntentType.CONFIRM, AgentIntentTarget("text", "确认")),
            intent(IntentType.DELETE, AgentIntentTarget("text", "删除")),
        ).map { PageActionLabels.of(it, emptyMap()) }
        assertEquals("点击『立即购买』", labels[0])
        assertEquals("点击『发送』", labels[1])
        assertEquals("点击『确认』", labels[2])
        assertEquals("点击『删除』", labels[3])
    }

    @Test
    fun `by id 定位时用反查表还原控件文字`() {
        val it = intent(IntentType.TAP, AgentIntentTarget("id", "send_btn"))
        assertEquals("点击『发送』", PageActionLabels.of(it, mapOf("send_btn" to "发送")))
        // 反查不到时退回原始 value
        assertEquals("点击『send_btn』", PageActionLabels.of(it, emptyMap()))
    }

    @Test
    fun `无目标的固定动作`() {
        assertEquals("返回", PageActionLabels.of(intent(IntentType.BACK), emptyMap()))
        assertEquals("回桌面", PageActionLabels.of(intent(IntentType.HOME), emptyMap()))
        assertEquals("直达页面", PageActionLabels.of(intent(IntentType.OPEN), emptyMap()))
        assertEquals("打开网页", PageActionLabels.of(intent(IntentType.BROWSE_OPEN), emptyMap()))
    }

    @Test
    fun `带参数的意图`() {
        assertEquals(
            "打开美团",
            PageActionLabels.of(intent(IntentType.OPEN_APP, app = "美团"), emptyMap()),
        )
        assertEquals(
            "滑动up",
            PageActionLabels.of(intent(IntentType.SWIPE, direction = "up"), emptyMap()),
        )
        assertEquals(
            "按键BACK",
            PageActionLabels.of(intent(IntentType.PRESS, key = "BACK"), emptyMap()),
        )
        assertEquals(
            "输入『手机号』",
            PageActionLabels.of(intent(IntentType.INPUT, AgentIntentTarget("text", "手机号")), emptyMap()),
        )
    }

    @Test
    fun `未收录的意图不产生文案`() {
        assertEquals("", PageActionLabels.of(intent(IntentType.WAIT), emptyMap()))
    }
}
