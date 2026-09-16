package com.phoneagent.overlay

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * 悬浮窗 UI 设计令牌（Material 3 Expressive 质感，原生 View 实现）。
 *
 * 视觉原则（emilkowalski/skills + M3 Expressive，避免俗套 AI 审美）：
 * - 克制的色彩：以中性高明度的液态玻璃为主，主色仅作点缀，不做彩虹堆叠
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

    // 圆角（dp）
    const val RADIUS_CARD = 28f       // 外层悬浮窗圆角
    const val RADIUS_PANEL = 18f      // 内层卡片圆角
    const val RADIUS_CHIP = 12f       // 小徽章
    const val RADIUS_INPUT = 12f      // 输入框

    // 玻璃底色（高透明白，透出屏幕内容）
    const val BASE = 0xEEF6F8FF.toInt()      // 热白半透明
    const val BASE_STROKE = 0x28FFFFFF.toInt()
    // 内层卡片底色（同基色偏白，用于在玻璃上叠加层次）
    const val PANEL = 0x1AFFFFFF.toInt()

    // 文字
    const val TEXT_PRIMARY = 0xFF1A1D24.toInt()
    const val TEXT_SECONDARY = 0xFF5C6270.toInt()
    const val TEXT_TERTIARY = 0xFF8A8F9C.toInt()

    // 主色（克制的靛蓝）
    const val ACCENT_BLUE = 0xFF3B6EF6.toInt()
    const val ACCENT_PURPLE = 0xFF7C4DFF.toInt()
    const val ACCENT_PINK = 0xFFEC407A.toInt()

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