package com.phoneagent.domain.rules

import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot

/**
 * AgentEngine 纯逻辑规则集（无 Android / Activity 依赖，可单测）。
 *
 * 拆分目的：AgentEngine 同时承担"编排 + 执行 + 展示"，将无状态判定逻辑
 * 下沉到此类，便于单元测试与复用。v2.3 从 AgentEngine 迁移过来。
 */
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
        if (type != ActionType.TAP && type != ActionType.CLICK &&
            type != ActionType.LONG_PRESS && type != ActionType.LONG_CLICK &&
            type != ActionType.TYPE_TEXT && type != ActionType.SHELL
        ) return false
        val target = action.target?.value ?: action.reason ?: action.reasoning
            ?: action.text ?: action.command ?: ""
        return listOf("发送", "提交", "下单", "立即支付", "去支付", "确认支付", "发布", "删除", "移除", "确认", "完成下单")
            .any { target.contains(it, ignoreCase = true) }
    }

    /** 幂等判定：当前页面是否已出现"完成成功"证据（避免重复执行副作用后再次触发） */
    fun idempotencyDone(snapshot: ScreenSnapshot): Boolean {
        val text = snapshot.toAiText()
        if (text.isBlank()) return false
        return listOf("发送成功", "提交成功", "下单成功", "支付成功", "发布成功", "删除成功", "办理成功", "操作成功", "交易成功", "已提交", "已完成")
            .any { text.contains(it) }
    }

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
        ActionType.SHELL -> "执行Shell"
        ActionType.WRITE_DOC -> "写入文档"
        ActionType.REMEMBER -> "记住信息"
        ActionType.DEVICE_QUERY -> "查询本机信息"
        ActionType.MCP_CALL -> "调用技能"
        ActionType.OPEN -> "打开链接/Scheme"
        ActionType.BACK -> "返回"
        ActionType.HOME -> "回到桌面"
        ActionType.RECENTS -> "最近任务"
        ActionType.KEY -> "按键"
        ActionType.WAIT -> "等待"
        ActionType.SCROLL -> "滚动查找"
        ActionType.TASK_DONE, ActionType.TASK_COMPLETE -> "任务完成"
        else -> type
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