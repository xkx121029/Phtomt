// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.phoneagent.core.window.OverlayWindowSpec
import com.phoneagent.engine.runtime.TaskNotice
import com.phoneagent.engine.runtime.TaskNoticeCenter

/** 扩展函数里没有 `this@FloatingWindowService` 这个标签，嵌套 lambda 中靠 `self` 取回宿主服务 */
private val FloatingWindowService.self: FloatingWindowService get() = this

// ==================== 降级提示细条 ====================

/**
 * 收到一条降级提示（或引擎清除时收到 null）。
 *
 * 展示规则（与 docs/task-notice-contract.md 对齐）：
 * - `text` **原样**显示，界面不再拼文案；
 * - `method` 作为「当前：X」标签；
 * - `requiresAction` 用高亮实色条 + 「需操作」徽章，与普通白底提示一眼可辨；
 * - 一条到底就覆盖，不堆历史；[TaskNoticePresentation.HoldMs] 后自己向上淡出。
 *
 * 已经在显示时只换内容、不重跑入场动画：同一次任务里连续换通道时，
 * 细条若反复"消失再进场"会像在闪，反而不如原地换字稳。
 */
internal fun FloatingWindowService.showNotice(notice: TaskNotice?) {
    handler.post {
        if (notice == null) {
            // 引擎主动清除：按正常退场收条，不要"啪"一下消失
            hideNotice()
            return@post
        }
        // 用户已收起整套面板：不把细条弹出来（收起是"让出屏幕"的明确意图）
        if (userHidden) return@post
        val showStartedAt = SystemClock.uptimeMillis()
        val root = ensureNoticeWindow() ?: return@post
        noticeRequiresAction = notice.requiresAction
        // text 是引擎产出的成品中文，原样落到 TextView
        noticeText?.text = notice.text
        val methodLabel = TaskNoticePresentation.methodLabel(notice.method)
        noticeMethod?.text = methodLabel.orEmpty()
        noticeMethod?.visibility = if (methodLabel == null) View.GONE else View.VISIBLE
        noticeBadge?.visibility =
            if (TaskNoticePresentation.actionBadge(notice.requiresAction) != null) View.VISIBLE else View.GONE
        applyNoticeStyle(noticeRequiresAction)
        val firstShow = !noticeShowing
        if (!captureHidden) {
            // 每次发布都要保证可见：细条窗口是复用的，一旦某条提示的退场/截图隐藏
            // 把视图留在了 GONE，只靠"首次显示"分支就再也弹不出来（只补动画，不补可见性）
            root.visibility = View.VISIBLE
        }
        if (firstShow && !captureHidden) {
            OverlayMotion.reset(root)
            OverlayMotion.enter(root)
        }
        noticeShowing = true
        if (!captureHidden) {
            scheduleNoticeHide()
            if (firstShow) {
                // 代码成本已含建窗/addView/文案落位；帧等待由 onPreDraw 单独补上
                OverlayPerf.firstFrame(
                    "notice-first-frame",
                    root,
                    showStartedAt,
                    SystemClock.uptimeMillis() - showStartedAt,
                )
            }
        }
        // 跑马灯胶囊高度是动态的：细条要稳稳落在它上方，等本帧布局完成后再校正 y
        root.post {
            val p = noticeParams ?: return@post
            p.y = noticeBottomOffset()
            runCatching { windowManager?.updateViewLayout(root, p) }
        }
    }
}

/** 重新计时：新提示覆盖上一条时，从"这一条"开始算停留时长 */
internal fun FloatingWindowService.scheduleNoticeHide() {
    handler.removeCallbacks(noticeAutoHide)
    handler.postDelayed(noticeAutoHide, TaskNoticePresentation.HoldMs)
}

/** 细条退场：统一向上淡出，收完之后留在窗口里待命（下次复用，避免反复 add/remove） */
internal fun FloatingWindowService.hideNotice() {
    handler.removeCallbacks(noticeAutoHide)
    if (!noticeShowing) return
    noticeShowing = false
    val view = noticeRoot ?: return
    OverlayMotion.exit(view) {
        // 退场期间若来了新提示，[showNotice] 会把 noticeShowing 置回 true，此时不能再收
        if (!noticeShowing) {
            view.visibility = View.GONE
            OverlayMotion.reset(view)
        }
    }
}

