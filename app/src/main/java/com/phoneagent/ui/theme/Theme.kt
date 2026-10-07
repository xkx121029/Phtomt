package com.phoneagent.ui.theme

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 形状令牌（Material 3 Expressive 圆角体系）
 * 由内到外分级：胶囊 < 瓦片 < 列表项 < 卡片 < 大容器。
 * 所有页面 / 组件统一引用，杜绝魔法数字，保证全项目视觉一致。
 */
object AppRadii {
    /** 状态胶囊 / 小徽标 */
    val Chip = 10.dp
    /** 内嵌小背景 / 紧凑控件（输入区、筛选条） */
    val Inline = 12.dp
    /** 图标瓦片 / 输入框 / 气泡 */
    val Tile = 14.dp
    /** 聊天气泡 / 中等圆角 */
    val Bubble = 18.dp
    /** 列表项 / 小卡片 */
    val Item = 20.dp
    /** 分组卡片 / 大面板 */
    val Card = 24.dp
    /** 品牌 Hero / 超大容器 */
    val Hero = 28.dp
    /** 覆盖层 / 对话框 / 手机外壳 */
    val Overlay = 32.dp
    /** 浮动页眉玻璃板：比 Hero 再圆一档，"离顶浮起"时才读得出是一块悬空的板 */
    val Header = 32.dp
}

/**
 * 间距令牌（与悬浮窗原生 View 侧 `FloatingUi.PAD_*` 保持同一套 4/8/12/16 体系）。
 * 共享组件统一引用，避免 16.dp / 12.dp 等魔法数字散落各处。
 */
object AppSpacing {
    /** 极小间距：图标与文字、徽章内边距 */
    val Xs = 4.dp
    /** 小间距：并列元素之间 */
    val Sm = 8.dp
    /** 中间距：卡片内元素分行 */
    val Md = 12.dp
    /** 标准间距：卡片内边距 / 屏幕左右留白 */
    val Lg = 16.dp
}

/**
 * 主题的「框线」开关（主题设置：标准 / 无框线）。
 *
 * 标准主题保留卡片、面板、输入面的发丝描边，层次靠"描边 + 底色"共同撑起来；
 * 无框线主题下所有装饰性描边一律不画，层次只靠底色差表达。
 *
 * 为什么做成 CompositionLocal 而不是给每个组件加参数：
 * 框线是**全局视觉语言**，全项目上百处描边都得跟着同一个开关走；逐个透传参数
 * 会把改动摊到整棵组件树。与 `LocalAppColors`、`LocalMotionSettings` 同一套办法。
 *
 * 收口方式：所有装饰性描边改走 [appBorder] / [appBorderStroke]，不再直接调
 * `Modifier.border` 或 `BorderStroke`。**功能性描边不在此列**——勾选框轮廓、
 * 选项选中态、标定标尺、状态指示灯仍是控件语义的一部分，无框线主题下照旧绘制。
 */
val LocalAppBorders = compositionLocalOf { true }

/** 框线开关读取入口，与 `AppTheme.colors` 同风格，避免各处直接触碰 CompositionLocal。 */
object AppBorders {
    /** true = 标准（画框线）；false = 无框线（不画装饰性框线） */
    val enabled: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalAppBorders.current
}

/**
 * 描边令牌（Material `border` 参数版）。无框线主题下返回 null，等价于"这条边不存在"。
 * 适用于 `Card(border = ...)` / `Surface(border = ...)` 这类把描边当参数的组件。
 */
@Composable
fun appBorderStroke(width: Dp, color: Color): BorderStroke? =
    if (AppBorders.enabled) BorderStroke(width, color) else null

/**
 * 描边令牌（Modifier 版）。无框线主题下原样返回，不追加任何绘制。
 * 适用于 `Modifier.border(...)` 这类把描边串进修饰符链的写法。
 */
@Composable
fun Modifier.appBorder(width: Dp, color: Color, shape: Shape): Modifier =
    if (AppBorders.enabled) border(width, color, shape) else this

