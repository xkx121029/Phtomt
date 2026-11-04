package com.phoneagent.overlay

import android.view.WindowManager
import com.phoneagent.core.window.OverlayWindowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮层窗口参数回归单测。
 *
 * 顶部光带"压在状态栏上"依赖四件事，任一缺失都会整体下移一条状态栏：
 * 真实屏幕尺寸、fitInsetsTypes=0、cutout=ALWAYS、LAYOUT_IN_SCREEN|NO_LIMITS。
 * 前三项是 LayoutParams 的赋值（只能在真机/截图回归里看到），这里固化可测的部分：
 * FLAG 组合、窗口类型、以及光带/胶囊的尺寸推导。
 */
class OverlayWindowSpecTest {

    @Test
    fun `被动覆盖层带上 LAYOUT_IN_SCREEN 与 NO_LIMITS`() {
        val flags = OverlayWindowSpec.passiveFlags()
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        // 只显示、不吃触摸
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }

    @Test
    fun `覆盖层窗口类型必须是 APPLICATION_OVERLAY 而不是 ACCESSIBILITY_OVERLAY`() {
        // API 26 及以上必须用 TYPE_APPLICATION_OVERLAY：它不依赖无障碍服务存活
        assertEquals(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OverlayWindowSpec.overlayWindowTypeFor(26),
        )
        assertEquals(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OverlayWindowSpec.overlayWindowTypeFor(34),
        )
        assertTrue(
            OverlayWindowSpec.overlayWindowTypeFor(34) !=
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        )
    }

    @Test
    fun `光带高度不小于状态栏 inset`() {
        // 状态栏 72px、最小可读高度 90px：取 90，保证文字放得下
        assertEquals(90, OverlayWindowSpec.marqueeBandHeightPx(72, 90))
        // 状态栏 120px：取 120，保证完全盖住状态栏
        assertEquals(120, OverlayWindowSpec.marqueeBandHeightPx(120, 90))
    }

    @Test
    fun `底部胶囊窗口宽度按屏幕宽去掉两侧留白`() {
        assertEquals(720, OverlayWindowSpec.marqueeWidthPx(1080, 180))
        // 极端窄屏也至少留出一像素的内容区
        assertTrue(OverlayWindowSpec.marqueeWidthPx(100, 180) > 0)
    }
}
