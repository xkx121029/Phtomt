package com.phoneagent.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 语义颜色令牌。
 *
 * 设计取向「玄青 · 流萤」：以玄青（墨玉绿）为品牌主色，琥珀为暖色辅色，雾蓝为冷色辅色，
 * 底色偏暖纸白 / 深墨绿黑，避开常见的蓝紫渐变科技感套路。
 *
 * 为什么用 data class + CompositionLocal，而不是全局可变状态：
 * 全局 `var` 在重组之外被改写时无法触发订阅者刷新，且多窗口 / 预览场景会互相污染。
 * CompositionLocal 随组合树传递，天然支持多主题并存与 Compose 预览。
 */
@Immutable
data class AppColors(
    /** 品牌主色（玄青） */
    val brand: Color,
    val onBrand: Color,
    val brandContainer: Color,
    val onBrandContainer: Color,
    /** 暖色辅色（琥珀） */
    val accentWarm: Color,
    val onAccentWarm: Color,
    val accentWarmContainer: Color,
    val onAccentWarmContainer: Color,
    /** 冷色辅色（雾蓝） */
    val accentCool: Color,
    val onAccentCool: Color,
    val accentCoolContainer: Color,
    val onAccentCoolContainer: Color,
    /** 页面底色 */
    val surfaceBase: Color,
    val onSurfaceBase: Color,
    /** 抬升容器（卡片、列表项） */
    val surfaceRaised: Color,
    val onSurfaceRaised: Color,
    /** 下沉容器（输入框、代码块底） */
    val surfaceSunken: Color,
    /** 强描边（分隔线、聚焦边框） */
    val outlineStrong: Color,
    /** 弱描边（卡片边界） */
    val outlineSoft: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Agent / 用户消息气泡底色 */
    val messageBubbleAgent: Color,
    val onMessageBubbleAgent: Color,
    val messageBubbleUser: Color,
    val onMessageBubbleUser: Color,
    /** 步骤轨道：未开始 / 进行中 / 已完成 */
    val railIdle: Color,
    val railActive: Color,
    val railDone: Color,
    /** 状态栏色条（覆盖系统状态栏区域，避免内容顶到屏幕边缘） */
    val statusBarScrim: Color,
    /** 手机预览外壳 */
    val phoneShell: Color,
    val phoneShellBorder: Color,
    val phoneCameraHole: Color,
    val phoneCameraHoleIdle: Color,
    /** 空状态 */
    val emptyStateIcon: Color,
    val emptyStateText: Color,
    /** 运行指示灯 */
    val runningIndicator: Color,
    /**
     * 毛玻璃质感令牌（仅用于真正浮在滚动内容之上的固定 chrome，平铺卡片一律不用）。
     *
     * 四个值分工明确，缺一不可：
     * - [glassTint] 叠在模糊结果之上，决定磨砂的浓淡；
     * - [glassFallback] 是平台不支持背景模糊时的替身，必须接近不透明，
     *   否则未模糊的内容会直接糊在文字后面；
     * - [glassBorder] 带品牌色相的发丝线，替代通用的白色描边；
     * - [glassSheen] 顶部受光高光。**刻意做到极淡**：玻璃面的价值在于"内容从中透出来"，
     *   高光一旦看得清就变成一块发白的塑料膜，反而盖住了内容与文字。
     */
    val glassTint: Color,
    val glassFallback: Color,
    val glassBorder: Color,
    val glassSheen: Color,
)

/** 浅色（默认）：暖纸白底 + 玄青主色 */
val LightAppColors = AppColors(
    brand = Color(0xFF0E7C66),
    onBrand = Color(0xFFFFFFFF),
    brandContainer = Color(0xFFCFEDE3),
    onBrandContainer = Color(0xFF033A2F),
    accentWarm = Color(0xFFB4632A),
    onAccentWarm = Color(0xFFFFFFFF),
    accentWarmContainer = Color(0xFFF7E3D2),
    onAccentWarmContainer = Color(0xFF4A2410),
    accentCool = Color(0xFF4A7C9B),
    onAccentCool = Color(0xFFFFFFFF),
    accentCoolContainer = Color(0xFFDCEAF3),
    onAccentCoolContainer = Color(0xFF17384C),
    surfaceBase = Color(0xFFF7F6F3),
    onSurfaceBase = Color(0xFF1A1D1B),
    surfaceRaised = Color(0xFFEDEFEA),
    onSurfaceRaised = Color(0xFF4B514C),
    surfaceSunken = Color(0xFFE3E6E0),
    outlineStrong = Color(0xFF6E756E),
    outlineSoft = Color(0xFFC9CEC6),
    error = Color(0xFFC4463C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDAD5),
    onErrorContainer = Color(0xFF4A0F09),
    success = Color(0xFF1F7A55),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD2EDDF),
    onSuccessContainer = Color(0xFF08321F),
    warning = Color(0xFF9A6B12),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFF6E6C4),
    onWarningContainer = Color(0xFF38270A),
    messageBubbleAgent = Color(0xFFE6F4EF),
    onMessageBubbleAgent = Color(0xFF10312A),
    messageBubbleUser = Color(0xFF0E7C66),
    onMessageBubbleUser = Color(0xFFFFFFFF),
    railIdle = Color(0xFFC9CEC6),
    railActive = Color(0xFF0E7C66),
    railDone = Color(0xFF7FBFA9),
    statusBarScrim = Color(0xFFFFFFFF),
    phoneShell = Color(0xFFE8EAE6),
    phoneShellBorder = Color(0xFFC8CCC4),
    phoneCameraHole = Color(0xFFB9BEB6),
    phoneCameraHoleIdle = Color(0xFFA9AEA6),
    emptyStateIcon = Color(0xFFB4BAB3),
    emptyStateText = Color(0xFF838A83),
    runningIndicator = Color(0xFF1F7A55),
    // 磨砂浓淡：从 0xA0（63%）压到 0x87（53%）——玻璃面更透，模糊内容更容易看出来；
    // 再低文字可读性开始受影响，浅色底到 0x78 附近就是下限
    glassTint = Color(0x87F7F6F3),
    // 不支持背景模糊的机型（Android 12 以下）走这一档：比玻璃浓、但不做实心，
    // 否则悬浮导航栏又会退化成"挡住内容的一块矩形"
    glassFallback = Color(0xE0F7F6F3),
    glassBorder = Color(0x260E7C66),
    // 高光只留一丝：要凑近看才觉得面上"有点光"，一抬眼就该看见内容与文字
    glassSheen = Color(0x14FFFFFF),
)

