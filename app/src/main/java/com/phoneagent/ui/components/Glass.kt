package com.phoneagent.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * 毛玻璃（亚克力 / 磨砂）材质。
 *
 * ## 用在哪，不用在哪
 * 只有**真正浮在滚动内容之上**的固定 chrome 才配得上毛玻璃：顶栏、底部输入区、
 * 浮层面板。平铺在页面底色上的卡片一律不要用——它背后没有任何东西可模糊，
 * 玻璃只会退化成一层灰蒙蒙的半透明，反而把层次搅乱。
 *
 * ## 怎么用
 * 取样源与玻璃面必须放在同一个 Box 里当兄弟节点，取样源在前：
 * ```
 * val glass = rememberGlassState()
 * Box {
 *     LazyColumn(Modifier.hazeSource(glass)) { ... }   // 被模糊的内容
 *     GlassSurface(glass, Modifier.align(Alignment.TopCenter)) { ... }  // 玻璃面
 * }
 * ```
 * 注意不要把 [hazeSource] 套在 [GlassSurface] 的外层祖先上：那样玻璃会把
 * 自己画进取样层，形成自反馈。
 *
 * ## 掉帧与平台差异
 * 背景模糊走 RenderEffect，Android 12 以下（以及部分已知有问题的机型）会关闭模糊，
 * 直接使用 [dev.chrisbanes.haze.HazeDefaults.blurEnabled] 的判定结果；
 * 此时由接近不透明的降级底色兜底，滚动内容不会糊在文字后面。
 * 系统「高对比度文字」开启时，色板里的玻璃底色本身就被换成了近乎不透明，
 * 透明度不再参与可读性，这一点在 [com.phoneagent.ui.theme.AppColors] 里落实。
 */
object GlassTokens {
    /** 大面 chrome（顶栏、输入区、悬浮导航栏）的模糊半径：看得清是"透过去的"，又不会糊成一团 */
    val Blur = 36.dp

    /** 单块浮层用的更克制的模糊半径 */
    val BlurCompact = 24.dp

    /** 噪点量：只做到"能感觉到颗粒"，再高就变成廉价的磨砂贴图了 */
    const val Noise = 0.04f

    /** 顶部受光高光在竖直方向上的衰减位置（占整体高度的比例）。压得短一点，高光就只是一层薄光晕 */
    const val SheenFade = 0.34f
}

/** 毛玻璃取样源状态。一个页面一个，交给 [hazeSource] 与 [GlassSurface] 共用。 */
@Composable
fun rememberGlassState(): HazeState = remember { HazeState() }

/**
 * 玻璃面：背景模糊 + 底色 + 顶部受光高光 + 品牌色发丝描边。
 *
 * @param hazeState 与本面同层的取样源状态（由 [rememberGlassState] 创建）
 * @param shape 圆角形状。玻璃绘制会被它裁切，所以形状必须在这里给，
 *   不要写在外层 modifier 上（否则边框与模糊会被裁到两个不同的范围）
 * @param blurRadius 模糊半径，默认取 [GlassTokens.Blur]
 * @param showSheen 是否画顶部高光。只有贴边的大面 chrome 需要它；
 *   四边都有留白的小浮层再加高光会显得脏
 */
@Composable
fun GlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadii.Card),
    blurRadius: Dp = GlassTokens.Blur,
    showSheen: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    // 不透明底色：节点边界处若取样源没覆盖到，露出的应当就是页面底色
                    backgroundColor = colors.surfaceBase,
                    // 叠在模糊结果之上的一层底色，浓淡由它决定
                    tint = HazeTint(colors.glassTint),
                    blurRadius = blurRadius,
                    noiseFactor = GlassTokens.Noise,
                    // 不支持背景模糊时的替身：接近不透明，保证文字仍然读得清
                    fallbackTint = HazeTint(colors.glassFallback),
                ),
            )
            .border(1.dp, colors.glassBorder, shape),
    ) {
        if (showSheen && colors.glassSheen.alpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to colors.glassSheen,
                                    GlassTokens.SheenFade to Color.Transparent,
                                ),
                            )
                        )
                    }
            )
        }
        content()
    }
}