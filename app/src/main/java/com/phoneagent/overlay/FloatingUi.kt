package com.phoneagent.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable

/**
 * 悬浮窗 UI 设计令牌（Material 3 Expressive 质感，原生 View 实现）。
 *
 * 视觉原则（emilkowalski/skills + M3 Expressive，避免俗套 AI 审美）：
 * - 统一主题色：任务卡片底色用 App 的「玄青」品牌色半透明化（[BRAND_GLASS]）+ 发丝描边。
 *   **不做背景模糊**：窗口的 blurBehindRadius 糊的是整块屏幕而不是卡片（真机表现为任务一跑
 *   整屏发灰），而"只糊窗口范围"的 Window#setBackgroundBlurRadius 悬浮窗拿不到（没有 Window
 *   对象，见 FloatingWindowService 类注释）。品牌色保持高不透明度（85%），底图透出来也不影响读字
 * - 清晰层级：通过 卡片嵌套 + 柔和阴影 + 差异化字号 建立主次
 * - 大圆角 + 胶囊：卡片 28dp、内层 18dp、选项行与输入 14dp、徽章/按钮全圆胶囊，体现 M3 流动感
 * - 舒适间距：统一 4/8/12 间距体系，不再全用 8dp 怼满
 */
object FloatingUi {
    // 间距（dp）
    const val PAD_S = 4
    const val PAD = 8
    const val PAD_L = 12
    const val PAD_XL = 16

    // 尺寸与位置（dp）：集中在令牌里，避免同一个魔法数字散落在窗口定位、惯性滑行、吸附各处
    const val WIDTH = 300          // 悬浮窗固定宽度
    const val ELEVATION = 12       // 投影高度（M3 柔和浮起，不用夸张阴影）
    const val EDGE_GAP = 8         // 贴边后与屏幕边缘的留白
    const val SNAP_ZONE = 28       // 松手时进入该距离内即吸附到边缘
    const val THINKING_H = 112     // AI 详情展开区高度（折叠时该区域完全不占位）

    // 圆角（dp）：三层口径，与 ui/theme 的 AppRadii 同源（Hero 28 / Tile 14 / Chip 10 的胶囊化）
    // 同一块面板内只允许出现这三档，不许再混进 12 / 18 / 20 这类"感觉差不多"的数字——
    // 圆角不一致时，读起来像几个不同来源的控件拼在一起
    const val RADIUS_CARD = 28f       // 第一层：顶层面板（状态卡 / 答疑选项卡 / App 底部浮层）
    const val RADIUS_PANEL = 18f      // 第二层：面板内的内层卡片（AI 详情 / 完成面板）
    const val RADIUS_TILE = 14f       // 第三层：选项行 / 输入框（对应 AppRadii.Tile）
    const val RADIUS_CHIP = 12f       // 状态卡头部的 26dp 小徽章（小尺寸下 12 已接近整圆）
    const val RADIUS_PILL = 999f      // 胶囊：出口按钮 / 徽章 —— 全圆，不参与上面的层级

    // 窗口底色：应用主题色实色（与 ui/theme 的「玄青」品牌色同源）
    const val BRAND = 0xFF0E7C66.toInt()            // 品牌主色
    const val BRAND_DEEP = 0xFF0A5F4E.toInt()       // 深一档：按下态 / 细描边

    // 任务卡片底色：品牌色 85% 不透明度（0xD9）。
    // 不做到更透是刻意的：卡片坐在别的 App 画面之上，这块底色就是白字唯一的背景，
    // 85% 的玄青在任何底图上都压得住字。注意这里没有模糊可配，见类注释。
    const val BRAND_GLASS = 0xD90E7C66.toInt()
    /** 玻璃收边的细描边：只在浅色底图上才看得出来 */
    const val GLASS_EDGE = 0x2EFFFFFF.toInt()
    const val ON_BRAND = 0xFFFFFFFF.toInt()         // 主题色底上的主文字
    const val ON_BRAND_SECONDARY = 0xFFC6E8DD.toInt()
    const val ON_BRAND_TERTIARY = 0xFF93C7B8.toInt()
    /** 主题色底上的状态层：只做层次，不改变窗口整体不透明度 */
    const val ON_BRAND_STATE = 0x33FFFFFF.toInt()
    const val ON_BRAND_STATE_WEAK = 0x1FFFFFFF.toInt()

    // 内层卡片底色（AI 详情 / 交互 / 完成）：暖纸白实色，与 App 主体底色一致。
    // 浅底上一律用「实色 + 发丝描边」做层次，不再叠半透明黑（0x0F000000 那种）——
    // 叠层会让表面变成半透明，坐在别的 App 画面上时底色透出来，读起来脏
    const val PANEL = 0xFFF7F6F3.toInt()
    const val PANEL_SUNKEN = 0xFFE3E6E0.toInt()
    /** 浅底上的发丝描边：替代半透明叠层做层次，保证表面全不透明 */
    const val PANEL_EDGE = 0x141A1D1B.toInt()

