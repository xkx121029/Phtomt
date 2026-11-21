package com.phoneagent.engine.execution

import com.phoneagent.device.a11y.NodeHit
import com.phoneagent.device.a11y.NodeSelector
import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.UiElement
import com.phoneagent.domain.rules.DangerousTargetWords
import com.phoneagent.domain.rules.effectiveLabel

/**
 * 派发前的「危险目标」闸门：这一下到底会落到谁身上，那个控件是不是不可逆的。
 *
 * 为什么下沉到这里：旧实现在 [com.phoneagent.engine.loop.ExecutionPhase] 里读
 * `action.target?.value`——那是**模型自报**的字符串。`by=coordinate` 时转译层拿不到元素
 * （`IntentTranslator` 的 resolveTarget 返回 elem==null → target==null），`hazardWordOf` 只看到 null；
 * `by=hint`（默认值）时看到的也只是模型的说法。而派发层天然握着**活节点**：
 * [com.phoneagent.device.a11y.ActionExecutor.hitTest] 已经问清了"这一下会落到谁身上"，
 * 只是过去只写日志、不参与判据。判据放在这里，证据与判据才同源。
 *
 * 判据只有这一份：[DangerousTargetWords] 一张词表 + [hitOf] 一个标签取值顺序。调用方
 * （[ClickRunner] 的点击/长按、[ExecutionCore] 的滑动）只负责"在派发前问一句"。
 *
 * **按动作类型收敛，不是所有动作都问**：只有"按下去就可能丢数据/花钱/不可恢复"的手势进闸门
 * （`TAP` / `CLICK` / `LONG_PRESS` / `LONG_CLICK` / `SWIPE`）。**手势类型本身不是危险信号**——
 * 滑动绝大多数时候只是滚动页面，所以判据看的是**手指落点下的那个控件**是不是「删除并退出」，
 * 而不是"这一下是滑动"。别把它扩大成"所有滑动都要确认"：那会让翻页也弹窗。
 *
 * 确认走调用方注入的 [confirm]（生产即 [com.phoneagent.engine.runtime.UserGate.awaitActionConfirmation]），
 * 与本文件外的确认门是同一个，不另造一套。
 */
internal class DangerousTargetGate(
    /** 用户确认门；返回 true 才允许派发 */
    private val confirm: suspend (AgentAction) -> Boolean,
    /** 执行日志出口，与 [ClickRunner] 用同一条 */
    private val log: (String) -> Unit = {},
) {

    /** 已经问过并获批的目标（同一次执行内不重复弹窗：同一个问题问两遍只会让人盲点允许） */
    private val approved = mutableSetOf<String>()

    /** 一次判定的结论：命中的词，以及命中的是哪个标签（两个都要进日志与原因文案） */
    data class Hit(
        val word: String,
        val label: String,
        /** 证据来源说明，直接进日志：`落点命中的控件` / `已知目标控件` / `AI 给的目标串` */
        val evidence: String,
    )

    /**
     * 判据本身（纯函数，便于单测）：取"这次执行能拿到的最强标签"逐个过词表。
     *
     * 优先真实控件文字（落点探针 > 已知目标控件 > 选择器），最后才退到 AI 给的目标串——
     * 它是弱信号，但比什么都没有强。
     */
    @Suppress("ReturnCount")
    fun hitOf(
        action: AgentAction,
        element: UiElement?,
        selector: NodeSelector?,
        hit: NodeHit?,
    ): Hit? {
        if (action.type !in GATED_TYPES) return null
        val probes = buildList {
            hit?.label?.takeIf { it.isNotBlank() }?.let { add(it to "落点命中的控件") }
            hit?.viewId?.takeIf { it.isNotBlank() }?.let { add(it to "落点命中的控件 id") }
            element?.effectiveLabel()?.takeIf { it.isNotBlank() }?.let { add(it to "已知目标控件") }
            element?.viewId?.takeIf { it.isNotBlank() }?.let { add(it to "已知目标控件 id") }
            selector?.label?.takeIf { it.isNotBlank() }?.let { add(it to "选择器标签") }
            selector?.viewId?.takeIf { it.isNotBlank() }?.let { add(it to "选择器 id") }
            action.target?.value?.takeIf { it.isNotBlank() }?.let { add(it to "AI 给的目标串") }
        }
        for ((label, evidence) in probes) {
            DangerousTargetWords.hit(label)?.let { return Hit(it, label, evidence) }
        }
        return null
    }

    /**
     * 派发前过闸门。
     *
     * @return null = 放行（没命中，或用户已经批准过同一目标）；非空 = 用户拒绝的原因，
     *   调用方必须**一步不落地中止**并把这段话回注给 AI。
     */
    @Suppress("ReturnCount")
    suspend fun check(
        action: AgentAction,
        element: UiElement?,
        selector: NodeSelector? = null,
        hit: NodeHit? = null,
    ): String? {
        // 上层确认门已经就同一个动作问过用户，不重复打扰
        if (action.needsUserConfirmation) return null
        val found = hitOf(action, element, selector, hit) ?: return null
        val key = "${action.type}|${found.label}"
        if (key in approved) return null
        log(
            "「${action.type}」的目标命中危险词「${found.word}」" +
                "（标签「${found.label}」，来源：${found.evidence}），转用户确认",
        )
        val asked = action.copy(
            // 确认框里必须是**真实控件**的名字：靠坐标点击时 action.target 根本是 null，
            // 不显式填进去，用户看到的只会是「点按」两个字
            target = ActionTarget(method = "label", value = found.label),
            reason = "目标「${found.label}」命中不可逆词「${found.word}」（来源：${found.evidence}）",
        )
        if (confirm(asked)) {
            approved += key
            return null
        }
        log("用户拒绝执行「${found.label}」（命中「${found.word}」），本次一步未下发")
        return "用户拒绝执行「${found.label}」（命中不可逆词「${found.word}」），本次没有执行。" +
            "请改用其他方案或询问用户，不要重复请求同一动作。"
    }

    companion object {
        /**
         * 进闸门的动作类型：只有"按下去就可能产生不可逆后果"的那几种。
         *
         * 刻意**不含** `SWIPE_UP/DOWN/LEFT/RIGHT` 这类方向滑动——它们是不带目标的纯滚动，
         * 判据没有"目标控件"可看，收进来就等于"所有翻页都要确认"。
         */
        val GATED_TYPES: Set<String> = setOf(
            ActionType.TAP, ActionType.CLICK,
            ActionType.LONG_PRESS, ActionType.LONG_CLICK,
            ActionType.SWIPE,
        )
    }
}
