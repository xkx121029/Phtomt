package com.phoneagent.domain.rules

/**
 * 手势时长预算（纯逻辑，可单测）。
 *
 * AI 通过 `duration_ms` 指定的按压/滑动时长不可信：
 * - 太短触发不了长按，表现出来是"点了没反应"，AI 会再点一遍；
 * - 太长会把整步卡住——长按 60 秒意味着执行链 60 秒不观察页面，期间页面早已变化，
 *   后续的"是否生效"判定全部基于过期状态。
 *
 * 因此在动作执行入口统一夹进可用区间；null / 非正数一律走默认（保持既有语义）。
 */
object GestureBudget {

    /** 未指定时长时的默认长按按压时长（800ms 足够触发系统长按判定） */
    const val DEFAULT_LONG_PRESS_MS = 800L

    /**
     * 长按下限：必须**跨过系统的长按判定阈值**（`ViewConfiguration.getLongPressTimeout()` 默认 500ms），
     * 否则抬上来的时长照样触发不了长按，只会被当成一次普通点击——AI 传 300ms 想长按，
     * 实际点了一下，页面没变化，AI 再点一遍。
     *
     * 取 600 而不是正好 500：留 100ms 余量吸收不同 ROM 把阈值调高 / 手势派发与系统计时之间的抖动。
     * 副屏（`input swipe x y x y <ms>`）与主屏手势都从这里取时长，改这一处两条链路同时生效。
     */
    const val MIN_LONG_PRESS_MS = 600L

    /** 长按上限：再长就是卡住执行链，没有额外收益 */
    const val MAX_LONG_PRESS_MS = 3000L

    /** 未指定时长时的默认滑动时长 */
    const val DEFAULT_SWIPE_MS = 400L

    /** 太短的滑动会被系统当成 fling 忽略 */
    const val MIN_SWIPE_MS = 100L

    /** 滑动上限：再长就变成"缓慢拖动"，可能被识别为长按/拖拽而非滑动 */
    const val MAX_SWIPE_MS = 3000L

    /** 长按按压时长：null/非正数走默认，其余夹进可用区间 */
    fun holdMs(requested: Long?): Long =
        if (requested == null || requested <= 0L) {
            DEFAULT_LONG_PRESS_MS
        } else {
            requested.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
        }

    /** 滑动时长：null/非正数走默认，其余夹进可用区间 */
    fun swipeMs(requested: Long?): Long =
        if (requested == null || requested <= 0L) {
            DEFAULT_SWIPE_MS
        } else {
            requested.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        }
}
