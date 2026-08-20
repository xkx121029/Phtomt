package com.phoneagent.decision

import com.phoneagent.model.AgentAction
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.perception.PageAnnotator
import com.phoneagent.perception.effectiveLabel

/**
 * 端侧决策引擎：对弹窗/加载/异常/完成四类场景直接给出动作，不消耗云端调用。
 * 只有非本地可处理的页面才返回 null，交由云端决策。
 * 对应文档“第 5 层 端侧决策引擎”。
 *
 * 带回环保护：连续 N 次本地决策后强制走云端，避免死循环。
 */
class LocalDecisionEngine {

    /** 连续本地决策达到该次数后强制走云端 */
    private val maxConsecutiveLocal = 5
    private var consecutiveLocal = 0

    /**
     * 返回本地生成的 [AgentAction]；若无法本地处理返回 null（走云端）。
     * @param snapshot 当前页面快照
     * @param taskFinished 由云端判断任务是否已完成
     */
    fun decide(snapshot: ScreenSnapshot): AgentAction? {
        val pageType = PageAnnotator.inferPageType(snapshot)
        val action = when (pageType) {
            "dialog_overlay" -> handleDialog(snapshot)
            "ad_with_countdown" -> handleAd(snapshot)
            "loading" -> AgentAction(type = "wait", durationMs = 1200, reason = "页面加载中")
            "error" -> handleError(snapshot)
            // 完成页需元素稀疏（≤5）才直接结束；否则交还 AI 判断，避免误判普通页面
            "completion" -> if (snapshot.elements.size <= 5) {
                AgentAction(type = "task_done", summary = "页面显示完成/成功状态", reason = "检测到完成页")
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

    private fun handleDialog(snapshot: ScreenSnapshot): AgentAction? {
        val positive = snapshot.elements.firstOrNull {
            val label = it.effectiveLabel() ?: ""
            listOf("允许", "同意", "确定", "知道了", "始终允许", "授权", "ok", "continue").any { k -> label.contains(k, ignoreCase = true) }
        }
        // 优先点正向按钮（允许/同意），否则点关闭按钮
        val target = positive ?: snapshot.elements.firstOrNull {
            val label = it.effectiveLabel() ?: ""
            listOf("关闭", "取消", "以后再说", "跳过", "稍后", "no", "cancel", "x", "✕").any { k -> label.contains(k, ignoreCase = true) }
        }
        return target?.let {
            val reason = if (positive != null && positive.index == it.index) "点击允许/同意按钮" else "关闭弹窗"
            AgentAction(type = "click", elementIndex = it.index, x = it.centerX, y = it.centerY, reason = reason)
        }
    }

    private fun handleAd(snapshot: ScreenSnapshot): AgentAction? {
        val skip = snapshot.elements.firstOrNull {
            (it.effectiveLabel() ?: "").contains("跳过")
        }
        return skip?.let { AgentAction(type = "click", elementIndex = it.index, x = it.centerX, y = it.centerY, reason = "跳过倒计时广告") }
    }

    private fun handleError(snapshot: ScreenSnapshot): AgentAction? {
        val retry = snapshot.elements.firstOrNull {
            (it.effectiveLabel() ?: "").contains("重试")
        }
        return retry?.let { AgentAction(type = "click", elementIndex = it.index, x = it.centerX, y = it.centerY, reason = "点击重试") }
            ?: AgentAction(type = "back", reason = "异常页，尝试返回")
    }
}