/**
 * Material 3 Expressive 风格的圆角定义
 * 小卡片用 small/medium，大容器用 large
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(AppRadii.Tile),
    large = RoundedCornerShape(AppRadii.Item),
    extraLarge = RoundedCornerShape(AppRadii.Hero),
)

/**
 * 无框线档的容器色调位移倍率。
 *
 * 标准档的层次是"底色差 + 发丝描边"两条腿走路，所以容器色调只敢位移 3%~12%；
 * 描边一旦拿掉，卡片与页面底色就只差约 3%——肉眼看不出边界，卡片会化进页面里。
 * 这里把位移量乘上一个系数，用更大的底色差把层次重新撑起来。
 *
 * 取 2.0 而不是更大：这是**补偿**不是重做配色。系数再大，浅色下卡片会开始发灰、
 * 深色下会开始发白，等于把配色换成另一套。只动"面与面之间"的距离，
 * 文字色、品牌色、状态色一律不碰——去掉框线不该顺手把正文也改浓。
 */
private const val BorderlessToneBoost = 2.0f

/**
 * 把语义色令牌映射成 M3 ColorScheme。
 *
 * 五级容器色调不写死十六进制，而是从 `surfaceBase` 出发做色调位移：
 * 浅色向黑下沉、深色向白抬升，保证换配色时整套层级自动跟随。
 *
 * @param toneBoost 容器色调位移倍率，标准档为 1，无框线档为 [BorderlessToneBoost]
 */
private fun lightSchemeOf(c: AppColors, toneBoost: Float = 1f): ColorScheme {
    fun tone(t: Float): Color = lerp(c.surfaceBase, Color.Black, (t * toneBoost).coerceAtMost(0.6f))
    return lightColorScheme(
        primary = c.brand,
        onPrimary = c.onBrand,
        primaryContainer = c.brandContainer,
        onPrimaryContainer = c.onBrandContainer,
        inversePrimary = c.brandContainer,
        secondary = c.accentWarm,
        onSecondary = c.onAccentWarm,
        secondaryContainer = c.accentWarmContainer,
        onSecondaryContainer = c.onAccentWarmContainer,
        tertiary = c.accentCool,
        onTertiary = c.onAccentCool,
        tertiaryContainer = c.accentCoolContainer,
        onTertiaryContainer = c.onAccentCoolContainer,
        background = c.surfaceBase,
        onBackground = c.onSurfaceBase,
        surface = c.surfaceBase,
        onSurface = c.onSurfaceBase,
        surfaceVariant = c.surfaceRaised,
        onSurfaceVariant = c.onSurfaceRaised,
        surfaceTint = c.brand,
        surfaceBright = tone(-0.02f),
        surfaceDim = tone(0.10f),
        surfaceContainerLowest = c.surfaceBase,
        surfaceContainerLow = tone(0.03f),
        surfaceContainer = tone(0.06f),
        surfaceContainerHigh = tone(0.09f),
        surfaceContainerHighest = tone(0.12f),
        inverseSurface = c.onSurfaceBase,
        inverseOnSurface = c.surfaceBase,
        outline = c.outlineStrong,
        outlineVariant = c.outlineSoft,
        error = c.error,
        onError = c.onError,
        errorContainer = c.errorContainer,
        onErrorContainer = c.onErrorContainer,
        scrim = Color(0xFF000000),
    )
}

