package com.phoneagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 新主题色板「石墨」。
 *
 * 取向与现有四套配色刻意拉开：不做墨色纸感，也不复用任何现有色值。
 * - 底色是**中性石墨**（不带绿/棕/紫的色偏），浅色下卡片比页面更亮（白卡压在冷灰画布上），
 *   与旧主题"卡片比页面更暗"的高度关系相反——这是新主题最容易被一眼认出的材质差；
 * - 强调色只用**一个**：靛蓝。页面上所有"需要被点"的东西都是它，其余靠明度层次；
 * - 暖 / 冷两个辅助色只用于设置页分组识别，不参与主操作。
 *
 * 状态色独立取值：成功走松绿、警告走暗金、错误走正红，与靛蓝强调色区分明确。
 */
val GraphiteLightAppColors = AppColors(
    brand = Color(0xFF3A4ED8),
    onBrand = Color(0xFFFFFFFF),
    brandContainer = Color(0xFFDDE1FB),
    onBrandContainer = Color(0xFF141B57),
    accentWarm = Color(0xFF9A5B1E),
    onAccentWarm = Color(0xFFFFFFFF),
    accentWarmContainer = Color(0xFFF6E6D4),
    onAccentWarmContainer = Color(0xFF3A2108),
    accentCool = Color(0xFF1C6B62),
    onAccentCool = Color(0xFFFFFFFF),
    accentCoolContainer = Color(0xFFD8EDEA),
    onAccentCoolContainer = Color(0xFF06302B),
    surfaceBase = Color(0xFFF2F3F5),
    onSurfaceBase = Color(0xFF14171B),
    // 卡片是纯白：新主题的"抬起"方向是变亮，与旧主题相反
    surfaceRaised = Color(0xFFFFFFFF),
    onSurfaceRaised = Color(0xFF555C66),
    surfaceSunken = Color(0xFFE8EAED),
    outlineStrong = Color(0xFF5F6670),
    outlineSoft = Color(0xFFDCDFE3),
    error = Color(0xFFC0362C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DCD8),
    onErrorContainer = Color(0xFF4A0D08),
    success = Color(0xFF1E6E4B),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD8EFE2),
    onSuccessContainer = Color(0xFF06311F),
    warning = Color(0xFF8A5A08),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFF6E8C8),
    onWarningContainer = Color(0xFF332203),
    messageBubbleAgent = Color(0xFFEDEFF2),
    onMessageBubbleAgent = Color(0xFF1A1D22),
    messageBubbleUser = Color(0xFF3A4ED8),
    onMessageBubbleUser = Color(0xFFFFFFFF),
    railIdle = Color(0xFFD5D9DE),
    railActive = Color(0xFF3A4ED8),
    railDone = Color(0xFF97A5EE),
    statusBarScrim = Color(0xFFFFFFFF),
    phoneShell = Color(0xFFE6E8EB),
    phoneShellBorder = Color(0xFFC6CACF),
    phoneCameraHole = Color(0xFFB4B9BF),
    phoneCameraHoleIdle = Color(0xFFA3A8AE),
    emptyStateIcon = Color(0xFFAEB4BA),
    emptyStateText = Color(0xFF737A82),
    runningIndicator = Color(0xFF1E6E4B),
    // 高密度浮板：比旧主题明显更实（72%），透出来的是轻微色相变化而不是一层雾
    glassTint = Color(0xB8F2F3F5),
    glassFallback = Color(0xF2F2F3F5),
    glassBorder = Color(0x223A4ED8),
    // 零高光：新主题的浮板不假装受光
    glassSheen = Color(0x00FFFFFF),
)

