package com.phoneagent.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤轴的回归护栏：
 * 1. 未知 / 空值必须回落经典，脏配置不能把界面切空白；
 * 2. 经典令牌保持改造前的数值，避免新主题顺手挪动老主题的留白与圆角；
 * 3. 石墨确实是一套新色板，不是现有色值换名；
 * 4. 两种模式下正文 / 次文本 / 主按钮 / 用户气泡都达到 4.5:1 的正文对比度。
 */
class ThemeSkinTest {

    @Test
    fun `legacy is the default skin`() {
        assertEquals("legacy", ThemeSkin.DEFAULT.id)
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.of("legacy"))
    }

    @Test
    fun `unknown skin falls back to legacy`() {
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.of(null))
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.of(""))
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.of("graphitee"))
    }

    @Test
    fun `legacy metrics keep the original values`() {
        val m = metricsFor(ThemeSkin.LEGACY)
        // 与改造前的字面量逐项对齐：这些值一旦漂移，老主题的版面就变了
        assertEquals(20f, m.pagePad.value)
        assertEquals(24f, m.sectionGap.value)
        assertEquals(16f, m.cardPad.value)
        assertEquals(24f, m.cardRadius.value)
        assertEquals(20f, m.itemRadius.value)
        assertEquals(14f, m.tileRadius.value)
        assertEquals(28f, m.heroRadius.value)
        assertEquals(32f, m.overlayRadius.value)
        assertEquals(36f, m.glassBlur.value)
        assertEquals(24f, m.glassBlurCompact.value)
        assertEquals(12f, m.navShadow.value)
        assertEquals(45, m.staggerMs)
        assertTrue(m.roundIconActions)
        assertTrue(m.glassSheen)
        assertTrue(m.sectionBarWidth > androidx.compose.ui.unit.Dp.Hairline)
    }

    @Test
    fun `graphite changes geometry and motion as a whole set`() {
        val m = metricsFor(ThemeSkin.GRAPHITE)
        val legacy = metricsFor(ThemeSkin.LEGACY)
        assertNotEquals(legacy.pagePad, m.pagePad)
        assertNotEquals(legacy.cardRadius, m.cardRadius)
        assertNotEquals(legacy.sectionRadius, m.sectionRadius)
        assertNotEquals(legacy.glassBlur, m.glassBlur)
        assertNotEquals(legacy.staggerMs, m.staggerMs)
        assertEquals(0f, m.glassNoise)
        assertEquals(0f, m.navShadow.value)
        assertTrue(m.backdropWash)
    }

    @Test
    fun `graphite palette is not copied from existing palettes`() {
        listOf(LightAppColors, LightContrastAppColors).forEach {
            assertNotEquals(it.surfaceBase, GraphiteLightAppColors.surfaceBase)
            assertNotEquals(it.brand, GraphiteLightAppColors.brand)
        }
        listOf(DarkAppColors, DarkContrastAppColors).forEach {
            assertNotEquals(it.surfaceBase, GraphiteDarkAppColors.surfaceBase)
            assertNotEquals(it.brand, GraphiteDarkAppColors.brand)
        }
    }

    @Test
    fun `graphite text pairs meet body contrast`() {
        assertContrast(GraphiteLightAppColors)
        assertContrast(GraphiteDarkAppColors)
        assertContrast(GraphiteLightContrastAppColors)
        assertContrast(GraphiteDarkContrastAppColors)
    }

    private fun assertContrast(c: AppColors) {
        assertAtLeast4_5(c.onSurfaceBase, c.surfaceBase, "正文")
        assertAtLeast4_5(c.onSurfaceRaised, c.surfaceRaised, "次文本")
        assertAtLeast4_5(c.onBrand, c.brand, "主按钮文字")
        assertAtLeast4_5(c.onMessageBubbleUser, c.messageBubbleUser, "用户气泡文字")
        assertAtLeast4_5(c.onMessageBubbleAgent, c.messageBubbleAgent, "助手气泡文字")
        assertAtLeast4_5(c.onBrandContainer, c.brandContainer, "主色容器文字")
    }

    private fun assertAtLeast4_5(fg: Color, bg: Color, label: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$label 对比度 $ratio 低于 4.5:1", ratio >= 4.5f)
    }

    /** WCAG 2.x 相对亮度与对比度，纯 sRGB 计算，不依赖 Android 运行时 */
    private fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun relativeLuminance(c: Color): Float =
        0.2126f * linear(c.red) + 0.7152f * linear(c.green) + 0.0722f * linear(c.blue)

    private fun linear(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).let { it * it * it }
}
