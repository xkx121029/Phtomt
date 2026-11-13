package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot

/**
 * 密码类输入的脱敏口径（唯一定义点）。
 *
 * 密码只用于**这一次执行**：执行器拿到原文照常输入，但所有本地留档、界面回显与下一轮提示词
 * 一律只写「已输入 N 位」。判定依据是目标控件自身的 `isPassword`（Provider 从
 * `TYPE_TEXT_VARIATION_PASSWORD` / `WEB_PASSWORD` / 数字密码推导），不靠字段名猜。
 *
 * 为什么不在这里判断"输入框长得像密码"：`viewId` 含 password、label 写"密码"都只是约定，
 * 真正决定系统是否遮蔽内容的是 `AccessibilityNodeInfo.isPassword`，以它为准才不会漏。
 */
object SensitiveInput {

    /** 留档/回注里代替密码正文的占位符 */
    fun masked(text: String?): String = "已输入 ${text?.length ?: 0} 位（内容不记录）"

    /** 这条意图是不是"往密码框里输入" */
    fun isPasswordInput(intent: AgentIntent, snapshot: ScreenSnapshot): Boolean {
        if (intent.intent != IntentType.INPUT) return false
        if (intent.text.isNullOrEmpty()) return false
        return IntentResolver().resolve(intent.target, snapshot).element?.isPassword == true
    }

    /** 把动作里的输入正文换成占位符；非输入类动作原样返回 */
    fun redactAction(action: AgentAction): AgentAction =
        if (action.type == ActionType.TYPE_TEXT && !action.text.isNullOrEmpty()) {
            action.copy(text = masked(action.text))
        } else {
            action
        }

    /**
     * 意图脱敏：只动 `text`，其余字段（目标、理由）保留。
     * 用于对话留档与本地决策轨迹——它们都会落盘并出现在界面上。
     */
    fun redactIntent(intent: AgentIntent): AgentIntent =
        if (intent.intent == IntentType.INPUT && !intent.text.isNullOrEmpty()) {
            intent.copy(text = masked(intent.text))
        } else {
            intent
        }

    /**
     * 模型原始响应里的输入正文同样要换掉：它会作为「原始数据」落进本地轨迹。
     *
     * 只替换 JSON 里的 `"text":"..."` 字段，避免拿短密码去 replace 整段 JSON 时误伤别的字符。
     */
    fun redactRawResponse(raw: String, password: String?): String {
        if (password.isNullOrEmpty() || raw.isEmpty()) return raw
        return TEXT_FIELD.replace(raw) { match ->
            match.groupValues[1] + masked(password) + match.groupValues[2]
        }
    }

    private val TEXT_FIELD = Regex("(\"text\"\\s*:\\s*\")[^\"]*(\")")
}