/** 石墨 · 深色：近黑中性底 + 浅靛蓝强调，卡片比底色亮一档 */
val GraphiteDarkAppColors = AppColors(
    brand = Color(0xFF9BA8FF),
    onBrand = Color(0xFF0B1240),
    brandContainer = Color(0xFF262E6E),
    onBrandContainer = Color(0xFFDCE1FF),
    accentWarm = Color(0xFFE0A462),
    onAccentWarm = Color(0xFF3A2209),
    accentWarmContainer = Color(0xFF4A3115),
    onAccentWarmContainer = Color(0xFFF6DDBE),
    accentCool = Color(0xFF6FC7BB),
    onAccentCool = Color(0xFF07302B),
    accentCoolContainer = Color(0xFF17423D),
    onAccentCoolContainer = Color(0xFFC6EFE8),
    surfaceBase = Color(0xFF101214),
    onSurfaceBase = Color(0xFFECEEF1),
    surfaceRaised = Color(0xFF1A1D21),
    onSurfaceRaised = Color(0xFFAEB4BC),
    surfaceSunken = Color(0xFF0A0C0E),
    outlineStrong = Color(0xFF9298A2),
    outlineSoft = Color(0xFF33383F),
    error = Color(0xFFFF8A7E),
    onError = Color(0xFF480A05),
    errorContainer = Color(0xFF6E1D16),
    onErrorContainer = Color(0xFFFFDAD5),
    success = Color(0xFF6FD3A2),
    onSuccess = Color(0xFF05301F),
    successContainer = Color(0xFF17452F),
    onSuccessContainer = Color(0xFFC4EFD8),
    warning = Color(0xFFE8B75E),
    onWarning = Color(0xFF3A2708),
    warningContainer = Color(0xFF4E370F),
    onWarningContainer = Color(0xFFF7E2B6),
    messageBubbleAgent = Color(0xFF232830),
    onMessageBubbleAgent = Color(0xFFE3E7EC),
    messageBubbleUser = Color(0xFF2A3470),
    onMessageBubbleUser = Color(0xFFDDE3FF),
    railIdle = Color(0xFF33383F),
    railActive = Color(0xFF9BA8FF),
    railDone = Color(0xFF4E5A9B),
    statusBarScrim = Color(0xF2101214),
    phoneShell = Color(0xFF16191C),
    phoneShellBorder = Color(0xFF2B3036),
    phoneCameraHole = Color(0xFF3A4046),
    phoneCameraHoleIdle = Color(0xFF252A2F),
    emptyStateIcon = Color(0xFF6B7178),
    emptyStateText = Color(0xFF6B7178),
    runningIndicator = Color(0xFF6FD3A2),
    glassTint = Color(0xB814171A),
    glassFallback = Color(0xF014171A),
    glassBorder = Color(0x339BA8FF),
    glassSheen = Color(0x00FFFFFF),
)

/** 石墨 · 浅色高对比度：纯白底、加深强调色与描边 */
val GraphiteLightContrastAppColors = GraphiteLightAppColors.copy(
    brand = Color(0xFF2A39B8),
    brandContainer = Color(0xFFD6DBFA),
    onBrandContainer = Color(0xFF0A0F3A),
    accentWarm = Color(0xFF78440F),
    accentWarmContainer = Color(0xFFF1DDC6),
    onAccentWarmContainer = Color(0xFF2A1704),
    accentCool = Color(0xFF0F544D),
    accentCoolContainer = Color(0xFFCDE7E3),
    onAccentCoolContainer = Color(0xFF04201D),
    surfaceBase = Color(0xFFFFFFFF),
    onSurfaceBase = Color(0xFF0A0B0C),
    surfaceRaised = Color(0xFFFFFFFF),
    onSurfaceRaised = Color(0xFF3A3E44),
    surfaceSunken = Color(0xFFECEEF0),
    outlineStrong = Color(0xFF3A3E44),
    outlineSoft = Color(0xFFB7BBC1),
    error = Color(0xFFA82A21),
    errorContainer = Color(0xFFF7D2CE),
    onErrorContainer = Color(0xFF3A0A06),
    success = Color(0xFF11573A),
    successContainer = Color(0xFFCDEBDD),
    onSuccessContainer = Color(0xFF042417),
    warning = Color(0xFF6E4705),
    warningContainer = Color(0xFFF2DDAE),
    onWarningContainer = Color(0xFF2A1C05),
    messageBubbleAgent = Color(0xFFE8EBEF),
    onMessageBubbleAgent = Color(0xFF0A0C0E),
    messageBubbleUser = Color(0xFF2A39B8),
    railIdle = Color(0xFF9DA3AA),
    railActive = Color(0xFF2A39B8),
    railDone = Color(0xFF3A49B0),
    phoneShell = Color(0xFFDCE0E4),
    phoneShellBorder = Color(0xFF9AA0A7),
    phoneCameraHole = Color(0xFF8E949B),
    phoneCameraHoleIdle = Color(0xFF8E949B),
    emptyStateIcon = Color(0xFF6A7178),
    emptyStateText = Color(0xFF4A5057),
    glassTint = Color(0xF2FFFFFF),
    glassFallback = Color(0xFFFFFFFF),
    glassBorder = Color(0x663A3E44),
    glassSheen = Color(0x00FFFFFF),
)