/**
 * 细条窗口：固定宽度（与底部胶囊同宽）、高度随内容自适应，`FLAG_NOT_TOUCHABLE` 不吃触摸。
 *
 * 只用一次 addView，之后复用：提示是低频事件，但每条都重建窗口会把成本压在任务关键时刻。
 */
internal fun FloatingWindowService.ensureNoticeWindow(): LinearLayout? {
    noticeRoot?.let { return it }
    val wm = windowManager ?: return null
    val startedAt = SystemClock.uptimeMillis()
    val screenW = OverlayWindowSpec.realScreenSize(this).first
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(FloatingUi.PAD_L), dp(10), dp(FloatingUi.PAD_L), dp(10))
        // 配色跟随深浅主题：登记式重上色，翻面时不用重建窗口
        themed { applyNoticeStyle(noticeRequiresAction) }
    }
    val titleRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    noticeBadge = TextView(this).apply {
        text = TaskNoticePresentation.actionBadge(true).orEmpty()
        textSize = 10f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        // 暖色实心徽章：在深玄青底上也跳得出来（不靠半透明叠层做强调）
        background = FloatingUi.capsule(self, FloatingUi.RADIUS_PILL, FloatingUi.ACCENT_WARM)
        setPadding(dp(FloatingUi.PAD), dp(2), dp(FloatingUi.PAD), dp(2))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(FloatingUi.PAD) }
    }
    noticeText = TextView(this).apply {
        textSize = 12f
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    titleRow.addView(noticeBadge)
    titleRow.addView(noticeText)
    noticeMethod = TextView(this).apply {
        textSize = 10f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(3), 0, 0)
        visibility = View.GONE
    }
    root.addView(titleRow)
    root.addView(noticeMethod)

    val lp = WindowManager.LayoutParams(
        OverlayWindowSpec.marqueeWidthPx(screenW, dp(marqueeSideMargin)),
        WindowManager.LayoutParams.WRAP_CONTENT,
        OverlayWindowSpec.overlayWindowType(),
        OverlayWindowSpec.passiveFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = 0
        y = noticeBottomOffset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }
    val added = runCatching { wm.addView(root, lp) }.isSuccess
    if (!added) return null
    noticeRoot = root
    noticeParams = lp
    OverlayPerf.code("notice-add", startedAt)
    return root
}

/**
 * 细条配色：
 * - 普通提示 = 暖纸白实色 + 发丝描边，是"信息"层级；
 * - `requiresAction` = 品牌实色条 + 白字，是"要动手"层级，不靠透明度区分。
 */
internal fun FloatingWindowService.applyNoticeStyle(requiresAction: Boolean) {
    val root = noticeRoot ?: return
    if (requiresAction) {
        root.background = FloatingUi.capsule(this, FloatingUi.RADIUS_PANEL, FloatingUi.BRAND)
        noticeText?.setTextColor(FloatingUi.ON_BRAND)
        noticeMethod?.setTextColor(FloatingUi.ON_BRAND_SECONDARY)
    } else {
        root.background = FloatingUi.capsule(
            this,
            FloatingUi.RADIUS_PANEL,
            palette.panel,
            palette.panelEdge,
            dp(1),
        )
        noticeText?.setTextColor(palette.textPrimary)
        noticeMethod?.setTextColor(palette.textSecondary)
    }
}

/** 细条底边：底部胶囊（含导航栏净空）之上再留 8dp，胶囊高度动态，取不到时用经验值兜底 */
internal fun FloatingWindowService.noticeBottomOffset(): Int {
    // 胶囊窗口的测量高度含上下光晕留白，扣掉才是胶囊体本身的厚度（公式见 OverlayGeometry）
    val visible = marquee?.visibility == View.VISIBLE
    return OverlayGeometry.noticeBottomOffset(
        marqueeBottomPx = marqueeBottomOffset(),
        capsuleVisible = visible,
        windowHeight = marquee?.height ?: 0,
        glowPadPx = marqueeGlowPad,
        fallbackCapsulePx = dp(FloatingWindowService.NOTICE_MARQUEE_FALLBACK_HEIGHT_DP),
        gapPx = dp(FloatingUi.PAD),
    )
}
