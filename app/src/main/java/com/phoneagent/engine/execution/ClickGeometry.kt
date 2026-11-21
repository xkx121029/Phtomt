package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ScreenSnapshot

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

    /** 一次手势候选落点：名称（写进执行日志，出现"点错位置"时靠它分清是哪一路算出来的）+ 像素坐标 */
    data class Candidate(val name: String, val point: Pair<Int, Int>)

    /**
     * 手势落点候选（按优先级从高到低）：活节点位置 → 元素最新位置 → 快照坐标。
     *
     * 几何基准（安全区、屏幕矩形）取**执行前新抓的** [fresh]，不取 AI 观察那一刻的 [observed]：
     * 元素坐标本身来自新树，若钳制还用观察时刻的屏幕尺寸，横竖屏切换后同一个 bounds 会被平移几百像素
     * （审计验算：横屏后同一 bounds 被夹平移 845px，点到完全不同的控件）。抓不到新快照时才退回 [observed]。
     *
     * 元素落点按「元素 ∩ 安全区」夹取（[clampInto]），快照坐标没有控件可依，只夹进安全区。
     */
    fun gesturePoints(
        liveCenter: Pair<Int, Int>?,
        elementCenter: Pair<Int, Int>?,
        target: Rect?,
        snapshotPoint: Pair<Int, Int>?,
        observed: ScreenSnapshot,
        fresh: ScreenSnapshot?,
        statusBarInset: Int,
        navBarInset: Int,
    ): List<Candidate> {
        val geometry = fresh?.takeIf { it.screenWidth > 0 && it.screenHeight > 0 } ?: observed
        val safe = safeArea(geometry.screenWidth, geometry.screenHeight, statusBarInset, navBarInset)
        val screen = Rect(0, 0, geometry.screenWidth, geometry.screenHeight)
        val points = ArrayList<Candidate>(3)
        liveCenter?.let { points += Candidate("活节点当前位置", clampInto(it.first, it.second, target, safe, screen)) }
        elementCenter?.let { points += Candidate("元素最新位置", clampInto(it.first, it.second, target, safe, screen)) }
        snapshotPoint?.let { points += Candidate("快照坐标", clampInto(it.first, it.second, null, safe, screen)) }
        return points.distinctBy { it.point }
    }

    /**
     * 屏幕**安全区**（而非整块物理屏）：四边各留 [EDGE_SAFE_MARGIN]，上下再避开系统栏。
     *
     * 不能退而求其次夹到 y=0（状态栏）或 y=h-1（导航条）：那等于把"点不到目标"变成"误触系统栏"，
     * 失败之外还多一点副作用。系统栏高度读不到（部分 ROM 返回 0）时用最小边距兜底，
     * 保证落点不会等于 0 或 h-1。
     */
    fun safeArea(width: Int, height: Int, statusBarInset: Int, navBarInset: Int): Rect {
        val w = width.takeIf { it > 0 } ?: DEFAULT_SCREEN_W
        val h = height.takeIf { it > 0 } ?: DEFAULT_SCREEN_H
        val left = EDGE_SAFE_MARGIN
        val right = (w - 1 - EDGE_SAFE_MARGIN).coerceAtLeast(left)
        val top = maxOf(statusBarInset, EDGE_SAFE_MARGIN)
        val bottom = (h - 1 - maxOf(navBarInset, EDGE_SAFE_MARGIN)).coerceAtLeast(top)
        return Rect(left = left, top = top, right = right + 1, bottom = bottom + 1)
    }

    /** 屏幕尺寸读不到时的兜底尺寸（仅用于把落点夹进屏幕内） */
    private const val DEFAULT_SCREEN_W = 1080
    private const val DEFAULT_SCREEN_H = 2400

    /** 落点安全区的四边最小边距（像素）：避开屏幕边缘，防止贴边误触 */
    private const val EDGE_SAFE_MARGIN = 24

    /** 元素矩形与安全区的可见交集；null 表示元素完全不可见（无从点击） */
    fun visiblePart(target: Rect, safe: Rect): Rect? = target.intersect(safe).takeUnless { it.isEmpty }

    private fun coerce(x: Int, y: Int, rect: Rect): Pair<Int, Int> =
        x.coerceIn(rect.left, maxOf(rect.left, rect.right - 1)) to
            y.coerceIn(rect.top, maxOf(rect.top, rect.bottom - 1))
}
