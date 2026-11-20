package com.phoneagent.engine.execution

/**
 * 点击落点几何（纯逻辑，可单测）。
 *
 * 对应真实失败：点击命中区偏移——点到了目标旁边的控件。
 *
 * 旧做法只把落点"夹进整块屏幕的安全区"。当目标控件只露出一部分（列表底部半截可见的行、
 * 贴边的小图标）时，夹到屏幕边缘的落点可能已经**离开控件本身**：比如控件横跨 x∈[-100,10]，
 * 只露右边 10px，夹进边距 24 后 x=24 落在控件右侧，实际点到的是相邻控件。
 *
 * 正确优先级：目标控件 ∩ 安全区 → 目标控件 ∩ 整屏 → 安全区。
 * 只要控件还有可见部分，落点就必须落在它身上；产不出交集时才退回安全区，
 * 此时本来就点不到目标，交给落点审计把差异写进失败原因。
 */
internal object ClickGeometry {

    /** 整数矩形；right/bottom 为独占边界（与 Android bounds 同口径） */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = width <= 0 || height <= 0

        fun intersect(other: Rect): Rect = Rect(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        )
    }

    /**
     * 把落点夹进目标区域。
     *
     * @param target 目标控件的 bounds；null = 这次落点不是按控件算的（快照坐标），直接夹进安全区
     * @param safe 屏幕安全区（避开状态栏/导航条/左右边距）
     * @param screen 整块屏幕（0,0,w,h），用于"控件露在边距里"这种退化情形
     */
    fun clampInto(x: Int, y: Int, target: Rect?, safe: Rect, screen: Rect): Pair<Int, Int> {
        // 优先落在"控件 ∩ 安全区"；控件被边距整段切掉时，退到"控件 ∩ 整屏"——
        // 露在边距里的那一小条仍然是控件本身，比落到控件外面点到邻居强
        val area = target?.intersect(safe)?.takeUnless { it.isEmpty }
            ?: target?.intersect(screen)?.takeUnless { it.isEmpty }
            ?: safe
        return coerce(x, y, area)
    }

    /** 元素矩形与安全区的可见交集；null 表示元素完全不可见（无从点击） */
    fun visiblePart(target: Rect, safe: Rect): Rect? = target.intersect(safe).takeUnless { it.isEmpty }

    private fun coerce(x: Int, y: Int, rect: Rect): Pair<Int, Int> =
        x.coerceIn(rect.left, maxOf(rect.left, rect.right - 1)) to
            y.coerceIn(rect.top, maxOf(rect.top, rect.bottom - 1))
}
