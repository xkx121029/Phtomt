package com.phoneagent.domain.rules.LocalDecisionEngine

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.PageAnnotator
import com.phoneagent.engine.perception.effectiveLabel

/**
 * 端侧决策引擎：对弹窗/加载/异常/完成四类场景直接给出意图，不消耗云端调用。
 * 只有非本地可处理的页面才返回 null，交由云端决策。
 * 对应文档"第 5 层 端侧决策引擎"。
 *
 * 本地动作输出与 AI 一致的"意图"格式，由转译层统一转译执行。
 * 带回环保护：连续 N 次本地决策后强制走云端，避免死循环。
 */
class LocalDecisionEngine {

    /** 连续本地决策达到该次数后强制走云端 */
    private val maxConsecutiveLocal = 5
    private var consecutiveLocal = 0

    /**
     * 返回本地生成的意图 [AgentIntent]；若无法本地处理返回 null（走云端）。
     * @param snapshot 当前页面快照
     */
    fun decide(snapshot: ScreenSnapshot): AgentIntent? {
        val pageType = PageAnnotator.inferPageType(snapshot)
        val action: AgentIntent? = when (pageType) {
            "dialog_overlay" -> handleDialog(snapshot)
            "ad_with_countdown" -> handleAd(snapshot)
            "loading" -> AgentIntent(intent = IntentType.WAIT, waitMs = 1200, reasoning = "页面加载中")
            "error" -> handleError(snapshot)
            // 完成页需元素稀疏（≤5）才直接结束；否则交还 AI 判断，避免误判普通页面
            "completion" -> if (snapshot.elements.size <= 5) {
                AgentIntent(intent = IntentType.FINISH, summary = "页面显示完成/成功状态", reasoning = "检测到完成页")
            } else null
            else -> null
        }

        return if (action != null) {
            consecutiveLocal++
            if (consecutiveLocal >= maxConsecutiveLocal) {
                // 防死循环：连续多次本地决策后强制走云端
                consecutiveLocal = 0
                null
            } else action
        } else {
            consecutiveLocal = 0
            null
        }
    }

    private fun handleDialog(snapshot: ScreenSnapshot): AgentIntent? {
        val positive = snapshot.elements.firstOrNull {
            val label = it.effectiveLabel() ?: ""
            listOf("允许", "同意", "确定", "知道了", "始终允许", "授权", "ok", "continue").any { k -> label.contains(k, ignoreCase = true) }
        }
        // 优先点正向按钮（允许/同意），否则点关闭按钮
        val target = positive ?: snapshot.elements.firstOrNull {
            val label = it.effectiveLabel() ?: ""
            listOf("关闭", "取消", "以后再说", "跳过", "稍后", "no", "cancel", "x", "✕").any { k -> label.contains(k, ignoreCase = true) }
        }
        val reason = if (positive != null && positive.index == target?.index) "点击允许/同意按钮" else "关闭弹窗"
        return target?.let { tapIntent(it, reason) }
    }

    private fun handleAd(snapshot: ScreenSnapshot): AgentIntent? {
        val skip = snapshot.elements.firstOrNull {
            (it.effectiveLabel() ?: "").contains("跳过")
        } ?: return null
        return tapIntent(skip, "跳过倒计时广告")
    }

    private fun handleError(snapshot: ScreenSnapshot): AgentIntent? {
        val retry = snapshot.elements.firstOrNull {
            (it.effectiveLabel() ?: "").contains("重试")
        }
        return retry?.let { tapIntent(it, "点击重试") }
            ?: AgentIntent(intent = IntentType.PRESS, key = "BACK", reasoning = "异常页，尝试返回")
    }

    /** 把命中的元素构造成一个"点击"意图（本地已经定位到控件，用最精确的 by 方式描述） */
    private fun tapIntent(elem: UiElement, reason: String): AgentIntent? {
        val target = when {
            !elem.semanticId.isNullOrBlank() -> AgentIntentTarget(by = "id", value = elem.semanticId!!)
            !elem.viewId.isNullOrBlank() -> AgentIntentTarget(by = "id", value = elem.viewId!!)
            !(elem.effectiveLabel() ?: "").isBlank() -> AgentIntentTarget(by = "text", value = elem.effectiveLabel()!!)
            // 无 id 也无文字：本地无法精确定位，交还云端做视觉定位
            else -> return null
        }
        return AgentIntent(intent = IntentType.TAP, target = target, reasoning = reason)
    }
}