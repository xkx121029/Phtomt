package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ScreenSnapshot

/**
 * 语音助手传话的定性（纯逻辑，可单测）。
 *
 * 这条路径过去是「凭一句文案判成功」：Provider 返回的说明里明确写着「已填入文字但未找到发送按钮」，
 * 执行层照样 `VerifyResult(true, …)`。后果有两层：
 * - 「填进去」被记成「发出去并确认」：执行历史的 ✅、进度、检查点都当它成功了；
 * - 完成门看不见它：[com.phoneagent.domain.rules.InputCommitRules] 只认 `TYPE_TEXT` 的输入闭环，
 *   「填进助手输入框但没发出去」在门那边完全不存在。
 *
 * 现在两类结构证据各管一件事：
 * - **发没发出去**：助手**输入框**里是否还留着这句话（只认 `editable` 的控件；
 *   聊天气泡里回显同一句话不算没发出去）；
 * - **生不生效**：页面有没有变化（[ShellFallbackPolicy.Verdict]，读不到页面 = 未确认）。
 */
internal object AssistantHandoffPolicy {

    /**
     * 传话之后，助手输入框里是否还留着这句话。
     *
     * 看见了就是硬证据：文字已经填进去、但没有离开输入框（没发出去）。看不见不代表发出去了，
     * 所以这里只做否决，不做放行。
     */
    fun stillInInputBox(snapshot: ScreenSnapshot, text: String?): Boolean {
        val phrase = text?.trim().orEmpty()
        if (phrase.isEmpty()) return false
        return snapshot.elements.any { e ->
            e.editable &&
                (e.text.orEmpty().contains(phrase) || e.currentValue.orEmpty().contains(phrase))
        }
    }

    /**
     * 一次传话的结论。
     *
     * @param handoff Provider/补发环节给出的「走到了哪一步」说明，只进原因，不参与成败判定
     * @param typedNotSent 页面上仍看得到那句话（填进去了、没发出去）
     * @param evidence 页面级证据
     */
    fun verdict(
        handoff: String,
        typedNotSent: Boolean,
        evidence: ShellFallbackPolicy.Verdict,
    ): VerifyResult = if (typedNotSent) {
        VerifyResult(
            false,
            "$handoff（助手输入框里仍然留着这句话，没有发出去：先确认助手的发送方式）",
            evidence.beforeFingerprint,
            evidence.afterFingerprint,
            notDelivered = false,
        )
    } else {
        VerifyResult(
            evidence.confirmed,
            handoff + evidence.suffix,
            evidence.beforeFingerprint,
            evidence.afterFingerprint,
            notDelivered = false,
            unconfirmed = evidence.unconfirmed,
        )
    }
}
