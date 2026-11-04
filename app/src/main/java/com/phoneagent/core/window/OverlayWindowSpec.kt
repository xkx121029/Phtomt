package com.phoneagent.core.window

import android.content.Context
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
 * 坐标系对齐（四条缺一不可，参照 Eta 的覆盖层实现）：
 * 真实屏幕尺寸（而非 MATCH_PARENT）+ `fitInsetsTypes = 0` + 刘海模式 ALWAYS +
 * `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`。窗口类型保持 TYPE_APPLICATION_OVERLAY，
 * 不要换成 TYPE_ACCESSIBILITY_OVERLAY——后者与无障碍服务存活强绑定，服务被系统收走浮层直接消失。
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
     * 覆盖层窗口类型：固定 TYPE_APPLICATION_OVERLAY（API 26+），低版本退回 TYPE_PHONE。
     *
     * 刻意不用 TYPE_ACCESSIBILITY_OVERLAY：后者与无障碍服务存活强绑定，
     * 服务被系统收走时整块浮层会直接消失；跑马灯、边缘光这类显示层必须独立存活。
     */
    fun overlayWindowType(): Int = overlayWindowTypeFor(Build.VERSION.SDK_INT)

    /** 按 API 级别取窗口类型（抽成纯函数便于单测：跑马灯必须始终是 APPLICATION_OVERLAY） */
    fun overlayWindowTypeFor(sdkInt: Int): Int = if (sdkInt >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    /**
     * 被动覆盖层的固定 FLAG 组合。
     *
     * `LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS` 让窗口坐标从物理屏原点算起、允许越出系统栏；
     * 缺一组都会让顶部光带整体下移一条（状态栏高度）。
     */
    fun passiveFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    /**
     * 顶部光带高度（px）：取状态栏 + 挖孔 inset 与最小可读高度的较大值。
     *
     * 光带要「压过」状态栏，高度就不能比 inset 小；但 inset 很矮的机型上还得放得下 14sp 文字。
     */
    fun marqueeBandHeightPx(statusBarInsetPx: Int, minHeightPx: Int): Int =
        maxOf(statusBarInsetPx, minHeightPx)

    /**
     * 底部跑马灯窗口宽度（px）：真实屏宽去掉两侧留白，**建窗时一次定死**。
     *
     * 文本高频更新时窗口宽度不变，就不会触发窗口重排；胶囊宽度只在绘制层随文字变。
     */
    fun marqueeWidthPx(screenWidthPx: Int, sideMarginPx: Int): Int =
        (screenWidthPx - 2 * sideMarginPx).coerceAtLeast(sideMarginPx * 2 + 1)

    /**
     * 构建全屏、不拦截触摸、不抢焦点的被动覆盖层参数。
     *
     * @param clearInsets API 30+ 是否清空 fitInsetsTypes。清空后窗口坐标系从物理屏顶开始，
     *        与元素树 / 手势 / Shizuku input 使用的物理坐标一致。
     *        **边缘光效也必须清空**：否则窗口内容区从状态栏下沿开始，光带画到自身 y=0
     *        在物理屏上仍落在状态栏下方（2026-11 定位的「顶部光带下移一条」根因）。
     * @param screenSize 真实屏幕尺寸（px）。传入后不再用 MATCH_PARENT：部分设备的
     *        MATCH_PARENT 不含系统栏，窗口原点与物理屏左上角不重合，光带会整体偏移。
     */
    fun passiveFullScreen(
        clearInsets: Boolean,
        alpha: Float = PASSIVE_ALPHA,
        screenSize: Pair<Int, Int>? = null,
    ): WindowManager.LayoutParams {
        val width = screenSize?.first ?: WindowManager.LayoutParams.MATCH_PARENT
        val height = screenSize?.second ?: WindowManager.LayoutParams.MATCH_PARENT
        return WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            passiveFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 刘海/挖孔屏：允许窗口铺进 cutout，否则顶部仍会留一条不能绘制的黑边
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (clearInsets && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+ 默认 fitInsetsTypes = systemBars()：不改会把内容整体推到状态栏下方，
                // 与 FLAG_LAYOUT_NO_LIMITS 是两套机制；清空后 y=0 才真正等于物理屏顶
                fitInsetsTypes = 0
            }
            this.alpha = alpha
        }
    }

    /**
     * 真实屏幕尺寸（含系统栏与刘海），用于覆盖层窗口的原点对齐。
     *
     * MATCH_PARENT 在部分设备/窗口类型下按「可用区域」计算，不含状态栏与导航栏；
     * 覆盖层要画到物理屏边缘，必须显式给出真实像素尺寸。
     */
    fun realScreenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point.x to point.y
        }
    }
}
