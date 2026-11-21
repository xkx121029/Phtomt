package com.phoneagent.domain.rules

import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.CapabilityRegistry
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot

/**
 * AgentEngine 纯逻辑规则集（无 Android / Activity 依赖，可单测）。
 *
 * 拆分目的：AgentEngine 同时承担"编排 + 执行 + 展示"，将无状态判定逻辑
 * 下沉到此类，便于单元测试与复用。v2.3 从 AgentEngine 迁移过来。
 */
// 无状态判定的集中地：按领域再细分类是独立重构，现在拆会打断现有调用方，故先豁免函数数上限
@Suppress("TooManyFunctions")
object EngineRules {

    /**
     * 任务退出后是否需要兜底复位运行状态。
     *
     * 两道守卫，缺一不可：
     * 1. 归属：非空 [taskId] 与 [currentTaskId] 不一致时**不复位**。`stop()` 取消协程后
     *    收尾是异步跑的，用户若立刻发起新任务，旧任务的收尾不能把新任务的运行状态一并复位
     *    （否则界面会被打回空闲、悬浮窗也会被关掉）。[taskId] 为 null 表示无归属信息（任务记忆尚未建立），
     *    此时只靠终态守卫兜底。
     * 2. 终态：[AgentState.Phase.DONE] 是任务正常完成的终态，兜底复位不能把它洗成「空闲」，
     *    否则任务完成提示会被抹掉。其余状态（含 ERROR）都允许复位，把运行标志清干净。
     */
    fun shouldFallbackReset(taskId: Long?, currentTaskId: Long, phase: AgentState.Phase): Boolean {
        if (taskId != null && taskId != currentTaskId) return false
        return phase != AgentState.Phase.DONE
    }

    /** 生效判定：动作是否属于"有副作用、需幂等保护"的操作（提交/发送/下单/支付/删除/发布等） */
    fun isFinalSubmit(action: AgentAction, type: String): Boolean {
        // 自由模式的无障碍端点直调（a11y_call）同样可能触发提交/发送/删除等副作用，必须纳入幂等保护；
        // 否则同一副作用会在「页面已出现完成证据」时被重复执行（原先白名单漏了它，形成绕过口）
        if (!isSideEffectType(type)) return false
        return SIDE_EFFECT_WORDS.any { targetText(action).contains(it, ignoreCase = true) }
    }

    /** 不可逆动作：删除/支付/下单这类做错了没法撤销，任何"跳过"或"重放"都必须先问用户 */
    fun isIrreversible(action: AgentAction, type: String): Boolean {
        if (!isSideEffectType(type)) return false
        return IRREVERSIBLE_WORDS.any { targetText(action).contains(it, ignoreCase = true) }
    }

    /**
     * 幂等键：同一动作 = 同一动作类型 + 同一目标文字。
     *
     * 用语义目标而不是坐标：AI 换个坐标点同一个「提交」还是同一件事；反过来，同一个应用里
     * 两条都叫「删除」的记录会被算成同一动作——所以跳过时还要求页面仍显示完成确认语（见 [idempotencyVerdict]）。
     */
    fun idempotencyKey(action: AgentAction, type: String): String =
        "${ActionType.ALIAS[type] ?: type}|${targetText(action).trim().lowercase()}"

    /** 本任务内一次已确认下发的副作用动作（幂等判据的唯一来源） */
    data class CommittedEffect(val key: String, val step: Int)

    /**
     * 幂等结论。
     *
     * 三种结论对应三种"绝对不能再搞错"的取向：
     * - [Skip]：本任务内我们已经下发并确认过同一动作 → 不重复副作用；
     * - [NeedsConfirm]：页面疑似已做完、或不可逆动作已下发过但页面不再是完成态 → 交用户决定，
     *   既不把页面文字当"我们做过"，也不静默把删除/支付再打一遍；
     * - [Proceed]：正常执行。
     */
    sealed class IdempotencyVerdict {
        /** 本任务第 [step] 步已下发并确认过同一动作 */
        data class Skip(val step: Int) : IdempotencyVerdict()

        /** 不可逆动作需要用户再确认一次（[reason] 直接进确认面板） */
        data class NeedsConfirm(val reason: String) : IdempotencyVerdict()

