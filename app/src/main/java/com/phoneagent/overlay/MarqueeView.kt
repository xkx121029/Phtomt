// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
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

    /** 底色 / 文字色 / 光晕色 + 光晕强度：三档联动，状态之间靠它做平滑过渡 */
    private data class Palette(val base: Int, val text: Int, val glow: Int, val intensity: Float)

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        color = Color.WHITE
    }

    // 背景：用户颜色序列柔化渐变，让面板成为一块实色底
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgGradient: LinearGradient? = null

    /** 光晕画笔：只画胶囊外侧的柔光，不进胶囊内部 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 光晕外扩留白（px）：窗口上下各留这么多，光晕才不会被窗口边界裁掉 */
    private val glowPadPx = dp(GLOW_PAD_DP)

    /** 当前配色（动画中的中间值）：底色、文字色、光晕色、光晕强度一起插值 */
    private var palette = Palette(DEFAULT_SOLID, Color.WHITE, DEFAULT_SOLID, 0.5f)

    private var paletteAnimator: ValueAnimator? = null

    /**
     * 上一次收到的目标配色。
     *
     * 状态更新（`updateStatus`）每秒可能下发数次，同一阶段反复下发时若每次都重启过渡动画，
     * 跑马灯就会被一个永不结束的颜色动画持续拉起重绘。这里记住目标，同色直接跳过。
     */
    private var targetPalette: Palette? = null

    /**
     * 单色实心底色（非 null 时优先于 [bgGradient]）。
     *
     * "跟随状态变色"只给一个阶段色，这时面板必须是**一整块纯色**——
     * 早先不足两色会被补成"首色+尾色"两段，一旦上游给空列表（配色串解析失败等）
     * 就退化成蓝→紫的横向渐变，跑马灯上凭空多出一层颜色，与设置页预览也对不上。
     */
    private var solidColor: Int? = null
    private val panel = RectF()
    private val glowRect = RectF()

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

    /**
     * 胶囊可用宽度（px）。
     *
     * 窗口比胶囊左右各宽 [glowPadPx]：光晕画在胶囊外侧，不把窗口撑大就只能画进胶囊内部，
     * 或让两侧柔光被窗口边界切掉。胶囊体本身的宽度与建窗时保持一致，视觉位置不变。
     */
    private val maxPanelWidth: Float
        get() = if (width > 0) (width - 2 * glowPadPx).coerceAtLeast(dp(120f))
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

    /**
     * 按执行阶段切换整套配色（底色 / 文字色 / 光晕色 / 光晕强度），280ms 平滑过渡。
     *
     * 这里刻意不复用设置页的用户配色：状态色是"当前在做什么"的语义色，
     * 与用户挑的装饰配色是两件事；强弱按阶段递减到克制区间，避免霓虹感。
     */
    fun setPhase(phase: String) {
        val target = paletteOfPhase(phase)
        // 同一阶段重复下发（常见的状态刷新）不重启动画：颜色已经对了，再跑一遍只是白烧帧
        if (target == targetPalette) return
        targetPalette = target
        // 阶段配色一律走实心底色：先切到实心模式，再让底色/文字色/光晕色/强度一起插值过去。
        // （这里是"跟随状态时改为单色实心"旧决定的延续：三档颜色是同一套调色板里的，
        //   但胶囊体本身仍是一整块纯色，不铺渐变见 CHANGELOG。）
        solidColor = target.base
        gradientColors = emptyList()
        bgGradient = null
        animateTo(target)
    }

    /** 设置面板底色：只给一个颜色（跟随状态变色）就是纯色实心，给两个以上才铺渐变 */
    fun setColors(colors: List<Int>) {
        // 换成用户配色后，下一次同阶段下发仍要重新过渡（阶段目标已不再是当前配色）
        targetPalette = null
        if (colors.size < 2) {
            // 单色：整块铺一个色，绝不走渐变（见 solidColor 说明）
            val c = colors.firstOrNull() ?: DEFAULT_SOLID
            if (solidColor == c && gradientColors.isEmpty()) return
            solidColor = c
            gradientColors = emptyList()
            bgGradient = null
            paletteAnimator?.cancel()
            palette = Palette(c, Color.WHITE, lighten(c), palette.intensity)
            invalidate()
            return
        }
        if (solidColor == null && gradientColors == colors) return
        solidColor = null
        gradientColors = colors
        // 用户自定义渐变：光晕取首个颜色，强度固定在中档，不跟状态跳动
        paletteAnimator?.cancel()
        palette = Palette(colors.first(), Color.WHITE, colors.first(), GLOW_INTENSITY_CUSTOM)
        buildBgGradient()
        invalidate()
    }

    /** 颜色插值到目标配色：只重绘，不动布局；连续切状态时从当前中间值接着走 */
    private fun animateTo(target: Palette) {
        paletteAnimator?.cancel()
        if (palette == target) return
        val from = palette
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PALETTE_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                palette = Palette(
                    base = lerpColor(from.base, target.base, t),
                    text = lerpColor(from.text, target.text, t),
                    glow = lerpColor(from.glow, target.glow, t),
                    intensity = from.intensity + (target.intensity - from.intensity) * t,
                )
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    palette = target
                    invalidate()
                }
            })
        }
        paletteAnimator = animator
        animator.start()
        invalidate()
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int = Color.argb(
        (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt(),
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
    )

    /** 把颜色往白色提一档：光晕色永远比底色亮，才像"光"而不是脏边 */
    private fun lighten(c: Int): Int = lerpColor(c, Color.WHITE, 0.45f)

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
        val h = if (bandMode) bandInsetPx + dp(BAND_ROW_HEIGHT_DP).toInt()
        else (contentH + 2 * padVPx + 2 * glowPadPx).toInt()
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
        paletteAnimator?.cancel()
        paletteAnimator = null
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
        // 胶囊上下各让出光晕留白：光晕画在胶囊外侧，不能被窗口边界切掉
        val padY = if (bandMode) 0f else glowPadPx
        val radius = if (bandMode) 0f else (h - 2 * padY) / 2f
        panel.set(left, padY, left + pw, h - padY)

        // 先在胶囊外侧铺柔光（只画外圈，不进面板内部）
        drawGlow(canvas, panel, radius)

        val solid = solidColor
        if (solid != null) {
            // 单色：实心一块，不留任何渐变痕迹（shader 必须清掉，否则旧渐变会接着生效）
            bgPaint.shader = null
            bgPaint.color = palette.base
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
        val textTop = if (bandMode) bandInsetPx.toFloat() else panel.top
        paint.color = palette.text
        canvas.save()
        canvas.clipRect(contentLeft, textTop, left + pw - padH, panel.bottom)

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

    /**
     * 外扩柔光：三层同心圆角矩形，越外层越大越淡。
     *
     * 用"多圈叠加"而不是 `BlurMaskFilter` / `setShadowLayer`：后两者会强制视图走
     * 软件图层，滚动时每帧整窗离屏合成，文字高频刷新时最容易掉帧。
     * 这里只在同一张画布上多画三个圆角矩形，成本与三次普通填充相当。
     *
     * 不同外层先画、内层后画，靠近胶囊边缘的地方叠加得更亮，形成向外的衰减。
     */
    private fun drawGlow(canvas: Canvas, capsule: RectF, radius: Float) {
        // 光带贴屏幕两侧，外扩柔光会被窗口边界整条切掉，只会剩上下两条毛边，干脆不画
        if (bandMode) return
        val strength = palette.intensity
        if (strength <= 0f) return
        glowPaint.color = palette.glow
        for (i in GLOW_RING_DP.indices) {
            val spread = dp(GLOW_RING_DP[i])
            glowPaint.alpha = (GLOW_RING_ALPHA[i] * strength * 255f).toInt()
            glowRect.set(
                capsule.left - spread,
                capsule.top - spread,
                capsule.right + spread,
                capsule.bottom + spread,
            )
            canvas.drawRoundRect(glowRect, radius + spread, radius + spread, glowPaint)
        }
    }

    private fun centerY(): Float {
        val fm = paint.fontMetrics
        // 光带只在下段文字行里居中；胶囊则在去掉光晕留白后的胶囊体内居中
        val rowTop = if (bandMode) bandInsetPx.toFloat() else panel.top
        val rowHeight = if (bandMode) height - rowTop else panel.height()
        return rowTop + (rowHeight - fm.ascent - fm.descent) / 2f
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 阶段配色表：底色压深、文字近白、光晕比底色亮一档，强度按阶段递减。
     *
     * 具体色值不在这里维护——统一取自 [FloatingUi.phaseTone]，与任务卡状态圆点、阶段徽章
     * 共用同一套（同一阶段在胶囊和卡片上必须是同一个色，否则同一时刻两处颜色对不上）。
     * 色相收在 VI 品牌色域内：雾蓝=观察、墨绿=思考、品红=执行、成功绿=完成、危险红=失败、琥珀=等待，
     * 不走彩虹渐变；光晕强度最高只到 0.72，保证"亮但不刺眼"。
     */
    private fun paletteOfPhase(phase: String): Palette =
        FloatingUi.phaseTone(phase).let { Palette(it.base, it.text, it.glow, it.intensity) }

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

        /**
         * 光晕外扩留白（dp）：窗口上下各让这么多，光晕才不会被窗口边界裁切。
         * 悬浮窗的落点计算也用它（胶囊体位置不变，多出来的是外圈柔光）。
         */
        const val GLOW_PAD_DP = 10f

        /** 配色过渡时长（ms）：与入场/强调同一档节奏，不额外造时长 */
        private const val PALETTE_ANIM_MS = 280L

        /** 自定义配色下的光晕强度：用户自己挑的颜色不跟状态跳动，固定中档 */
        private const val GLOW_INTENSITY_CUSTOM = 0.5f

        /** 光晕三层外扩半径（dp）：由外到内，越大越淡 */
        private val GLOW_RING_DP = floatArrayOf(9f, 5f, 2f)

        /** 三层光晕的透明度（乘上阶段强度后使用）：叠起来边缘约 0.2~0.3，够看清又不糊脸 */
        private val GLOW_RING_ALPHA = floatArrayOf(0.05f, 0.09f, 0.14f)
    }
}
