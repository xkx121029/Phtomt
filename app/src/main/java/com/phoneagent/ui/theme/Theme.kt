package com.phoneagent.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
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
 * 把语义色令牌映射成 M3 ColorScheme。
 *
 * 五级容器色调不写死十六进制，而是从 `surfaceBase` 出发做色调位移：
 * 浅色向黑下沉、深色向白抬升，保证换配色时整套层级自动跟随。
 */
private fun lightSchemeOf(c: AppColors): ColorScheme {
    fun tone(t: Float): Color = lerp(c.surfaceBase, Color.Black, t)
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

private fun darkSchemeOf(c: AppColors): ColorScheme {
    fun tone(t: Float): Color = lerp(c.surfaceBase, Color.White, t)
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
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val highContrast = rememberHighContrast()
    val palette = when {
        darkTheme && highContrast -> DarkContrastAppColors
        darkTheme -> DarkAppColors
        highContrast -> LightContrastAppColors
        else -> LightAppColors
    }
    val colorScheme = remember(palette, darkTheme) {
        if (darkTheme) darkSchemeOf(palette) else lightSchemeOf(palette)
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

    CompositionLocalProvider(LocalAppColors provides palette) {
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