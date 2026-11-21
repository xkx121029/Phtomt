package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction

/**
 * 执行结果的后处理判据与呈现（Verifier 角色的纯逻辑部分，无 Android 依赖，可直接单测）。
 *
 * 拆分目的：[AgentEngine] 原先在 600 行主循环里内联判断"这次失败要不要重试""这条错误还算不算
 * 结构性错误""这一步在历史里记成什么状态"。这些判据原先全部依赖 `verify.reason` 这种**文案**，
 * 一旦哪条提示词改了字，重试行为会跟着悄悄变——夹在主循环里既看不见也没法验证。
 * 现在重试只看结构化字段（[VerifyResult.delivery] / [VerifyResult.structural]），
 * "改文案是否影响重试"成为一条可断言的规则。
 *
 * 只做判定与拼装：真正的执行、重试循环、状态写入仍留在引擎。
 */
internal object VerifyRules {

    /**
     * 外层重试上限（含首次执行，即最多尝试 [MAX_ATTEMPTS] 次）。
     *
     * 点击类动作走 [com.phoneagent.engine.execution.ClickRunner] 内部的分层重试，不参与外层重试
     * （见 [shouldRetry]），所以这个上限只约束滑动/滚动/输入这类"执行一次、看页面变不变"的动作。
     */
    const val MAX_ATTEMPTS = 3

    /**
     * 「无障碍通道不支持直接刷新」的统一文案。
     *
     * 产出方（ExecutionCore 的 REFRESH 分支）与提示语共用同一常量：
     * 原先判定方按文案匹配识别这类失败，现在改由 [VerifyResult.structural] 显式标注，
     * 文案只负责告诉 AI 下一步怎么做。
     */
    const val NO_A11Y_REFRESH = "无障碍通道暂不支持直接刷新"

    /**
     * 是否属于点击类动作。
     *
     * [ClickRunner] 内部已经把"活节点直点 → 手势点最新位置 → 快照坐标 → 滚动查找"逐级穷尽过一遍，
     * 外层再重跑一遍只是把同一串动作重复执行（还可能造成重复副作用，如重复提交）。
     */
    fun isClickLike(type: String): Boolean = when (type) {
        ActionType.CLICK, ActionType.TAP, ActionType.LONG_CLICK, ActionType.LONG_PRESS -> true
        else -> false
    }

    /**
     * 外层是否应当重试这一动作。
     *
     * @param attempt 当前已尝试次数（首次执行后为 1）
     * @param active 协程是否仍在运行：任务被取消/暂停时不再重试
     * @param delivery 结构化交付结论。只有「没送达」才允许重试；
     *   「已送达但没生效」再跑一遍就是重复副作用——闹钟降级链整条重跑两次，
     *   结果就是闹钟列表里出现两条一样的条目。
     * @param structural 请求本身不成立（未知命令/参数非法）：换执行次数也不会变好，交给 AI 重新决策
     */
    fun shouldRetry(
        type: String,
        delivery: Delivery,
        structural: Boolean,
        attempt: Int,
        active: Boolean,
    ): Boolean =
        active && attempt < MAX_ATTEMPTS &&
            delivery == Delivery.NOT_DELIVERED && !structural && !isClickLike(type)

    /**
     * 失败回注给 AI 的「下一步怎么做」，按交付状态分开。
     *
     * 同为失败，两种情况的正确应对完全不同：
     * - 没送达：动作根本没碰到设备，换通道 / 换落点值得再试一次；
     * - 已送达未生效：动作已经落到页面上，原样重放就是重复副作用，该换入口 / 换控件 / 换策略。
     *
     * 只回一句笼统的失败，AI 只会把同一下再点一遍——这正是"心形按钮点二十多次"的病根之一。
     * 请求本身不成立（structural）时返回空串：此时 [com.phoneagent.engine.runtime.ExecutionFeedback]
     * 的结构性错误提示已经给出更准确的纠正方向，不再叠一层。
     */
    fun failureHint(delivery: Delivery, reason: String, structural: Boolean): String = when {
        structural || delivery == Delivery.EFFECTIVE -> ""
        delivery == Delivery.NOT_DELIVERED ->
            "\n\n注意：这一步的动作没有送达设备（$reason）。可以换一条执行通道或换一个落点再试，或改用别的意图；不要反复下发同一条无效动作。"
        delivery == Delivery.DELIVERED_UNCONFIRMED ->
            "\n\n注意：这一步的动作已下发，但此刻读不到页面（$reason）。这不等于失败，也还没有确认生效：" +
                "先观察当前页面再决定下一步，不要原地重复同一下——发送/提交/删除这类动作重放就是重复副作用。"
        else ->
            "\n\n注意：这一步的动作已经送达并落到页面上，但页面没有出现预期变化（$reason）。" +
                "不要原地重复同一下——先观察当前页面；若目标仍未达成，换入口、换控件或换意图。"
    }