/** 石墨 · 深色高对比度：近黑底、提亮强调色与描边 */
val GraphiteDarkContrastAppColors = GraphiteDarkAppColors.copy(
    brand = Color(0xFFC3CCFF),
    onBrand = Color(0xFF060A2E),
    brandContainer = Color(0xFF3A4390),
    onBrandContainer = Color(0xFFE8EBFF),
    accentWarm = Color(0xFFFFC98A),
    accentWarmContainer = Color(0xFF5E3B1B),
    onAccentWarmContainer = Color(0xFFFFE6CC),
    accentCool = Color(0xFF8FE0D4),
    accentCoolContainer = Color(0xFF1F4F49),
    onAccentCoolContainer = Color(0xFFDCF4EF),
    surfaceBase = Color(0xFF050607),
    onSurfaceBase = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFF14171A),
    onSurfaceRaised = Color(0xFFD6DAE0),
    surfaceSunken = Color(0xFF000000),
    outlineStrong = Color(0xFFAEB4BC),
    outlineSoft = Color(0xFF5A6169),
    error = Color(0xFFFFB3AA),
    errorContainer = Color(0xFF7E2A21),
    onErrorContainer = Color(0xFFFFE2DE),
    success = Color(0xFF8AE3B4),
    successContainer = Color(0xFF1C5238),
    onSuccessContainer = Color(0xFFD6F6E5),
    warning = Color(0xFFF5CA7E),
    warningContainer = Color(0xFF5C421A),
    onWarningContainer = Color(0xFFFBEBCB),
    messageBubbleAgent = Color(0xFF20242A),
    onMessageBubbleAgent = Color(0xFFEDF1F5),
    messageBubbleUser = Color(0xFF333D80),
    onMessageBubbleUser = Color(0xFFE6EAFF),
    railIdle = Color(0xFF5A6169),
    railActive = Color(0xFFC3CCFF),
    railDone = Color(0xFF6672B8),
    statusBarScrim = Color(0xF2050607),
    phoneShell = Color(0xFF121619),
    phoneShellBorder = Color(0xFF3C4249),
    phoneCameraHole = Color(0xFF4A5057),
    phoneCameraHoleIdle = Color(0xFF2E3338),
    emptyStateIcon = Color(0xFF868D94),
    emptyStateText = Color(0xFF868D94),
    runningIndicator = Color(0xFF8AE3B4),
    glassTint = Color(0xF214171A),
    glassFallback = Color(0xFF14171A),
    glassBorder = Color(0x66AEB4BC),
    glassSheen = Color(0x00FFFFFF),
)

/**
 * 石墨的 M3 ColorScheme。
 *
 * 与旧主题「从底色往黑/白位移」不同，这里显式给出五级容器：
 * 浅色向**白**抬升（卡片纯白、内嵌面回落到灰），深色向**白**抬升但幅度更小。
 * 这样 `surfaceContainerLow` 作为全站卡片底时，两种模式下都读作"比页面更靠前"，
 * 而不是"同一块底的另一种灰度"。
 */
private const val LIGHT_CONTAINER_MIX = 0.45f
private const val LIGHT_HIGHEST_MIX = 0.35f
private const val LIGHT_CONTRAST_OUTLINE_MIX = 0.15f
/**
 * 石墨把 M3 的 secondaryContainer 从"暖沙色"拉回冷调（靛蓝容器 55% + 页面底色）。
 *
 * 理由：M3 的分段控件、筛选芯片等把 secondaryContainer 当作**选中填充色**用。
 * 石墨整体是「中性石墨底 + 靛蓝强调」，选中态如果还铺一层暖沙，会像旧主题没换干净。
 * 暖色仍保留在 `secondary`（图标、状态标签的第二强调），那里是"强调"而非"填充"。
 */
private const val LIGHT_SECONDARY_MIX = 0.55f
private const val DARK_BRIGHT_MIX = 0.08f
private const val DARK_CONTAINER_MIX = 0.04f
private const val DARK_HIGH_MIX = 0.08f
private const val DARK_HIGHEST_MIX = 0.13f
private const val DARK_CONTRAST_OUTLINE_MIX = 0.18f
/** 同 [LIGHT_SECONDARY_MIX] 的深色版：靛蓝容器 50% + 抬升面，选中填充仍读作冷调 */
private const val DARK_SECONDARY_MIX = 0.5f