/** 深色：深墨底 + 流萤青主色 */
val DarkAppColors = AppColors(
    brand = Color(0xFF5FD9B4),
    onBrand = Color(0xFF04302A),
    brandContainer = Color(0xFF17453A),
    onBrandContainer = Color(0xFFA8EBD6),
    accentWarm = Color(0xFFF0A868),
    onAccentWarm = Color(0xFF3A1F09),
    accentWarmContainer = Color(0xFF4E3117),
    onAccentWarmContainer = Color(0xFFF8DCBE),
    accentCool = Color(0xFF84B6D4),
    onAccentCool = Color(0xFF0E2A3A),
    accentCoolContainer = Color(0xFF1E3B4C),
    onAccentCoolContainer = Color(0xFFCBE4F2),
    surfaceBase = Color(0xFF121513),
    onSurfaceBase = Color(0xFFE8EAE6),
    surfaceRaised = Color(0xFF1B1F1C),
    onSurfaceRaised = Color(0xFFB7BDB6),
    surfaceSunken = Color(0xFF0C0F0D),
    outlineStrong = Color(0xFF8B928A),
    outlineSoft = Color(0xFF3A403A),
    error = Color(0xFFFF9A90),
    onError = Color(0xFF4A0F09),
    errorContainer = Color(0xFF6E1F17),
    onErrorContainer = Color(0xFFFFDAD5),
    success = Color(0xFF6FD3A2),
    onSuccess = Color(0xFF05301F),
    successContainer = Color(0xFF17452F),
    onSuccessContainer = Color(0xFFC4EFD8),
    warning = Color(0xFFE8B75E),
    onWarning = Color(0xFF3A2708),
    warningContainer = Color(0xFF4E370F),
    onWarningContainer = Color(0xFFF7E2B6),
    messageBubbleAgent = Color(0xFF1E332C),
    onMessageBubbleAgent = Color(0xFFDCEBE4),
    messageBubbleUser = Color(0xFF1B4A3F),
    onMessageBubbleUser = Color(0xFFCFF3E8),
    railIdle = Color(0xFF3A403A),
    railActive = Color(0xFF5FD9B4),
    railDone = Color(0xFF3E7A66),
    statusBarScrim = Color(0xF2121513),
    phoneShell = Color(0xFF161A17),
    phoneShellBorder = Color(0xFF2A2F2A),
    phoneCameraHole = Color(0xFF3A403A),
    phoneCameraHoleIdle = Color(0xFF252A26),
    emptyStateIcon = Color(0xFF6A716B),
    emptyStateText = Color(0xFF6A716B),
    runningIndicator = Color(0xFF4CC38A),
    // 磨砂浓淡：同浅色一起调透，0x8C（55%）→ 0x73（45%）；深色底上文字对比更脆，不再往下压
    glassTint = Color(0x73141816),
    // 同浅色：模糊不可用时也要留出一点透感，不能变回实心矩形
    glassFallback = Color(0xE0141816),
    glassBorder = Color(0x3D5FD9B4),
    // 同浅色：深色底上的一点受光，只在纯色区域才勉强分辨得出
    glassSheen = Color(0x0AFFFFFF),
)

