// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

/**
 * 限高滚动容器：内容短时贴合内容高度，超长时限到上限并内部滚动。
 *
 * 为什么不用固定高度：短文案下会在卡片里留一片空白，面板看着像"没做完"。
 * 为什么不用「高度 0 + weight 1」：选项卡窗口是 WRAP_CONTENT，LinearLayout 在 AT_MOST 下
 * 会把整屏剩余空间分给权重子视图，窗口被撑到整屏高 —— 一层透明却可触摸的窗口会盖住整块屏幕。
 */
internal class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}

/**
 * 正文底部的渐变遮罩：从透明过渡到卡片底色，提示"下面还有内容"。
 *
 * 它只盖在滚动区最后 28dp 上，不改卡片本身的实色——遮罩之外仍是完整不透明的面板底，
 * 不会出现"半透明长条"。
 */
internal class DoneFadeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fadeColor = Color.TRANSPARENT

    fun setFadeColor(color: Int) {
        if (fadeColor == color) return
        fadeColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0 || width <= 0) return
        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            fadeColor and 0x00FFFFFF,
            fadeColor,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}

/** 普通控件的按压反馈（不涉及完成卡片计时）：同样在 ACTION_DOWN 当帧生效 */
internal fun FloatingWindowService.bindPress(vararg views: View) {
    views.forEach { v ->
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> OverlayMotion.pressDown(v)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> OverlayMotion.pressUp(v)
            }
            false
        }
    }
}
