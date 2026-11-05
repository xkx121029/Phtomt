// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.core.ai

/**
 * 决策流解析层的记账（Eta 对标结论之二）。
 *
 * 目的：把「重发占比」与「首 token 占比」变成可读实测值，而不是靠猜。
 * - 重发占比 = 纠错重发次数 / 决策调用次数
 * - 首 token 占比 = 各次首 token耗时合计 / 各次总耗时合计
 * - 增量提前结束 = 边收边解析命中、不等流关闭就直接返回的次数
 * - 本地修复成功 = 完整正文解析失败后、靠本地补全/去尾逗号救回来的次数（省掉一次重发）
 *
 * 只做统计与日志，不改变任何请求、提示词或解析契约。
 */
internal object DecisionStreamStats {

    private val lock = Any()
    private var calls = 0
    private var resends = 0
    private var localRepairs = 0
    private var earlyStops = 0
    private var ttftMsSum = 0L
    private var totalMsSum = 0L
    private var timedCalls = 0

    fun onCall() = synchronized(lock) { calls++ }

    fun onResend() = synchronized(lock) { resends++ }

    fun onLocalRepair() = synchronized(lock) { localRepairs++ }

    fun onEarlyStop() = synchronized(lock) { earlyStops++ }

    fun onTiming(ttftMs: Long, totalMs: Long) = synchronized(lock) {
        timedCalls++
        ttftMsSum += ttftMs.coerceAtLeast(0)
        totalMsSum += totalMs.coerceAtLeast(0)
    }

    fun counts(): Counts = synchronized(lock) {
        Counts(calls, resends, localRepairs, earlyStops, ttftMsSum, totalMsSum, timedCalls)
    }

    /** 一行摘要，直接落应用内日志；调用占比在样本为 0 时按 0% 处理 */
    fun summaryText(): String = synchronized(lock) {
        val resendRatio = if (calls > 0) resends * 100 / calls else 0
        val earlyRatio = if (calls > 0) earlyStops * 100 / calls else 0
        val ttftRatio = if (totalMsSum > 0) ttftMsSum * 100 / totalMsSum else 0
        val avgTtft = if (timedCalls > 0) ttftMsSum / timedCalls else 0
        "决策解析统计：调用 $calls 次，纠错重发 $resends 次（重发占比 ${resendRatio}%），" +
            "本地修复成功 $localRepairs 次，增量提前结束 $earlyStops 次（${earlyRatio}%）；" +
            "首 token 合计 ${ttftMsSum}ms / 总耗时 ${totalMsSum}ms（首 token 占比 ${ttftRatio}%，均值 ${avgTtft}ms）"
    }

    fun reset() = synchronized(lock) {
        calls = 0
        resends = 0
        localRepairs = 0
        earlyStops = 0
        ttftMsSum = 0L
        totalMsSum = 0L
        timedCalls = 0
    }

    internal data class Counts(
        val calls: Int,
        val resends: Int,
        val localRepairs: Int,
        val earlyStops: Int,
        val ttftMsSum: Long,
        val totalMsSum: Long,
        val timedCalls: Int,
    )
}
