package com.phoneagent.domain.rules

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType

/**
 * 意图 → 页面路径边上的动作文案（纯函数，可单测）。
 *
 * 这段文案落在页面记忆的路径边上（「上页 —[动作]→ 本页」），并与提示注入共用；
 * 同一控件用 id 或文字定位时必须得到同一句文案，路径边才能按「文案 + 起止」合并成一条。
 *
 * 原实现内联在 PageMemoryRecorder 里（复杂度 15 的 when）；下沉后
 * 采集器只负责取数，文案口径独立可测。
 */
object PageActionLabels {

    /** 文案里控件/应用名的最大字符数：过长会把任务流与记忆列表撑爆 */
    private const val NAME_MAX_CHARS = 12

    /** 统一记为「点击」的意图：都等价于对某控件按一下 */
    private val CLICK_INTENTS = setOf(
        IntentType.TAP, IntentType.LONG_PRESS,
        IntentType.SEARCH, IntentType.SEND, IntentType.CONFIRM, IntentType.CLOSE,
        IntentType.SHARE, IntentType.COLLECT, IntentType.COPY, IntentType.DELETE,
        IntentType.DOWNLOAD, IntentType.ADD, IntentType.SWITCH, IntentType.CLEAR_INPUT,
        IntentType.REFRESH, IntentType.SCROLL_TO,
    )

    /** 不带控件文案的固定动作 */
    private val FIXED_LABELS = mapOf(
        IntentType.OPEN to "直达页面",
        IntentType.BACK to "返回",
        IntentType.HOME to "回桌面",
        IntentType.BROWSE_OPEN to "打开网页",
    )

    /**
     * @param idToLabel 语义 id → 控件文字的反查表：AI 用 `by=id` 定位时把 id 还原成文字，
     *        保证与 `by=text` 定位得到同一句动作文案
     */
    fun of(intent: AgentIntent, idToLabel: Map<String, String>): String {
        val raw = intent.target?.value?.take(NAME_MAX_CHARS).orEmpty()
        val target = if (intent.target?.by == "id") {
            idToLabel[intent.target?.value]?.take(NAME_MAX_CHARS) ?: raw
        } else {
            raw
        }
        val quoted = if (target.isBlank()) "" else "『$target』"
        return when {
            intent.intent in CLICK_INTENTS -> "点击$quoted"
            intent.intent == IntentType.BROWSE_CLICK -> "点击网页元素$quoted"
            intent.intent == IntentType.INPUT -> "输入$quoted"
            intent.intent == IntentType.OPEN_APP -> "打开${intent.app.orEmpty().take(NAME_MAX_CHARS)}"
            intent.intent == IntentType.SWIPE -> "滑动${intent.direction.orEmpty()}"
            intent.intent == IntentType.PRESS -> "按键${intent.key.orEmpty()}"
            else -> FIXED_LABELS[intent.intent].orEmpty()
        }
    }
}