        object Proceed : IdempotencyVerdict()
    }

    /**
     * 幂等判定：**只认本任务的执行历史**，页面上的字只是"该不该再问一句"的提示。
     *
     * 旧实现在这里只看页面文字（含「已完成/操作成功/已提交」），于是待办详情页的状态标签
     * 就让整条删除被跳过、动作根本没下发却记成成功（HAPP-5 P0-1）。现在页面文字单独不成立：
     * 跳过要求"我们确实做过 + 页面仍是完成态"，不可逆动作在证据不足时一律转人工确认。
     *
     * @param history 本任务内已确认下发过的副作用动作（[CommittedEffect]）
     */
    // 每种判据各自早退最清楚，合成单出口反而要引入状态变量；留待幂等判据整体重构
    @Suppress("ReturnCount")
    fun idempotencyVerdict(
        history: List<CommittedEffect>,
        action: AgentAction,
        type: String,
        snapshot: ScreenSnapshot,
    ): IdempotencyVerdict {
        if (!isFinalSubmit(action, type)) return IdempotencyVerdict.Proceed
        val committed = history.lastOrNull { it.key == idempotencyKey(action, type) }
        val confirmed = confirmationEvidence(snapshot)
        if (committed != null && confirmed != null) return IdempotencyVerdict.Skip(committed.step)
        if (!isIrreversible(action, type)) return IdempotencyVerdict.Proceed
        if (committed == null) {
            val suspected = doneStateEvidence(snapshot) ?: return IdempotencyVerdict.Proceed
            return IdempotencyVerdict.NeedsConfirm(
                "当前页面已呈现完成态（「$suspected」），但本任务并没有下发过这个动作。" +
                    "请确认是否继续：允许＝真的执行一次，取消＝当作已经做过。",
            )
        }
        return IdempotencyVerdict.NeedsConfirm(
            "本任务第 ${committed.step} 步已下发过同一动作，但当前页面没有完成确认语。" +
                "不可逆操作不静默重放，请确认是否再执行一次。",
        )
    }

    /**
     * 页面上的完成确认语（null = 没有）。
     *
     * 只认带宾语的确认句（「提交成功」「删除成功」…）：**它们是"这一步提交成功"的结果提示**，
     * 不会出现在任务还没做的页面上。「已完成」「已提交」「操作成功」是从词表里删掉的状态标签——
     * 待办/工单详情页在没删没改时同样显示「已完成」，拿它当证据会让动作整条被跳过。
     */
    fun confirmationEvidence(snapshot: ScreenSnapshot): String? = matchWords(snapshot, CONFIRMATION_WORDS)

    /**
     * 疑似完成态（确认语 + 状态标签），只用于"不可逆动作要不要先问用户"，**不可**用于跳过动作。
     */
    fun doneStateEvidence(snapshot: ScreenSnapshot): String? = matchWords(snapshot, DONE_STATE_WORDS)

    /**
     * 页面是否出现完成确认语。
     *
     * 单独成立**不构成**"已经做过"：跳过副作用必须以本任务执行历史为准（见 [idempotencyVerdict]），
     * 可见的用法只有「历史非空 && 本判据成立」。
     */
    fun idempotencyDone(snapshot: ScreenSnapshot): Boolean = confirmationEvidence(snapshot) != null

    private fun isSideEffectType(type: String): Boolean =
        type == ActionType.TAP || type == ActionType.CLICK ||
            type == ActionType.LONG_PRESS || type == ActionType.LONG_CLICK ||
            type == ActionType.TYPE_TEXT || type == ActionType.SHELL ||
            type == ActionType.A11Y_CALL

    /** 动作的目标文字：target.value 优先，逐级回退到 reason/reasoning/text/command */
    private fun targetText(action: AgentAction): String =
        action.target?.value ?: action.reason ?: action.reasoning
            ?: action.text ?: action.command ?: ""

    private fun matchWords(snapshot: ScreenSnapshot, words: List<String>): String? {
        val text = snapshot.toAiText()
        if (text.isBlank()) return null
        return words.firstOrNull { text.contains(it) }
    }

