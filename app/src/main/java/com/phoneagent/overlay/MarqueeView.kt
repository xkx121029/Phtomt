package com.phoneagent.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 底部跑马灯面板：圆角胶囊 + 单行状态文字向左匀速滚动。
 *
 * 它是**独立窗口的根视图**（不挂在任务卡片里）：浮在屏幕底边之上、任务期间常驻，显示 AI 当前动作简述。
 * 长宽随内容自适应——宽 = 文字宽 + 左右内边距（超过屏幕可用宽就封顶并开始滚动），
 * 高 = 文字高 + 上下内边距（内边距由设置里的「跑马灯厚度」滑块控制）。
 * 底色是不透明实色——浮窗压在别的 App 上，半透明白会让文字随时失去对比度。
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
 * 5. **滚动判据是"文字宽 vs 内容区宽"**。面板宽度跟着文字走，用 view 宽度判断
 *    会让所有文本都算"放得下"，跑马灯就永远不滚了。
 */
class MarqueeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        color = Color.WHITE
    }

    // 背景：用户颜色序列柔化渐变，让面板成为一块实色底
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgGradient: LinearGradient? = null
    private val panel = RectF()

    /** 跑马灯色带颜色（按顺序组成渐变，可在设置中自定义，默认蓝→紫→粉） */
    private var gradientColors = listOf(
        Color.rgb(0x4f, 0xa3, 0xff),
        Color.rgb(0x9b, 0x5c, 0xff),
        Color.rgb(0xff, 0x6b, 0x9d),
    )

    private var text = ""
    private var textWidth = 0f

    /** 横向内边距：固定值，保证文字与胶囊边缘之间始终留白 */
    private val padH = dp(16f)

    /** 纵向内边距（像素）：由设置项控制，决定面板厚度 */
    private var padVPx = dp(8f).toInt()

    /** 面板宽度上限：屏幕宽减两侧留白，长文本封顶后靠滚动展示 */
    private val maxPanelWidth: Float
        get() = (resources.displayMetrics.widthPixels - 2 * dp(PANEL_MARGIN))
            .coerceAtLeast(dp(120f))

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

    /** 单帧最大推进时长：视图不可见一段时间后恢复，不该让文字瞬移一大段 */
    private val maxFrameSeconds = 0.1f

    /** 更新文字与颜色主题（内容未变时直接跳过，避免无谓重绘） */
    fun setText(text: String, color: Int = Color.WHITE) {
        if (this.text == text && paint.color == color) return
        this.text = text
        paint.color = color
        textWidth = paint.measureText(text)
        // 宽度随文字自适应：文字换了要重新测量，窗口才会跟着变宽变窄
        requestLayout()
        // 刻意不重置 offset：见类注释第 2 条
        ensureRunning()
        invalidate()
    }

    /** 设置纵向内边距（像素）：决定面板厚度，设置项变化时调用 */
    fun setPadV(px: Int) {
        if (padVPx == px) return
        padVPx = px
        requestLayout()
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
                0xFF,
                (Color.red(c) + 3 * 255) / 4,
                (Color.green(c) + 3 * 255) / 4,
                (Color.blue(c) + 3 * 255) / 4,
            )
        }
        val arr = (soft + soft.first()).toIntArray()
        bgGradient = LinearGradient(0f, 0f, width.toFloat(), 0f, arr, null, Shader.TileMode.REPEAT)
    }

    /**
     * 长宽自适应：宽按文字量，封顶 [maxPanelWidth]；高按字号加纵向内边距。
     *
     * 窗口是 WRAP_CONTENT，这里量多少窗口就多大——文字一变宽窄就跟着变。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetrics
        val contentH = fm.descent - fm.ascent
        val desiredW = (textWidth + 2 * padH).coerceAtMost(maxPanelWidth)
        val w = desiredW.coerceAtLeast(dp(48f))
        val h = contentH + 2 * padVPx
        setMeasuredDimension(
            resolveSize(w.toInt(), widthMeasureSpec),
            resolveSize(h.toInt(), heightMeasureSpec),
        )
    }

    /** 内容区宽：面板宽度去掉左右内边距，文字只能在这个区间里滚 */
    private val contentWidth: Float
        get() = width - 2 * padH

    /** 只有文字放不进内容区才需要滚动；放得下就停在原地，也就不必每帧重绘 */
    private fun ensureRunning() {
        if (textWidth <= contentWidth) {
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

        val w = width.toFloat()
        val h = height.toFloat()
        // 圆角胶囊：半径取半高，隆起的一块面板
        val radius = h / 2f
        panel.set(0f, 0f, w, h)
        bgGradient?.let {
            bgPaint.shader = it
            canvas.drawRoundRect(panel, radius, radius, bgPaint)
        }
        if (text.isEmpty()) return

        val left = padH
        val avail = w - 2 * padH
        // 文字裁进内容区：既不会压到胶囊圆角上，滚动时也在边缘干净地消失
        canvas.save()
        canvas.clipRect(left, 0f, w - padH, h)

        if (textWidth <= avail) {
            // 短文本：居中静止，省掉每帧重绘
            scrolling = false
            canvas.drawText(text, left + (avail - textWidth) / 2f, centerY(), paint)
            canvas.restore()
            return
        }

        val now = System.nanoTime()
        val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0f, maxFrameSeconds)
        lastTime = now
        offset -= scrollSpeed * dt
        // 完全滚出左侧才回位：见类注释第 3 条
        if (offset < -(textWidth + gap)) offset = 0f

        // 两段循环：主段滚出时，次段正好接上，形成无缝字幕
        canvas.drawText(text, left + offset, centerY(), paint)
        canvas.drawText(text, left + offset + textWidth + gap, centerY(), paint)
        canvas.restore()

        // 不可见时不再续帧（见 canAnimate 说明）
        if (canAnimate) postInvalidateOnAnimation() else scrolling = false
    }

    private fun centerY(): Float {
        val fm = paint.fontMetrics
        return (height - fm.ascent - fm.descent) / 2f
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /** 面板两侧至少留出的空白：长文本封顶后也不能顶到屏幕边缘 */
        private const val PANEL_MARGIN = 16f
    }
}
