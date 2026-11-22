// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller

@Suppress("ReturnCount")
internal fun FloatingWindowService.onTouchDrag(event: MotionEvent): Boolean {
    val p = params ?: return false
    val vtracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
    vtracker.addMovement(event)
    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            // 取消进行中的惯性滑动
            isFlinging = false
            flingScroller?.forceFinished(true)
            startX = p.x; startY = p.y
            startTouchX = event.rawX; startTouchY = event.rawY
            dragging = false
            vtracker.clear()
            vtracker.addMovement(event)
        }
        MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - startTouchX
            val dy = event.rawY - startTouchY
            // 越过阈值才算拖动：否则手指的微小抖动会把"轻点"变成位移，
            // 窗口跟着抖一下、点击展开也永远触发不了
            if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                dragging = true
            }
            if (!dragging) return true
            p.x = (startX + dx).toInt()
            p.y = (startY + dy).toInt()
            // 拖动期间悬浮窗可能被销毁（removeWindow），root 为空时跳过更新，避免 NPE
            root?.let { r -> windowManager?.updateViewLayout(r, p) }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            vtracker.computeCurrentVelocity(1000)
            val vx = vtracker.xVelocity
            val vy = vtracker.yVelocity
            velocityTracker?.recycle()
            velocityTracker = null
            if (!dragging) {
                // 轻点头部：切换 AI 详情展开/收起
                toggleThinking()
            } else {
                settlePosition(vx, vy)
            }
        }
    }
    return true
}

/**
 * 松手后的归位：先做「边缘吸附 / 越界回收」，都不需要时才让惯性滑行。
 *
 * 窗口用 FLAG_LAYOUT_NO_LIMITS，本身可以被拖到屏幕外——不收拾就会出现
 * "窗口拖丢了、任务还在跑却看不到状态"的情况；靠近左右边缘时吸附贴边，
 * 也让悬浮窗更像系统组件而不是随手丢在屏幕中间的补丁。
 */
@Suppress("ReturnCount")
internal fun FloatingWindowService.settlePosition(vx: Float, vy: Float) {
    val p = params ?: return
    val r = root ?: return
    val point = android.graphics.Point()
    runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
    val screenW = if (point.x > 0) point.x else dp(360)
    val screenH = if (point.y > 0) point.y else dp(640)
    val winW = dp(FloatingUi.WIDTH)
    val winH = if (r.height > 0) r.height else dp(120)
    val gap = dp(FloatingUi.EDGE_GAP)
    val snapZone = dp(FloatingUi.SNAP_ZONE)
    val targetX = when {
        p.x <= snapZone -> gap
        p.x + winW >= screenW - snapZone -> (screenW - winW - gap).coerceAtLeast(gap)
        else -> null
    }
    // 纵向：顶部最多贴到状态栏下方，底部至少留一角可抓
    val minY = cardTopLimit()
    val maxY = (screenH - winH - dp(24)).coerceAtLeast(minY)
    val targetY = p.y.coerceIn(minY, maxY)
    if (targetX != null || targetY != p.y) {
        animateTo(targetX ?: p.x, targetY)
        return
    }
    if (kotlin.math.abs(vx) + kotlin.math.abs(vy) > 900f) {
        startFling(p.x, p.y, vx, vy)
    }
}

/** 位置动画：吸附/回收用，起快收缓，避免窗口"跳"过去 */
internal fun FloatingWindowService.animateTo(targetX: Int, targetY: Int) {
    val p = params ?: return
    val r = root ?: return
    val sx = p.x
    val sy = p.y
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = OverlayMotion.EnterMs.toLong()
        interpolator = OverlayMotion.EaseOut
        addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            p.x = sx + ((targetX - sx) * t).toInt()
            p.y = sy + ((targetY - sy) * t).toInt()
            runCatching { windowManager?.updateViewLayout(r, p) }
        }
        start()
    }
}

/** 惯性滑行：用 OverScroller 沿松手速度衰减滑动，平滑停止 */
internal fun FloatingWindowService.startFling(startX: Int, startY: Int, vx: Float, vy: Float) {
    val point = android.graphics.Point()
    runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
    val screenW = if (point.x > 0) point.x else dp(360)
    val screenH = if (point.y > 0) point.y else dp(640)
    val winW = dp(FloatingUi.WIDTH)
    val rootH = root?.height ?: 0
    val winH = if (rootH > 0) rootH else dp(120)
    val gap = dp(FloatingUi.EDGE_GAP)
    flingScroller?.forceFinished(true)
    flingScroller = OverScroller(this).apply {
        fling(
            startX, startY,
            vx.toInt(), vy.toInt(),
            gap, (screenW - winW - gap).coerceAtLeast(gap),   // x：始终保留完整窗口在屏内
            cardTopLimit(),                                    // y：顶部不越过状态栏
            (screenH - winH - dp(24)).coerceAtLeast(cardTopLimit()),
        )
    }
    isFlinging = true
    Choreographer.getInstance().postFrameCallback(frameCallback)
}

@Suppress("ReturnCount")
internal fun FloatingWindowService.onFlingFrame() {
    if (!isFlinging) return
    val scroller = flingScroller ?: return
    val p = params ?: return
    val r = root ?: return
    if (scroller.computeScrollOffset()) {
        if (p.x != scroller.currX || p.y != scroller.currY) {
            p.x = scroller.currX
            p.y = scroller.currY
            windowManager?.updateViewLayout(r, p)
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    } else {
        isFlinging = false
    }
}
