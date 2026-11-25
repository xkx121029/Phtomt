package com.phoneagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计令牌的纯逻辑单测（`:ui:design` 自己的那份）。
 *
 * ## 这里能测什么、为什么只测这些
 *
 * Compose 的**测量**（谁画多宽、浮层量成几像素）在 JVM 单测里拿不到——本仓库没有 Robolectric、
 * androidTest 0 文件、CI 也没有模拟器（见 CREW.md「浮层尺寸类缺陷加不出 CI 闸门」）。
 * 所以本文件只钉三类**纯函数/纯数据**的东西，它们改坏了单测会红，而不是复述实现：
 *
 * 1. 令牌取值与排序（形状/间距阶梯、"经典皮肤与改造前逐项一致"）；
 * 2. 语义映射（皮肤 → 几何量、配色 → 浅/深是否判为浅底、脏数据归一）；
 * 3. 磨砂浓淡的唯一实现点 [glassTintWithOffset]（只动 alpha、夹在 40~230）。
 *
 * 组件层的 @Composable 不在测试范围：调用它们需要 Compose 运行时与测量，JVM 上做不到，
 * 硬写一条「永远绿」的断言比没有测试更糟。
 */
class DesignTokensTest {

    // ---------------------------------------------------------------------
    // 1. 取值与阶梯：只搬不改的机械证据
    // ---------------------------------------------------------------------

    @Test
    fun `经典皮肤的几何量与改造前逐项一致`() {
        // 这九个数是引入皮肤轴之前写在各页里的值，KDoc 里也逐项列了同样的数字。
        // 搬进模块时它们一个都不许动——这条断言就是「只搬不改」的夹具。
        assertEquals("页面左右留白", 20.dp, LegacyMetrics.pagePad)
        assertEquals("分区竖向节奏", 24.dp, LegacyMetrics.sectionGap)
        assertEquals("卡片内边距", 16.dp, LegacyMetrics.cardPad)
        assertEquals("分组大卡圆角", 24.dp, LegacyMetrics.cardRadius)
        assertEquals("列表项圆角", 20.dp, LegacyMetrics.itemRadius)
        assertEquals("瓦片圆角", 14.dp, LegacyMetrics.tileRadius)
        assertEquals("胶囊圆角", 10.dp, LegacyMetrics.chipRadius)
        assertEquals("Hero 圆角", 28.dp, LegacyMetrics.heroRadius)
        assertEquals("覆盖层圆角", 32.dp, LegacyMetrics.overlayRadius)
        assertEquals("发丝线宽", 1.dp, LegacyMetrics.hairline)
    }

    @Test
    fun `形状令牌由内到外逐档不减小`() {
        val ladder = listOf(
            "Chip" to AppRadii.Chip,
            "Inline" to AppRadii.Inline,
            "Tile" to AppRadii.Tile,
            "Bubble" to AppRadii.Bubble,
            "Item" to AppRadii.Item,
            "Card" to AppRadii.Card,
            "Hero" to AppRadii.Hero,
            "Overlay" to AppRadii.Overlay,
        )
        ladder.zipWithNext().forEach { (inner, outer) ->
            assertTrue(
                "内层 ${inner.first}=${inner.second} 不应大于外层 ${outer.first}=${outer.second}",
                inner.second <= outer.second,
            )
        }
        assertTrue("胶囊不能是 0 圆角", AppRadii.Chip > 0.dp)
    }

    @Test
    fun `间距令牌逐档增大且页面留白落在 Lg 与 Xl 之间`() {
        assertTrue(AppSpacing.Xxs < AppSpacing.Xs)
        assertTrue(AppSpacing.Xs < AppSpacing.Sm)
        assertTrue(AppSpacing.Sm < AppSpacing.Md)
        assertTrue(AppSpacing.Md < AppSpacing.Lg)
        assertTrue(AppSpacing.Lg < AppSpacing.Page)
        assertTrue(AppSpacing.Page < AppSpacing.Xl)
        assertTrue(AppSpacing.Xl < AppSpacing.Xxl)
        // 页边距必须与经典皮肤的 pagePad 同一格：两者写的是同一件事（屏幕左右留白）
        assertEquals("间距令牌与皮肤令牌对页面留白的口径必须一致", AppSpacing.Page, LegacyMetrics.pagePad)
    }

    @Test
    fun `两套皮肤的形状阶梯各自成立`() {
        listOf("经典" to LegacyMetrics, "石墨" to GraphiteMetrics).forEach { (name, m) ->
            assertTrue("$name：胶囊 ≤ 瓦片", m.chipRadius <= m.tileRadius)
            assertTrue("$name：瓦片 ≤ 列表项", m.tileRadius <= m.itemRadius)
            assertTrue("$name：列表项 ≤ 大卡", m.itemRadius <= m.cardRadius)
            assertTrue("$name：大卡 ≤ Hero", m.cardRadius <= m.heroRadius)
            assertTrue("$name：Hero ≤ 覆盖层", m.heroRadius <= m.overlayRadius)
        }
    }

    // ---------------------------------------------------------------------
    // 2. 语义映射
    // ---------------------------------------------------------------------

