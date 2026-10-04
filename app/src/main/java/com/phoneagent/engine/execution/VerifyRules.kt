package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction

/**
 * 执行结果的后处理判据与呈现（Verifier 角色的纯逻辑部分，无 Android 依赖，可直接单测）。
 *
 * 拆分目的：[AgentEngine] 原先在 600 行主循环里内联判断"这次失败要不要重试""这条错误还算不算
 * 结构性错误""这一步在历史里记成什么状态"。这些判据全部依赖 `verify.reason` 这种**文案**，
 * 一旦哪条提示词改了字，重试行为会跟着悄悄变——夹在主循环里既看不见也没法验证。
 * 抽出来之后，"改文案是否影响重试"成为一条可断言的规则。
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
     * 结构性错误的文案特征：重跑同一串动作**不会自己变好**，继续重试只是白等两轮。
     *
     * 这类错误全部来自端侧自己的校验（命令表里没有这条命令、必填参数没给、端点名写错），
     * 与页面状态无关，唯一出路是让 AI 重新决策。语义类失败（"页面未变化"）才值得重试。
     */
    private val STRUCTURAL_ERROR_MARKERS = listOf(
        // shell 通道：命令不在端侧命令表 / 命令体为空 / 参数值不合法
        "未知 shell 命令",
        "命令为空",
        "参数无效",
        // 按键：不在端侧支持的键值集合内
        "未知按键",
        // 自由模式无障碍端点直调：端点名不在白名单 / 必填参数没给
        "未知无障碍端点",
        "缺少参数",
        "缺少 endpoint",
        // 无障碍通道能力缺口：ENTER/REFRESH 这类通道本身不支持的直接操作，重试也不会变好
        "无障碍通道暂不支持直接刷新",
    )

    /** 该失败是否属于"重试也不会变好"的结构性错误 */
    fun isStructuralError(reason: String): Boolean = STRUCTURAL_ERROR_MARKERS.any { reason.contains(it) }

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
     */
    fun shouldRetry(type: String, reason: String, attempt: Int, active: Boolean): Boolean =
        active && attempt < MAX_ATTEMPTS && !isStructuralError(reason) && !isClickLike(type)

    // ---- 呈现 ----

    /** 执行历史里的验证结论（[StepRecord.isConfirmed] 以同一口径判定"是否确认生效"） */
    const val STATUS_VERIFIED = "verified_success"

    /** 执行历史里的未确认结论 */
    const val STATUS_UNVERIFIED = "unverified"

    /** 验证结果 → 执行历史状态 */
    fun historyStatus(verified: Boolean): String = if (verified) STATUS_VERIFIED else STATUS_UNVERIFIED

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
