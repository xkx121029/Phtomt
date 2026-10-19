package com.phoneagent.domain.rules

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
class LocalDecisionEngine(
    /**
     * 本应用自身的包名：Agent 自己的界面（时间线卡片里的"失败/重试"等文案）绝不能被当作
     * "异常页/弹窗"来本地处理——真机实测（BUG 21）Agent 页横幅与卡片文字命中 errorWords，
     * 端侧决策在自家页面上反复输出"按返回"，形成原地打转的盲循环。
     */
    private val selfPackage: String? = null,
) {

    /** 连续本地决策达到该次数后强制走云端（与引擎侧"连续 5 次端侧决策"的死循环提醒错开一档，避免互相打死） */
    private val maxConsecutiveLocal = 6
    private var consecutiveLocal = 0

    /**
     * 新任务开始时调用：清零连续本地决策计数。
     * 不清的话，上一个任务结尾的高计数会让新任务开头被强制走一次云端（防死循环计数跨任务残留）。
     */
    fun reset() {
        consecutiveLocal = 0
    }

    /**
     * 返回本地生成的意图 [AgentIntent]；若无法本地处理返回 null（走云端）。
     * @param snapshot 当前页面快照
     */
    fun decide(snapshot: ScreenSnapshot): AgentIntent? {
        // 本应用自己的界面不做本地决策：时间线/横幅里的"失败、重试、无法"等文案是 UI 词汇不是页面证据
        if (selfPackage != null && snapshot.packageName == selfPackage) return null
        // 元素树为空时同样不做本地决策：读不到页面 ≠ 页面是异常页，
        // 此时凭空按返回/点关闭只会盲转（真机 BUG 21/22 的帮凶），交还给云端走视觉链路
        if (snapshot.elements.isEmpty()) return null
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
        // 否定守卫：「不允许」「不同意」是拒绝表达，只是子串里含正向词，绝不能当正向按钮点
        val negativeWords = listOf("不允许", "不同意")
        val positive = snapshot.elements.firstOrNull {
            val label = it.effectiveLabel() ?: ""
            listOf("允许", "同意", "始终允许", "授权").any { k -> label.contains(k, ignoreCase = true) } &&
                negativeWords.none { k -> label.contains(k) }
        }
        // 优先点正向按钮（允许/同意），否则点关闭按钮。
        // 裸「确定」「ok」「continue」语义太泛（普通页确认按钮也会命中）移出正向词表；
        // 「知道了」是知晓类关闭，归入关闭词表
        // 裸「x」只允许整 label 精确命中：子串匹配会把 "exit"/"next"/"Max"/"text" 全误判成关闭按钮
        val dismissSubstrings = listOf("关闭", "取消", "以后再说", "跳过", "稍后", "知道了", "no", "cancel", "✕")
        val dismissExact = listOf("x", "X")
        val target = positive ?: snapshot.elements.firstOrNull {
            val label = it.effectiveLabel()?.trim() ?: ""
            dismissSubstrings.any { k -> label.contains(k, ignoreCase = true) } ||
                dismissExact.any { k -> label.equals(k, ignoreCase = true) }
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