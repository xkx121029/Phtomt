package com.phoneagent.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * 顶部状态色带跑马灯：彩色渐变底 + 单行状态文字向左匀速滚动。
 *
 * 几个刻意为之的地方（都是踩过的坑）：
 *
 * 1. **文字用相位色纯色，不再叠渐变 shader**。
 *    `Paint` 里 shader 的优先级高于 color，早先给文字设了渐变 shader 之后，
 *    `setText(text, marqueeColor(phase))` 传进来的相位色被无声覆盖 —— 相位色从未生效过。
 *    底色渐变已经承担了彩色视觉，文字保持纯色反而更易读。
 * 2. **更新文字不重置滚动相位**。AI 状态是高频更新的（每秒数次），
 *    每次重置都会把文字钉回起点，看上去像卡住不动。
 * 3. **回位判据必须是"完全滚出"**。文字画在 x = offset，完全离开左侧要满足
 *    `offset < -(textWidth)`；原判据 `offset < -w` 在文字比屏幕窄时会提前回位，
 *    于是文字还在屏内就跳回入场位 —— 肉眼可见的抽动。
 * 4. **短文本居中静止**。跑马灯的意义是显示放不下的长文本；
 *    短文本硬滚只会让人等它绕一圈，还白烧每帧重绘。
 */
class MarqueeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        color = Color.WHITE
    }

    // 背景色带：用户颜色序列柔化渐变，让跑马灯成为填满顶部的实色状态色带
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgGradient: LinearGradient? = null

    /** 跑马灯色带颜色（按顺序组成渐变，可在设置中自定义，默认蓝→紫→粉） */
    private var gradientColors = listOf(
        Color.rgb(0x4f, 0xa3, 0xff),
        Color.rgb(0x9b, 0x5c, 0xff),
        Color.rgb(0xff, 0x6b, 0x9d),
    )

    private var text = ""
    private var textWidth = 0f

    /** 滚动相位：主段文字左边缘的 x 坐标（0 = 刚入场，负值 = 已在滚动中） */
    private var offset = 0f
    private var scrolling = false
    private var lastTime = 0L

    /** 两段文字之间的间隔，小间隔形成字幕式连续流动，避免屏幕出现空档 */
    private var gap = 120f
    private val scrollSpeed = 60f // px/s

    /**
     * 是否处于"可动画"状态（已附着且可见）。
     *
     * 悬浮窗在截图时会被整体置为 GONE，任务结束后也会隐藏；GONE 的视图每帧
     * 继续请求重绘毫无意义 —— 还会持续把 Choreographer 帧回调与遍历拉起来。
     * 不可见时停帧、恢复可见时从当前相位继续。
     */
    private var canAnimate = false

    /**
     * 内容安全区顶部偏移（状态栏高度，px）。
     * 色带背景要从屏幕物理顶边铺下来（含状态栏区域），但**文字必须画在状态栏下方**，
     * 否则会被状态栏图标压住。两者靠这个偏移区分开。
     */
    private var contentTopInset = 0

    /** 设置内容安全区顶部偏移；由悬浮窗在创建/尺寸变化时传入 */
    fun setTopInset(px: Int) {
        if (contentTopInset == px) return
        contentTopInset = px
        invalidate()
    }

    /** 单帧最大推进时长：视图不可见一段时间后恢复，不该让文字瞬移一大段 */
    private val maxFrameSeconds = 0.1f

    /** 更新文字与颜色主题（内容未变时直接跳过，避免无谓重绘） */
    fun setText(text: String, color: Int = Color.WHITE) {
        if (this.text == text && paint.color == color) return
        this.text = text
        paint.color = color
        textWidth = paint.measureText(text)
        // 刻意不重置 offset：见类注释第 2 条
        ensureRunning()
        invalidate()
    }

    /** 设置跑马灯色带颜色（至少 2 色，按顺序组成循环渐变） */
    fun setColors(colors: List<Int>) {
        val list = if (colors.size >= 2) colors
            else listOf(
                colors.firstOrNull() ?: Color.rgb(0x4f, 0xa3, 0xff),
                colors.lastOrNull() ?: Color.rgb(0x9b, 0x5c, 0xff),
            )
        if (gradientColors == list) return
        gradientColors = list
        buildBgGradient()
        invalidate()
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

    /** 只有长文本才需要滚动；短文本停在原地，也就不必每帧重绘 */
    private fun ensureRunning() {
        if (textWidth <= width) {
            scrolling = false
            return
        }
        if (!scrolling) {
            scrolling = true
            resumeFrames()
        }
    }

    /** 续上帧循环：重置计时基准，避免停帧期间积累的时间差让文字瞬移 */
    private fun resumeFrames() {
        if (!canAnimate || !scrolling) return
        lastTime = System.nanoTime()
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        canAnimate = true
        // 走 ensureRunning 而不是 resumeFrames：停帧时我们已把 scrolling 置回 false，
        // 直接 resume 会因为判空而什么都不做，文字就此停住不再滚动
        ensureRunning()
    }

    override fun onDetachedFromWindow() {
        canAnimate = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 悬浮窗截图隐藏 / 任务结束隐藏都会走到这里：不可见即停帧，恢复可见再续上
        canAnimate = visibility == View.VISIBLE && isAttachedToWindow
        if (canAnimate) ensureRunning() else scrolling = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildBgGradient()
        ensureRunning()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 顶部状态色带：色带顶部贴合屏幕顶
        bgGradient?.let {
            bgPaint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }
        if (text.isEmpty()) return

        val w = width.toFloat()

        // 短文本：居中静止，省掉每帧重绘
        if (textWidth <= w) {
            scrolling = false
            canvas.drawText(text, (w - textWidth) / 2f, centerY(), paint)
            return
        }

        val now = System.nanoTime()
        val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0f, maxFrameSeconds)
        lastTime = now
        offset -= scrollSpeed * dt
        // 完全滚出左侧才回位：见类注释第 3 条
        if (offset < -(textWidth + gap)) offset = 0f

        // 两段循环：主段滚出时，次段正好接上，形成无缝字幕
        canvas.drawText(text, offset, centerY(), paint)
        canvas.drawText(text, offset + textWidth + gap, centerY(), paint)
        // 不可见时不再续帧（见 canAnimate 说明）
        if (canAnimate) postInvalidateOnAnimation() else scrolling = false
    }

    private fun centerY(): Float {
        val fm = paint.fontMetrics
        // 只在"状态栏以下"的可用高度里居中：色带含状态栏区域，文字不能一起居中去被压住
        val top = contentTopInset.toFloat().coerceAtMost(height - 1f)
        return top + (height - top - fm.ascent - fm.descent) / 2f
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}