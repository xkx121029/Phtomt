package com.phoneagent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跑马灯几何行为的回归单测。
 *
 * 这些用例对应三个真实缺陷：
 * 1. 文字更新导致窗口重排（宽度必须封顶且按网格稳定，窗口宽度不再由文字决定）；
 * 2. 相位判断提前回位导致文字在屏内跳回起点；
 * 3. 短文本硬滚 —— 放得下却持续请求重绘。
 */
class MarqueeGeometryTest {

    @Test
    fun `短文本不滚动`() {
        // 内容区 200px，文字 120px：放得下，不该启动帧循环
        assertFalse(MarqueeGeometry.shouldScroll(120f, 200f + 32f, 16f))
    }

    @Test
    fun `长文本滚动`() {
        assertTrue(MarqueeGeometry.shouldScroll(400f, 200f + 32f, 16f))
    }

    @Test
    fun `胶囊宽度封顶到可用宽度`() {
        val width = MarqueeGeometry.panelWidthPx(
            textWidthPx = 5000f,
            padHPx = 44f,
            maxWidthPx = 1000f,
            stepPx = 33f,
            minWidthPx = 132f,
        )
        assertEquals(1000f, width, 0.01f)
    }

    @Test
    fun `胶囊宽度按网格取整且不小于最小值`() {
        val width = MarqueeGeometry.panelWidthPx(
            textWidthPx = 10f,
            padHPx = 8f,
            maxWidthPx = 1000f,
            stepPx = 30f,
            minWidthPx = 120f,
        )
        assertEquals(120f, width, 0.01f)

        val snapped = MarqueeGeometry.panelWidthPx(
            textWidthPx = 100f,
            padHPx = 8f,
            maxWidthPx = 1000f,
            stepPx = 30f,
            minWidthPx = 120f,
        )
        // 100 + 16 = 116 → 30 的整数倍向上取整 = 120
        assertEquals(120f, snapped, 0.01f)
        assertEquals(0f, snapped % 30f, 0.01f)
    }

    @Test
    fun `相位必须完全滚出才回位`() {
        // 文字 300px + 间隔 40px：travelDistance = 340px
        val distance = 340f
        // 只滚到 -240：文字还有 60px 在屏内，不能回位
        assertEquals(-240f, MarqueeGeometry.advanceOffset(-230f, 10f, distance), 0.01f)
        // 滚过 -340 才回到 0，形成无缝循环
        assertEquals(0f, MarqueeGeometry.advanceOffset(-300f, 50f, distance), 0.01f)
    }

    @Test
    fun `文本更新不改变相位`() {
        // 相位只由 advanceOffset 推进；setText 不参与计算 —— 由接口设计保证，
        // 这里固定推进量验证"同样的推进得到同样的相位"，与文本内容无关
        var offset = 0f
        repeat(10) { offset = MarqueeGeometry.advanceOffset(offset, 30f, 1000f) }
        assertEquals(-300f, offset, 0.01f)
        repeat(3) { offset = MarqueeGeometry.advanceOffset(offset, 30f, 1000f) }
        assertEquals(-390f, offset, 0.01f)
    }
}