    /** 有副作用的动作目标词：命中即需幂等保护（提交/发送/下单/支付/删除/发布…） */
    private val SIDE_EFFECT_WORDS = listOf(
        "发送", "提交", "下单", "立即支付", "去支付", "确认支付", "发布", "删除", "移除", "确认", "完成下单",
    )

    /** 不可逆动作词：做错了没法撤销 */
    private val IRREVERSIBLE_WORDS = listOf("删除", "移除", "支付", "付款", "下单", "退款", "购买")

    /** 真正的完成确认句（带宾语）：页面只在动作真的提交成功后才出现 */
    private val CONFIRMATION_WORDS = listOf(
        "发送成功", "提交成功", "下单成功", "支付成功", "发布成功", "删除成功", "办理成功", "交易成功",
    )

    /** 疑似完成态：确认句 + 状态标签。状态标签只能触发"问用户"，不能触发"跳过动作" */
    private val DONE_STATE_WORDS = CONFIRMATION_WORDS + listOf("已完成", "已提交", "操作成功")

    /** 计算滑动终点（屏幕越界钳制），屏幕尺寸由调用方传入（来源：真实分辨率/快照） */
    fun swipeEndpoints(
        x: Int,
        y: Int,
        direction: String?,
        distanceArg: Int?,
        screenW: Int,
        screenH: Int,
    ): Pair<Int, Int> {
        val vDist = distanceArg?.takeIf { it > 0 } ?: screenH
        val hDist = distanceArg?.takeIf { it > 0 } ?: screenW
        // 起点与终点统一收敛到屏幕内（有效像素 0..size-1），避免越界 1px 或负坐标
        val sx = x.coerceIn(0, screenW - 1)
        val sy = y.coerceIn(0, screenH - 1)
        return when (direction) {
            "up" -> sx to (sy - vDist).coerceIn(0, screenH - 1)
            "down" -> sx to (sy + vDist).coerceIn(0, screenH - 1)
            "left" -> (sx - hDist).coerceIn(0, screenW - 1) to sy
            "right" -> (sx + hDist).coerceIn(0, screenW - 1) to sy
            else -> sx to sy
        }
    }

    /** 动作类型 → 中文人类可读标签（模板化展示用） */
    fun actionLabel(type: String): String = when (type) {
        ActionType.TAP, ActionType.CLICK -> "点按"
        ActionType.LONG_PRESS, ActionType.LONG_CLICK -> "长按"
        ActionType.SWIPE, ActionType.SWIPE_UP, ActionType.SWIPE_DOWN, ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT -> "滑动"
        ActionType.TYPE_TEXT -> "输入文本"
        ActionType.LAUNCH -> "启动应用"
        ActionType.LAUNCH_ASSISTANT -> "唤起语音助手"
        ActionType.SHELL -> "执行Shell"
        ActionType.A11Y_CALL -> "调用无障碍端点"
        ActionType.WRITE_DOC -> "写入文档"
        ActionType.REMEMBER -> "记住信息"
        ActionType.DEVICE_QUERY -> "查询本机信息"
        ActionType.SAY -> "说"
        ActionType.MCP_CALL -> "调用技能"
        ActionType.OPEN -> "打开链接/Scheme"
        // 看图追问：由引擎就地调用视觉模型直出（同 browse_* 的做法，不设独立 ActionType）
        IntentType.SEE -> "看图追问"
        // 内置浏览器的 10 个子操作：由 BrowserChannel 直出，不经转译层，故直接按意图名映射
        IntentType.BROWSE_OPEN -> "打开网页"
        IntentType.BROWSE_READ -> "抓取网页内容"
        IntentType.BROWSE_CLICK -> "点击网页元素"
        IntentType.BROWSE_INPUT -> "填写网页表单"
        IntentType.BROWSE_SCROLL -> "滚动网页"
        IntentType.BROWSE_BACK -> "网页后退"
        IntentType.BROWSE_INFO -> "查看网页状态"
        IntentType.BROWSE_RELOAD -> "刷新网页"
        IntentType.BROWSE_WAIT -> "等待网页元素"
        IntentType.WEB_SEARCH -> "网页搜索"
        ActionType.BACK -> "返回"
        ActionType.HOME -> "回到桌面"
        ActionType.RECENTS -> "最近任务"
        ActionType.KEY -> "按键"
        ActionType.WAIT -> "等待"
        ActionType.SCROLL -> "滚动查找"
        ActionType.TASK_DONE, ActionType.TASK_COMPLETE -> "任务完成"
        // 兜底不吐英文意图名：能力注册表里有的（定闹钟、剪贴板、日历…）直接用登记的中文名，
        // 这条标签会出现在授权确认与步骤回显里，用户不该看到 set_alarm 这种内部标识
        else -> CapabilityRegistry.specOf(type)?.name ?: type
    }

