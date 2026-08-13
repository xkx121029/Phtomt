package com.phoneagent.ui.theme

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
 * Material 3 Expressive 风格的圆角定义
 * 小卡片用 small/medium，大容器用 large
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
