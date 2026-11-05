package com.phoneagent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮层显示设置的判定点：默认值、脏数据回落、状态行文案拼装。
 *
 * 这三个东西一旦改错，用户看到的就是"文字消失"或"文字又重复"，所以用测试钉住。
 */
class OverlayDisplaySettingsTest {

    @Test
    fun `默认只保留一处 - 跑马灯`() {
        assertEquals(StepTextPlacement.MARQUEE, OverlayDisplaySettings.DEFAULT_STEP_TEXT_PLACEMENT)
        assertEquals(StepTextPlacement.MARQUEE, StepTextPlacement.DEFAULT)
        assertTrue(OverlayDisplaySettings.DEFAULT_EDGE_LIGHTING_ENABLED)
    }

    @Test
    fun `未写入或脏数据回落到默认`() {
        assertEquals(StepTextPlacement.MARQUEE, StepTextPlacement.fromValue(null))
        assertEquals(StepTextPlacement.MARQUEE, StepTextPlacement.fromValue(""))
        assertEquals(StepTextPlacement.MARQUEE, StepTextPlacement.fromValue("top_only"))
    }

    @Test
    fun `三个取值可往返`() {
        StepTextPlacement.values().forEach { placement ->
            assertEquals(placement, StepTextPlacement.fromValue(placement.value))
        }
        assertEquals(StepTextPlacement.CARD, StepTextPlacement.fromValue("card"))
        assertEquals(StepTextPlacement.BOTH, StepTextPlacement.fromValue("both"))
    }

    @Test
    fun `跑马灯模式下状态行只留步数`() {
        assertEquals(
            "第 3/6 步",
            StepTextContent.cardLabel(
                step = 3, total = 6, description = "正在核对订单", includeDescription = false,
            ),
        )
    }

    @Test
    fun `卡片模式下状态行带描述`() {
        assertEquals(
            "第 3/6 步 · 正在核对订单",
            StepTextContent.cardLabel(
                step = 3, total = 6, description = "正在核对订单", includeDescription = true,
            ),
        )
    }

    @Test
    fun `空闲态只显示状态文案`() {
        assertEquals(
            "等待任务...",
            StepTextContent.cardLabel(
                step = 0, total = 0, description = "等待任务...", includeDescription = true,
            ),
        )
    }

    @Test
    fun `总步数未知时只给当前步`() {
        assertEquals(
            "第 2 步",
            StepTextContent.cardLabel(
                step = 2, total = 0, description = "", includeDescription = true,
            ),
        )
    }

    @Test
    fun `描述为空时不留下悬空的分隔符`() {
        assertEquals(
            "第 1/3 步",
            StepTextContent.cardLabel(
                step = 1, total = 3, description = "   ", includeDescription = true,
            ),
        )
    }

    @Test
    fun `边缘光效默认开启且键名稳定`() {
        assertTrue(OverlayDisplaySettings.DEFAULT_EDGE_LIGHTING_ENABLED)
        assertEquals("overlay_display", OverlayDisplaySettings.PREFS_NAME)
        assertEquals("step_text_placement", OverlayDisplaySettings.KEY_STEP_TEXT_PLACEMENT)
        assertEquals("edge_lighting_enabled", OverlayDisplaySettings.KEY_EDGE_LIGHTING_ENABLED)
        assertFalse(OverlayDisplaySettings.DEFAULT_STEP_TEXT_PLACEMENT == StepTextPlacement.CARD)
    }
}
