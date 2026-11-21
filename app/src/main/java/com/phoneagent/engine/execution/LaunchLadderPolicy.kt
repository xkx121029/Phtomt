package com.phoneagent.engine.execution

/**
 * 启动阶梯每一级的定性（纯逻辑，可单测）。
 *
 * 为什么需要这一层：`awaitLaunched` 的结论本来就是三态，而三个调用点过去都把它折成
 * `VerifyResult(success = true)` + `Delivered(confirmed = false)`——同一个结果里两个出口
 * 说法相反：执行历史写 ✅、进度与检查点照存、失败计数每步清零（于是「连续失败请求用户介入」
 * 永远不会触发），而通道阶梯与降级文案说的是「未确认生效」。
 *
 * 三态在这里各自落到结构上，不再由一句「已启动」文案顶替：
 * - `true`  → 已确认：唯一能报成功的分支；
 * - `null`  → 已交付、未确认：`success=false` + `unconfirmed=true` + `notDelivered=false`。
 *   既不记成功（不清失败计数），也不换通道重跑（应用可能已经被拉起，重跑只会多弹一次）；
 * - `false` → 没交付：交给通道阶梯换下一级。
 */
internal object LaunchLadderPolicy {

    /** 一级启动尝试的结论 */
    internal data class Verdict(
        /** 非空 = 这一级的验证结果；null = 没交付，换下一级通道 */
        val result: VerifyResult?,
        /** 交给通道阶梯的步骤结论 */
        val step: ChannelStepResult,
    )

    /**
     * @param observed [ExecutionCore.awaitLaunched] 的返回值
     * @param confirmedReason 确认到前台时的原因文案
     * @param unconfirmedReason 读不到页面时的原因文案（必须自明：已交付、未确认）
     * @param notDeliveredReason 观察窗内没见到目标应用前台的原因
     */
    // 三态各自的原因文案必须由调用方给（那条通道才知道"为什么没确认"），
    // 压成一个枚举 + 文案表会把调用点变成查表
    @Suppress("LongParameterList")
    fun verdict(
        observed: Boolean?,
        confirmedReason: String,
        unconfirmedReason: String,
        notDeliveredReason: String,
        beforeFingerprint: String = "",
        afterFingerprint: String = "",
    ): Verdict = when (observed) {
        true -> Verdict(
            VerifyResult(true, confirmedReason, beforeFingerprint, afterFingerprint),
            ChannelStepResult.Delivered(confirmed = true),
        )
        // 读不到页面：动作确实已经下发，但既不能确认生效、也没有证据说它没生效
        null -> Verdict(
            VerifyResult(
                false,
                unconfirmedReason,
                beforeFingerprint,
                afterFingerprint,
                notDelivered = false,
                unconfirmed = true,
            ),
            ChannelStepResult.Delivered(confirmed = false),
        )
        false -> Verdict(null, ChannelStepResult.NotDelivered(notDeliveredReason))
    }
}
