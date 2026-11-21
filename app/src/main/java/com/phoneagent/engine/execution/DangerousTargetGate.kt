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
 *
 * **三态而非布尔**：看得见且安全 → 放行；看得见且命中 → 确认；**看不见 → 也确认**。
 * 坐标点击的落点探针读不到控件时，「未知」既不当成安全（那就是本组缺陷的病根：
 * Provider 抖一下就静默放行「删除」），也不当成危险（会让抖动期间所有坐标点击全废，
 * 而"点屏幕上没文字的地方"正是手机智能体的核心能力）。未知走确认门，不猜。
 */
internal class DangerousTargetGate(
    /** 用户确认门；返回 true 才允许派发 */
    private val confirm: suspend (AgentAction) -> Boolean,
    /** 执行日志出口，与 [ClickRunner] 用同一条 */
    private val log: (String) -> Unit = {},
) {

    /** 已经问过并获批的目标（同一次执行内不重复弹窗：同一个问题问两遍只会让人盲点允许） */
    private val approved = mutableSetOf<String>()

    /**
     * 一次判定的结论：**三态**，不是布尔。
     *
     * 把「未知」折成「安全」（放行）或「危险」（硬拒）都是错的：前者就是本组缺陷的病根
     * （Provider 抖一下就静默放行「删除」），后者会让 Provider 抖动期间**所有坐标点击全废**——
     * 而"点屏幕上没文字的地方"正是手机智能体的核心能力。所以未知走确认门，不猜。
     */
    sealed class Verdict {
        /** 看得见目标，且不危险 */
        object Clear : Verdict()

        /** 看得见目标，标签命中不可逆词 */
        data class Hit(
            val word: String,
            val label: String,
            /** 证据来源说明，直接进日志：`落点命中的控件` / `已知目标控件` / `AI 给的目标串` */
            val evidence: String,
        ) : Verdict()

        /** 坐标点击且落点探针拿不到控件：既没命中、也没看见，判不了 */
        data class Unknown(val point: String) : Verdict()
    }

    /**
     * 判据本身（纯函数，便于单测）：取"这次执行能拿到的最强标签"逐个过词表。
     *
     * 优先真实控件文字（落点探针 > 已知目标控件 > 选择器），最后才退到 AI 给的目标串——
     * 它是弱信号，但比什么都没有强。
     *
     * 什么都拿不到时按动作类型分两种结局，这个区分是刻意的：
     * - **点击类**（TAP/CLICK/长按）且只给了坐标 → [Verdict.Unknown]，交确认门；
     * - **滑动**（SWIPE）→ [Verdict.Clear]，不打扰。滑动起点压在什么控件上本来就无关紧要
     *   （绝大多数是滚动页面），探针读不到不该让每次滚动都弹窗。
     */
    @Suppress("ReturnCount")
    fun verdictOf(
        action: AgentAction,
        element: UiElement?,
        selector: NodeSelector?,
        hit: NodeHit?,
    ): Verdict {
        if (action.type !in GATED_TYPES) return Verdict.Clear
        for ((label, evidence) in labelsOf(action, element, selector, hit)) {
            DangerousTargetWords.hit(label)?.let { return Verdict.Hit(it, label, evidence) }
        }
        // 到这里说明一个标签都没拿到（或都不危险）。坐标点击且探针失败 = 判不了，走确认
        val blind = hit == null && element == null && selector == null &&
            (action.x != null || action.y != null) && action.type in PROBE_ONLY_TYPES
        return if (blind) Verdict.Unknown("${action.x ?: "-"},${action.y ?: "-"}") else Verdict.Clear
    }

    /** 候选标签，按证据强度从强到弱；这张顺序就是判据的口径，别的地方不要再排一遍 */
    private fun labelsOf(
        action: AgentAction,
        element: UiElement?,
        selector: NodeSelector?,
        hit: NodeHit?,
    ): List<Pair<String, String>> = listOfNotNull(
        hit?.label?.takeIf { it.isNotBlank() } to "落点命中的控件",
        hit?.viewId?.takeIf { it.isNotBlank() } to "落点命中的控件 id",
        element?.effectiveLabel()?.takeIf { it.isNotBlank() } to "已知目标控件",
        element?.viewId?.takeIf { it.isNotBlank() } to "已知目标控件 id",
        selector?.label?.takeIf { it.isNotBlank() } to "选择器标签",
        selector?.viewId?.takeIf { it.isNotBlank() } to "选择器 id",
        action.target?.value?.takeIf { it.isNotBlank() } to "AI 给的目标串",
    ).mapNotNull { (label, evidence) -> label?.let { it to evidence } }

    /**
     * 派发前过闸门。
     *
     * @return null = 放行（看得见且不危险，或用户已经批准过同一目标）；非空 = 用户拒绝的原因，
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
        return when (val verdict = verdictOf(action, element, selector, hit)) {
            Verdict.Clear -> null
            is Verdict.Hit -> askHit(action, verdict)
            is Verdict.Unknown -> askBlind(action, verdict)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun askHit(action: AgentAction, found: Verdict.Hit): String? {
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

    /**
     * 看得见的路径走不通时问一句，而不是替用户猜。
     *
     * 文案刻意**不说危险、也不说安全**——「无法读取」就是它本来的意思。把未知写成
     * 「疑似不可逆」会让用户以为我们看见了什么，下次真的危险时那句提醒就贬值了。
     */
    @Suppress("ReturnCount")
    private suspend fun askBlind(action: AgentAction, unknown: Verdict.Unknown): String? {
        val key = "${action.type}|unknown|${unknown.point}"
        if (key in approved) return null
        log("「${action.type}」在 (${unknown.point}) 这一下读不到落点控件，目标不可判定，转用户确认")
        val asked = action.copy(
            target = ActionTarget(method = "coordinate", value = unknown.point),
            reason = "无法读取这一下的目标控件（无障碍/Provider 未返回落点），判定不了它是否不可逆：请确认这次点按",
        )
        if (confirm(asked)) {
            approved += key
            return null
        }
        log("用户拒绝在 (${unknown.point}) 的不可判定点按，本次一步未下发")
        return "这一下在 (${unknown.point}) 无法读取目标控件，用户拒绝执行不可判定的点按。" +
            "请先观察当前页面、改用带文字或 id 的目标重新定位，不要重复请求同一下。"
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

        /**
         * 「看不见就问」只适用于点击类：它们没有别的证据可退，只能靠落点探针。
         * 滑动不在此列——滚动本身不产生不可逆副作用，探针读不到不该让每次滚动都弹窗。
         */
        private val PROBE_ONLY_TYPES: Set<String> = setOf(
            ActionType.TAP, ActionType.CLICK,
            ActionType.LONG_PRESS, ActionType.LONG_CLICK,
        )
    }
}