/** 浅色高对比度：纯白底 + 加深主色与描边，服务于「高对比度文字」无障碍开关 */
val LightContrastAppColors = LightAppColors.copy(
    brand = Color(0xFF0A5A4A),
    brandContainer = Color(0xFFBCE5D8),
    onBrandContainer = Color(0xFF022A21),
    accentWarm = Color(0xFF8A4A1E),
    accentWarmContainer = Color(0xFFF2DBC6),
    onAccentWarmContainer = Color(0xFF331706),
    accentCool = Color(0xFF35617D),
    accentCoolContainer = Color(0xFFCFE3EF),
    onAccentCoolContainer = Color(0xFF0B2433),
    surfaceBase = Color(0xFFFFFFFF),
    onSurfaceBase = Color(0xFF0B0D0C),
    surfaceRaised = Color(0xFFF2F4F0),
    onSurfaceRaised = Color(0xFF333833),
    surfaceSunken = Color(0xFFE8EBE5),
    outlineStrong = Color(0xFF3F443F),
    outlineSoft = Color(0xFFA9AFA8),
    error = Color(0xFFA82F26),
    errorContainer = Color(0xFFF7CDC7),
    onErrorContainer = Color(0xFF3A0B06),
    success = Color(0xFF12603F),
    successContainer = Color(0xFFC4E8D5),
    onSuccessContainer = Color(0xFF042417),
    warning = Color(0xFF7A5309),
    warningContainer = Color(0xFFF2DDAE),
    onWarningContainer = Color(0xFF2A1C05),
    messageBubbleAgent = Color(0xFFDCF0E9),
    onMessageBubbleAgent = Color(0xFF08110E),
    messageBubbleUser = Color(0xFF0A5A4A),
    railIdle = Color(0xFF9DA39C),
    railActive = Color(0xFF0A5A4A),
    railDone = Color(0xFF1F6E58),
    statusBarScrim = Color(0xFFFFFFFF),
    phoneShell = Color(0xFFDCE0DA),
    phoneShellBorder = Color(0xFF9AA097),
    phoneCameraHole = Color(0xFF8E948C),
    phoneCameraHoleIdle = Color(0xFF8E948C),
    emptyStateIcon = Color(0xFF6A716B),
    emptyStateText = Color(0xFF4A504A),
    runningIndicator = Color(0xFF12603F),
    // 高对比度无障碍：玻璃一律退化为近乎不透明，透明度不再参与可读性
    glassTint = Color(0xF2FFFFFF),
    glassFallback = Color(0xFFFFFFFF),
    glassBorder = Color(0x663F443F),
    glassSheen = Color(0x00FFFFFF),
)

/** 深色高对比度：纯黑底 + 提亮主色与描边 */
val DarkContrastAppColors = DarkAppColors.copy(
    brand = Color(0xFF7FEFCB),
    onBrand = Color(0xFF00251E),
    brandContainer = Color(0xFF1F5C4C),
    onBrandContainer = Color(0xFFD3F7EB),
    accentWarm = Color(0xFFFFBE80),
    accentWarmContainer = Color(0xFF5E3B1B),
    onAccentWarmContainer = Color(0xFFFFE6CC),
    accentCool = Color(0xFFA3CFE7),
    accentCoolContainer = Color(0xFF27495C),
    onAccentCoolContainer = Color(0xFFDCEEF9),
    surfaceBase = Color(0xFF0A0C0A),
    onSurfaceBase = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFF171B18),
    onSurfaceRaised = Color(0xFFD6DCD5),
    surfaceSunken = Color(0xFF050705),
    outlineStrong = Color(0xFFB6BCB5),
    outlineSoft = Color(0xFF5A615A),
    error = Color(0xFFFFB3AA),
    errorContainer = Color(0xFF7E2A21),
    onErrorContainer = Color(0xFFFFE2DE),
    success = Color(0xFF8AE3B4),
    successContainer = Color(0xFF1C5238),
    onSuccessContainer = Color(0xFFD6F6E5),
    warning = Color(0xFFF5CA7E),
    warningContainer = Color(0xFF5C421A),
    onWarningContainer = Color(0xFFFBEBCB),
    messageBubbleAgent = Color(0xFF202F29),
    onMessageBubbleAgent = Color(0xFFEAF4EF),
    messageBubbleUser = Color(0xFF23584A),
    onMessageBubbleUser = Color(0xFFDEF6EC),
    railIdle = Color(0xFF5A615A),
    railActive = Color(0xFF7FEFCB),
    railDone = Color(0xFF4F9B82),
    statusBarScrim = Color(0xF20A0C0A),
    phoneShell = Color(0xFF121613),
    phoneShellBorder = Color(0xFF3C423C),
    phoneCameraHole = Color(0xFF4A504A),
    phoneCameraHoleIdle = Color(0xFF2E332F),
    emptyStateIcon = Color(0xFF868D86),
    emptyStateText = Color(0xFF868D86),
    runningIndicator = Color(0xFF6FD3A2),
    // 高对比度无障碍：同上，玻璃退化为近乎不透明
    glassTint = Color(0xF20A0C0A),
    glassFallback = Color(0xFF0A0C0A),
    glassBorder = Color(0x66B6BCB5),
    glassSheen = Color(0x00FFFFFF),
)

/** 当前主题色令牌。读取方式：`AppTheme.colors.brand` */
val LocalAppColors = staticCompositionLocalOf { LightAppColors }

// ========== 扩展配色（玄青之外的三套，结构与默认色板一一对应） ==========
//
// 设计原则与「玄青·流萤」同源：避开高饱和的蓝紫科技感套路，全部走"低饱和墨色 + 纸感底色"。
// 状态色（成功/警告/错误）跨色板共用同一组值——状态色的语义是全局的，不随配色换色相。
// 磨砂三件套的 alpha 基准与默认色板一致（浅 0x87 / 深 0x73，降级 0xE0），只有 RGB 跟着底色走。

