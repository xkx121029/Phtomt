package com.phoneagent.core.window

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * 全屏「被动」覆盖层窗口参数的统一构建点（边缘光效 / 虚拟光标 / 页面标记）。
 *
 * 这三层覆盖物的参数原本各写一份（同样的 TYPE_APPLICATION_OVERLAY 兼容分支 + 四个 FLAG +
 * 不透明度口径），任何一处改动都容易漏掉另外两处。这里只保留一份构造逻辑。
 *
 * 不透明度口径（踩过的坑）：Android 12+ 会按**所有覆盖层叠加后**的组合不透明度判定是否放行
 * 穿透触摸，组合值超过系统上限（默认 0.8）时用户点击/滑动会被整条丢弃，而 Agent 的无障碍手势
 * 不经过这道判定——表现为"只剩 AI 能操作手机"。任务期间最多三层同时在场，
 * 每层取 [PASSIVE_ALPHA]（0.4）时组合值 = 1 - 0.6³ ≈ 0.784，仍在阈值之下。
 */
object OverlayWindowSpec {

    /** 被动覆盖层单层不透明度：三层叠加后仍低于系统 0.8 的穿透判定阈值 */
    const val PASSIVE_ALPHA = 0.4f

    /**
     * 构建全屏、不拦截触摸、不抢焦点的被动覆盖层参数。
     *
     * @param clearInsets API 30+ 是否清空 fitInsetsTypes。清空后窗口坐标系从物理屏顶开始，
     *        与元素树 / 手势 / Shizuku input 使用的物理坐标一致（光标、页面标记必须清空）；
     *        边缘光效按自身 inset 参数绘制，保持系统默认。
     */
    fun passiveFullScreen(
        clearInsets: Boolean,
        alpha: Float = PASSIVE_ALPHA,
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (clearInsets && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
            this.alpha = alpha
        }
    }
}
