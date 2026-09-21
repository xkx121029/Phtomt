package com.phoneagent.overlay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * 液态玻璃背景（悬浮窗专用，原生 Drawable 实现）。
 *
 * 视觉原则：**只保留必要层次**——半透明白底 + 一道顶部折射高光 + 一条左上斜向柔光，
 * 再由内侧发丝描边与细边框收边。悬浮窗是长时间贴在屏幕上的元素，
 * 叠加层越多越热闹，越像贴纸而不像系统组件，也越容易干扰背后的内容。
 *
 * 早先版本叠了菲涅尔四边反射、动态光斑、底部阴影、棱镜虹彩色散共 8 层，
 * 带来两个问题：一是视觉过载（虹彩让白玻璃显脏），二是每帧要新建十来个 Shader。
 * 现在 Shader 只在尺寸变化时重建并缓存，绘制期间零分配 —— 悬浮窗在任务执行期间
 * 每帧都在重绘，这一条对耗电与掉帧是实打实的。
 */
class LiquidGlassDrawable(
    private val cornerRadius: Float,
    private val baseColor: Int,
    private val strokeColor: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val roundRect = RectF()

    // 缓存的光学层：仅在尺寸变化时重建，绘制期间不再新建对象
    private var topHighlight: LinearGradient? = null
    private var topHighlightH = 0f
    private var sheen: LinearGradient? = null
    private var sheenSize = 0f
    private var cachedW = -1f
    private var cachedH = -1f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild(bounds.width().toFloat(), bounds.height().toFloat())
    }

    /** 按当前尺寸重建光学层；尺寸未变时直接返回（悬浮窗高度随内容变化，这里会被反复调用） */
    private fun rebuild(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        if (w == cachedW && h == cachedH) return
        cachedW = w
        cachedH = h
        // 顶部折射高光：一条自上而下衰减的窄带
        topHighlightH = (h * 0.14f).coerceAtLeast(1f)
        topHighlight = LinearGradient(
            0f, 0f, 0f, topHighlightH,
            0x33FFFFFF.toInt(), 0x00FFFFFF.toInt(),
            Shader.TileMode.CLAMP,
        )
        // 左上斜向柔光：把玻璃的"液面"感做出来，一道就够
        sheenSize = min(w, h) * 0.6f
        sheen = LinearGradient(
            0f, 0f, sheenSize, sheenSize,
            0x1FFFFFFF.toInt(), 0x00FFFFFF.toInt(),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        // 首帧兜底：bounds 可能在 onBoundsChange 之前就被读取
        rebuild(w, h)

        path.reset()
        path.addRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)

        // 1. 半透明白底
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = baseColor
        canvas.drawRect(0f, 0f, w, h, paint)

        // 2. 顶部折射高光
        topHighlight?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, w, topHighlightH, paint)
        }

        // 3. 左上斜向柔光
        sheen?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, sheenSize, sheenSize, paint)
        }

        // 4. 内侧发丝描边：玻璃厚度
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x59FFFFFF.toInt()
        roundRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        canvas.drawRoundRect(roundRect, cornerRadius, cornerRadius, paint)

        canvas.restore()

        // 5. 细边框：强化边界，让玻璃在浅色背景上也有轮廓
        paint.color = strokeColor
        roundRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(roundRect, cornerRadius, cornerRadius, paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}