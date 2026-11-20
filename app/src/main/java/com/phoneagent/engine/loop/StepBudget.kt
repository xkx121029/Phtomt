package com.phoneagent.engine.loop

/**
 * 单步墙钟预算（纯逻辑，可单测）。
 *
 * 为什么需要：观察 / 决策 / 执行三段各有内部超时，但"一整步"没有上限——
 * 一次慢页面 + 两次决策往返 + 动作延迟复核叠起来，用户看到的是"卡住不动"，
 * 而步数计数几乎没走动。把每步墙钟单独度量后，超预算的步会留下
 * "第几步、花了多久"的记录，而不是最后只剩一句"达到最大步数"。
 *
 * 只做软预算，不做硬取消：执行段正处在"动作已下发、等待确认"的窗口时强行取消，
 * 会让下一步在不确定的状态上继续（重复提交的根源）。超预算的处置是记录 + 下一步提示，
 * 真正的停止仍交给既有看门狗与止损机制。
 */
internal object StepBudget {
    /** 单步预算（ms）：普通一步（观察≈1s + 决策≈3-10s + 执行≈1-3s）远低于此值 */
    internal const val BUDGET_MS = 60_000L

    /** 毫秒转秒：提示文案只报秒，避免几十万毫秒的数字让人读不出量级 */
    private const val MS_PER_SECOND = 1000L

    /** 该步耗时是否超出预算 */
    internal fun exceeded(elapsedMs: Long): Boolean = elapsedMs > BUDGET_MS

    /**
     * 超预算时注入下一步决策的提示。
     *
     * 必须给出"下一步怎么变"，只报耗时数字 AI 不会改变行为；
     * 同时避免直接建议"再等一会"——原地等待正是把整步拖长的原因之一。
     */
    internal fun hint(step: Int, elapsedMs: Long): String =
        "注意：第 $step 步耗时 ${elapsedMs / MS_PER_SECOND} 秒" +
            "，已超出单步预算（${BUDGET_MS / MS_PER_SECOND} 秒）。" +
            "先看当前页面是否还在加载；若已加载完仍未推进，不要在同一入口上继续重复，" +
            "换入口、换控件或换一种策略。"
}
