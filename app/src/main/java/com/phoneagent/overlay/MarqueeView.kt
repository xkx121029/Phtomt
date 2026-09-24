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

/**
 * 底部跑马灯面板：圆角胶囊 + 单行状态文字向左匀速滚动。
 *
 * 它是**独立窗口的根视图**（不挂在任务卡片里）：浮在屏幕底边之上、任务期间常驻，显示 AI 当前动作简述。
 * 长宽随内容自适应——宽 = 文字宽 + 左右内边距（超过屏幕可用宽就封顶并开始滚动），
 * 高 = 文字高 + 上下内边距（内边距由设置里的「内边距」滑块控制）。
 * 底色是不透明实色——浮窗压在别的 App 上，半透明白会让文字随时失去对比度。
 *
 * 几个刻意为之的地方（都是踩过的坑）：
 *
 * 1. **底色就是用户选的原色，文字固定白色**。
 *    底色已在设置里由用户配色，再向白色柔化或按阶段混色都会让配色选择器形同虚设，
 *    设置页的预览也就永远对不上实际——预览与实际必须同源。
 *    另外 `Paint` 里 shader 的优先级高于 color：给文字设了渐变 shader 之后，
 *    传进来的颜色会被无声覆盖 —— 早先的相位色就是这么失效的。
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

    /**
     * 单色实心底色（非 null 时优先于 [bgGradient]）。
     *
     * "跟随状态变色"只给一个阶段色，这时面板必须是**一整块纯色**——
     * 早先不足两色会被补成"首色+尾色"两段，一旦上游给空列表（配色串解析失败等）
     * 就退化成蓝→紫的横向渐变，跑马灯上凭空多出一层颜色，与设置页预览也对不上。
     */
    private var solidColor: Int? = null
    private val panel = RectF()

    /** 面板底色渐变颜色（按顺序组成渐变，可在设置中自定义，默认蓝→紫→粉） */
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

    /** 更新文字（内容未变时直接跳过，避免无谓重绘） */
    fun setText(text: String) {
        if (this.text == text) return
        this.text = text
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

    /** 设置面板底色：只给一个颜色（跟随状态变色）就是纯色实心，给两个以上才铺渐变 */
    fun setColors(colors: List<Int>) {
        if (colors.size < 2) {
            // 单色：整块铺一个色，绝不走渐变（见 solidColor 说明）
            val c = colors.firstOrNull() ?: DEFAULT_SOLID
            if (solidColor == c && gradientColors.isEmpty()) return
            solidColor = c
            gradientColors = emptyList()
            bgGradient = null
            invalidate()
            return
        }
        if (solidColor == null && gradientColors == colors) return
        solidColor = null
        gradientColors = colors
        buildBgGradient()
        invalidate()
    }

    /** 背景渐变：用户配色原样使用（不透明实色），随窗口尺寸重建 */
    private fun buildBgGradient() {
        if (width <= 0 || gradientColors.isEmpty()) return
        // 首色补到末尾：渐变首尾同色，色带接缝处才不会断开
        val arr = (gradientColors + gradientColors.first()).toIntArray()
        // 整幅渐变正好铺满面板宽度，两端用 CLAMP 收口即可。
        // REPEAT 是平铺语义：万一渐变的宽度与面板当前宽度不同步，它会把整条色带再重复一遍，
        // 屏幕上就凭空多出几段颜色——设置页预览用的是不平铺的横向渐变，这里必须同源。
        bgGradient = LinearGradient(0f, 0f, width.toFloat(), 0f, arr, null, Shader.TileMode.CLAMP)
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
        val solid = solidColor
        if (solid != null) {
            // 单色：实心一块，不留任何渐变痕迹（shader 必须清掉，否则旧渐变会接着生效）
            bgPaint.shader = null
            bgPaint.color = solid
            canvas.drawRoundRect(panel, radius, radius, bgPaint)
        } else {
            bgGradient?.let {
                bgPaint.shader = it
                canvas.drawRoundRect(panel, radius, radius, bgPaint)
            }
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

        /** 单色模式的兜底色（上游一个颜色都没给时用），取默认渐变的头色 */
        private const val DEFAULT_SOLID = 0xFF4FA3FF.toInt()
    }
}
