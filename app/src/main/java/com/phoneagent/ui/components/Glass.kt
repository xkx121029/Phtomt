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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/** 页眉从"吸顶"过渡到"浮起"需要滚动的距离：滚过这么多就完全浮起，再滚也不会继续抬 */
internal val HeaderLiftDistance = 48.dp

/**
 * 页眉浮起时在上缘额外让出的距离：完全浮起时上缘外边距是
 * [GlassHeaderInset] + [HeaderLiftAmount]，吸顶时归 0。
 */
internal val HeaderLiftAmount = 12.dp

/**
 * 页眉吸顶状态：**贴顶时是一条通栏直角的吸顶栏，离顶后才收成一块圆角浮板**。
 *
 * 两端形态由同一个 [progress] 插值出来（[sideInset] / [topInset] / [cornerRadius]），
 * 中间态跟着手指连续变化，因此"到顶"这件事不需要单独判定一个布尔量：
 * - progress = 0：页面就在最顶上，页眉背后没有任何内容，"浮板"没有存在理由，
 *   只会在顶部平白多出一圈留白——于是左右不留白、上缘贴住内容区上沿、四角全直角；
 * - progress = 1：页面已离顶，页眉真正压在滚动内容之上，才收成四角全圆的浮板。
 *
 * 离顶距离不去各页要——调试页与技能页的滚动容器藏在页签面板里，逐个透传会把改动
 * 摊到整棵组件树——改为从嵌套滚动里听：它自己就是一个 [NestedScrollConnection]，
 * 挂在页面根节点上（见 [headerLift]），页面里**所有**滚动容器派发的位移都会汇总到这里。
 *
 * 只听"被消费掉的位移"（没被消费说明滚不动），并在两处夹住，
 * 免得滚到底继续拉把浮起量推高、回到顶部却收不回来：
 * - 想往上滚却一点没被消费 → 已经在最顶上，直接复位；
 * - 累计量夹在 [0, 浮起距离] 之间，越界不再累加。
 */
@Stable
class HeaderLiftState internal constructor(private val distancePx: Float) : NestedScrollConnection {
    private var scrolled by mutableFloatStateOf(0f)

    /**
     * 0f = 贴顶吸顶（通栏直角）；1f = 完全浮起（圆角浮板）。
     * 直接跟着滚动量连续变化，不再补一层动画——补了反而会落后于手指。
     */
    val progress: Float get() = (scrolled / distancePx).coerceIn(0f, 1f)

    /** 左右外边距：吸顶时 0（通栏到屏幕两沿），浮起时为 [GlassHeaderInset] */
    val sideInset: Dp get() = GlassHeaderInset * progress

    /** 上缘外边距：吸顶时 0（贴住内容区上沿），浮起时为 [GlassHeaderInset] + [HeaderLiftAmount] */
    val topInset: Dp get() = (GlassHeaderInset + HeaderLiftAmount) * progress

    /** 四角圆角：吸顶时 0（四角全直角），浮起时为 [AppRadii.Header] */
    val cornerRadius: Dp get() = AppRadii.Header * progress

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (consumed.y == 0f && available.y < 0f) {
            scrolled = 0f
        } else {
            scrolled = (scrolled + consumed.y).coerceIn(0f, distancePx)
        }
        return Offset.Zero
    }
}

/** 创建本页的页眉浮起状态，配合 [headerLift] 使用 */
@Composable
fun rememberHeaderLiftState(): HeaderLiftState {
    val density = LocalDensity.current
    val distancePx = with(density) { HeaderLiftDistance.toPx() }
    return remember(distancePx) { HeaderLiftState(distancePx) }
}

/**
 * 把本页的滚动位移汇总给 [state]，页眉据此决定浮起多少。
 *
 * 必须挂在**页面根节点**上，而不是某个滚动容器上：一页里往往有多个滚动容器
 * （页签面板、内嵌列表），挂在根上才能把它们都收进来。
 */
fun Modifier.headerLift(state: HeaderLiftState): Modifier = nestedScroll(state)

/**
 * 玻璃页眉板**浮起后**距屏幕左右（含上缘）的外边距；吸顶时这一段收为 0，页眉通栏。
 * 页眉内容若要与其他页面 20dp 的内容留白落在同一条竖直线上，
 * 得把这段外边距从 [AppTopBar] 的 contentPadding 里减掉，见 [GlassHeaderInnerPad]。
 * （吸顶时外边距为 0，标题会相应左移到 12dp 那条线上，这是"通栏"本身的形态：
 * 通栏吸顶栏按惯例不保留页面留白，左右两沿直接顶到屏幕边。）
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
 * 页眉**始终吸顶**，但页面离顶后会自动脱开上缘浮起一段（见 [HeaderLiftState]）：
 * 贴在顶上时它是一条吸顶横杠，滚起来之后才是一块悬在内容上方的浮板。
 * 页面不需要为此传任何参数，正文的滚动位移由骨架自己从嵌套滚动里听。
 *
 * @param header 页眉内容，会被套进一块四角全圆的玻璃板
 * @param content 正文，参数是页眉实测高度 + 上缘外边距，供正文垫净空
 * @param overlay 页内浮层（如内嵌确认层）。给的是骨架最外层的 Box 作用域，
 *   所以它压得住玻璃页眉——浮层不该在页眉下面断开。
 */
@Composable
fun GlassHeaderScaffold(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = AppTheme.colors
    val glass = rememberGlassState()
    val density = LocalDensity.current
    val lift = rememberHeaderLiftState()
    // 实测高度回填给正文，首项不会被压在玻璃页眉下面
    var headerHeight by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceBase)
            .headerLift(lift),
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
            shape = RoundedCornerShape(AppRadii.Header),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(
                    start = GlassHeaderInset,
                    end = GlassHeaderInset,
                    // 离顶后上缘多让一口气：这口气就是"浮起来"的全部信息量
                    top = GlassHeaderInset + HeaderLiftAmount * lift.progress,
                )
                .onSizeChanged { headerHeight = it.height },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                header()
                // 玻璃的圆角下沿不能贴着内容：留一口气，圆角才看得出来
                Spacer(Modifier.height(AppSpacing.Sm))
            }
        }

        // 页内浮层放最后：它要盖住正文，也要盖住玻璃页眉
        overlay?.invoke(this)
    }
}