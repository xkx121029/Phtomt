// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** 扩展函数里没有 `this@FloatingWindowService` 这个标签，嵌套 lambda 中靠 `self` 取回宿主服务 */
private val FloatingWindowService.self: FloatingWindowService get() = this

/**
 * 创建悬浮球（只在用户点「隐藏」时才建，建好后长期复用）：收起面板后屏幕上唯一可点的入口。
 *
 * 只做一件事——点它把整套面板唤回来，所以刻意做成一个小圆形实色按钮，
 * 不抢视线，也不挡住用户正在操作的界面。
 */
internal fun FloatingWindowService.showMiniBall() {
    if (miniRoot != null) return
    val wm = windowManager ?: return
    val ball = TextView(this).apply {
        text = "AI"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(FloatingUi.ON_BRAND)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = FloatingUi.capsule(self, 999f, FloatingUi.BRAND)
        elevation = dp(6).toFloat()
        setOnClickListener {
            showFromUser()
            FloatingWindowService.onInteraction?.invoke("resume", "")
        }
    }
    // 按下当帧反馈，不等系统 ripple（悬浮球是收起后唯一的入口，反馈必须立刻可见）
    bindPress(ball)
    val point = android.graphics.Point()
    runCatching { wm.defaultDisplay.getRealSize(point) }
    val screenW = if (point.x > 0) point.x else dp(360)
    val size = dp(FloatingWindowService.MINI_BALL_SIZE)
    val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 右上角、状态栏下方：既不压状态栏，也不容易挡住用户正在看的内容
        x = screenW - size - dp(FloatingUi.PAD_L)
        y = cardTopLimit() + dp(FloatingUi.PAD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }
    try {
        wm.addView(ball, lp)
        miniRoot = ball
    } catch (_: Exception) {
        // 无悬浮窗权限：球建不出来，但卡片上的「隐藏」不会因此崩掉
    }
}

/**
 * 用户点「隐藏」：收起卡片/跑马灯，只留悬浮球（任务搁置交由引擎处理）。
 *
 * 底部选项卡刻意不动：它承载的是"AI 正在等用户回答/确认"这类不能让用户错过的问题，
 * 收起状态面板不应顺手把它藏掉——藏了以后 AI 仍在等输入，面板又回不来，任务就永久挂住了。
 */
internal fun FloatingWindowService.hideForUser() {
    // 先确保悬浮球建得出来：球建不出来（无悬浮窗权限）还把面板收起，
    // 用户就再也叫不回来了——这种情况宁可不收起
    showMiniBall()
    val ball = miniRoot ?: return
    userHidden = true
    root?.visibility = View.GONE
    setMarqueeVisible(false)
    ball.visibility = View.VISIBLE
    // 收起即"让出屏幕"：降级提示细条一并收掉，不等它 4 秒后自己走
    hideNotice()
}

/** 用户点悬浮球：原样唤出面板（任务由引擎继续执行） */
internal fun FloatingWindowService.showFromUser() {
    if (!userHidden) return
    userHidden = false
    root?.visibility = View.VISIBLE
    setMarqueeVisible(true)
    miniRoot?.visibility = View.GONE
}

/**
 * 收起状态下的"防复现"闸门：状态更新每步都会跑 [resetPanel]，而它会把面板重新置为可见；
 * 每次更新之后按收起状态再压回去，面板才不会自己冒出来。
 */
internal fun FloatingWindowService.applyUserHidden() {
    if (!userHidden) return
    root?.visibility = View.GONE
    setMarqueeVisible(false)
}
