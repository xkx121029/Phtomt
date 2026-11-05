// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
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
import com.phoneagent.BuildConfig

/**
 * 跑马灯几何计算（纯函数，单独抽出来是为了能跑 JVM 单测）。
 *
 * 跑马灯是**固定宽度窗口**的根视图：窗口宽度在建窗时一次定死（屏幕宽减两侧留白），
 * 文字变化只改内部胶囊宽度与滚动相位，不再触发 requestLayout / 窗口重排。
 * 之前的做法是 `WRAP_CONTENT` + 按 12dp 网格取整重排：AI 状态每秒更新数次，
 * 只要文案长度跨过网格，窗口就重排一次——这正是"定时抖一下"的来源。
 */
internal object MarqueeGeometry {

    /** 胶囊宽度取整网格（dp）：同长度区间的文案共用一个宽度，胶囊不会逐字抖动 */
    const val WidthStepDp = 12f

    /** 胶囊最小宽度（dp）：极短文案（一个"✓"）也要有个像样的底 */
    const val MinWidthDp = 48f

    /**
     * 胶囊宽度：文字宽 + 左右内边距，封顶可用宽度，再按网格向上取整。
     * 封顶后长文案宽度恒定，滚动区宽度也跟着恒定。
     */
    fun panelWidthPx(
        textWidthPx: Float,
        padHPx: Float,
        maxWidthPx: Float,
        stepPx: Float,
        minWidthPx: Float,
    ): Float {
        val raw = (textWidthPx + 2 * padHPx).coerceAtMost(maxWidthPx)
        val step = stepPx.coerceAtLeast(1f)
        val snapped = kotlin.math.ceil(raw / step) * step
        return snapped.coerceAtMost(maxWidthPx).coerceAtLeast(minWidthPx)
    }

    /** 内容区宽：胶囊宽度去掉左右内边距，文字只能在这个区间里滚 */
    fun contentWidthPx(panelWidthPx: Float, padHPx: Float): Float =
        (panelWidthPx - 2 * padHPx).coerceAtLeast(1f)

    /** 只有文字放不进内容区才需要滚动；放得下就停在原地，也就不必每帧重绘 */
    fun shouldScroll(textWidthPx: Float, panelWidthPx: Float, padHPx: Float): Boolean =
        textWidthPx > contentWidthPx(panelWidthPx, padHPx)

    /**
     * 推进滚动相位。
     *
     * 回位判据必须是「完全滚出」：文字画在 x = offset，完全离开左侧要满足
     * `offset < -(textWidth + gap)`。原判据 `offset < -panelWidth` 在文字比面板窄时
     * 会提前回位，文字还在屏内就跳回入场位——肉眼可见的抽动。
     */
    fun advanceOffset(offsetPx: Float, deltaPx: Float, travelDistancePx: Float): Float =
        if (offsetPx - deltaPx < -travelDistancePx) 0f else offsetPx - deltaPx
}

/**
 * 底部跑马灯面板：圆角胶囊 + 单行状态文字向左匀速滚动。
 *
 * 它是**独立窗口的根视图**（不挂在任务卡片里）：浮在屏幕底边之上、任务期间常驻，显示 AI 当前动作简述。
 * 窗口宽度固定（见 [MarqueeGeometry]），胶囊宽度随文字自适应并居中；高度 = 文字高 + 上下内边距。
 * 底色是不透明实色——浮窗压在别的 App 上，半透明白会让文字随时失去对比度。
 *
 * 几个刻意为之的地方（都是踩过的坑）：
 *
 * 1. **底色就是用户选的原色，文字固定白色**。设置页预览与实际必须同源；
 *    `Paint` 里 shader 优先级高于 color，给文字设渐变会让传入颜色被无声覆盖。
 * 2. **更新文字不重置滚动相位**。AI 状态每秒数次更新，每次重置都会把文字钉回起点。
 * 3. **更新文字不触发重排**。窗口宽度固定，胶囊宽度只影响绘制；文字更新只测量 + invalidate。
 * 4. **短文本居中静止**。跑马灯的意义是显示放不下的长文本，短文本硬滚只是白烧每帧重绘。
 * 5. **滚动判据是"文字宽 vs 内容区宽"**，不是 view 宽度（窗口宽度是固定的）。
 */
class MarqueeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    /** true = 顶部光带（整宽实心带、高度固定）；false = 底部胶囊（宽度自适应、居中） */
    private val bandMode: Boolean = false,
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

    /** 窗口可用宽度：窗口本身是固定宽度，量出来就是它；未布局时按屏幕宽兜底 */
    private val maxPanelWidth: Float
        get() = if (width > 0) width.toFloat()
        else (resources.displayMetrics.widthPixels - 2 * dp(PANEL_MARGIN)).coerceAtLeast(dp(120f))

    /** 当前胶囊宽度（px）：由文字量决定，绘制时居中 */
    private var panelWidth = 0f

    /** 顶部光带里"状态栏/挖孔"那一段的高度（px）：文字行固定落在它下面，不压系统图标 */
    private var bandInsetPx = 0

    /** 滚动相位：主段文字左边缘相对内容区起点的 x 坐标（0 = 刚入场，负值 = 已在滚动中） */
    private var offset = 0f
    private var scrolling = false
    private var lastTime = 0L

    /** 两段文字之间的间隔（dp），小间隔形成字幕式连续流动，避免屏幕出现空档 */
    private val gapPx = dp(GAP_DP)

    /** 滚动速度（dp/s）：按密度换算，不同屏幕上"看起来一样快" */
    private val scrollSpeedPxPerSec = dp(SCROLL_DP_PER_SEC)

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

    // ---- debug 埋点：证明"文本高频更新不触发重排" ----
    private var debugTextUpdates = 0
    private var debugMeasures = 0
    private var debugLayoutRequests = 0

    /** 更新文字（内容未变时直接跳过，避免无谓重绘） */
    fun setText(text: String) {
        if (this.text == text) return
        this.text = text
        textWidth = paint.measureText(text)
        // 关键：只更新胶囊宽度与相位，不 requestLayout。
        // 窗口宽度固定，胶囊宽度是纯绘制属性 —— 状态每秒更新数次也不会让窗口重排。
        val desired = computePanelWidth()
        if (kotlin.math.abs(desired - panelWidth) > 0.5f) {
            panelWidth = desired
            buildBgGradient()
        }
        debugTextUpdates++
        if (BuildConfig.DEBUG && debugTextUpdates % DEBUG_LOG_EVERY == 0) {
            OverlayPerf.log(
                "marquee-updates",
                "updates=$debugTextUpdates measures=$debugMeasures relayouts=$debugLayoutRequests",
            )
        }
        // 刻意不重置 offset：见类注释第 2 条
        ensureRunning()
        invalidate()
    }

    /** 设置纵向内边距（像素）：决定面板厚度，设置项变化时调用（低频，允许重排） */
    fun setPadV(px: Int) {
        if (padVPx == px) return
        padVPx = px
        requestLayout()
        invalidate()
    }

    /**
     * 顶部光带：上段 inset 铺进状态栏/挖孔区，下段固定一行高度放文字。
     *
     * 窗口高度随之固定（不由文字决定），状态栏那一段永远整宽纯色；
     * 文字只在下段里滚动，绝不和时钟、电量挤同一行。
     */
    fun setBandInset(px: Int) {
        val target = px.coerceAtLeast(0)
        if (bandInsetPx == target) return
        bandInsetPx = target
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

    /** 背景渐变：用户配色原样使用（不透明实色），随胶囊宽度重建（只在宽度变化时重建） */
    private fun buildBgGradient() {
        if (panelWidth <= 0f || gradientColors.isEmpty()) {
            bgGradient = null
            return
        }
        // 首色补到末尾：渐变首尾同色，色带接缝处才不会断开
        val arr = (gradientColors + gradientColors.first()).toIntArray()
        // 整幅渐变正好铺满胶囊宽度，两端用 CLAMP 收口即可。
        // REPEAT 是平铺语义：万一渐变宽度与胶囊宽度不同步，它会把整条色带再重复一遍。
        bgGradient = LinearGradient(0f, 0f, panelWidth, 0f, arr, null, Shader.TileMode.CLAMP)
    }

    /** 胶囊宽度：文字宽 + 内边距，封顶窗口宽，按网格取整（见 [MarqueeGeometry]） */
    private fun computePanelWidth(): Float = MarqueeGeometry.panelWidthPx(
        textWidthPx = textWidth,
        padHPx = padH,
        maxWidthPx = maxPanelWidth,
        stepPx = dp(MarqueeGeometry.WidthStepDp),
        minWidthPx = dp(MarqueeGeometry.MinWidthDp),
    )

    /**
     * 长宽：窗口宽度固定（EXACT）。底部胶囊高度 = 字号 + 纵向内边距；
     * 顶部光带高度 = 状态栏 + 挖孔 inset，保证光带真的压在状态栏上。
     *
     * 这里**不再用文字宽决定窗口宽**——窗口宽度固定是"文本更新不重排"的前提。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (BuildConfig.DEBUG) debugMeasures++
        val fm = paint.fontMetrics
        val contentH = fm.descent - fm.ascent
        val w = resolveSize(maxPanelWidth.toInt(), widthMeasureSpec)
        val h = if (bandMode) bandInsetPx + dp(BAND_ROW_HEIGHT_DP).toInt() else (contentH + 2 * padVPx).toInt()
        if (panelWidth <= 1f) panelWidth = if (bandMode) w.toFloat() else computePanelWidth()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    /** 只有文字放不进内容区才需要滚动；放得下就停在原地，也就不必每帧重绘 */
    private fun ensureRunning() {
        if (!MarqueeGeometry.shouldScroll(textWidth, panelWidth, padH)) {
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
        // 窗口宽度是固定的：旋转后宽度会变，胶囊宽度按新上限重算；光带永远是整宽
        panelWidth = if (bandMode) w.toFloat() else computePanelWidth()
        buildBgGradient()
        ensureRunning()
    }

    override fun requestLayout() {
        if (BuildConfig.DEBUG) debugLayoutRequests++
        super.requestLayout()
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 光带整宽、直角；底部胶囊居中、圆角取半高
        if (bandMode) {
            panelWidth = w
        } else if (panelWidth <= 1f) {
            panelWidth = computePanelWidth()
        }
        val pw = panelWidth.coerceAtMost(w)
        val left = if (bandMode) 0f else (w - pw) / 2f
        val radius = if (bandMode) 0f else h / 2f
        panel.set(left, 0f, left + pw, h)
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

        val contentLeft = left + padH
        val avail = MarqueeGeometry.contentWidthPx(pw, padH)
        // 文字裁进内容区：既不会压到胶囊圆角上，滚动时也在边缘干净地消失。
        // 光带还要再从 inset 下沿起裁：上段是给状态栏纯色打底的，文字不能爬进去压系统图标。
        val textTop = if (bandMode) bandInsetPx.toFloat() else 0f
        canvas.save()
        canvas.clipRect(contentLeft, textTop, left + pw - padH, h)

        if (!MarqueeGeometry.shouldScroll(textWidth, pw, padH)) {
            // 短文本：居中静止，省掉每帧重绘
            scrolling = false
            canvas.drawText(text, contentLeft + (avail - textWidth) / 2f, centerY(), paint)
            canvas.restore()
            return
        }

        val now = System.nanoTime()
        val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0f, maxFrameSeconds)
        lastTime = now
        // 回位判据见 [MarqueeGeometry.advanceOffset]：必须完全滚出（含段间隔）才回位
        offset = MarqueeGeometry.advanceOffset(offset, scrollSpeedPxPerSec * dt, textWidth + gapPx)

        // 两段循环：主段滚出时，次段正好接上，形成无缝字幕
        canvas.drawText(text, contentLeft + offset, centerY(), paint)
        canvas.drawText(text, contentLeft + offset + textWidth + gapPx, centerY(), paint)
        canvas.restore()

        // 不可见时不再续帧（见 canAnimate 说明）
        if (canAnimate) postInvalidateOnAnimation() else scrolling = false
    }

    private fun centerY(): Float {
        val fm = paint.fontMetrics
        // 光带只在下段文字行里居中；上段（状态栏高度）不参与文字排版
        val rowTop = if (bandMode) bandInsetPx.toFloat() else 0f
        val rowHeight = height - rowTop
        return rowTop + (rowHeight - fm.ascent - fm.descent) / 2f
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /** 胶囊两侧至少留出的空白：长文本封顶后也不能顶到屏幕边缘 */
        private const val PANEL_MARGIN = 16f

        /** 单色模式的兜底色（上游一个颜色都没给时用），取默认渐变的头色 */
        private const val DEFAULT_SOLID = 0xFF4FA3FF.toInt()

        /** 两段文字之间的间隔（dp） */
        private const val GAP_DP = 40f

        /** 滚动速度（dp/s）：约等于原先 60px/s 在 2.75 密度屏上的观感 */
        private const val SCROLL_DP_PER_SEC = 22f

        /** debug 下每多少次文字更新打一条滚动统计 */
        private const val DEBUG_LOG_EVERY = 20

        /** 顶部光带文字行高度（dp）：状态栏那一段之下再给 14sp 文字留出可读的一行 */
        private const val BAND_ROW_HEIGHT_DP = 30f
    }
}
