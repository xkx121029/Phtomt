package com.phoneagent.device.a11y

/**
 * 滚动手势几何（纯逻辑，可单测）。
 *
 * 对应真机失败：分屏下"往下滚"变成往上滚；元素贴边时用 1px 位移"补救"，
 * 而 1px 远小于触摸阈值，系统判成一次点击——页面没滚，`scroll` 却返回成功。
 *
 * 两条规则：
 * 1. **只用一个坐标空间**。元素 bounds 来自 `getBoundsInScreen`，属**屏幕坐标系**，
 *    所以起止点与钳制一律按屏幕物理尺寸算。过去钳制用的是
 *    `applicationContext.resources.displayMetrics`——那是本应用**窗口**的尺寸，
 *    分屏/悬浮窗下比屏幕小，窗口内的 y 与屏幕 y 不是同一个刻度，钳制会把起点拽到别处，
 *    方向都可能反过来。
 * 2. **不拿 1px 充当位移**。位移必须远大于触摸阈值（touch slop，约 8dp），否则就是一次点击。
 *    起点贴边时把**起点挪进屏内**、位移保持不变；屏幕小到连最小位移都放不下就明确失败
 *    （[plan] 返回 null），不用"勉强划一下"冒充成功。
 */
internal object ScrollGesture {

    /** 一次滚动的位移（像素）：约 1/4 到 1/2 屏，够翻动内容又不会把列表甩飞 */
    const val STEP_PX = 600

    /** 小于该位移就不是滚动而是点击（触摸阈值约 8dp≈24px，这里留一倍余量） */
    const val MIN_TRAVEL_PX = 48

    /** 滚动手势时长（与 [GestureBudget.swipeMs] 的默认值同量级） */
    const val DURATION_MS = 300L

    /** 一次滚动的起止点（屏幕坐标）；y1 是按下点，y2 是抬起点 */
    data class Stroke(val x: Int, val y1: Int, val y2: Int) {
        val travel: Int get() = kotlin.math.abs(y2 - y1)
    }

    /**
     * 排一次滚动手势。
     *
     * @param centerX/centerY 目标元素中心（**屏幕坐标**）；null = 没有元素，按屏幕中心划
     * @param width/height 屏幕**物理**尺寸（像素）
     * @param direction up = 手指自下而上（内容往下翻、看到后面的内容）；其余按 down
     * @return null = 这个屏幕放不下一次有效位移，调用方必须报失败
     */
    fun plan(centerX: Int?, centerY: Int?, width: Int, height: Int, direction: String): Stroke? {
        if (width <= 1 || height <= 1) return null
        val travel = minOf(STEP_PX, height - 1)
        if (travel < MIN_TRAVEL_PX) return null
        val x = (centerX ?: width / 2).coerceIn(0, width - 1)
        val start = centerY ?: height / 2
        return if (direction == "up") {
            // 起点不能低于 travel，否则抬起点会越出屏幕顶端
            val y1 = start.coerceIn(travel, height - 1)
            Stroke(x, y1, y1 - travel)
        } else {
            val y1 = start.coerceIn(0, height - 1 - travel)
            Stroke(x, y1, y1 + travel)
        }
    }
}
