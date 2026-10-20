package com.phoneagent.feature.assistant

/**
 * 数字助理上下文暂存：系统经长按电源键/手势唤起本应用时，
 * 会把当前屏幕的 AssistStructure（元素树，无需无障碍）带进来，这里暂存最近一份。
 *
 * 引擎决策时若这份上下文仍然新鲜（< [FRESH_MS]），注入一段「用户唤起时正看着的页面」，
 * 让"长按电源键→直接对着当前页面下任务"成为闭环。
 */
object AssistContextStore {

    /** 上下文有效期：超过视为过期，不再注入（用户唤起后可能已经乱逛了几页） */
    const val FRESH_MS = 90_000L

    /** 注入文本上限：AssistantStructure 摘要截断，防长上下文挤占决策窗口 */
    const val MAX_CHARS = 1200

    @Volatile
    private var screenText: String = ""

    @Volatile
    private var capturedAt: Long = 0L

    fun put(text: String) {
        screenText = text.take(MAX_CHARS)
        capturedAt = System.currentTimeMillis()
    }

    /** 新鲜度内的暂存上下文；过期/为空返回空串（调用方按空串跳过注入） */
    fun freshText(): String {
        if (screenText.isBlank()) return ""
        if (System.currentTimeMillis() - capturedAt > FRESH_MS) return ""
        return screenText
    }
}