    /**
     * 从 AI 原始输出中稳健提取 JSON 对象体。
     * 策略（逐级回退）：
     * 1. 处理 ```json ... ``` 或 ``` ... ``` 代码块包裹
     * 2. 处理中间出现代码块的情况（前有文字+```json ... ```）
     * 3. 反向搜索：从末尾 '}' 向前匹配最外层完整 JSON 对象（推荐，规避前文解释文字）
     * 4. 兜底：首 '{' 到末 '}'
     */
    fun extractJsonObject(content: String): String {
        val trimmed = content.trim()
        if (trimmed.startsWith("```")) {
            val firstNewline = trimmed.indexOf("\n")
            val lastFence = trimmed.lastIndexOf("```")
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim()
            }
        }
        val codeBlockStart = trimmed.indexOf("```json")
        if (codeBlockStart >= 0) {
            val afterMarker = trimmed.indexOf("\n", codeBlockStart)
            val blockEnd = trimmed.indexOf("```", afterMarker)
            if (afterMarker > 0 && blockEnd > afterMarker) {
                return trimmed.substring(afterMarker + 1, blockEnd).trim()
            }
        }
        val backwardResult = extractJsonBackward(trimmed)
        if (backwardResult != null) return backwardResult
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    /**
     * 从字符串末尾反向搜索最外层完整 JSON 对象。
     * 从最后一个 '}' 开始，向前匹配括号直到找到对应的 '{'。
     * 正确处理字符串内的括号（不参与计数）。
     */
    fun extractJsonBackward(text: String): String? {
        val lastBrace = text.lastIndexOf('}')
        if (lastBrace < 0) return null

        var depth = 0
        var inString = false
        var escape = false
        for (i in lastBrace downTo 0) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (c == '}') {
                depth++
            } else if (c == '{') {
                depth--
                if (depth == 0) {
                    val extracted = text.substring(i, lastBrace + 1).trim()
                    return if (extracted.length >= 2) extracted else null
                }
            }
        }
        return null
    }

    /** 粗略判断文本是否以中文为主（含中文字符比例 > 30%） */
    fun isMostlyChinese(text: String): Boolean {
        if (text.isEmpty()) return false
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        return cjk.toFloat() / text.length > 0.3f
    }

    // ---- 决策温度 ----

    /** 每步决策（正常）温度：低温度保证稳定决策 */
    const val DECISION_TEMPERATURE = 0.1
    /** 失败 3 次后重规划温度：更高鼓励换思路 */
    const val REPLAN_TEMPERATURE = 0.5

    /** 决策温度：失败越频繁越鼓励换思路（失败 ≥3 次用重规划温度）。对应温度文档"阶梯上升"一节。 */
    fun decisionTemperature(failures: Int): Double =
        if (failures >= 3) REPLAN_TEMPERATURE else DECISION_TEMPERATURE

    // ---- 意图审核预筛 ----

    /** 本地硬规则预筛：仅当意图确实需要独立审核（完成/放弃、目标无元素证据的点击/输入/滑动）才升审核；
     *  其余动作由执行层验证兜底，跳过二次调用以降低开销 */
    fun needsReviewIntent(intent: AgentIntent, snapshot: ScreenSnapshot): Boolean = when (intent.intent) {
        IntentType.FINISH, IntentType.GIVE_UP -> true
        IntentType.TAP, IntentType.LONG_PRESS, IntentType.INPUT, IntentType.SWIPE ->
            intent.target == null || intent.target.by == "hint"
        else -> false
    }
}