/** 赭石 · 浅色：暖沙纸底 + 陶赭主色，暖金辅色、灰绿冷辅 */
val LightOchreAppColors = AppColors(
    brand = Color(0xFFB0592C),
    onBrand = Color(0xFFFFFFFF),
    brandContainer = Color(0xFFF6DFCB),
    onBrandContainer = Color(0xFF47220D),
    accentWarm = Color(0xFF9A7B12),
    onAccentWarm = Color(0xFFFFFFFF),
    accentWarmContainer = Color(0xFFF5EAC6),
    onAccentWarmContainer = Color(0xFF372A08),
    accentCool = Color(0xFF5E7D66),
    onAccentCool = Color(0xFFFFFFFF),
    accentCoolContainer = Color(0xFFDFEAE2),
    onAccentCoolContainer = Color(0xFF1D3324),
    surfaceBase = Color(0xFFF8F5F0),
    onSurfaceBase = Color(0xFF211B15),
    surfaceRaised = Color(0xFFEFE9E0),
    onSurfaceRaised = Color(0xFF59524A),
    surfaceSunken = Color(0xFFE6DFD4),
    outlineStrong = Color(0xFF777066),
    outlineSoft = Color(0xFFD2CABC),
    error = LightAppColors.error,
    onError = LightAppColors.onError,
    errorContainer = LightAppColors.errorContainer,
    onErrorContainer = LightAppColors.onErrorContainer,
    success = LightAppColors.success,
    onSuccess = LightAppColors.onSuccess,
    successContainer = LightAppColors.successContainer,
    onSuccessContainer = LightAppColors.onSuccessContainer,
    warning = LightAppColors.warning,
    onWarning = LightAppColors.onWarning,
    warningContainer = LightAppColors.warningContainer,
    onWarningContainer = LightAppColors.onWarningContainer,
    messageBubbleAgent = Color(0xFFF4E8D8),
    onMessageBubbleAgent = Color(0xFF362619),
    messageBubbleUser = Color(0xFFB0592C),
    onMessageBubbleUser = Color(0xFFFFFFFF),
    railIdle = Color(0xFFD2CABC),
    railActive = Color(0xFFB0592C),
    railDone = Color(0xFFCFA27E),
    statusBarScrim = Color(0xFFFFFFFF),
    phoneShell = Color(0xFFEAE5DC),
    phoneShellBorder = Color(0xFFC9C2B4),
    phoneCameraHole = Color(0xFFB8B1A2),
    phoneCameraHoleIdle = Color(0xFFA8A192),
    emptyStateIcon = Color(0xFFB5AEA0),
    emptyStateText = Color(0xFF878070),
    runningIndicator = LightAppColors.runningIndicator,
    glassTint = Color(0x87F8F5F0),
    glassFallback = Color(0xE0F8F5F0),
    glassBorder = Color(0x26B0592C),
    glassSheen = Color(0x14FFFFFF),
)

/** 赭石 · 深色：深褐墨底 + 流萤赭主色 */
val DarkOchreAppColors = AppColors(
    brand = Color(0xFFE89B6B),
    onBrand = Color(0xFF341806),
    brandContainer = Color(0xFF4E2C13),
    onBrandContainer = Color(0xFFF8D8BC),
    accentWarm = Color(0xFFD9B45C),
    onAccentWarm = Color(0xFF322708),
    accentWarmContainer = Color(0xFF4A3A12),
    onAccentWarmContainer = Color(0xFFF4E2B4),
    accentCool = Color(0xFF9FBF8E),
    onAccentCool = Color(0xFF1C2E14),
    accentCoolContainer = Color(0xFF2C4021),
    onAccentCoolContainer = Color(0xFFD4E8C8),
    surfaceBase = Color(0xFF16130F),
    onSurfaceBase = Color(0xFFEDE9E2),
    surfaceRaised = Color(0xFF1F1B15),
    onSurfaceRaised = Color(0xFFBCB4A7),
    surfaceSunken = Color(0xFF0F0C08),
    outlineStrong = Color(0xFF958D80),
    outlineSoft = Color(0xFF423D33),
    error = DarkAppColors.error,
    onError = DarkAppColors.onError,
    errorContainer = DarkAppColors.errorContainer,
    onErrorContainer = DarkAppColors.onErrorContainer,
    success = DarkAppColors.success,
    onSuccess = DarkAppColors.onSuccess,
    successContainer = DarkAppColors.successContainer,
    onSuccessContainer = DarkAppColors.onSuccessContainer,
    warning = DarkAppColors.warning,
    onWarning = DarkAppColors.onWarning,
    warningContainer = DarkAppColors.warningContainer,
    onWarningContainer = DarkAppColors.onWarningContainer,
    messageBubbleAgent = Color(0xFF33281B),
    onMessageBubbleAgent = Color(0xFFEAE0D0),
    messageBubbleUser = Color(0xFF4A2A14),
    onMessageBubbleUser = Color(0xFFF5DCC2),
    railIdle = Color(0xFF423D33),
    railActive = Color(0xFFE89B6B),
    railDone = Color(0xFF8A6A45),
    statusBarScrim = Color(0xF216130F),
    phoneShell = Color(0xFF1A1611),
    phoneShellBorder = Color(0xFF2F2A21),
    phoneCameraHole = Color(0xFF3B352A),
    phoneCameraHoleIdle = Color(0xFF272219),
    emptyStateIcon = Color(0xFF6E675A),
    emptyStateText = Color(0xFF6E675A),
    runningIndicator = DarkAppColors.runningIndicator,
    glassTint = Color(0x7316130F),
    glassFallback = Color(0xE016130F),
    glassBorder = Color(0x3DE89B6B),
    glassSheen = Color(0x0AFFFFFF),
)

