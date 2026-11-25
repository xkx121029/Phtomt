package com.phoneagent.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主题皮肤（持久化在 `AppSettings.Settings.skin`）。
 *
 * 与 [ThemePalette]（只换色相）和 [UiStyle]（只换圆角口径与页面结构）不同，
 * 皮肤是一整套视觉语言：色板、字体层级、形状、间距、材质与动效节奏一起换。
 * 因此它是一条独立的轴——切到新皮肤后配色选项不再参与，切回经典则原有配色、
 * 框线与工作台风格全部原样恢复，不做任何破坏性覆盖。
 *
 * [LEGACY] 是默认值，行为与引入本轴之前完全一致；[GRAPHITE] 是新主题。
 */
enum class ThemeSkin(
    val id: String,
    val label: String,
    val summary: String,
) {
    LEGACY(
        id = "legacy",
        label = "经典",
        summary = "墨色纸感、浮起的大圆角卡片与磨砂玻璃，现有配色与工作台风格全部保留",
    ),
    GRAPHITE(
        id = "graphite",
        label = "石墨",
        summary = "中性石墨底 + 靛蓝强调，方正形状、发丝分隔与高密度浮板，版式优先",
    ),
    ;

    /** 是否新主题：只在需要分支「同一语义、两种表现」的共享组件里读 */
    val isGraphite: Boolean get() = this == GRAPHITE

    companion object {
        /** 缺省皮肤：经典，保证升级后的老用户观感不变 */
        val DEFAULT = LEGACY

        /** 脏数据归一：未知 id 回落经典，不抛异常 */
        fun of(raw: String?): ThemeSkin = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/** 当前皮肤；与 `LocalAppColors`、`LocalUiStyle` 同一套传递方式 */
val LocalThemeSkin = staticCompositionLocalOf { ThemeSkin.DEFAULT }

/**
 * 布局令牌：页面节奏与几何量的唯一来源。
 *
 * 旧主题的取值与引入本令牌之前逐项一致（20 / 24 / 16 / 24 / 20 / 14 / 10 / 28 / 32），
 * 所以经典主题不会因为这次改造发生任何位移。新主题通过另一份实例整体改变
 * 「页面留白、分区节奏、卡片内边距、圆角档位、玻璃材质与入场节拍」。
 *
 * 只收结构性的几何量；内容驱动的尺寸（文本自适应、进度百分比）仍由布局自己算。
 */
@Immutable
data class AppMetrics(
    /** 页面左右留白（设置页与各主页面共用的栅格边距） */
    val pagePad: Dp,
    /** 分区之间的竖向节奏 */
    val sectionGap: Dp,
    /** 卡片 / 面板内边距 */
    val cardPad: Dp,
    /** 分组大卡圆角 */
    val cardRadius: Dp,
    /** 列表项 / 小卡片圆角 */
    val itemRadius: Dp,
    /** 图标瓦片 / 输入框 / 气泡圆角 */
    val tileRadius: Dp,
    /** 状态胶囊 / 小徽标圆角 */
    val chipRadius: Dp,
    /** Hero 容器 / 浮起页眉 / 底部导航圆角 */
    val heroRadius: Dp,
    /** 覆盖层 / 对话框圆角 */
    val overlayRadius: Dp,
    /** 设置页分区容器圆角 */
    val sectionRadius: Dp,
    /** 装饰性发丝线宽度 */
    val hairline: Dp,
    /** 设置分区底色里掺入强调色的比例（0 = 纯中性面） */
    val sectionAccentAlpha: Float,
    /** 设置分区标题旁的眉条宽度（0 = 不画眉条） */
    val sectionBarWidth: Dp,
    /** 玻璃大面的模糊半径 */
    val glassBlur: Dp,
    /** 玻璃小浮层的模糊半径 */
    val glassBlurCompact: Dp,
    /** 玻璃噪点量（0 = 干净的浮板） */
    val glassNoise: Float,
    /** 玻璃是否画顶部受光高光 */
    val glassSheen: Boolean,
    /** 悬浮导航阴影高度（0 = 只靠发丝线浮起） */
    val navShadow: Dp,
    /** 圆形图标底盘（新主题改为圆角方块，与方形控件同一套形状语言） */
    val roundIconActions: Boolean,
    /** 玻璃页眉浮起后距屏幕左右的外边距 */
    val headerInset: Dp,
    /** 是否在页面底色上叠一层极淡的品牌色光晕 */
    val backdropWash: Boolean,
    /** 列表逐项入场间隔（毫秒） */
    val staggerMs: Int,
)

/** 经典主题的几何量：与改造前逐项一致 */
val LegacyMetrics = AppMetrics(
    pagePad = 20.dp,
    sectionGap = 24.dp,
    cardPad = 16.dp,
    cardRadius = 24.dp,
    itemRadius = 20.dp,
    tileRadius = 14.dp,
    chipRadius = 10.dp,
    heroRadius = 28.dp,
    overlayRadius = 32.dp,
    sectionRadius = 28.dp,
    hairline = 1.dp,
    sectionAccentAlpha = 0.06f,
    sectionBarWidth = 4.dp,
    glassBlur = 36.dp,
    glassBlurCompact = 24.dp,
    glassNoise = 0.04f,
    glassSheen = true,
    navShadow = 12.dp,
    roundIconActions = true,
    headerInset = 12.dp,
    backdropWash = false,
    staggerMs = 45,
)

/**
 * 石墨主题的几何量。
 *
 * 形状整体下移一档、留白反而更大：方正的小圆角配更松的页边距，读起来是"版式"，
 * 而不是"卡片堆"。玻璃换成高密度浮板（低模糊、零噪点、无高光），因为它在新主题里的
 * 角色是"盖在内容上的一块实心板"，不需要假装成透明薄膜。
 */
val GraphiteMetrics = AppMetrics(
    pagePad = 24.dp,
    sectionGap = 28.dp,
    cardPad = 18.dp,
    cardRadius = 16.dp,
    itemRadius = 12.dp,
    tileRadius = 10.dp,
    chipRadius = 8.dp,
    heroRadius = 20.dp,
    overlayRadius = 24.dp,
    sectionRadius = 18.dp,
    hairline = 1.dp,
    sectionAccentAlpha = 0.03f,
    sectionBarWidth = 0.dp,
    glassBlur = 20.dp,
    glassBlurCompact = 16.dp,
    glassNoise = 0f,
    glassSheen = false,
    navShadow = 0.dp,
    roundIconActions = false,
    headerInset = 10.dp,
    backdropWash = true,
    staggerMs = 24,
)

/** 当前布局令牌 */
val LocalAppMetrics = staticCompositionLocalOf { LegacyMetrics }

/** 皮肤对应的布局令牌 */
fun metricsFor(skin: ThemeSkin): AppMetrics = when (skin) {
    ThemeSkin.LEGACY -> LegacyMetrics
    ThemeSkin.GRAPHITE -> GraphiteMetrics
}
