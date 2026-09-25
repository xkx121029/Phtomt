package com.phoneagent.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 光标形态：三种动作各有一套画法与时间线。
 *
 * - [TAP] 点击：飞向落点 → 按下脉冲 → 松开
 * - [LONG_PRESS] 长按：飞向落点 → 压住不放（呼吸光环 + 外圈）→ 松开
 * - [SWIPE] 滑动：落在起点 → 沿轨迹推进到终点（虚线指示去向、实线指示已走过）→ 轨迹淡出
 */
enum class CursorMode { TAP, LONG_PRESS, SWIPE }

/**
 * 光标视图：全屏透明，在 (cx, cy) 处绘制一个圆润指针（内核实心圆 + 半透明光环），
 * 并按 [CursorMode] 叠加"长按外圈"或"滑动轨迹"。
 *
 * 只改内部状态并 invalidate，**不做 View 位移**——全屏 View 反复 layout 代价高。
 * 一条 ValueAnimator 时间线串起整段动效、各段用不同缓动，因此"等光标到位"只需在移动段结束时回调，
 * 无需串联多个动画。
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

        /** 按下时缩到的比例 */
        const val PRESS_SCALE = 0.72f

        /** 光环填充透明度 */
        const val RING_FILL_ALPHA = 38
        /** 光环描边透明度 */
        const val RING_STROKE_ALPHA = 140

        /** 长按：外圈描边透明度（"按住不放"的标记） */
        const val HOLD_OUTER_ALPHA = 96
        /** 长按：外圈半径相对光环的倍数 */
        const val HOLD_OUTER_SCALE = 2.1f
        /** 长按：保持段的呼吸幅度（光环半径上下浮动比例） */
        const val HOLD_BREATH = 0.16f
        /** 长按：保持段呼吸次数；取整数让首尾都落在零相位，衔接不断 */
        const val HOLD_BREATHS = 2f

        /** 滑动：推进过程中光标压到的比例（拖动不是"死按"，比点击的按压略轻） */
        const val SWIPE_SCALE = 0.86f
        /** 滑动：推进过程中的暖色进度 */
        const val SWIPE_TINT = 0.85f
        /** 滑动：未走到那段轨迹的虚线实/空长度 */
        const val TRACE_DASH_DP = 7f
        /** 滑动：未走到那段轨迹的线宽 */
        const val TRACE_WIDTH_DP = 2f
        /** 滑动：已走过那段轨迹的线宽 */
        const val TRAVEL_WIDTH_DP = 4f
        /** 滑动：终点箭头长度 */
        const val ARROW_DP = 11f
        /** 滑动：未走到那段轨迹的透明度 */
        const val TRACE_ALPHA = 120
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
    /** 当前形态：决定画什么、时间线怎么切段 */
    private var mode = CursorMode.TAP

    /** 光标当前位置（TAP/LONG_PRESS 是落点；SWIPE 是沿线推进到的位置） */
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f

    /** 0 = 常态色（玄青），1 = 按下色（琥珀） */
    private var pressProgress = 0f

    /** 长按呼吸：光环半径的临时倍数 */
    private var ringPulse = 1f

    // ---- 滑动轨迹状态 ----
    /** 轨迹起点 / 终点 */
    private var ax = 0f
    private var ay = 0f
    private var bx = 0f
    private var by = 0f

    /** 轨迹可见度：0 不画，1 全显，松手后淡出到 0 */
    private var traceAlpha = 0f

    // ---- 本次时间线的段长（由 [animate] 按形态写入） ----
    /** 移动段：光标已在落点上时压到 0，不做无意义的空移 */
    private var moveMs = MOVE_MS
    /** 长按保持段 */
    private var holdMs = 0f
    /** 滑动推进段 */
    private var travelMs = 0f

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
    /** 长按外圈 */
    private val holdOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    /** 滑动轨迹与终点箭头共用；线宽与虚线随段落临时切换 */
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dashEffect = DashPathEffect(floatArrayOf(TRACE_DASH_DP * density, TRACE_DASH_DP * density), 0f)

    /** 直接定位（首次出现、或滑动落点时用，不做移动动画） */
    fun setPosition(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }

    /**
     * 播放一次动作时间线。
     *
     * @param x1,y1 落点（TAP/LONG_PRESS）或轨迹起点（SWIPE）
     * @param x2,y2 轨迹终点；非滑动形态忽略
     * @param holdMs 长按保持时长；由调用方按真实按压时长传入，呼吸动效才和手感一致
     * @param durationMs 滑动推进时长；与手势 durationMs 一致，轨迹才和手指同步
     * @param onArrived 移动段结束时回调（同步模式据此等到位再执行动作）
     */
    fun animate(
        mode: CursorMode,
        x1: Float,
        y1: Float,
        x2: Float = x1,
        y2: Float = y1,
        holdMs: Long = 0L,
        durationMs: Long = 0L,
        onArrived: (() -> Unit)? = null,
    ) {
        animator?.cancel()
        this.mode = mode
        ax = x1
        ay = y1
        bx = x2
        by = y2

        val fromX = cx
        val fromY = cy
        // 光标已经压在落点上时移动段没有意义（时间与视觉都白花）：直接压到 0
        moveMs = if (hypot(x1 - fromX, y1 - fromY) < 1f) 0f else MOVE_MS
        this.holdMs = holdMs.toFloat().coerceAtLeast(0f)
        travelMs = durationMs.toFloat().coerceAtLeast(0f)

        if (mode == CursorMode.SWIPE) {
            // 滑动不做"先飞过去"：真实手指也是骤然落在起点上的，
            // 先飞会让整条轨迹晚于手势，看起来像光标在追着手指跑
            cx = x1
            cy = y1
        }

        val total = when (mode) {
            CursorMode.TAP -> moveMs + PRESS_MS + RELEASE_MS
            CursorMode.LONG_PRESS -> moveMs + this.holdMs + RELEASE_MS
            CursorMode.SWIPE -> travelMs + RELEASE_MS
        }.coerceAtLeast(1f)

        traceAlpha = 0f
        ringPulse = 1f
        startTimeline(total, onArrived) { t ->
            when (mode) {
                CursorMode.TAP -> frameTap(t, fromX, fromY, x1, y1)
                CursorMode.LONG_PRESS -> frameLongPress(t, fromX, fromY, x1, y1)
                CursorMode.SWIPE -> frameSwipe(t)
            }
        }
    }

    /**
     * 起一条线性推进的时间线，各段自行按时间点套缓动。
     * 结束（含被取消）时统一复位，避免光标停在按下态、或轨迹留在屏幕上。
     */
    private fun startTimeline(totalMs: Float, onArrived: (() -> Unit)?, frame: (Float) -> Unit) {
        animator = ValueAnimator.ofFloat(0f, totalMs).apply {
            duration = totalMs.toLong()
            interpolator = null
            addUpdateListener { anim ->
                frame(anim.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scale = 1f
                    pressProgress = 0f
                    ringPulse = 1f
                    traceAlpha = 0f
                    invalidate()
                }
            })
            start()
        }
        // 移动段结束即回调，早于整条时间线结束
        onArrived?.let { arrived -> postDelayed({ arrived() }, moveMs.toLong()) }
    }

    /** TAP：移动 → 按下 → 松开 */
    private fun frameTap(t: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val moveEnd = moveMs
        val pressEnd = moveEnd + PRESS_MS
        when {
            t <= moveEnd -> {
                val p = if (moveMs > 0f) easeInOut.getInterpolation((t / moveMs).coerceIn(0f, 1f)) else 1f
                cx = fromX + (toX - fromX) * p
                cy = fromY + (toY - fromY) * p
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

    /** LONG_PRESS：移动 → 压住不放（呼吸光环）→ 松开 */
    private fun frameLongPress(t: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val moveEnd = moveMs
        val holdEnd = moveEnd + holdMs
        when {
            t <= moveEnd -> {
                val p = if (moveMs > 0f) easeInOut.getInterpolation((t / moveMs).coerceIn(0f, 1f)) else 1f
                cx = fromX + (toX - fromX) * p
                cy = fromY + (toY - fromY) * p
            }
            t <= holdEnd -> {
                cx = toX
                cy = toY
                val p = if (holdMs > 0f) ((t - moveEnd) / holdMs).coerceIn(0f, 1f) else 1f
                // 起手仍要"压下去"，否则看起来只是亮了一下，不像按住
                val pressed = (p * holdMs / PRESS_MS).coerceIn(0f, 1f)
                scale = 1f - (1f - PRESS_SCALE) * pressed
                pressProgress = pressed
                ringPulse = 1f + HOLD_BREATH * sin((2 * PI * HOLD_BREATHS * p).toFloat())
            }
            else -> {
                cx = toX
                cy = toY
                val p = easeOut.getInterpolation(((t - holdEnd) / RELEASE_MS).coerceIn(0f, 1f))
                scale = PRESS_SCALE + (1f - PRESS_SCALE) * p
                pressProgress = 1f - p
                ringPulse = 1f
            }
        }
    }

    /** SWIPE：沿轨迹推进 → 松手后轨迹淡出（终点即光标停留处） */
    private fun frameSwipe(t: Float) {
        when {
            t <= travelMs -> {
                val p = if (travelMs > 0f) (t / travelMs).coerceIn(0f, 1f) else 1f
                val travel = easeInOut.getInterpolation(p)
                cx = ax + (bx - ax) * travel
                cy = ay + (by - ay) * travel
                scale = SWIPE_SCALE
                pressProgress = SWIPE_TINT
                traceAlpha = 1f
            }
            else -> {
                val p = ((t - travelMs) / RELEASE_MS).coerceIn(0f, 1f)
                cx = bx
                cy = by
                scale = SWIPE_SCALE + (1f - SWIPE_SCALE) * p
                pressProgress = SWIPE_TINT * (1f - p)
                traceAlpha = 1f - p
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tint = blend(brandColor, warmColor, pressProgress)
        // 轨迹画在光标下层：先铺"手指走过的路"，再把光标压在上面
        if (mode == CursorMode.SWIPE) drawTrace(canvas, tint)

        val r = ringRadius * scale * ringPulse
        val core = coreRadius * scale

        // 长按才有的外圈：随按压进度出现，松开时收回，一眼能看出"这是按住不放"
        if (mode == CursorMode.LONG_PRESS) {
            holdOuterPaint.color = withAlpha(tint, (HOLD_OUTER_ALPHA * pressProgress).toInt())
            canvas.drawCircle(cx, cy, r * HOLD_OUTER_SCALE, holdOuterPaint)
        }

        ringFillPaint.color = withAlpha(tint, RING_FILL_ALPHA)
        canvas.drawCircle(cx, cy, r, ringFillPaint)

        ringStrokePaint.color = withAlpha(tint, RING_STROKE_ALPHA)
        canvas.drawCircle(cx, cy, r, ringStrokePaint)

        corePaint.color = tint
        canvas.drawCircle(cx, cy, core, corePaint)
    }

    /** 滑动轨迹：细虚线画"要去哪"，粗实线画"走了多远"，终点补一个箭头 */
    private fun drawTrace(canvas: Canvas, tint: Int) {
        val visible = traceAlpha.coerceIn(0f, 1f)
        if (visible <= 0.01f) return

        tracePaint.strokeWidth = TRACE_WIDTH_DP * density
        tracePaint.pathEffect = dashEffect
        tracePaint.color = withAlpha(tint, (TRACE_ALPHA * visible).toInt())
        canvas.drawLine(ax, ay, bx, by, tracePaint)

        tracePaint.pathEffect = null
        tracePaint.strokeWidth = TRAVEL_WIDTH_DP * density
        tracePaint.color = withAlpha(tint, (255 * visible).toInt())
        canvas.drawLine(ax, ay, cx, cy, tracePaint)

        drawArrowHead(canvas, tint, visible)
    }

    /** 终点箭头：不引依赖，按方向向量手算张口的两条斜边 */
    private fun drawArrowHead(canvas: Canvas, tint: Int, visible: Float) {
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val head = ARROW_DP * density
        val backX = bx - ux * head
        val backY = by - uy * head
        // 垂直于方向向量的张开口：(-uy, ux) 即箭头两条边的偏移
        val px = -uy * head * 0.45f
        val py = ux * head * 0.45f
        tracePaint.color = withAlpha(tint, (255 * visible).toInt())
        canvas.drawLine(bx, by, backX + px, backY + py, tracePaint)
        canvas.drawLine(bx, by, backX - px, backY - py, tracePaint)
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