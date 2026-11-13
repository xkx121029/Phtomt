package com.phoneagent.overlay

/**
 * 悬浮层的位置计算（纯算术，不含 View / Context）。
 *
 * 从 [FloatingWindowService] 抽出来的原因：这些公式原本内联在窗口代码里，
 * 每一步都对像素（胶囊窗口高度含光晕留白、手势条高度、内边距）做加减，
 * 但**没有任何测试**——而算错一像素的表现是「细条压在胶囊上」或「被手势条挡住」，
 * 只能靠肉眼在真机上发现。抽成纯函数后可以直接断言。
 */
object OverlayGeometry {

    /**
     * 底部跑马灯的落点：约 0.5cm，遇到系统手势条时让到手势区之上。
     *
     * @param navBarInset 系统导航条高度（拿不到时传 0，API 30 以下属于这种情况）
     * @param minGapPx 基础留白（约 0.5cm 对应的 px）
     * @param glowPadPx 窗口高度比胶囊体多出的光晕留白，落点要减掉这一截
     */
    fun marqueeBottomOffset(navBarInset: Int, minGapPx: Int, glowPadPx: Int): Int =
        (maxOf(navBarInset, minGapPx) - glowPadPx).coerceAtLeast(0)

    /**
     * 胶囊体本身的厚度：窗口测量高度含上下两圈光晕留白，扣掉才是视觉厚度。
     *
     * @param windowHeight 胶囊窗口的测量高度；<= 0 表示还没布局好（或不可见），按 [fallbackPx] 兜底
     */
    fun capsuleThickness(windowHeight: Int, glowPadPx: Int, fallbackPx: Int): Int =
        if (windowHeight > 0) (windowHeight - 2 * glowPadPx).coerceAtLeast(0) else fallbackPx

    /**
     * 降级提示细条的 y 坐标：落在胶囊**上方**，间隔一个内边距。
     *
     * @param capsuleVisible 胶囊当前是否可见；不可见时细条仍要留出同样的空间，
     *   否则细条会在"胶囊隐藏"的瞬间向下跳一截
     */
    // 参数偏多是有意的：这里刻意不做成 data class，避免为一个公式引入一个类型；
    // 调用点已用命名参数传值，可读性由名字保证。
    @Suppress("LongParameterList")
    fun noticeBottomOffset(
        marqueeBottomPx: Int,
        capsuleVisible: Boolean,
        windowHeight: Int,
        glowPadPx: Int,
        fallbackCapsulePx: Int,
        gapPx: Int,
    ): Int {
        val thickness = if (capsuleVisible) {
            capsuleThickness(windowHeight, glowPadPx, fallbackCapsulePx)
        } else {
            fallbackCapsulePx
        }
        return marqueeBottomPx + thickness + gapPx
    }
}