internal fun graphiteLightScheme(c: AppColors, contrast: Boolean): ColorScheme = lightColorScheme(
    primary = c.brand,
    onPrimary = c.onBrand,
    primaryContainer = c.brandContainer,
    onPrimaryContainer = c.onBrandContainer,
    inversePrimary = c.brandContainer,
    secondary = c.accentWarm,
    onSecondary = c.onAccentWarm,
    secondaryContainer = lerp(c.brandContainer, c.surfaceBase, LIGHT_SECONDARY_MIX),
    onSecondaryContainer = c.brand,
    tertiary = c.accentCool,
    onTertiary = c.onAccentCool,
    tertiaryContainer = c.accentCoolContainer,
    onTertiaryContainer = c.onAccentCoolContainer,
    background = c.surfaceBase,
    onBackground = c.onSurfaceBase,
    surface = c.surfaceBase,
    onSurface = c.onSurfaceBase,
    surfaceVariant = c.surfaceSunken,
    onSurfaceVariant = c.onSurfaceRaised,
    surfaceTint = c.brand,
    surfaceBright = Color.White,
    surfaceDim = c.surfaceSunken,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = c.surfaceRaised,
    surfaceContainer = lerp(c.surfaceRaised, c.surfaceBase, LIGHT_CONTAINER_MIX),
    surfaceContainerHigh = c.surfaceSunken,
    surfaceContainerHighest = lerp(c.surfaceSunken, c.surfaceBase, LIGHT_HIGHEST_MIX),
    inverseSurface = c.onSurfaceBase,
    inverseOnSurface = c.surfaceBase,
    outline = c.outlineStrong,
    outlineVariant = if (contrast) lerp(c.outlineSoft, Color.Black, LIGHT_CONTRAST_OUTLINE_MIX) else c.outlineSoft,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorContainer,
    onErrorContainer = c.onErrorContainer,
    scrim = Color.Black,
)

internal fun graphiteDarkScheme(c: AppColors, contrast: Boolean): ColorScheme = darkColorScheme(
    primary = c.brand,
    onPrimary = c.onBrand,
    primaryContainer = c.brandContainer,
    onPrimaryContainer = c.onBrandContainer,
    inversePrimary = c.brandContainer,
    secondary = c.accentWarm,
    onSecondary = c.onAccentWarm,
    secondaryContainer = lerp(c.brandContainer, c.surfaceRaised, DARK_SECONDARY_MIX),
    onSecondaryContainer = c.onBrandContainer,
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
    surfaceBright = lerp(c.surfaceRaised, Color.White, DARK_BRIGHT_MIX),
    surfaceDim = c.surfaceSunken,
    surfaceContainerLowest = c.surfaceSunken,
    surfaceContainerLow = c.surfaceRaised,
    surfaceContainer = lerp(c.surfaceRaised, Color.White, DARK_CONTAINER_MIX),
    surfaceContainerHigh = lerp(c.surfaceRaised, Color.White, DARK_HIGH_MIX),
    surfaceContainerHighest = lerp(c.surfaceRaised, Color.White, DARK_HIGHEST_MIX),
    inverseSurface = c.onSurfaceBase,
    inverseOnSurface = c.surfaceBase,
    outline = c.outlineStrong,
    outlineVariant = if (contrast) lerp(c.outlineSoft, Color.White, DARK_CONTRAST_OUTLINE_MIX) else c.outlineSoft,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorContainer,
    onErrorContainer = c.onErrorContainer,
    scrim = Color.Black,
)

/**
 * 新主题的字体层级。
 *
 * 与旧层级的三处差别：
 * 1. **整体收紧**：正文 15/24（旧 16/26）、标题 16/22，信息密度更高；
 * 2. **层级靠字重而不是字号拉开**：标题一律 Bold，正文固定 Normal，避免页面里出现
 *    五六种"看起来差不多大"的字号；
 * 3. **小字加字距**：11sp 标签带 0.4~0.5sp 字距，小尺寸中文与数字才不粘在一起。
 *
 * 所有字号单位仍是 sp，跟随系统字体缩放；行高用 sp 比例给出，放大字号时不会压字。
 */
val GraphiteTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1f).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.8f).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5f).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4f).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2f).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1f).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.05.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.5.sp,
    ),
)

/** 石墨的 M3 Shapes：整体下移一到两档，方正但不至于变成直角 */
val GraphiteShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
