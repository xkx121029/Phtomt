package com.phoneagent.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 彩色跑马灯：单行文字向左匀速滚动，带彩色渐变，支持运行时更新文字。
 * 对应文档“第 8 层 UI / 跑马灯”。
 */
class MarqueeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        color = Color.WHITE
    }
    private var text = ""
    private var textWidth = 0f
    private var offset = 0f
    private var gradient: LinearGradient? = null
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null

    // 跑马灯渐变颜色（按顺序组成渐变，可在设置中自定义，默认蓝→紫→粉）
    private var gradientColors = listOf(
        Color.rgb(0x4f, 0xa3, 0xff),
        Color.rgb(0x9b, 0x5c, 0xff),
        Color.rgb(0xff, 0x6b, 0x9d),
    )

    // 背景色带：用户颜色序列柔化渐变，让跑马灯成为填满顶部的实色状态色带
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgGradient: LinearGradient? = null

    private val scrollSpeed = 60f // px/s

    private var lastTime = 0L
    private var running = false

    /** 更新文字与颜色主题 */
    fun setText(text: String, color: Int = Color.WHITE) {
        this.text = text
        paint.color = color
        textWidth = paint.measureText(text)
        // 彩色渐变：由主色派生（主色→偏紫→偏暖→主色循环），主色可在设置中自定义
        buildGradient()
        offset = 0f
        ensureRunning()
        invalidate()
    }

    /** 设置跑马灯渐变颜色（至少 2 色，按顺序组成循环渐变） */
    fun setColors(colors: List<Int>) {
        val list = if (colors.size >= 2) colors
            else listOf(
                colors.firstOrNull() ?: Color.rgb(0x4f, 0xa3, 0xff),
                colors.lastOrNull() ?: Color.rgb(0x9b, 0x5c, 0xff),
            )
        if (gradientColors == list) return
        gradientColors = list
        buildGradient()
        buildBgGradient()
        invalidate()
    }

    /** 由渐变颜色生成循环渐变：颜色序列 + 首色首尾相接，REPEAT 无缝循环 */
    private fun buildGradient() {
        val arr = (gradientColors + gradientColors.first()).toIntArray()
        gradient = LinearGradient(
            0f, 0f, textWidth, 0f,
            arr, null, Shader.TileMode.REPEAT,
        )
    }

    /** 背景色带：用户颜色序列柔化（向白色混合）作底色，随窗口尺寸重建 */
    private fun buildBgGradient() {
        if (width <= 0 || gradientColors.isEmpty()) return
        val soft = gradientColors.map { c ->
            Color.argb(
                0xB0,
                (Color.red(c) + 3 * 255) / 4,
                (Color.green(c) + 3 * 255) / 4,
                (Color.blue(c) + 3 * 255) / 4,
            )
        }
        val arr = (soft + soft.first()).toIntArray()
        bgGradient = LinearGradient(0f, 0f, width.toFloat(), 0f, arr, null, Shader.TileMode.REPEAT)
    }

    private fun ensureRunning() {
        if (!running) {
            running = true
            lastTime = System.nanoTime()
            postInvalidateOnAnimation()
        } else {
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildBgGradient()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 顶部状态色带：用户颜色序列柔化渐变，填满跑马灯区域，色带顶部贴合屏幕顶
        bgGradient?.let {
            bgPaint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }
        if (text.isEmpty()) return
        val now = System.nanoTime()
        val dt = (now - lastTime) / 1_000_000_000f
        lastTime = now
        offset -= scrollSpeed * dt
        val w = width.toFloat()
        // 文本比可视区窄时匀速滚动一整段后循环
        if (textWidth <= w) {
            if (offset < -w) offset = 0f
            paint.alpha = 255
            paint.shader = gradient
            canvas.drawText(text, offset + w, centerY(), paint)
        } else {
            // 文本宽于可视区：两段循环滚动实现无缝隙
            if (offset < -textWidth) offset = 0f
            paint.alpha = 255
            paint.shader = gradient
            canvas.drawText(text, offset, centerY(), paint)
            canvas.drawText(text, offset + textWidth + gap, centerY(), paint)
        }
        postInvalidateOnAnimation()
    }

    private var gap = 120f

    private fun centerY(): Float {
        val fm = paint.fontMetrics
        // ascent 为负值，正确居中基线 = (height - ascent - descent) / 2
        return (height - fm.ascent - fm.descent) / 2f
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}