/** 同 [lightSchemeOf]，只是色调位移方向朝白，@param toneBoost 含义一致 */
private fun darkSchemeOf(c: AppColors, toneBoost: Float = 1f): ColorScheme {
    fun tone(t: Float): Color = lerp(c.surfaceBase, Color.White, (t * toneBoost).coerceAtMost(0.6f))
    return darkColorScheme(
        primary = c.brand,
        onPrimary = c.onBrand,
        primaryContainer = c.brandContainer,
        onPrimaryContainer = c.onBrandContainer,
        inversePrimary = c.brandContainer,
        secondary = c.accentWarm,
        onSecondary = c.onAccentWarm,
        secondaryContainer = c.accentWarmContainer,
        onSecondaryContainer = c.onAccentWarmContainer,
        tertiary = c.accentCool,
        onTertiary = c.onAccentCool,
        tertiaryContainer = c.accentCoolContainer,
        onTertiaryContainer = c.onAccentCoolContainer,
        background = c.surfaceBase,
        onBackground = c.onSurfaceBase,
        surface = c.surfaceBase,
        onSurface = c.onSurfaceBase,
        surfaceVariant = c.surfaceRaised,
        onSurfaceVariant = c.onSurfaceRaised,
        surfaceTint = c.brand,
        surfaceBright = tone(0.14f),
        surfaceDim = c.surfaceBase,
        surfaceContainerLowest = c.surfaceSunken,
        surfaceContainerLow = tone(0.03f),
        surfaceContainer = tone(0.06f),
        surfaceContainerHigh = tone(0.09f),
        surfaceContainerHighest = tone(0.12f),
        inverseSurface = c.onSurfaceBase,
        inverseOnSurface = c.surfaceBase,
        outline = c.outlineStrong,
        outlineVariant = c.outlineSoft,
        error = c.error,
        onError = c.onError,
        errorContainer = c.errorContainer,
        onErrorContainer = c.onErrorContainer,
        scrim = Color(0xFF000000),
    )
}

@Composable
fun PhoneAgentTheme(
    darkTheme: Boolean = isSystemDark(),
    /** 主题设置里的框线开关：true = 标准（画框线），false = 无框线 */
    bordersEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val highContrast = rememberHighContrast()
    val palette = when {
        darkTheme && highContrast -> DarkContrastAppColors
        darkTheme -> DarkAppColors
        highContrast -> LightContrastAppColors
        else -> LightAppColors
    }
    // 无框线档没有描边帮忙划边界，靠加大容器色调位移把层次补回来
    val toneBoost = if (bordersEnabled) 1f else BorderlessToneBoost
    val colorScheme = remember(palette, darkTheme, toneBoost) {
        if (darkTheme) darkSchemeOf(palette, toneBoost) else lightSchemeOf(palette, toneBoost)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalAppColors provides palette,
        LocalAppBorders provides bordersEnabled,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            ProvideMotionSettings {
                content()
            }
        }
    }
}

@Composable
private fun isSystemDark(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

/**
 * 系统「高对比度文字」无障碍开关。
 * 在每次进入组合时读取一次；开关变化通常伴随 Activity 重建，无需额外监听。
 */
@Composable
private fun rememberHighContrast(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        // AccessibilityManager 无公开的高对比度文字查询接口，改读系统安全设置项
        Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    }
}

// ========== 主题感知色（深/浅主题自动适配） ==========
/** 手机外壳主体色 */
@Composable
fun phoneShellColor(darkTheme: Boolean = isSystemDark()): Color =
    (if (darkTheme) DarkAppColors else LightAppColors).phoneShell

/** 手机外壳边框色 */
@Composable
fun phoneShellBorderColor(darkTheme: Boolean = isSystemDark()): Color =
    (if (darkTheme) DarkAppColors else LightAppColors).phoneShellBorder

/** 摄像头挖孔（激活态） */
@Composable
fun phoneCameraHoleColor(active: Boolean, darkTheme: Boolean = isSystemDark()): Color {
    val palette = if (darkTheme) DarkAppColors else LightAppColors
    return if (active) palette.phoneCameraHole else palette.phoneCameraHoleIdle
}

/** 空状态图标色 */
@Composable
fun emptyStateIconColor(darkTheme: Boolean = isSystemDark()): Color =
    (if (darkTheme) DarkAppColors else LightAppColors).emptyStateIcon

/** 空状态文字色 */
@Composable
fun emptyStateTextColor(darkTheme: Boolean = isSystemDark()): Color =
    (if (darkTheme) DarkAppColors else LightAppColors).emptyStateText

/** 运行指示灯色 */
@Composable
fun runningIndicatorColor(darkTheme: Boolean = isSystemDark()): Color =
    (if (darkTheme) DarkAppColors else LightAppColors).runningIndicator