/** 黛蓝 · 浅色：冷纸灰底 + 黛蓝主色（低饱和墨蓝，非科技蓝），暗金辅色、灰绿冷辅 */
val LightInkBlueAppColors = AppColors(
    brand = Color(0xFF35618E),
    onBrand = Color(0xFFFFFFFF),
    brandContainer = Color(0xFFD8E4F0),
    onBrandContainer = Color(0xFF10263C),
    accentWarm = LightAppColors.accentWarm,
    onAccentWarm = LightAppColors.onAccentWarm,
    accentWarmContainer = LightAppColors.accentWarmContainer,
    onAccentWarmContainer = LightAppColors.onAccentWarmContainer,
    accentCool = Color(0xFF5E7D66),
    onAccentCool = Color(0xFFFFFFFF),
    accentCoolContainer = Color(0xFFDFEAE2),
    onAccentCoolContainer = Color(0xFF1D3324),
    surfaceBase = Color(0xFFF4F6F8),
    onSurfaceBase = Color(0xFF191D21),
    surfaceRaised = Color(0xFFE9EDF1),
    onSurfaceRaised = Color(0xFF4D555D),
    surfaceSunken = Color(0xFFDFE4E9),
    outlineStrong = Color(0xFF6C7580),
    outlineSoft = Color(0xFFC8CED5),
    error = LightAppColors.error,
    onError = LightAppColors.onError,
    errorContainer = LightAppColors.errorContainer,
    onErrorContainer = LightAppColors.onErrorContainer,
    success = LightAppColors.success,
    onSuccess = LightAppColors.onSuccess,
    successContainer = LightAppColors.successContainer,
    onSuccessContainer = LightAppColors.onSuccessContainer,
    warning = LightAppColors.warning,
    onWarning = LightAppColors.onWarning,
    warningContainer = LightAppColors.warningContainer,
    onWarningContainer = LightAppColors.onWarningContainer,
    messageBubbleAgent = Color(0xFFE2EBF3),
    onMessageBubbleAgent = Color(0xFF142A3E),
    messageBubbleUser = Color(0xFF35618E),
    onMessageBubbleUser = Color(0xFFFFFFFF),
    railIdle = Color(0xFFC8CED5),
    railActive = Color(0xFF35618E),
    railDone = Color(0xFF7FA3C4),
    statusBarScrim = Color(0xFFFFFFFF),
    phoneShell = Color(0xFFE7EAEE),
    phoneShellBorder = Color(0xFFC7CDD4),
    phoneCameraHole = Color(0xFFB5BCC3),
    phoneCameraHoleIdle = Color(0xFFA5ACB3),
    emptyStateIcon = Color(0xFFB2B9C0),
    emptyStateText = Color(0xFF82898E),
    runningIndicator = LightAppColors.runningIndicator,
    glassTint = Color(0x87F4F6F8),
    glassFallback = Color(0xE0F4F6F8),
    glassBorder = Color(0x2635618E),
    glassSheen = Color(0x14FFFFFF),
)

/** 黛蓝 · 深色：冷墨底 + 月光蓝主色 */
val DarkInkBlueAppColors = AppColors(
    brand = Color(0xFF8FB8DE),
    onBrand = Color(0xFF0B2338),
    brandContainer = Color(0xFF1E3A56),
    onBrandContainer = Color(0xFFC8DFF2),
    accentWarm = DarkAppColors.accentWarm,
    onAccentWarm = DarkAppColors.onAccentWarm,
    accentWarmContainer = DarkAppColors.accentWarmContainer,
    onAccentWarmContainer = DarkAppColors.onAccentWarmContainer,
    accentCool = Color(0xFF93C4A8),
    onAccentCool = Color(0xFF0F2A1C),
    accentCoolContainer = Color(0xFF21402F),
    onAccentCoolContainer = Color(0xFFCDE8DA),
    surfaceBase = Color(0xFF111417),
    onSurfaceBase = Color(0xFFE6E9EC),
    surfaceRaised = Color(0xFF1A1E22),
    onSurfaceRaised = Color(0xFFB4BBC2),
    surfaceSunken = Color(0xFF0B0D0F),
    outlineStrong = Color(0xFF8C939C),
    outlineSoft = Color(0xFF3A4046),
    error = DarkAppColors.error,
    onError = DarkAppColors.onError,
    errorContainer = DarkAppColors.errorContainer,
    onErrorContainer = DarkAppColors.onErrorContainer,
    success = DarkAppColors.success,
    onSuccess = DarkAppColors.onSuccess,
    successContainer = DarkAppColors.successContainer,
    onSuccessContainer = DarkAppColors.onSuccessContainer,
    warning = DarkAppColors.warning,
    onWarning = DarkAppColors.onWarning,
    warningContainer = DarkAppColors.warningContainer,
    onWarningContainer = DarkAppColors.onWarningContainer,
    messageBubbleAgent = Color(0xFF1D2C3A),
    onMessageBubbleAgent = Color(0xFFD8E4EE),
    messageBubbleUser = Color(0xFF1E3A56),
    onMessageBubbleUser = Color(0xFFCFE4F5),
    railIdle = Color(0xFF3A4046),
    railActive = Color(0xFF8FB8DE),
    railDone = Color(0xFF3E6285),
    statusBarScrim = Color(0xF2111417),
    phoneShell = Color(0xFF15191D),
    phoneShellBorder = Color(0xFF2A2F35),
    phoneCameraHole = Color(0xFF3A4046),
    phoneCameraHoleIdle = Color(0xFF252A2F),
    emptyStateIcon = Color(0xFF6B7278),
    emptyStateText = Color(0xFF6B7278),
    runningIndicator = DarkAppColors.runningIndicator,
    glassTint = Color(0x73111417),
    glassFallback = Color(0xE0111417),
    glassBorder = Color(0x3D8FB8DE),
    glassSheen = Color(0x0AFFFFFF),
)

