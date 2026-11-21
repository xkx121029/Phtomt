package com.phoneagent.engine.execution

/**
 * 无人值守下的降级策略表（候选 → 生效条件 → 失败判据 → 用户可见说明）。
 *
 * 这张表解决的是「降级发生了，但用户不知道现在是什么方式」：
 * 通道阶梯、超时看门狗、无进展看门狗各自都在降级，以前只有日志里能看到，
 * 用户看到的往往是「失败」两个字。这里统一成四种触发原因，每种都自带一句
 * **点名当前方式**的中文说明，调用方只负责把 [notice] 的结果带出去。
 *
 * 刻意不做的事：
 * - 不在这里执行降级（执行仍由 [ChannelLadder] / 主循环看门狗负责），避免策略与副作用耦合；
 * - 不在说明里写"请稍候""正在处理"这类无信息量的话，必须说清楚换了什么方式。
 */
internal enum class DegradationTrigger(val key: String, val label: String) {
    /** 权限缺失：无障碍/悬浮窗/通知等被门控拦下 */
    PERMISSION_MISSING("permission_missing", "权限缺失"),

    /** 通道不可用：当前通道未交付，阶梯切到下一条 */
    CHANNEL_UNAVAILABLE("channel_unavailable", "通道不可用"),

    /** 模型超时：云端决策/视觉超过看门狗阈值 */
    MODEL_TIMEOUT("model_timeout", "模型超时"),

    /** 页面卡死：同签名动作重复或长时间无进展 */
    PAGE_STUCK("page_stuck", "页面卡死"),

    /** 输入未提交：把内容填进输入框后直接宣称完成，页面/后端从未收到提交 */
    UNCOMMITTED_INPUT("uncommitted_input", "输入未提交"),

    /** 依据瞬时提示：把 toast/绿勾这类过渡态当成完成状态，没有核对持久页面状态 */
    TRANSIENT_EVIDENCE("transient_evidence", "依据瞬时提示"),
}

internal data class DegradationRule(
    val trigger: DegradationTrigger,
    /** 什么情况下触发这一条 */
    val condition: String,
    /** 自动降级到哪（必须能继续，而不是停下等人） */
    val fallback: String,
    /** 失败判据：什么情况下不再继续降级、改为明确求助 */
    val failureCriterion: String,
)

internal object DegradationPolicy {

    val rules: List<DegradationRule> = listOf(
        DegradationRule(
            trigger = DegradationTrigger.PERMISSION_MISSING,
            condition = "动作被权限门控拒绝，或无障碍/悬浮窗/通知开关处于关闭状态",
            fallback = "按通道阶梯改用内置 Shizuku；启动类再退到本应用直启",
            failureCriterion = "所有候选通道都不可用，或该能力本身就要求被拒的权限",
        ),
        DegradationRule(
            trigger = DegradationTrigger.CHANNEL_UNAVAILABLE,
            condition = "当前通道返回「未交付」（超时、启动被拦、节点失效）",
            fallback = "切到下一条候选通道，健康度低的通道自动后移",
            failureCriterion = "候选全部未交付，且没有本应用直启等本地兜底",
        ),
        DegradationRule(
            trigger = DegradationTrigger.MODEL_TIMEOUT,
            condition = "云端决策超过 45s，或视觉分析超过 25s 未返回",
            fallback = "本步改为等待/端侧决策，下一轮自动重试云端",
            failureCriterion = "连续异常达到上限，收尾并向用户说明链路不可用",
        ),
        DegradationRule(
            trigger = DegradationTrigger.PAGE_STUCK,
            condition = "同签名动作连续 4 次，或长时间没有完成任何一步",
            fallback = "第 4 次注入换做法提示，第 6 次升级为请求用户指导",
            failureCriterion = "纠偏后仍重复，或达到无进展上限",
        ),
        DegradationRule(
            trigger = DegradationTrigger.UNCOMMITTED_INPUT,
            condition = "输入框已填入内容，但没有执行提交/发送/保存就宣称完成",
            fallback = "拒绝完成并注入提交纠偏提示；达到拒绝上限后按完成处理，同时在任务记忆里标注未提交",
            failureCriterion = "连续拒绝后仍未提交（记为未确认成功，不当作干净完成）",
        ),
        DegradationRule(
            trigger = DegradationTrigger.TRANSIENT_EVIDENCE,
            condition = "完成依据是 toast/绿勾这类瞬时提示，再观察一次已消失，或页面上根本没有这段文字",
            fallback = "拒绝完成一次并注入证据纠偏，要求以持久页面状态（列表条目/播放控制/记录）确认",
            failureCriterion = "纠偏后仍只凭提示语宣称完成",
        ),
    )

    fun ruleOf(trigger: DegradationTrigger): DegradationRule =
        rules.first { it.trigger == trigger }

    /**
     * 生成一条点名当前方式的中文说明。
     *
     * @param detail 触发细节（原通道名、超时秒数、重复次数等）
     * @param channel 现在实际使用的方式（为空表示没有可用方式）
     */
    // 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免；
    // 分档本身是一条 trigger→文案 的映射链，拆成多个函数会让「哪档说什么」更难对照
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun notice(
        trigger: DegradationTrigger,
        detail: String? = null,
        channel: String? = null,
        /**
         * 换到新方式之后**有没有拿到页面级证据**。
         *
         * true / false / null 三态都要能表达：null 表示调用方没有证据可言，
         * 此时按「未确认」说——宁可不宣称，也不让用户自己去推断。
         * 结构性区分（[com.phoneagent.engine.execution.VerifyResult.delivery]）如果不在文案里体现，
         * 对用户就等于没有做。
         */
        confirmed: Boolean? = null,
    ): String {
        val why = detail?.takeIf { it.isNotBlank() } ?: ruleOf(trigger).condition
        val now = channel?.takeIf { it.isNotBlank() }
        return when (trigger) {
            DegradationTrigger.PERMISSION_MISSING -> if (now != null) {
                "相关权限未开启（$why），已改用${now}继续执行。"
            } else {
                "相关权限未开启（$why），没有可替代的通道，需要你授权后才能继续。"
            }

            DegradationTrigger.CHANNEL_UNAVAILABLE -> if (now != null) {
                if (confirmed == true) {
                    "通道切换：$why 未交付，已改用${now}执行并确认生效。"
                } else {
                    "通道切换：$why 未交付，已改用${now}执行，未确认生效（没有拿到页面级证据）。"
                }
            } else {
                "通道切换：$why 未交付，所有候选通道都不可用；已保留现场并按中文原因停止。"
            }

            DegradationTrigger.MODEL_TIMEOUT -> "云端决策超时（$why），本步已改用${now ?: "等待后重试"}" +
                "继续，下一轮会自动重试云端。"

            DegradationTrigger.PAGE_STUCK -> "页面连续（$why）没有变化，已改用${now ?: "换一种做法"}" +
                "继续；仍无进展时会停下来征求你的意见。"

            DegradationTrigger.UNCOMMITTED_INPUT -> "「$why」的内容还没有提交，已${now ?: "要求先提交"}" +
                "；在此之前页面读到的仍是未提交状态。"

            DegradationTrigger.TRANSIENT_EVIDENCE -> "完成依据「$why」是瞬时提示，已${now ?: "要求重新核对持久状态"}" +
                "；提示语消失后的页面状态才是判据。"
        }
    }
}
