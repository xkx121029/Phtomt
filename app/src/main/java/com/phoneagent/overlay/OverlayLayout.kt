// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.view.WindowManager
import com.phoneagent.core.window.OverlayWindowSpec

/**
 * 屏幕旋转后重排所有悬浮窗口。
 *
 * 各窗口的差异：
 * - 任务卡片（[root]）：x/y 是拖动/初始定位留下的绝对坐标，旋转后可能整块出屏，
 *   按新屏幕尺寸夹回「顶部不越状态栏、底部留 24dp、左右留边距」的既有边界（与惯性滑行同界）；
 * - 悬浮球（[miniRoot]）：固定在右上角，x 按新屏宽重算；
 * - 跑马灯（[marquee]）：gravity=BOTTOM|CENTER_HORIZONTAL，横向自动跟随新屏宽，
 *   只需把底部偏移按新方向的导航栏 inset 重算；
 * - 底部选项卡（[sheetRoot]）：MATCH_PARENT + BOTTOM 居中，系统旋转时自动重排，无需处理。
 */
internal fun FloatingWindowService.relayoutOverlays() {
    val point = android.graphics.Point()
    runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
    val screenW = if (point.x > 0) point.x else dp(360)
    val screenH = if (point.y > 0) point.y else dp(640)
    val gap = dp(FloatingUi.EDGE_GAP)
    params?.let { p ->
        val r = root ?: return@let
        val winW = dp(FloatingUi.WIDTH)
        val rootH = r.height
        val winH = if (rootH > 0) rootH else dp(120)
        p.x = p.x.coerceIn(gap, (screenW - winW - gap).coerceAtLeast(gap))
        p.y = p.y.coerceIn(cardTopLimit(), (screenH - winH - dp(24)).coerceAtLeast(cardTopLimit()))
        runCatching { windowManager?.updateViewLayout(r, p) }
    }
    miniRoot?.let { ball ->
        val lp = ball.layoutParams as? WindowManager.LayoutParams ?: return@let
        lp.x = screenW - dp(FloatingWindowService.MINI_BALL_SIZE) - dp(FloatingUi.PAD_L)
        lp.y = cardTopLimit() + dp(FloatingUi.PAD)
        runCatching { windowManager?.updateViewLayout(ball, lp) }
    }
    barParams?.let { lp ->
        val bar = marquee ?: return@let
        // 窗口宽度随屏幕方向重算：宽度仍是固定值，文本更新不会改变它
        lp.width = marqueeWindowWidthPx(screenW)
        lp.y = marqueeBottomOffset()
        runCatching { windowManager?.updateViewLayout(bar, lp) }
    }
    noticeParams?.let { lp ->
        val notice = noticeRoot ?: return@let
        // 提示细条：宽度跟胶囊一起换，底边重新贴到胶囊上方（导航栏 inset 也随方向变了）
        lp.width = OverlayWindowSpec.marqueeWidthPx(screenW, dp(marqueeSideMargin))
        lp.y = noticeBottomOffset()
        runCatching { windowManager?.updateViewLayout(notice, lp) }
    }
}
