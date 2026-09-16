package com.phoneagent.ui.theme.Theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
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

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentContainer,
    onPrimaryContainer = OnAccentContainer,
    secondary = Cyan,
    onSecondary = Color(0xFF003731),
    secondaryContainer = CyanContainer,
    onSecondaryContainer = Color(0xFF003731),
    tertiary = IconIris,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF21005D),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color(0xFFE9EBF3),
    onSurfaceVariant = Color(0xFF4A4F63),
    surfaceContainerHighest = Color(0xFFE6E8F0),
    surfaceContainerHigh = Color(0xFFECEEF4),
    surfaceContainer = Color(0xFFF3F4F8),
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainerLowest = Color.White,
    outline = Color(0xFF7A7F94),
    outlineVariant = Color(0xFFCACBD7),
    error = Error,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF343E7A),
    onPrimaryContainer = AccentContainer,
    secondary = Cyan,
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005449),
    onSecondaryContainer = Color(0xFF9CFFF1),
    tertiary = IconIris,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF4F378B),
    onTertiaryContainer = Color(0xFFEADDFF),
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF1E2230),
    onSurfaceVariant = Color(0xFFB9BDCE),
    surfaceContainerHighest = Color(0xFF2A2E3D),
    surfaceContainerHigh = Color(0xFF222633),
    surfaceContainer = Color(0xFF181C27),
    surfaceContainerLow = Color(0xFF141722),
    surfaceContainerLowest = Color(0xFF0B0F19),
    outline = Color(0xFF8A8FA3),
    outlineVariant = Color(0xFF444654),
    error = Error,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 语义化颜色扩展 - 用于状态指示
 */
val LightSuccessContainer = Color(0xFFB8F3DA)
val LightOnSuccessContainer = Color(0xFF00210F)
val DarkSuccessContainer = Color(0xFF005D34)
val DarkOnSuccessContainer = Color(0xFF76F5B4)

val LightWarningContainer = Color(0xFFFFF0D4)
val LightOnWarningContainer = Color(0xFF281800)
val DarkWarningContainer = Color(0xFF573900)
val DarkOnWarningContainer = Color(0xFFFFE08A)

@Composable
fun PhoneAgentTheme(
    darkTheme: Boolean = isSystemDark(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

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

@Composable
private fun isSystemDark(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

// ========== 主题感知色（深/浅主题自动适配） ==========
/** 手机外壳主体色 */
@Composable
fun phoneShellColor(darkTheme: Boolean = isSystemDark()): Color =
    if (darkTheme) PhoneShellDark else PhoneShellLight

/** 手机外壳边框色 */
@Composable
fun phoneShellBorderColor(darkTheme: Boolean = isSystemDark()): Color =
    if (darkTheme) PhoneShellBorderDark else PhoneShellBorderLight

/** 摄像头挖孔（激活态） */
@Composable
fun phoneCameraHoleColor(active: Boolean, darkTheme: Boolean = isSystemDark()): Color =
    if (active) {
        if (darkTheme) PhoneCameraHoleDark else PhoneCameraHoleLight
    } else {
        if (darkTheme) PhoneCameraHoleIdleDark else PhoneCameraHoleLight
    }

/** 空状态图标色 */
@Composable
fun emptyStateIconColor(darkTheme: Boolean = isSystemDark()): Color =
    if (darkTheme) EmptyStateIconDark else EmptyStateIconLight

/** 空状态文字色 */
@Composable
fun emptyStateTextColor(darkTheme: Boolean = isSystemDark()): Color =
    if (darkTheme) EmptyStateTextDark else EmptyStateTextLight

/** 运行指示灯色 */
@Composable
fun runningIndicatorColor(darkTheme: Boolean = isSystemDark()): Color =
    if (darkTheme) RunningIndicatorDark else RunningIndicatorLight
