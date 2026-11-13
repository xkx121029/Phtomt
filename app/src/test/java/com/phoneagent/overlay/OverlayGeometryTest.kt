package com.phoneagent.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮层位置计算。
 *
 * 这些公式原先内联在 [FloatingWindowService] 里、零测试覆盖，而算错一像素的表现是
 * 「细条压在胶囊上」或「被手势条挡住」——只能靠真机肉眼发现。抽出 [OverlayGeometry] 后在这里钉住。
 */
class OverlayGeometryTest {

    // ---------------- marqueeBottomOffset ----------------

    @Test
    fun `无导航条时用基础留白`() {
        // 0.5cm ≈ 19dp，按 3x 密度约 57px；光晕留白 0
        assertEquals(57, OverlayGeometry.marqueeBottomOffset(navBarInset = 0, minGapPx = 57, glowPadPx = 0))
    }

    @Test
    fun `手势条更高时让到手势区之上`() {
        // 手势条 120px 大于基础留白 57px，应取 120
        assertEquals(120, OverlayGeometry.marqueeBottomOffset(navBarInset = 120, minGapPx = 57, glowPadPx = 0))
    }

    @Test
    fun `光晕留白从落点里扣掉_胶囊视觉位置不变`() {
        // 加光晕后窗口更高，落点要减去这一截，否则胶囊会被顶高
        assertEquals(100, OverlayGeometry.marqueeBottomOffset(navBarInset = 120, minGapPx = 57, glowPadPx = 20))
    }

    @Test
    fun `扣掉光晕后不会出现负偏移`() {
        assertEquals(0, OverlayGeometry.marqueeBottomOffset(navBarInset = 0, minGapPx = 10, glowPadPx = 50))
    }

    // ---------------- capsuleThickness ----------------

    @Test
    fun `胶囊厚度扣掉上下两圈光晕`() {
        assertEquals(60, OverlayGeometry.capsuleThickness(windowHeight = 100, glowPadPx = 20, fallbackPx = 48))
    }

    @Test
    fun `未布局时用经验值兜底`() {
        assertEquals(48, OverlayGeometry.capsuleThickness(windowHeight = 0, glowPadPx = 20, fallbackPx = 48))
    }

    @Test
    fun `窗口比光晕还小时厚度为0而不是负数`() {
        assertEquals(0, OverlayGeometry.capsuleThickness(windowHeight = 30, glowPadPx = 20, fallbackPx = 48))
    }

    // ---------------- noticeBottomOffset ----------------

    @Test
    fun `细条落在胶囊上方_间隔一个内边距`() {
        val y = OverlayGeometry.noticeBottomOffset(
            marqueeBottomPx = 100,
            capsuleVisible = true,
            windowHeight = 140,
            glowPadPx = 20,
            fallbackCapsulePx = 48,
            gapPx = 8,
        )
        // 胶囊厚度 = 140 - 2*20 = 100；100 + 100 + 8
        assertEquals(208, y)
    }

    @Test
    fun `胶囊不可见时仍留出同样空间_避免细条下跳`() {
        val y = OverlayGeometry.noticeBottomOffset(
            marqueeBottomPx = 100,
            capsuleVisible = false,
            windowHeight = 140,
            glowPadPx = 20,
            fallbackCapsulePx = 48,
            gapPx = 8,
        )
        assertEquals(156, y)
    }

    @Test
    fun `胶囊可见但尚未布局_用兜底高度`() {
        val y = OverlayGeometry.noticeBottomOffset(
            marqueeBottomPx = 100,
            capsuleVisible = true,
            windowHeight = 0,
            glowPadPx = 20,
            fallbackCapsulePx = 48,
            gapPx = 8,
        )
        assertEquals(156, y)
    }
}