    // 深色主题下的一整套对应色：内层卡片从暖纸白翻成近黑，文字同步翻白。
    // 取值与 ui/theme 的 DarkAppColors 同源（surfaceBase / surfaceSunken / onSurfaceBase …），
    // 这样悬浮窗与 App 在同一个系统开关下呈现的是同一套黑白
    const val PANEL_DARK = 0xFF121513.toInt()
    const val PANEL_SUNKEN_DARK = 0xFF0C0F0D.toInt()
    /** 深底上的发丝描边：与浅色的 8% 黑同强度，换成 8% 白 */
    const val PANEL_EDGE_DARK = 0x14FFFFFF.toInt()
    const val TEXT_PRIMARY_DARK = 0xFFE8EAE6.toInt()
    const val TEXT_SECONDARY_DARK = 0xFFB7BDB6.toInt()
    const val TEXT_TERTIARY_DARK = 0xFF8B928A.toInt()
    // 深色主题下的主色：与 DarkAppColors 同为「流萤青」，且底/字关系随之翻转
    // （深色下主色是亮青、压在上面的字是深墨）—— 深绿 #0E7C66 画在近黑面板上会糊掉
    const val BRAND_DARK = 0xFF5FD9B4.toInt()
    const val ON_BRAND_DARK = 0xFF04302A.toInt()

    /**
     * 悬浮窗当前该用哪一套「黑 / 白」。
     *
     * 悬浮窗跑在 Service 里，拿不到 App 的 Compose 主题（LocalAppColors），
     * 所以直接读系统深浅色开关 —— App 侧 PhoneAgentTheme 默认也是跟着它走的，
     * 于是两边永远在同一个时刻翻面，不需要跨进程同步状态。
     */
    fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 面板配色：只描述「内层表面 + 表面上的文字/主色」这一层，阶段色不参与切换
     * （观察/思考/执行那几档由语义决定，与深浅色无关）。
     *
     * 玄青卡片（顶部任务卡）与跑马灯也不切换：它们是品牌/阶段表面而非「黑白」表面，
     * 深浅两套下都压得住白字，跟着翻面反而会丢掉悬浮窗的身份。
     */
    data class Palette(
        val isDark: Boolean,
        val panel: Int,
        val panelSunken: Int,
        val panelEdge: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textTertiary: Int,
        /** 主色：浅色下画深绿字、深色下画亮青字 */
        val brand: Int,
        /** 主色实底上的字色（深色下是深墨字，底/字关系整体翻转） */
        val onBrand: Int,
    ) {
        companion object {
            val Light = Palette(
                isDark = false,
                panel = PANEL,
                panelSunken = PANEL_SUNKEN,
                panelEdge = PANEL_EDGE,
                textPrimary = TEXT_PRIMARY,
                textSecondary = TEXT_SECONDARY,
                textTertiary = TEXT_TERTIARY,
                brand = BRAND,
                onBrand = ON_BRAND,
            )
            val Dark = Palette(
                isDark = true,
                panel = PANEL_DARK,
                panelSunken = PANEL_SUNKEN_DARK,
                panelEdge = PANEL_EDGE_DARK,
                textPrimary = TEXT_PRIMARY_DARK,
                textSecondary = TEXT_SECONDARY_DARK,
                textTertiary = TEXT_TERTIARY_DARK,
                brand = BRAND_DARK,
                onBrand = ON_BRAND_DARK,
            )

            fun of(dark: Boolean): Palette = if (dark) Dark else Light
        }
    }

    // 文字（画在暖纸白内层卡片上）
    const val TEXT_PRIMARY = 0xFF1A1D1B.toInt()
    const val TEXT_SECONDARY = 0xFF4B514C.toInt()
    const val TEXT_TERTIARY = 0xFF838A83.toInt()

    // 暖色辅色（与主题调色板一致），用于「助手」徽章
    const val ACCENT_WARM = 0xFFB4632A.toInt()

    // 阶段色（观察/思考/执行/完成/错误）——克制的语义色
    fun phaseColor(phase: String): Int = when (phase) {
        "OBSERVING" -> 0xFF3B6EF6.toInt()
        "THINKING" -> 0xFF7C4DFF.toInt()
        "ACTING" -> 0xFFEC407A.toInt()
        "DONE" -> 0xFF00A877.toInt()
        "ERROR" -> 0xFFE5484D.toInt()
        else -> 0xFFF59E0B.toInt()
    }

    /** 胶囊形背景（圆角可再放大） */
    fun capsule(radius: Float = 999f, color: Int, stroke: Int? = null, strokeW: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (stroke != null) setStroke(strokeW, stroke)
        }
}