/** 绛紫 · 浅色：淡纸底 + 绛红主色（墨色绛，非亮紫），暗金辅色、青灰冷辅 */
val LightPlumAppColors = AppColors(
    brand = Color(0xFF8E3D5C),
    onBrand = Color(0xFFFFFFFF),
    brandContainer = Color(0xFFF2D9E1),
    onBrandContainer = Color(0xFF3A1220),
    accentWarm = LightAppColors.accentWarm,
    onAccentWarm = LightAppColors.onAccentWarm,
    accentWarmContainer = LightAppColors.accentWarmContainer,
    onAccentWarmContainer = LightAppColors.onAccentWarmContainer,
    accentCool = Color(0xFF4E7A72),
    onAccentCool = Color(0xFFFFFFFF),
    accentCoolContainer = Color(0xFFDCE9E5),
    onAccentCoolContainer = Color(0xFF16332C),
    surfaceBase = Color(0xFFF8F5F6),
    onSurfaceBase = Color(0xFF1F191C),
    surfaceRaised = Color(0xFFEFE9EC),
    onSurfaceRaised = Color(0xFF584B51),
    surfaceSunken = Color(0xFFE5DEE1),
    outlineStrong = Color(0xFF796B72),
    outlineSoft = Color(0xFFD1C7CC),
    error = LightAppColors.error,
    onError = LightAppColors.onError,
    errorContainer = LightAppColors.errorContainer,
    onErrorContainer = LightAppColors.onErrorContainer,
    success = LightAppColors.success,
    onSuccess = LightAppColors.onSuccess,
    successContainer = LightAppColors.successContainer,
    onSuccessContainer = LightAppColors.onSuccessContainer,
    warning = LightAppColors.warning,
    onWarning = LightAppColors.onWarning,
    warningContainer = LightAppColors.warningContainer,
    onWarningContainer = LightAppColors.onWarningContainer,
    messageBubbleAgent = Color(0xFFF2E2E9),
    onMessageBubbleAgent = Color(0xFF331522),
    messageBubbleUser = Color(0xFF8E3D5C),
    onMessageBubbleUser = Color(0xFFFFFFFF),
    railIdle = Color(0xFFD1C7CC),
    railActive = Color(0xFF8E3D5C),
    railDone = Color(0xFFC68CA4),
    statusBarScrim = Color(0xFFFFFFFF),
    phoneShell = Color(0xFFEAE4E7),
    phoneShellBorder = Color(0xFFC9BFC4),
    phoneCameraHole = Color(0xFFB8ACB1),
    phoneCameraHoleIdle = Color(0xFFA89CA1),
    emptyStateIcon = Color(0xFFB5AAAF),
    emptyStateText = Color(0xFF878078),
    runningIndicator = LightAppColors.runningIndicator,
    glassTint = Color(0x87F8F5F6),
    glassFallback = Color(0xE0F8F5F6),
    glassBorder = Color(0x268E3D5C),
    glassSheen = Color(0x14FFFFFF),
)

