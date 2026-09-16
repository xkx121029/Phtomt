package com.phoneagent.feature.edge.EdgeLightingView

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 跑马光效视图：沿屏幕四边绘制流动的彩色光效。
 *
 * 颜色循环（对称反射）: 黄-绿-蓝-紫-粉-紫-蓝-绿...
 * 每条边使用独立的 LinearGradient 实现平滑渐变，
 * 圆角弧段使用相邻边颜色插值，BlurMaskFilter 实现自然光晕。
 *
 * @param context 上下文
 * @param attrs 属性
 */
class EdgeLightingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // 颜色循环：黄-绿-蓝-紫-粉-紫-蓝-绿...
    private val colorCycle = intArrayOf(
        Color.parseColor("#FFD600"), // 黄
        Color.parseColor("#00E676"), // 绿
        Color.parseColor("#2979FF"), // 蓝
        Color.parseColor("#AA00FF"), // 紫
        Color.parseColor("#FF4081"), // 粉
        Color.parseColor("#AA00FF"), // 紫
        Color.parseColor("#2979FF"), // 蓝
        Color.parseColor("#00E676"), // 绿
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var progress = 0f
    private var speed = 0.3f
    private var lastTime = 0L
    private var isRunning = false
    private var previewMode = false

    // 标定参数
    private var insetTop = 0f
    private var insetBottom = 0f
    private var insetLeft = 0f
    private var insetRight = 0f
    private var cornerRadius = 0f
    // 光带粗细缩放（由设置"光带粗细"换算，基准 glow 20dp）
    private var strokeScale = 1f

    // 光晕配置
    private val coreStrokeWidth = 5f
    private val glowStrokeWidth = 20f
    private val blurRadius = 14f

    fun setCalibration(
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        radius: Int,
        width: Int = 20,
    ) {
        val density = resources.displayMetrics.density
        insetTop = top * density
        insetBottom = bottom * density
        insetLeft = left * density
        insetRight = right * density
        cornerRadius = radius * density
        strokeScale = (width / 20f).coerceIn(0.3f, 3f)
        invalidate()
    }

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.05f, 2f)
    }

    fun setPreviewMode(enabled: Boolean) {
        previewMode = enabled
        invalidate()
    }

    fun start() {
        isRunning = true
        lastTime = System.nanoTime()
        postInvalidateOnAnimation()
    }

    fun stop() {
        isRunning = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val dt = (now - lastTime) / 1_000_000_000f
        lastTime = now

        if (isRunning) {
            progress = (progress + speed * dt) % 1f
            postInvalidateOnAnimation()
        }

        val w = width.toFloat()
        val h = height.toFloat()

        // 绘制区域：光带尺寸随粗细缩放；顶部不内缩（光带中心线紧贴屏幕物理顶，确保到顶）
        val glow = glowStrokeWidth * strokeScale
        val halfGlow = glow / 2f
        val rect = RectF(
            insetLeft + halfGlow,
            insetTop,
            w - insetRight - halfGlow,
            h - insetBottom - halfGlow
        )

        val radius = if (cornerRadius > 0) {
            // 预留 1px 余量，避免圆角恰好等于半宽导致分段长度为 0 时 0/0 产生 NaN 坐标
            min(cornerRadius, min(rect.width(), rect.height()) / 2 - 1f).coerceAtLeast(0f)
        } else {
            0f
        }

        if (previewMode) {
            drawPreviewMode(canvas, rect, radius)
        } else {
            drawRunningMode(canvas, rect, radius)
        }
    }

    /**
     * 预览模式：绘制完整的彩色渐变边框
     * 按边+角分段绘制，每段使用独立的 LinearGradient
     */
    private fun drawPreviewMode(canvas: Canvas, rect: RectF, radius: Float) {
        val w = rect.width()
        val h = rect.height()
        val segTop = w - 2 * radius
        val segRight = h - 2 * radius
        val segBottom = w - 2 * radius
        val segLeft = h - 2 * radius
        val arcLength = (Math.PI * radius / 2).toFloat()

        // 计算总路径长度和每段的起始距离
        val totalPath = segTop + arcLength + segRight + arcLength + segBottom + arcLength + segLeft + arcLength

        // 辅助函数：根据边框上的距离获取颜色
        val colorAtDist: (Float) -> Int = { dist ->
            val normalized = (dist / totalPath + progress) % 1f
            val p = if (normalized < 0) normalized + 1f else normalized
            val index = (p * colorCycle.size).toInt() % colorCycle.size
            val nextIndex = (index + 1) % colorCycle.size
            val t = (p * colorCycle.size) % 1f
            lerpColor(colorCycle[index], colorCycle[nextIndex], t)
        }

        // 1. 绘制外层光晕（模糊）
        paint.strokeWidth = glowStrokeWidth * strokeScale
        paint.alpha = 160
        paint.maskFilter = BlurMaskFilter(blurRadius * strokeScale, BlurMaskFilter.Blur.NORMAL)
        drawSegmentedBorder(canvas, rect, radius, colorAtDist, totalPath)
        paint.maskFilter = null

        // 2. 绘制核心亮线
        paint.strokeWidth = coreStrokeWidth * strokeScale
        paint.alpha = 255
        drawSegmentedBorder(canvas, rect, radius, colorAtDist, totalPath)
    }

    /**
     * 按边+角分段绘制边框，每段使用独立的着色器
     */
    private fun drawSegmentedBorder(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        colorAtDist: (Float) -> Int,
        totalPath: Float,
    ) {
        val w = rect.width()
        val h = rect.height()
        val segTop = w - 2 * radius
        val segRight = h - 2 * radius
        val segBottom = w - 2 * radius
        val segLeft = h - 2 * radius
        val arcLength = (Math.PI * radius / 2).toFloat()

        var dist = 0f

        // 上边（左到右）
        val topStart = colorAtDist(dist)
        val topEnd = colorAtDist(dist + segTop)
        paint.shader = LinearGradient(
            rect.left + radius, rect.top,
            rect.right - radius, rect.top,
            topStart, topEnd,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(rect.left + radius, rect.top, rect.right - radius, rect.top, paint)
        dist += segTop

        // 右上角
        drawArcSegment(canvas,
            rect.right - radius, rect.top + radius,
            rect.right, rect.top + radius,
            rect.right - radius, rect.top,
            colorAtDist(dist), colorAtDist(dist + arcLength)
        )
        dist += arcLength

        // 右边（上到下）
        val rightStart = colorAtDist(dist)
        val rightEnd = colorAtDist(dist + segRight)
        paint.shader = LinearGradient(
            rect.right, rect.top + radius,
            rect.right, rect.bottom - radius,
            rightStart, rightEnd,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(rect.right, rect.top + radius, rect.right, rect.bottom - radius, paint)
        dist += segRight

        // 右下角
        drawArcSegment(canvas,
            rect.right - radius, rect.bottom - radius,
            rect.right, rect.bottom - radius,
            rect.right - radius, rect.bottom,
            colorAtDist(dist), colorAtDist(dist + arcLength)
        )
        dist += arcLength

        // 下边（右到左）
        val bottomStart = colorAtDist(dist)
        val bottomEnd = colorAtDist(dist + segBottom)
        paint.shader = LinearGradient(
            rect.right - radius, rect.bottom,
            rect.left + radius, rect.bottom,
            bottomStart, bottomEnd,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(rect.right - radius, rect.bottom, rect.left + radius, rect.bottom, paint)
        dist += segBottom

        // 左下角
        drawArcSegment(canvas,
            rect.left + radius, rect.bottom - radius,
            rect.left + radius, rect.bottom,
            rect.left, rect.bottom - radius,
            colorAtDist(dist), colorAtDist(dist + arcLength)
        )
        dist += arcLength

        // 左边（下到上）
        val leftStart = colorAtDist(dist)
        val leftEnd = colorAtDist(dist + segLeft)
        paint.shader = LinearGradient(
            rect.left, rect.bottom - radius,
            rect.left, rect.top + radius,
            leftStart, leftEnd,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(rect.left, rect.bottom - radius, rect.left, rect.top + radius, paint)
        dist += segLeft

        // 左上角
        drawArcSegment(canvas,
            rect.left + radius, rect.top + radius,
            rect.left, rect.top + radius,
            rect.left + radius, rect.top,
            colorAtDist(dist), colorAtDist(dist + arcLength)
        )

        paint.shader = null
    }

    /**
     * 绘制圆角弧段（使用路径绘制以获得平滑曲线）
     */
    private fun drawArcSegment(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        startColor: Int,
        endColor: Int,
    ) {
        // 计算弧的起始角和终止角
        val startAngle = kotlin.math.atan2(
            (startY - centerY).toDouble(),
            (startX - centerX).toDouble()
        )
        val endAngle = kotlin.math.atan2(
            (endY - centerY).toDouble(),
            (endX - centerX).toDouble()
        )

        // 计算角度差（取最小路径）
        var sweep = endAngle - startAngle
        if (sweep > Math.PI) sweep -= 2 * Math.PI
        if (sweep < -Math.PI) sweep += 2 * Math.PI

        val arcRadius = sqrt(
            (startX - centerX).toDouble() * (startX - centerX).toDouble() +
            (startY - centerY).toDouble() * (startY - centerY).toDouble()
        ).toFloat()

        // 绘制弧线路径
        val path = Path()
        val steps = 12
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val angle = startAngle + sweep * t
            val cosA = kotlin.math.cos(angle).toFloat()
            val sinA = kotlin.math.sin(angle).toFloat()
            val x = centerX + arcRadius * cosA
            val y = centerY + arcRadius * sinA
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // 使用线性渐变着色器
        paint.shader = LinearGradient(
            startX, startY, endX, endY,
            startColor, endColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paint)
    }

    /**
     * 正常运行模式：绘制流动的光点沿边框移动
     */
    private fun drawRunningMode(canvas: Canvas, rect: RectF, radius: Float) {
        val w = rect.width()
        val h = rect.height()
        val segTop = w - 2 * radius
        val segRight = h - 2 * radius
        val segBottom = w - 2 * radius
        val segLeft = h - 2 * radius
        val arcLength = (Math.PI * radius / 2).toFloat()
        val totalPath = segTop + arcLength + segRight + arcLength + segBottom + arcLength + segLeft + arcLength

        // 背景光晕（低透明度整圈）
        val colorAtDist: (Float) -> Int = { dist ->
            val normalized = (dist / totalPath + progress) % 1f
            val p = if (normalized < 0) normalized + 1f else normalized
            val index = (p * colorCycle.size).toInt() % colorCycle.size
            val nextIndex = (index + 1) % colorCycle.size
            val t = (p * colorCycle.size) % 1f
            lerpColor(colorCycle[index], colorCycle[nextIndex], t)
        }

        paint.strokeWidth = glowStrokeWidth * strokeScale * 0.7f
        paint.alpha = 20
        paint.maskFilter = BlurMaskFilter(10f * strokeScale, BlurMaskFilter.Blur.NORMAL)
        drawSegmentedBorder(canvas, rect, radius, colorAtDist, totalPath)
        paint.maskFilter = null

        paint.strokeWidth = coreStrokeWidth * strokeScale
        paint.alpha = 50
        drawSegmentedBorder(canvas, rect, radius, colorAtDist, totalPath)

        // 流动光点
        val cometCount = 5
        for (i in 0 until cometCount) {
            val offset = (progress + i.toFloat() / cometCount) % 1f
            val distance = offset * totalPath
            val pos = getPointOnBorder(rect, radius, distance)
            val colorIndex = (offset * colorCycle.size).toInt() % colorCycle.size
            val mainColor = colorCycle[colorIndex]

            paint.style = Paint.Style.FILL
            paint.shader = null

            paint.color = mainColor
            paint.maskFilter = BlurMaskFilter(14f * strokeScale, BlurMaskFilter.Blur.NORMAL)
            paint.alpha = 40
            canvas.drawCircle(pos.x, pos.y, 18f * strokeScale, paint)

            paint.alpha = 100
            canvas.drawCircle(pos.x, pos.y, 10f * strokeScale, paint)

            paint.maskFilter = null
            paint.alpha = 255
            canvas.drawCircle(pos.x, pos.y, 5f * strokeScale, paint)

            paint.style = Paint.Style.STROKE
        }

        paint.shader = null
    }

    /**
     * 计算边框上指定距离的点坐标
     */
    private fun getPointOnBorder(rect: RectF, radius: Float, distance: Float): PointF {
        val w = rect.width()
        val h = rect.height()
        val segTop = w - 2 * radius
        val segRight = h - 2 * radius
        val segBottom = w - 2 * radius
        val segLeft = h - 2 * radius
        val arcLength = (Math.PI * radius / 2).toFloat()

        var d = distance

        // 上边
        if (d <= segTop) {
            val t = d / segTop
            return PointF(rect.left + radius + t * segTop, rect.top)
        }
        d -= segTop

        // 右上角
        if (d <= arcLength) {
            val angle = -Math.PI / 2 + (d / arcLength) * (Math.PI / 2)
            return PointF(
                rect.right - radius + radius * kotlin.math.cos(angle.toFloat()),
                rect.top + radius + radius * kotlin.math.sin(angle.toFloat())
            )
        }
        d -= arcLength

        // 右边
        if (d <= segRight) {
            val t = d / segRight
            return PointF(rect.right, rect.top + radius + t * segRight)
        }
        d -= segRight

        // 右下角
        if (d <= arcLength) {
            val angle = (d / arcLength) * (Math.PI / 2)
            return PointF(
                rect.right - radius + radius * kotlin.math.cos(angle.toFloat()),
                rect.bottom - radius + radius * kotlin.math.sin(angle.toFloat())
            )
        }
        d -= arcLength

        // 下边
        if (d <= segBottom) {
            val t = d / segBottom
            return PointF(rect.right - radius - t * segBottom, rect.bottom)
        }
        d -= segBottom

        // 左下角
        if (d <= arcLength) {
            val angle = Math.PI / 2 + (d / arcLength) * (Math.PI / 2)
            return PointF(
                rect.left + radius + radius * kotlin.math.cos(angle.toFloat()),
                rect.bottom - radius + radius * kotlin.math.sin(angle.toFloat())
            )
        }
        d -= arcLength

        // 左边
        if (d <= segLeft) {
            val t = d / segLeft
            return PointF(rect.left, rect.bottom - radius - t * segLeft)
        }
        d -= segLeft

        // 左上角
        val angle = Math.PI + (d / arcLength) * (Math.PI / 2)
        return PointF(
            rect.left + radius + radius * kotlin.math.cos(angle.toFloat()),
            rect.top + radius + radius * kotlin.math.sin(angle.toFloat())
        )
    }

    /**
     * 颜色线性插值
     */
    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a); val aa = Color.alpha(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b); val ba = Color.alpha(b)

        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        val al = (aa + (ba - aa) * t).toInt().coerceIn(0, 255)

        return Color.argb(al, r, g, bl)
    }

    private data class PointF(val x: Float, val y: Float)
}