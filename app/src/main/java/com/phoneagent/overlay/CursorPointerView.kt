package com.phoneagent.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

/**
 * 点击光标视图：全屏透明，在 (cx, cy) 处绘制一个圆润指针（内核实心圆 + 半透明光环）。
 *
 * 只改内部状态并 invalidate，**不做 View 位移**——全屏 View 反复 layout 代价高。
 * 一条 ValueAnimator 时间线串起「移动 → 按下 → 松开」三段、各段用不同缓动，
 * 因此"等光标到位"只需在移动段结束时回调，无需串联多个动画。
 *
 * 配色与 ui/theme/Color.kt 的 brand / accentWarm 保持一致（浅色玄青→琥珀，深色流萤青→暖橙）。
 */
class CursorPointerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        /** 移动段时长（与 Motion.DurationSlow 同量级） */
        const val MOVE_MS = 260f
        /** 按下段 */
        const val PRESS_MS = 90f
        /** 松开段（Motion.DurationFast） */
        const val RELEASE_MS = 160f
        const val TOTAL_MS = MOVE_MS + PRESS_MS + RELEASE_MS

        /** 按下时缩到的比例 */
        const val PRESS_SCALE = 0.72f

        /** 光环填充透明度 */
        const val RING_FILL_ALPHA = 38
        /** 光环描边透明度 */
        const val RING_STROKE_ALPHA = 140
    }

    private val density = resources.displayMetrics.density

    /** 光环半径（常态） */
    private val ringRadius = 17f * density

    /** 内核实心圆半径（常态） */
    private val coreRadius = 7f * density

    /** 深色主题下取深色色板，与 AppColors 的 Dark 值一致 */
    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val brandColor: Int get() = if (isNight) 0xFF5FD9B4.toInt() else 0xFF0E7C66.toInt()
    private val warmColor: Int get() = if (isNight) 0xFFF0A868.toInt() else 0xFFB4632A.toInt()

    // ---- 绘制状态 ----
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f
    /** 0 = 常态色（玄青），1 = 按下色（琥珀） */
    private var pressProgress = 0f

    private var animator: ValueAnimator? = null

    /** 缓动：移动用 EaseInOut，按压/松开用 EaseOut（取自 Motion.kt 的贝塞尔值） */
    private val easeInOut = PathInterpolator(0.77f, 0f, 0.175f, 1f)
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    private val ringFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 直接定位（首次出现时用，不做移动动画） */
    fun setPosition(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }

    /**
     * 平滑移动到 (x, y)，到达后自动做一次「按下 → 松开」脉冲。
     * @param onArrived 移动段结束时回调（同步模式据此等到位再执行点击）
     */
    fun animateTo(x: Float, y: Float, onArrived: (() -> Unit)? = null) {
        animator?.cancel()
        val fromX = cx
        val fromY = cy
        animator = ValueAnimator.ofFloat(0f, TOTAL_MS).apply {
            duration = TOTAL_MS.toLong()
            // 线性推进，各段自行套缓动，便于按时间点精确切分三个阶段
            interpolator = null
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                applyTimeline(t, fromX, fromY, x, y)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 被取消时也复位，避免光标停在按下态
                    scale = 1f
                    pressProgress = 0f
                    invalidate()
                }
            })
            start()
        }
        // 移动段结束即回调，早于整条时间线结束
        postDelayed({ onArrived?.invoke() }, MOVE_MS.toLong())
    }

    /** 把整条时间线的进度映射到「移动 / 按下 / 松开」三段 */
    private fun applyTimeline(t: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val moveEnd = MOVE_MS
        val pressEnd = MOVE_MS + PRESS_MS
        when {
            t <= moveEnd -> {
                val p = easeInOut.getInterpolation((t / MOVE_MS).coerceIn(0f, 1f))
                cx = fromX + (toX - fromX) * p
                cy = fromY + (toY - fromY) * p
                scale = 1f
                pressProgress = 0f
            }
            t <= pressEnd -> {
                val p = easeOut.getInterpolation(((t - moveEnd) / PRESS_MS).coerceIn(0f, 1f))
                cx = toX
                cy = toY
                scale = 1f - (1f - PRESS_SCALE) * p
                pressProgress = p
            }
            else -> {
                val p = easeOut.getInterpolation(((t - pressEnd) / RELEASE_MS).coerceIn(0f, 1f))
                cx = toX
                cy = toY
                scale = PRESS_SCALE + (1f - PRESS_SCALE) * p
                pressProgress = 1f - p
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = ringRadius * scale
        val core = coreRadius * scale
        val tint = blend(brandColor, warmColor, pressProgress)

        ringFillPaint.color = withAlpha(tint, RING_FILL_ALPHA)
        canvas.drawCircle(cx, cy, r, ringFillPaint)

        ringStrokePaint.color = withAlpha(tint, RING_STROKE_ALPHA)
        canvas.drawCircle(cx, cy, r, ringStrokePaint)

        corePaint.color = tint
        canvas.drawCircle(cx, cy, core, corePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    /** 线性插值两个颜色（含 alpha），避免为一个工具函数引入额外依赖 */
    private fun blend(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * k).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt(),
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
