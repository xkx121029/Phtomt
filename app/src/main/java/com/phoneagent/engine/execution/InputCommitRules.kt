package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.IntentType

/**
 * 输入闭环判据（纯逻辑，可单测）。
 *
 * 对应真实失败：把正文输入答题框后直接判完成，没有点提交，判分读到空答案。
 * 根因是执行链只验证了"文字进了控件"（TYPE_TEXT 生效），却把"填了"当成"交了"——
 * 输入框里的字在提交之前对页面/后端没有任何意义。
 *
 * 这里只做判定与状态迁移，不碰 Android：执行阶段据此在"完成"前检查是否还有未提交的输入。
 */
internal object InputCommitRules {

    /** 输入未提交时最多拒绝几次"完成"，超过即放行但记下可归因的未提交结论（防死循环） */
    const val MAX_FINISH_REJECTIONS = 2

    /**
     * 待提交输入的保鲜步数：填入后超过这么多步仍未提交，视为输入上下文已被后续动作带离，
     * 不再拦截完成。给上限是为了避免"搜索框输入 → 点进结果页"这类正常流程被误判。
     */
    const val PENDING_INPUT_TTL_STEPS = 3

    /** 待提交输入内容摘要的长度上限（只用于提示文案点名"输入了什么"，不需要全文） */
    private const val PREVIEW_CHARS = 24

    /** 提交语义的控件文字特征：点击命中任一即视为"把填入的内容交出去了" */
    private val COMMIT_LABELS = listOf(
        "提交", "发送", "保存", "发布", "确认", "确定", "搜索", "查询",
        "登录", "注册", "下一步", "结账", "下单", "支付", "购买", "完成",
    )

    /** 本身就是"提交/确认/搜索"的意图 */
    private val COMMIT_INTENTS = setOf(IntentType.SEND, IntentType.CONFIRM, IntentType.SEARCH)

    /** 会把输入上下文带离当前页的动作：执行成功后待提交输入随之作废 */
    private val NAVIGATION_TYPES = setOf(
        ActionType.BACK, ActionType.HOME, ActionType.RECENTS,
        ActionType.OPEN, ActionType.LAUNCH, ActionType.LAUNCH_ASSISTANT,
    )

    /** 只有把文字写进控件的动作才会产生"待提交"状态 */
    fun isTextInput(type: String): Boolean = type == ActionType.TYPE_TEXT

    /** 该动作是否把已填入的内容真正交出去（点提交/发送/保存，或本就是 send/confirm/search 意图，或回车） */
    fun isCommitAction(action: AgentAction, intentType: String?): Boolean {
        val intentCommits = intentType != null && intentType in COMMIT_INTENTS
        val enterKey = action.type == ActionType.KEY && action.keycode.equals("ENTER", ignoreCase = true)
        val labelCommits = VerifyRules.isClickLike(action.type) && run {
            val label = action.target?.value.orEmpty()
            label.isNotBlank() && COMMIT_LABELS.any { label.contains(it) }
        }
        return intentCommits || enterKey || labelCommits
    }

    /**
     * 推进一步后新的"待提交输入"状态。
     *
     * @param current 上一步的待提交输入
     * @param verified 本步动作是否确认生效（没生效的动作不改变状态）
     * @param step 本步步号
     */
    // 分支就是本规则的全部语义，拆开反而难对照，此处按规则豁免
    @Suppress("ReturnCount")
    fun nextPending(
        current: PendingInput?,
        action: AgentAction,
        intentType: String?,
        verified: Boolean,
        step: Int,
    ): PendingInput? {
        if (!verified) return current
        if (isTextInput(action.type)) {
            val text = action.text.orEmpty()
            if (text.isBlank()) return current
            return PendingInput(
                label = action.target?.value?.takeIf { it.isNotBlank() } ?: "输入框",
                preview = text.take(PREVIEW_CHARS),
                step = step,
            )
        }
        if (isCommitAction(action, intentType)) return null
        if (action.type in NAVIGATION_TYPES) return null
        if (current != null && step - current.step > PENDING_INPUT_TTL_STEPS) return null
        return current
    }
}

/** 一处已填入、但还没被提交出去的输入 */
internal data class PendingInput(
    /** 输入目标（控件文字/描述），用于在纠偏提示里点名"哪个框没提交" */
    val label: String,
    /** 输入内容摘要（已脱敏，敏感输入在更早阶段已替换为空串/掩码） */
    val preview: String,
    /** 填入时的步号 */
    val step: Int,
)
