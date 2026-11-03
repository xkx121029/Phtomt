package com.phoneagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * 界面风格预设（持久化在 [com.phoneagent.data.prefs.AppSettings.Settings.uiStyle]）。
 *
 * 这不是另一套样式系统：风格只给**已有令牌**换取值口径——圆角取 [AppRadii] 的哪几档、
 * 容器色调位移多少。颜色仍来自 [AppColors]，间距仍来自 [AppSpacing]，
 * 动效仍来自 [DurationFast]/[DurationSlow] 与 [EaseOut]，框线仍走 [appBorder]。
 *
 * [CLASSIC] 是原有主题，默认值；[WORKBENCH] 是新预设，页面结构另见
 * [com.phoneagent.ui.workbench.WorkbenchScreen]。
 */
enum class UiStyle(
    val id: String,
    val label: String,
    val summary: String,
    /**
     * 容器色调位移倍率：与 [BorderlessToneBoost] 同一口径（只放大/收窄面与面之间的底色差，
     * 不动文字色、品牌色与状态色）。工作台要"平铺"，所以层次靠发丝线而不是阴影与色阶，
     * 位移收窄到 0.8，避免整页看起来像一层层浮起的卡片。
     */
    internal val toneScale: Float,
) {
    CLASSIC(
        id = "classic",
        label = "经典",
        summary = "浮起的圆角卡片与玻璃页眉，层次靠描边与底色差",
        toneScale = 1f,
    ),
    WORKBENCH(
        id = "workbench",
        label = "工作台",
        summary = "平铺分区与发丝分隔线，信息紧凑，首屏直接进入工作区",
        toneScale = 0.8f,
    ),
    ;

    companion object {
        /** 缺省风格：保留原有主题，切换失败一律回落到它 */
        val DEFAULT = CLASSIC

        /** 脏数据归一：未知 id 回落经典，不抛异常 */
        fun of(raw: String?): UiStyle = values().firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/** 当前界面风格；与 `LocalAppColors`、`LocalAppBorders` 同一套传递方式 */
val LocalUiStyle = compositionLocalOf { UiStyle.DEFAULT }

/** 风格读取入口，避免各处直接触碰 CompositionLocal */
object AppStyle {
    val current: UiStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalUiStyle.current
}

/**
 * 工作台的 M3 Shapes：所有档位取 [AppRadii] 更小的一档。
 * 不新增圆角数值，只是把"由内到外"的分级整体下移一级——
 * 卡片读作"分区面板"，而不是"浮起的卡片"。
 */
private val WorkbenchShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadii.Chip),
    small = RoundedCornerShape(AppRadii.Inline),
    medium = RoundedCornerShape(AppRadii.Tile),
    large = RoundedCornerShape(AppRadii.Item),
    extraLarge = RoundedCornerShape(AppRadii.Card),
)

/** 风格对应的 M3 Shapes；[PhoneAgentTheme] 只认这一份映射 */
internal fun shapesFor(style: UiStyle): Shapes = when (style) {
    UiStyle.CLASSIC -> AppShapes
    UiStyle.WORKBENCH -> WorkbenchShapes
}