    @Test
    fun `皮肤映射到它自己的几何量`() {
        assertEquals(LegacyMetrics, metricsFor(ThemeSkin.LEGACY))
        assertEquals(GraphiteMetrics, metricsFor(ThemeSkin.GRAPHITE))
        // 两套皮肤不能是同一份数据，否则「换皮肤」在几何上什么也没发生
        assertNotEquals(
            "两套皮肤的页面留白不该相同",
            metricsFor(ThemeSkin.LEGACY).pagePad,
            metricsFor(ThemeSkin.GRAPHITE).pagePad,
        )
        assertNotEquals(
            "两套皮肤的大卡圆角不该相同",
            metricsFor(ThemeSkin.LEGACY).cardRadius,
            metricsFor(ThemeSkin.GRAPHITE).cardRadius,
        )
    }

    @Test
    fun `每套配色的浅色判为浅底、深色判为深底`() {
        ThemePalette.entries.forEach { palette ->
            assertTrue("${palette.id} 的浅色底色应判为浅底", isLightSurface(palette.light.surfaceBase))
            assertFalse("${palette.id} 的深色底色应判为深底", isLightSurface(palette.dark.surfaceBase))
            assertTrue("${palette.id} 的高对比浅色应判为浅底", isLightSurface(palette.lightContrast.surfaceBase))
            assertFalse("${palette.id} 的高对比深色应判为深底", isLightSurface(palette.darkContrast.surfaceBase))
        }
    }

    @Test
    fun `石墨皮肤的浅深底色同样各就各位`() {
        assertTrue(isLightSurface(GraphiteLightAppColors.surfaceBase))
        assertFalse(isLightSurface(GraphiteDarkAppColors.surfaceBase))
        assertTrue(isLightSurface(GraphiteLightContrastAppColors.surfaceBase))
        assertFalse(isLightSurface(GraphiteDarkContrastAppColors.surfaceBase))
    }

    @Test
    fun `同一套配色的浅色与深色不是同一个底色`() {
        ThemePalette.entries.forEach { palette ->
            assertNotEquals("${palette.id} 的浅深底色相同", palette.light.surfaceBase, palette.dark.surfaceBase)
        }
        assertNotEquals(GraphiteLightAppColors.surfaceBase, GraphiteDarkAppColors.surfaceBase)
    }

    @Test
    fun `皮肤与风格轴的脏数据归一回落到默认值`() {
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.of(null))
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.of(""))
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.of("no_such_skin"))
        assertEquals(ThemeSkin.GRAPHITE, ThemeSkin.of("graphite"))

        assertEquals(UiStyle.CLASSIC, UiStyle.of(null))
        assertEquals(UiStyle.CLASSIC, UiStyle.of("no_such_style"))
        assertEquals(UiStyle.WORKBENCH, UiStyle.of("workbench"))

        assertEquals(ThemePalette.INK_GREEN, ThemePalette.of(null))
        assertEquals(ThemePalette.INK_GREEN, ThemePalette.of("no_such_palette"))
        ThemePalette.entries.forEach { assertEquals(it, ThemePalette.of(it.id)) }
    }

    @Test
    fun `配色的存储 id 互不重复`() {
        // id 重复会让 ThemePalette.of 的解析结果取决于枚举顺序，而存的是字符串——
        // 用户切到「绛紫」再重启可能回到「赭石」，且没有任何报错
        val ids = ThemePalette.entries.map { it.id }
        assertEquals("配色 id 必须唯一", ids.size, ids.toSet().size)
        val skinIds = ThemeSkin.entries.map { it.id }
        assertEquals("皮肤 id 必须唯一", skinIds.size, skinIds.toSet().size)
    }

    @Test
    fun `工作台风格的色调位移比经典更收敛`() {
        // 工作台靠发丝线与分区表达层次，底色差要收窄；写反了整页会读成一层层浮起的卡片
        assertTrue(UiStyle.WORKBENCH.toneScale < UiStyle.CLASSIC.toneScale)
        assertEquals(UiStyle.CLASSIC, UiStyle.DEFAULT)
        assertEquals(ThemeSkin.LEGACY, ThemeSkin.DEFAULT)
    }

    // ---------------------------------------------------------------------
    // 3. 磨砂浓淡：alpha 位移的唯一实现点
    // ---------------------------------------------------------------------

    @Test
    fun `磨砂浓淡只动 alpha 不动 RGB`() {
        val base = Color(0x87F7F6F3)
        val shifted = glassTintWithOffset(base, 10)
        assertEquals("R 不该被位移", base.red, shifted.red, 0f)
        assertEquals("G 不该被位移", base.green, shifted.green, 0f)
        assertEquals("B 不该被位移", base.blue, shifted.blue, 0f)
        // 0x87 = 135/255，+10 个百分比点 → 145/255
        assertEquals("alpha 应平移 10 个点", 145f / 255f, shifted.alpha, 0.004f)
    }

    @Test
    fun `磨砂浓淡的 alpha 夹在 40 到 230`() {
        val half = Color(0x80FFFFFF)
        assertEquals("向下越界要夹到 40", 40f / 255f, glassTintWithOffset(half, -1000).alpha, 0.004f)
        assertEquals("向上越界要夹到 230", 230f / 255f, glassTintWithOffset(half, 1000).alpha, 0.004f)
        // 夹紧之后仍然是同一个色相
        assertEquals(Color(0x80FFFFFF).green, glassTintWithOffset(half, 1000).green, 0f)
    }

    @Test
    fun `零位移保持原样`() {
        val base = Color(0x73141816)
        val same = glassTintWithOffset(base, 0)
        assertEquals("alpha 不该变", base.alpha, same.alpha, 0.002f)
        assertEquals("R 不该变", base.red, same.red, 0f)
        assertEquals("G 不该变", base.green, same.green, 0f)
        assertEquals("B 不该变", base.blue, same.blue, 0f)
    }
}
