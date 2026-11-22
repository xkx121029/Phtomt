// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * 悬浮窗的窗口宿主：把任务卡片窗口建出来 / 摘掉，外加它依赖的屏幕几何。
 *
 * 从 `FloatingWindowService` 原样搬出，调用顺序没变：服务的 `onStartCommand` / `onDestroy`
 * 仍按原来的时机调 `showWindow()` / `removeWindow()`。
 */

/**
 * 任务卡片可拖动的上边界：退到状态栏之下再留一行。
 *
 * 顶部光带已移除，上边界不再需要让出整条光带高度；只保证卡片不压住状态栏文字。
 */
internal fun FloatingWindowService.cardTopLimit(): Int = statusBarHeight() + dp(12)

/**
 * 状态栏高度（像素）。
 *
 * 该值决定任务卡片可拖动的上边界（见 [cardTopLimit]），因此不能用「查系统资源名」这一种方式：
 * `getIdentifier("status_bar_height", ...)` 在相当一部分 ROM / 高版本系统上取不到，返回 0 ——
 * 于是边界变成 0，卡片会压住状态栏上的时间与电量。
 *
 * 取值顺序：WindowInsets（API 30+，最可靠）→ 系统资源名 → 经验兜底值。
 */
@Suppress("ReturnCount")
internal fun FloatingWindowService.statusBarHeight(): Int {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        // 同时算上刘海/挖孔：有 cutout 的机型上，屏幕物理顶到可视内容之间的实际距离
        // 比状态栏更高，只取 statusBars 会偏小，卡片就差那么一截压到状态栏上
        val inset = runCatching {
            windowManager?.currentWindowMetrics?.windowInsets
                ?.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.displayCutout(),
                )
                ?.top
        }.getOrNull()
        if (inset != null && inset > 0) return inset
    }
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    if (id > 0) {
        val h = resources.getDimensionPixelSize(id)
        if (h > 0) return h
    }
    // 兜底：宁可多留一点，也不能返回 0 —— 返回 0 会让卡片整块压到状态栏上
    return dp(24)
}

// 分派函数多处早退以保证可读性，此处按规则豁免
@Suppress("ReturnCount")
internal fun FloatingWindowService.showWindow() {
    if (root != null) return
    // 量化埋点起点：从建视图到首个绘制帧（含 addView + 一次测量/布局）
    val startedAt = SystemClock.uptimeMillis()
    val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
    windowManager = wm
    val layout = buildPanel()
    val builtAt = SystemClock.uptimeMillis()
    OverlayPerf.log("task-card-build", "${builtAt - startedAt}ms")
    // 获取当前屏幕尺寸，根据横竖屏调整初始位置，避免旋转后悬浮窗位置出屏
    val point = android.graphics.Point()
    runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
    val screenW = if (point.x > 0) point.x else dp(360)
    val winW = dp(FloatingUi.WIDTH)
    // 宽度固定，高度随内容自适应
    params = WindowManager.LayoutParams(
        winW, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 初始位置：水平居中，纵向落在状态栏下方（跑马灯已移到底部，卡片上边界只看状态栏）
        x = (screenW - winW) / 2
        y = cardTopLimit() + dp(FloatingUi.PAD)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // 关键：API 30+ 默认 fitInsetsTypes = systemBars()，会把窗口内容整体推到状态栏下方，
            // 这是与 FLAG_LAYOUT_NO_LIMITS 无关的另一套机制（inset 适配）。
            // 清空后窗口坐标系才真正从物理屏顶开始，y = 0 贴顶、负 y 出屏才有意义。
            fitInsetsTypes = 0
        }
        // 这里曾调用 applyBlurBehind()：blurBehindRadius 会把整块屏幕糊掉（不只是卡片），
        // 任务一跑整屏发灰。悬浮窗又没有 Window 对象，拿不到"只糊窗口范围"的
        // Window#setBackgroundBlurRadius，所以卡片就只是一块半透明品牌色。
    }
    // 真实投影：让卡片浮起在屏幕之上，elevation 阴影随圆角轮廓（M3 柔和浮起）
    layout.elevation = dp(FloatingUi.ELEVATION).toFloat()
    layout.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(FloatingUi.RADIUS_CARD.toInt()).toFloat())
        }
    }
    // 先 addView、成功后再记 root。以前先赋 root 再 try/catch 吞掉失败，
    // 一旦 addView 抛异常（悬浮窗权限被收回、window token 失效），root 仍是
    // 非空、后续 showWindow 全部被开头那行 return 挡掉，悬浮窗就永久起不来。
    val added = runCatching { wm.addView(layout, params) }.isSuccess
    if (!added) {
        Log.w("FloatingWindowService", "悬浮窗添加失败，等待下次重试")
        return
    }
    root = layout
    val addedAt = SystemClock.uptimeMillis()
    // 代码成本 = 建视图 + addView；帧等待 = 到首个 onPreDraw 的差值
    OverlayPerf.log("task-card-add", "${addedAt - builtAt}ms")
    OverlayPerf.firstFrame("task-card-first-frame", layout, startedAt, addedAt - startedAt)
    showMarquee()
    showSheetWindow()
    // 预热完成卡片：任务一开始就把底部卡片窗口建好（隐藏、0 高、不吃触摸），
    // 收尾时只翻可见性。放到首帧之后再建，避免和任务卡自己抢这一帧的时间。
    layout.post { ensureDoneWindow() }
}

internal fun FloatingWindowService.removeWindow() {
    root?.let { runCatching { windowManager?.removeView(it) } }
    root = null
    // 跑马灯是独立窗口：移除后屏幕上不留任何入口
    marquee?.let { runCatching { windowManager?.removeView(it) } }
    marquee = null
    barParams = null
    // 悬浮球同样是独立窗口：移除后屏幕上不留任何入口（任务已结束，不需要再唤出）
    miniRoot?.let { runCatching { windowManager?.removeView(it) } }
    miniRoot = null
    // 底部选项卡是独立窗口，需一并移除
    sheetRoot?.let { runCatching { windowManager?.removeView(it) } }
    sheetRoot = null
    sheetParams = null
    // 完成卡片同样是独立窗口：任务结束/服务退出后不能留在屏幕上
    removeDoneCardNow()
    // 降级提示细条也是独立窗口：先停计时再移除，避免移除后回调再动已脱离窗口的视图
    handler.removeCallbacks(noticeAutoHide)
    noticeShowing = false
    noticeRoot?.let { runCatching { windowManager?.removeView(it) } }
    noticeRoot = null
    noticeParams = null
}
