package com.phoneagent.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.pow

/**
 * 打勾动效视图：任务完成时，从左上到右下动态绘制一个彩色对勾。
 *
 * 视觉风格与项目其他自定义 View 保持一致：
 * - 对勾使用蓝→绿渐变（LinearGradient）
 * - 圆头笔帽/圆角连接，按 PathMeasure 进度分段绘制
 * - 线条粗细随视图尺寸自适应，路径预留内边距避免显示不完整
 */
class SuccessMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 内描边：浅色细线勾勒内部，提升清晰度
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val checkPath = Path()
    private val pathMeasure = PathMeasure()

    private var pathLength = 0f
    private var progress = 0f
    private var lastTime = 0L
    private var running = false

    private val startColor = Color.parseColor("#00C853")
    private val endColor = Color.parseColor("#2979FF")

    /** 启动打勾动画 */
    fun start() {
        progress = 0f
        running = true
        lastTime = System.nanoTime()
        postInvalidateOnAnimation()
    }

    /** 重置打勾动画（新任务开始时调用，清除完成态残留） */
    fun reset() {
        progress = 0f
        running = false
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = min(w, h)
        val stroke = s * 0.08f
        paint.strokeWidth = stroke
        innerPaint.strokeWidth = stroke * 0.35f

        // 对勾路径（相对 view 中心）：左下角起笔 → 中段 → 右上角收笔
        // 使用 len 控制对勾整体跨度，并预留 20% 内边距确保完整显示
        val cx = w / 2f
        val cy = h / 2f
        val len = s * 0.32f
        checkPath.reset()
        checkPath.moveTo(cx - len, cy + len * 0.1f)
        checkPath.lineTo(cx - len * 0.25f, cy + len * 0.6f)
        checkPath.lineTo(cx + len, cy - len * 0.6f)
        pathMeasure.setPath(checkPath, false)
        pathLength = pathMeasure.length
        progress = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pathLength <= 0f) return

        if (running) {
            val now = System.nanoTime()
            val dt = (now - lastTime) / 1_000_000_000f
            lastTime = now
            progress += dt * 1.1f
            if (progress >= 1f) {
                progress = 1f
                running = false
            }
        }

        val grad = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            startColor, endColor, Shader.TileMode.CLAMP,
        )
        paint.shader = grad
        innerPaint.color = Color.WHITE

        // 对进度施加 ease-out 三次缓动：起笔快、收笔缓，
        // 让打勾的“书写感”更自然（对应“进入用 ease-out”的设计原则）
        val eased = 1f - (1f - progress).pow(3f)
        val segment = Path()
        pathMeasure.getSegment(0f, pathLength * min(eased, 1f), segment, true)
        // 先画白色内描边，再画渐变外描边，形成清晰的双层效果
        canvas.drawPath(segment, innerPaint)
        canvas.drawPath(segment, paint)

        if (running) postInvalidateOnAnimation()
    }
}