    /**
     * 动作执行后的界面稳定等待（毫秒）。
     *
     * 主循环原先固定 `delay(400)`：不管这一步有没有碰界面，都一律空等 400ms。
     * 但 SAY / REMEMBER / DEVICE_QUERY 这三类只是"说一句 / 记一条 / 查设备信息"，
     * 页面根本不会因为执行它们而改变，等待 400ms 纯属白等（长任务里累积起来很可观）。
     * 其余动作涉及动画与页面切换，必须保留完整等待，否则下一步会读到画了一半的页面。
     */
    fun stepSettleMs(type: String): Long =
        if (type in NO_SETTLE_TYPES) NO_SETTLE_MS else SETTLE_MS

    /** 会改变界面的动作：执行后等动画/转场走完再观察 */
    const val SETTLE_MS = 400L

    /** 不改变界面的动作：只留极短的让出时间，够日志刷一帧即可 */
    const val NO_SETTLE_MS = 60L

    /** 不触碰 UI 的动作类型：执行它们不会让页面发生任何变化 */
    private val NO_SETTLE_TYPES = setOf(ActionType.SAY, ActionType.REMEMBER, ActionType.DEVICE_QUERY)

    /**
     * 本就不期待页面变化的动作类型（比 [NO_SETTLE_TYPES] 更宽）。
     *
     * 页面级止损按"上一步之后页面变没变"计数。这些动作执行完页面必然不变，
     * 若也参与计数，一次等待加载、一次 say 说明就会被误判成"原地打转"。
     * 注意只跳过、不复位：wait 之后再点同一个没用的按钮仍是同一条死路。
     */
    private val NON_PAGE_CHANGE_TYPES = setOf(
        ActionType.SAY, ActionType.REMEMBER, ActionType.DEVICE_QUERY, ActionType.DEVICE_OPS,
        ActionType.SHELL, ActionType.SHOW_AGENT, ActionType.MCP_CALL, ActionType.READ_NOTIFS,
        ActionType.CLEAR_NOTIFS, ActionType.WRITE_DOC, ActionType.TASK_DONE,
    )

    /** 该动作是否期待页面（元素树）发生变化 */
    fun expectsPageChange(type: String): Boolean = type !in NON_PAGE_CHANGE_TYPES


    // ---- 呈现 ----

    /** 执行历史里的验证结论（[StepRecord.isConfirmed] 以同一口径判定"是否确认生效"） */
    const val STATUS_VERIFIED = "verified_success"

    /**
     * 执行历史里「已交付、但读不到页面」的结论：**未确认**。
     *
     * 既不是成功，也没有证据说失败——动作已经下发，我们只是没资格下结论。
     */
    const val STATUS_UNVERIFIED = "unverified"

    /**
     * 执行历史里「已交付、页面读到了、但没有预期变化」的结论：**未生效**。
     *
     * 与 [STATUS_UNVERIFIED] 分成两个字面量的理由很实际：二者对用户和模型是完全不同的两件事
     * （一个是"没看见"，一个是"看见了没成"）。折成一个 `unverified` 时，提示词与界面文案
     * 也就只剩一句话，读者只能自己猜——这正是「结构上分开了、用户看不到」的老问题。
     */
    const val STATUS_NO_EFFECT = "no_effect"

    /** 执行历史里的中止/未送达结论：❌ 这一下没有生效（没碰到设备，或路径已被判定不通） */
    const val STATUS_FAILED = "failed"

    /** 验证结果 → 执行历史状态（三态，由结构化的 [Delivery] 推导，不解析文案） */
    fun historyStatus(delivery: Delivery): String = when (delivery) {
        Delivery.EFFECTIVE -> STATUS_VERIFIED
        Delivery.DELIVERED_UNCONFIRMED -> STATUS_UNVERIFIED
        Delivery.DELIVERED_NO_EFFECT -> STATUS_NO_EFFECT
        // 没送达也是"没生效"：动作根本没上设备，说"已交付"才是错话
        Delivery.NOT_DELIVERED -> STATUS_FAILED
    }

    /**
     * 系统直连（device_ops）的三态结论。
     *
     * 与普通动作不同，直连失败要再分一层：命令是否真的下发到系统了。
     * - 成功 → [STATUS_VERIFIED]（✅）
     * - 已下发但读不到回执/回读不一致 → [STATUS_UNVERIFIED]（⚠️，用户去手机上核对）
     * - 根本没执行（通道不可用、参数非法、Provider 拒绝）→ [STATUS_FAILED]（❌，用户去修通道/改参数）
     */
    fun deviceOpsStatus(ok: Boolean, dispatched: Boolean): String = when {
        ok -> STATUS_VERIFIED
        dispatched -> STATUS_UNVERIFIED
        else -> STATUS_FAILED
    }

    /**
     * 把一步动作拼装成人类可读的执行说明（步骤留档用）。
     *
     * [label] 由调用方传入（[com.phoneagent.domain.rules.EngineRules.actionLabel]），
     * 避免这里再依赖一层动作类型 → 中文名的映射表。
     */
    fun actionDescription(action: AgentAction, verified: Boolean, label: String): String {
        // 优先用 AI 的 reasoning（它解释了"为什么这么做"），退回 reason（端侧自己给的原因）
        val reason = action.reasoning?.takeIf { it.isNotBlank() } ?: action.reason?.takeIf { it.isNotBlank() }
        return buildString {
            append(if (verified) "✅ 已生效" else "⚠️ 待确认")
            append(" · $label")
            reason?.let { append("\n$it") }
        }
    }
}