/** 绛紫 · 深色：暗梅墨底 + 流萤绛主色 */
val DarkPlumAppColors = AppColors(
    brand = Color(0xFFE39BB4),
    onBrand = Color(0xFF340F1D),
    brandContainer = Color(0xFF4E1F30),
    onBrandContainer = Color(0xFFF6D3E0),
    accentWarm = DarkAppColors.accentWarm,
    onAccentWarm = DarkAppColors.onAccentWarm,
    accentWarmContainer = DarkAppColors.accentWarmContainer,
    onAccentWarmContainer = DarkAppColors.onAccentWarmContainer,
    accentCool = Color(0xFF8FBEA8),
    onAccentCool = Color(0xFF0F2A1E),
    accentCoolContainer = Color(0xFF204035),
    onAccentCoolContainer = Color(0xFFCCE8DB),
    surfaceBase = Color(0xFF151114),
    onSurfaceBase = Color(0xFFEBE6E9),
    surfaceRaised = Color(0xFF1E191C),
    onSurfaceRaised = Color(0xFFB9AEB4),
    surfaceSunken = Color(0xFF0E0B0D),
    outlineStrong = Color(0xFF92878D),
    outlineSoft = Color(0xFF3E363B),
    error = DarkAppColors.error,
    onError = DarkAppColors.onError,
    errorContainer = DarkAppColors.errorContainer,
    onErrorContainer = DarkAppColors.onErrorContainer,
    success = DarkAppColors.success,
    onSuccess = DarkAppColors.onSuccess,
    successContainer = DarkAppColors.successContainer,
    onSuccessContainer = DarkAppColors.onSuccessContainer,
    warning = DarkAppColors.warning,
    onWarning = DarkAppColors.onWarning,
    warningContainer = DarkAppColors.warningContainer,
    onWarningContainer = DarkAppColors.onWarningContainer,
    messageBubbleAgent = Color(0xFF32222A),
    onMessageBubbleAgent = Color(0xFFEDDDE4),
    messageBubbleUser = Color(0xFF46202F),
    onMessageBubbleUser = Color(0xFFF2D2DE),
    railIdle = Color(0xFF3E363B),
    railActive = Color(0xFFE39BB4),
    railDone = Color(0xFF8A5A70),
    statusBarScrim = Color(0xF2151114),
    phoneShell = Color(0xFF191417),
    phoneShellBorder = Color(0xFF2E282C),
    phoneCameraHole = Color(0xFF3E363B),
    phoneCameraHoleIdle = Color(0xFF282226),
    emptyStateIcon = Color(0xFF6E666B),
    emptyStateText = Color(0xFF6E666B),
    runningIndicator = DarkAppColors.runningIndicator,
    glassTint = Color(0x73151114),
    glassFallback = Color(0xE0151114),
    glassBorder = Color(0x3DE39BB4),
    glassSheen = Color(0x0AFFFFFF),
)

/**
 * 浅色高对比度通用派生：纯白底、加深主色与描边。
 * 只给玄青之外的三套配色用——玄青有自己的手工精调版（[LightContrastAppColors]），不走这里。
 * 数值变换与精调版同一取向：文字/描边/主色向黑压，底色提到纯白，玻璃退化为近乎不透明。
 */
private fun lightContrastOf(c: AppColors): AppColors = c.copy(
    brand = lerp(c.brand, Color.Black, 0.18f),
    onBrandContainer = lerp(c.onBrandContainer, Color.Black, 0.3f),
    accentWarm = lerp(c.accentWarm, Color.Black, 0.2f),
    onAccentWarmContainer = lerp(c.onAccentWarmContainer, Color.Black, 0.3f),
    accentCool = lerp(c.accentCool, Color.Black, 0.2f),
    onAccentCoolContainer = lerp(c.onAccentCoolContainer, Color.Black, 0.3f),
    surfaceBase = Color.White,
    onSurfaceBase = lerp(c.onSurfaceBase, Color.Black, 0.5f),
    surfaceRaised = lerp(c.surfaceRaised, Color.White, 0.25f),
    onSurfaceRaised = lerp(c.onSurfaceRaised, Color.Black, 0.35f),
    surfaceSunken = lerp(c.surfaceSunken, Color.Black, 0.1f),
    outlineStrong = lerp(c.outlineStrong, Color.Black, 0.4f),
    outlineSoft = lerp(c.outlineSoft, Color.Black, 0.22f),
    error = lerp(c.error, Color.Black, 0.12f),
    success = lerp(c.success, Color.Black, 0.12f),
    warning = lerp(c.warning, Color.Black, 0.12f),
    onMessageBubbleAgent = lerp(c.onMessageBubbleAgent, Color.Black, 0.5f),
    railIdle = lerp(c.railIdle, Color.Black, 0.22f),
    railActive = lerp(c.brand, Color.Black, 0.18f),
    railDone = lerp(c.railDone, Color.Black, 0.2f),
    statusBarScrim = Color.White,
    emptyStateText = lerp(c.emptyStateText, Color.Black, 0.35f),
    runningIndicator = lerp(c.runningIndicator, Color.Black, 0.12f),
    // 高对比度无障碍：玻璃退化为近乎不透明，与玄青精调版同一处理
    glassTint = Color(0xF2FFFFFF),
    glassFallback = Color(0xFFFFFFFF),
    glassBorder = lerp(c.outlineStrong, Color.Black, 0.4f).copy(alpha = 0x66 / 255f),
    glassSheen = Color(0x00FFFFFF),
)

/**
 * 深色高对比度通用派生：纯黑底、提亮主色与描边。同 [lightContrastOf]，只服务非默认配色。
 */
