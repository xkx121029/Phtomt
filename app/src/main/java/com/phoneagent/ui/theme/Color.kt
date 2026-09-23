package com.phoneagent.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    glassTint = Color(0xA0F7F6F3),
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
    glassTint = Color(0x8C141816),
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