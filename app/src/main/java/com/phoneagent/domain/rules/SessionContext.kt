package com.phoneagent.domain.rules

/**
 * 连续对话的会话承接规则（纯逻辑，可单测）。
 *
 * 背景：Agent 页上用户常常连着说几轮话——"帮我在美团点一份黄焖鸡" → "再改一下，不要辣"。
 * 第二轮单独看是读不懂的，必须知道它在指上一轮。这里只负责判断"本轮是否在承接上一轮"，
 * 具体怎么把上一轮任务写进提示词由 [com.phoneagent.engine.AgentPrompts.sessionContext] 负责。
 */
object SessionContext {

    /**
     * 明确的承接/指代词：出现即认定本轮在说上一轮的事。
     * 这些词单独成句时几乎不可能是一个完整的新任务，误判风险低。
     */
    private val STRONG_MARKERS = listOf(
        "接着", "然后", "继续", "刚才", "上面那", "上一个", "这个", "那个", "它",
        "改成", "换成", "去掉", "再加上", "加上", "再来一次", "同样的", "一样的", "同上", "照旧",
    )

    /**
     * 弱承接词"再"：只在短句里才算追问。
     * "再改一下"是追问；"再帮我订一张明天下午的电影票"自带完整目标，不该被上一轮带偏。
     */
    private const val WEAK_MARKER = "再"
    private const val SHORT_TEXT_MAX = 12

    /** 本轮输入是否承接上一轮任务 */
    fun isFollowUp(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (STRONG_MARKERS.any { t.contains(it) }) return true
        return t.contains(WEAK_MARKER) && t.length <= SHORT_TEXT_MAX
    }
}