private fun darkContrastOf(c: AppColors): AppColors {
    val base = lerp(c.surfaceBase, Color.Black, 0.5f)
    val outline = lerp(c.outlineStrong, Color.White, 0.3f)
    return c.copy(
        brand = lerp(c.brand, Color.White, 0.18f),
        onBrandContainer = lerp(c.onBrandContainer, Color.White, 0.3f),
        accentWarm = lerp(c.accentWarm, Color.White, 0.18f),
        onAccentWarmContainer = lerp(c.onAccentWarmContainer, Color.White, 0.3f),
        accentCool = lerp(c.accentCool, Color.White, 0.18f),
        onAccentCoolContainer = lerp(c.onAccentCoolContainer, Color.White, 0.3f),
        surfaceBase = base,
        onSurfaceBase = Color.White,
        surfaceRaised = lerp(c.surfaceRaised, Color.White, 0.08f),
        onSurfaceRaised = lerp(c.onSurfaceRaised, Color.White, 0.25f),
        surfaceSunken = lerp(c.surfaceSunken, Color.Black, 0.4f),
        outlineStrong = outline,
        outlineSoft = lerp(c.outlineSoft, Color.White, 0.3f),
        error = lerp(c.error, Color.White, 0.15f),
        success = lerp(c.success, Color.White, 0.15f),
        warning = lerp(c.warning, Color.White, 0.15f),
        messageBubbleUser = lerp(c.messageBubbleUser, Color.White, 0.1f),
        onMessageBubbleAgent = lerp(c.onMessageBubbleAgent, Color.White, 0.3f),
        onMessageBubbleUser = lerp(c.onMessageBubbleUser, Color.White, 0.3f),
        railIdle = lerp(c.outlineSoft, Color.White, 0.3f),
        railActive = lerp(c.brand, Color.White, 0.18f),
        railDone = lerp(c.railDone, Color.White, 0.15f),
        statusBarScrim = base.copy(alpha = 0xF2 / 255f),
        emptyStateIcon = lerp(c.emptyStateIcon, Color.White, 0.25f),
        emptyStateText = lerp(c.emptyStateText, Color.White, 0.25f),
        runningIndicator = lerp(c.runningIndicator, Color.White, 0.15f),
        glassTint = base.copy(alpha = 0xF2 / 255f),
        glassFallback = base,
        glassBorder = outline.copy(alpha = 0x66 / 255f),
        glassSheen = Color(0x00FFFFFF),
    )
}

/**
 * 可选配色方案。每套配色浅/深各一份，深浅跟随系统；
 * 系统开「高对比度文字」时玄青走手工精调版，其余配色由通用派生函数实时算出。
 * 存设置时只存 [id] 字符串，新增配色不影响旧数据反序列化。
 */
enum class ThemePalette(
    val id: String,
    val label: String,
    val subtitle: String,
    val light: AppColors,
    val dark: AppColors,
) {
    /** 默认：墨玉绿 × 暖纸白（玄青·流萤） */
    INK_GREEN("ink_green", "玄青", "墨玉绿 × 暖纸白（默认）", LightAppColors, DarkAppColors),
    /** 暖调：陶赭 × 暖沙纸 */
    OCHRE("ochre", "赭石", "陶赭 × 暖沙纸", LightOchreAppColors, DarkOchreAppColors),
    /** 冷调：低饱和墨蓝 × 冷纸灰 */
    INK_BLUE("ink_blue", "黛蓝", "墨蓝 × 冷纸灰", LightInkBlueAppColors, DarkInkBlueAppColors),
    /** 浓调：墨色绛红 × 淡纸 */
    PLUM("plum", "绛紫", "绛红 × 淡纸", LightPlumAppColors, DarkPlumAppColors),
    ;

    val lightContrast: AppColors
        get() = if (this == INK_GREEN) LightContrastAppColors else lightContrastOf(light)
    val darkContrast: AppColors
        get() = if (this == INK_GREEN) DarkContrastAppColors else darkContrastOf(dark)

    companion object {
        /** 按存储的 id 解析；未知值（旧版本/脏数据）回落默认玄青 */
        fun of(id: String?): ThemePalette = entries.firstOrNull { it.id == id } ?: INK_GREEN
    }
}

/** 主题色令牌读取入口，避免各处直接触碰 CompositionLocal。 */
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

// ========== 兼容旧调用点的具名颜色（值已对齐新配色，不再新增引用） ==========
/** 品牌深色（图标点缀、雷达图中心） */
val BrandNavy = Color(0xFF0A2E27)
/** 品牌主色（玄青） */
val Accent = LightAppColors.brand
/** 状态色：成功 / 警告 / 错误 */
val Success = LightAppColors.success
val Warning = LightAppColors.warning
val Error = LightAppColors.error

// ========== 记忆图谱配色（与品牌色系对齐） ==========
/** 图谱中心根节点 */
val MemoryRoot = LightAppColors.brand
/** 异常经验分类（暖琥珀） */
val MemoryAnomaly = Color(0xFFD97A2B)
val MemoryAnomalySoft = Color(0xFFF0BC8C)
/** 用户画像分类（雾蓝） */
val MemoryProfile = Color(0xFF4A7C9B)
val MemoryProfileSoft = Color(0xFFA9CBDD)

// ========== 测试分组配色（保留分组辨识度，色相全部落在新色系内） ==========
val TestReal = Color(0xFF4A7C9B)        // 真实场景（雾蓝）
val TestFormat = Color(0xFF0E7C66)      // 格式合规（玄青）
val TestTargeting = Color(0xFF2A9E96)   // 目标定位（青绿）
val TestDecision = Color(0xFF9A6B12)    // 决策（暗金）
val TestMerge = Color(0xFFA24E6E)       // 链路聚合（绛紫）
val TestRegression = Color(0xFF1F7A55)  // 回归基准（松绿）