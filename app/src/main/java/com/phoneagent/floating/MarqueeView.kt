package com.phoneagent.floating

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

    private val scrollSpeed = 60f // px/s

    private var lastTime = 0L
    private var running = false

    /** 更新文字与颜色主题 */
    fun setText(text: String, color: Int = Color.WHITE) {
        this.text = text
        paint.color = color
        textWidth = paint.measureText(text)
        // 彩色渐变：首色→中间→末色循环
        gradient = LinearGradient(
            0f, 0f, textWidth, 0f,
            intArrayOf(Color.rgb(0x4f, 0xa3, 0xff), Color.rgb(0x9b, 0x5c, 0xff), Color.rgb(0xff, 0x6b, 0x9d), Color.rgb(0x4f, 0xa3, 0xff)),
            null, Shader.TileMode.REPEAT,
        )
        offset = 0f
        ensureRunning()
        invalidate()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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