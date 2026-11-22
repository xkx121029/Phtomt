// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.phoneagent.core.window.OverlayWindowSpec

/**
 * 跑马灯窗口宽度（px）：胶囊宽度之外再留出两侧光晕的绘制空间。
 *
 * 胶囊体宽度仍是「屏宽 - 两侧留白」，多出来的只有外圈柔光；
 * 窗口宽度依旧在建窗时定死，文本更新不会让它改变。
 */
internal fun FloatingWindowService.marqueeWindowWidthPx(screenW: Int): Int =
    OverlayWindowSpec.marqueeWidthPx(screenW, dp(marqueeSideMargin)) + 2 * marqueeGlowPad

/**
 * 跑马灯配色：跟随状态时交给 `MarqueeView.setPhase`（底色/文字色/光晕色三档联动、
 * 按阶段平滑过渡），否则用用户自定义的渐变配色。
 */
internal fun FloatingWindowService.applyMarqueePalette(view: MarqueeView) {
    if (marqueeAutoColor) view.setPhase(currentPhase) else view.setColors(marqueeColors)
}

/**
 * 设置跑马灯内边距 / 配色：顶部光带与底部胶囊同步应用。
 *
 * 底部胶囊厚度由内边距决定（窗口宽度固定、高度自适应）；顶部光带高度由状态栏 inset 决定，
 * 内边距只影响文字垂直居中，两者都不需要动窗口 LayoutParams。
 */
internal fun FloatingWindowService.applyMarqueeSettings() {
    marquee?.let {
        applyMarqueePalette(it)
        it.setPadV(dp(marqueeHeightDp))
    }
}

/**
 * 创建跑马灯：**同一时刻只存在一个文字载体**，任务期间常驻。
 *
 * 形态是贴底长条胶囊：固定宽度（真实屏宽 - 两侧留白）、两端全圆角（半径 = 高度一半，由
 * `MarqueeView` 自绘）、锚在导航栏之上、离底边约 0.5cm，横向铺开而不是方块色块。
 *
 * 为什么只有这一个载体：曾经同时存在「顶部光带 + 底部胶囊」，两个窗口逐字渲染同一段
 * 任务描述，用户看到的就是"同一步被画了两遍"；去掉光带后文字只有一处可读，也不再需要
 * 为光带单独处理状态栏 inset、刘海裁切与 `LAYOUT_IN_SCREEN` 那一套窗口参数。
 *
 * 窗口是 `FLAG_NOT_TOUCHABLE`：只负责显示，绝不吃掉任何触摸。
 * 窗口宽度在建窗时一次定死，之后文本怎么高频更新都不会触发窗口重排。
 */
internal fun FloatingWindowService.showMarquee() {
    val wm = windowManager ?: return
    if (marquee != null) return
    val screenSize = OverlayWindowSpec.realScreenSize(this)
    val screenW = screenSize.first

    val startedAt = SystemClock.uptimeMillis()
    val barW = marqueeWindowWidthPx(screenW)
    val bar = MarqueeView(this).apply {
        applyMarqueePalette(this)
        setPadV(dp(marqueeHeightDp))
    }
    val lp = WindowManager.LayoutParams(
        // 固定宽度：文本更新只改绘制，不重排窗口
        barW, WindowManager.LayoutParams.WRAP_CONTENT,
        OverlayWindowSpec.overlayWindowType(),
        OverlayWindowSpec.passiveFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = 0
        y = marqueeBottomOffset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }
    try {
        wm.addView(bar, lp)
        marquee = bar
        barParams = lp
        OverlayPerf.code("marquee-add", startedAt)
    } catch (_: Exception) {
        // 无悬浮窗权限：跑马灯建不出来，任务卡片照常工作
    }
    // 新建的窗口默认可见：这里立刻按用户设置和当前状态补一次（只任务卡模式下直接 GONE）
    applyStepTextPlacement()
}

/**
 * 更新跑马灯文案：只写唯一那个载体。
 *
 * 相位由 `MarqueeView` 自己保持：换文案不会重置滚动位置，也不会触发窗口重排。
 */
internal fun FloatingWindowService.setMarqueeText(text: String) {
    marquee?.setText(text)
}

/**
 * 跑马灯整体显示/隐藏。
 *
 * [visible] 表达"当前界面状态想不想显示"（任务运行中显示、完成卡/用户收起/截图时隐藏）；
 * 这里再叠一道用户设置：只任务卡模式（[StepTextPlacement.CARD]）下跑马灯一律不显示。
 */
internal fun FloatingWindowService.setMarqueeVisible(visible: Boolean) {
    marqueeRequestedVisible = visible
    val v = if (visible && stepPlacement != StepTextPlacement.CARD) View.VISIBLE else View.GONE
    marquee?.apply {
        // 交接淡出可能刚把透明度降到 0：重新显示时从 1 开始
        if (v == View.VISIBLE) alpha = 1f
        visibility = v
    }
}

/**
 * 按当前显示设置重绘两处文字，并重算跑马灯可见性。
 *
 * 每次状态更新与设置变更都走这里，切换显示方式时正在跑的任务即时生效。
 */
internal fun FloatingWindowService.applyStepTextPlacement() {
    val desc = lastReasoningText.ifBlank { lastStatusText }
    val showMarqueeText = stepPlacement != StepTextPlacement.CARD
    stepText?.text = StepTextContent.cardLabel(
        step = lastStep,
        total = lastTotal,
        description = desc,
        includeDescription = stepPlacement != StepTextPlacement.MARQUEE,
    )
    setMarqueeText(if (showMarqueeText) desc else "")
    setMarqueeVisible(marqueeRequestedVisible)
}

internal fun FloatingWindowService.isMarqueeVisible(): Boolean =
    marquee?.visibility == View.VISIBLE

/**
 * 底部跑马灯距屏幕底边的距离：约 0.5cm；系统手势条更高时让到手势区之上。
 *
 * 面板压在系统手势条上会干扰上滑手势，所以取 `max(手势条高度, 19dp)`。
 * 拿不到导航栏 inset（API 30 以下）就只留基础留白，面板照常显示。
 *
 * 窗口高度比胶囊体上下各多出 [marqueeGlowPad]（光晕留白），所以窗口落点要减掉这一截，
 * 胶囊体的视觉位置才和加光晕之前完全一致。
 */
internal fun FloatingWindowService.marqueeBottomOffset(): Int {
    val navBar = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                ?.bottom
        } else null
    }.getOrNull() ?: 0
    // 纯算术已抽到 OverlayGeometry（可单测）；这里只负责取系统 inset 与 dp 换算
    return OverlayGeometry.marqueeBottomOffset(navBar, dp(marqueeBottomGap), marqueeGlowPad)
}

/** 跑马灯是独立窗口：隐藏状态仍按 [setMarqueeVisible] 的规则走，只是先淡出再落 GONE */
internal fun FloatingWindowService.fadeOutMarquee(durationMs: Int) {
    marqueeRequestedVisible = false
    val v = marquee ?: return
    v.animate().cancel()
    if (v.visibility != View.VISIBLE) {
        v.alpha = 1f
        return
    }
    v.animate()
        .alpha(0f)
        .setDuration(durationMs.toLong())
        .setInterpolator(OverlayMotion.EaseIn)
        .withEndAction {
            v.visibility = View.GONE
            v.alpha = 1f
        }
        .start()
}
