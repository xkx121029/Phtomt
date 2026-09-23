package com.phoneagent.overlay

import android.graphics.drawable.GradientDrawable

/**
 * 悬浮窗 UI 设计令牌（Material 3 Expressive 质感，原生 View 实现）。
 *
 * 视觉原则（emilkowalski/skills + M3 Expressive，避免俗套 AI 审美）：
 * - 统一主题色：窗口底色用 App 的「玄青」品牌实色，不做半透明/玻璃质感——
 *   浮窗压在其他 App 之上，透出的底图会让文字随时失去对比度，实色反而更克制
 * - 清晰层级：通过 卡片嵌套 + 柔和阴影 + 差异化字号 建立主次
 * - 大圆角 + 胶囊：卡片 28dp、内层 20dp、徽章/按钮胶囊，体现 M3 流动感
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

    // 圆角（dp）
    const val RADIUS_CARD = 28f       // 外层悬浮窗圆角
    const val RADIUS_PANEL = 18f      // 内层卡片圆角
    const val RADIUS_CHIP = 12f       // 小徽章
    const val RADIUS_INPUT = 12f      // 输入框

    // 窗口底色：应用主题色实色（与 ui/theme 的「玄青」品牌色同源）
    const val BRAND = 0xFF0E7C66.toInt()            // 品牌主色
    const val BRAND_DEEP = 0xFF0A5F4E.toInt()       // 深一档：按下态 / 细描边
    const val ON_BRAND = 0xFFFFFFFF.toInt()         // 主题色底上的主文字
    const val ON_BRAND_SECONDARY = 0xFFC6E8DD.toInt()
    const val ON_BRAND_TERTIARY = 0xFF93C7B8.toInt()
    /** 主题色底上的状态层：只做层次，不改变窗口整体不透明度 */
    const val ON_BRAND_STATE = 0x33FFFFFF.toInt()
    const val ON_BRAND_STATE_WEAK = 0x1FFFFFFF.toInt()

    // 内层卡片底色（AI 详情 / 交互 / 完成）：暖纸白实色，与 App 主体底色一致
    const val PANEL = 0xFFF7F6F3.toInt()
    const val PANEL_SUNKEN = 0xFFE3E6E0.toInt()
    const val PANEL_STATE = 0x0F000000.toInt()

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