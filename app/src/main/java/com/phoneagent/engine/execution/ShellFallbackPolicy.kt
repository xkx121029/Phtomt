package com.phoneagent.engine.execution

/**
 * shell 兜底的交付定性（纯逻辑，可单测）。
 *
 * 为什么需要这一层：通道阶梯切到「系统命令兜底」时，能拿到的唯一信号是**命令的退出码**。
 * 但退出码 0 只证明事件被系统接受，不证明它在 UI 上生效：
 * - `input tap x y` 退出 0 表示这次触摸被派发进去了，既不代表落点是目标控件，也不代表目标有任何反应；
 * - `monkey -p pkg 1` 退出 0 也不代表应用真的到了前台（部分 ROM 静默拦截后台 startActivity 时同样退出 0，
 *   这正是 [com.phoneagent.engine.execution.ExecutionCore] 的启动阶梯要回读前台的原因）。
 *
 * 所以「已交付」和「已确认生效」必须分开，而且只有回读页面这一个判据：
 * - 已交付：命令被系统接受（退出码 0）。这是调用方**已经确认过**的前提，本策略只在此时被调用；
 *   它在结构化结果里表达为 `VerifyResult.notDelivered = false`，决定要不要换通道；
 * - 已确认（[Verdict.confirmed]）：兜底之后回读页面，看到页面真的发生了变化。它决定这一步算不算成功。
 *
 * 没有页面证据时按「已交付未确认」如实回报。两个方向都不能走：
 * - 报成功 → AI 与用户都以为这一步生效了，后续步骤建在假前提上；
 * - 报未交付 → 外层换通道把同一个动作再执行一遍，对发送/删除这类按钮就是重复副作用。
 */
internal object ShellFallbackPolicy {

    /**
     * 一次兜底尝试的定性。
     *
     * 刻意不设 `dispatched` 字段：本策略只在「命令全部退出码 0」之后才被调用，交付这件事已经是既定前提，
     * 由调用方在 `VerifyResult.notDelivered = false` 上表达。再存一份只会出现两个真相。
     */
    internal data class Verdict(
        /** 兜底后是否回读到页面变化——唯一决定这步算不算成功的判据 */
        val confirmed: Boolean,
        /** 追加在失败/成功原因末尾的说明，点名到底有没有拿到证据 */
        val suffix: String,
        /** 页面级证据的指纹（读不到为空串），供执行留档与排障 */
        val beforeFingerprint: String = "",
        val afterFingerprint: String = "",
    )

    /**
     * 判定一次兜底是否真的生效。
     *
     * @param beforeFingerprint 兜底**之前**的页面指纹；空/空白 = 那一刻读不到页面
     * @param afterFingerprint 兜底**之后**回读到的页面指纹；空/空白 = 回读失败
     */
    fun judge(beforeFingerprint: String?, afterFingerprint: String?): Verdict {
        val before = if (beforeFingerprint.isNullOrBlank()) null else beforeFingerprint
        val after = if (afterFingerprint.isNullOrBlank()) null else afterFingerprint
        if (before == null) {
            // 没有基准就没有证据：不能拿退出码冒充生效
            return Verdict(false, "（已交付，但兜底前的页面读不到，无法比对是否生效）", "", after.orEmpty())
        }
        if (after == null) {
            return Verdict(false, "（已交付，但兜底后读不到页面，未能确认生效）", before, "")
        }
        if (before != after) {
            return Verdict(true, "（已确认页面变化）", before, after)
        }
        return Verdict(false, "（已交付，但页面没有变化，未能确认生效）", before, after)
    }
}
