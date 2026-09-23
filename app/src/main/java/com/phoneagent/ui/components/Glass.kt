package com.phoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
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
            ) {
                // 必须显式放行取样区，否则整块玻璃会一个字都不画（表现为完全透明）。
                // 原因：Haze 默认只画「zIndex 严格小于本层取样源」的区域，而取样源的 zIndex
                // 由 ModifierLocalCurrentHazeZIndex 逐层累加着传给后代。全局悬浮导航栏在内容层
                // 之外又套了一层取样源，它把 zIndex=0 传给了本页所有后代；本页自己的取样源
                // zIndex 也是 0，于是过滤条件 0 < 0 为 false，本页取样区被全部滤掉 →
                // areas 为空 → hazeEffect 直接跳过绘制。这里本面只取样同一 Box 下的兄弟取样源，
                // 不存在把自己画进取样层的自反馈，过滤没有意义，一律放行。
                canDrawArea = { true }
            }
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

/**
 * 玻璃页眉板距屏幕左右（含上缘）的外边距，与 Agent 页顶栏同一套形态。
 * 页眉内容若要与其他页面 20dp 的内容留白落在同一条竖直线上，
 * 得把这段外边距从 [AppTopBar] 的 contentPadding 里减掉，见 [GlassHeaderInnerPad]。
 */
val GlassHeaderInset = AppSpacing.Md

/** 玻璃页眉板内层内容的左右留白：补上板子自身的外边距，屏幕上仍是原来的 20dp */
val GlassHeaderInnerPad = 20.dp - GlassHeaderInset

/**
 * 玻璃页眉骨架：正文整屏铺开当取样源，页眉是一块浮在正文之上的玻璃板。
 *
 * 把「取样源 + 玻璃面必须是同层兄弟节点」这套约定收进骨架，页面只管摆内容：
 * ```
 * GlassHeaderScaffold(header = { AppTopBar("调试", contentPadding = ...) }) { pad ->
 *     LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding())) { ... }
 * }
 * ```
 * 正文必须把 [PaddingValues] 的顶部净空用在**滚动容器内部**——LazyColumn 的 contentPadding、
 * 或 verticalScroll 之后再 padding。用在滚动容器外面只是把内容整体压低，页眉背后永远是
 * 一块纯底色，玻璃会退化成一条灰蒙蒙的色带；用在里面，内容滚动时才会从玻璃下穿过。
 *
 * @param header 页眉内容，会被套进一块四角全圆的玻璃板
 * @param content 正文，参数是页眉实测高度 + 上缘外边距，供正文垫净空
 */
@Composable
fun GlassHeaderScaffold(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = AppTheme.colors
    val glass = rememberGlassState()
    val density = LocalDensity.current
    // 实测高度回填给正文，首项不会被压在玻璃页眉下面
    var headerHeight by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceBase),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(glass),
        ) {
            content(PaddingValues(top = with(density) { headerHeight.toDp() } + GlassHeaderInset))
        }

        GlassSurface(
            hazeState = glass,
            shape = RoundedCornerShape(AppRadii.Hero),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = GlassHeaderInset, end = GlassHeaderInset, top = GlassHeaderInset)
                .onSizeChanged { headerHeight = it.height },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                header()
                // 玻璃的圆角下沿不能贴着内容：留一口气，圆角才看得出来
                Spacer(Modifier.height(AppSpacing.Sm))
            }
        }
    }
}