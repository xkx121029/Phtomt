package com.phoneagent.engine.perception

import com.phoneagent.domain.model.ControlExtra
import com.phoneagent.domain.model.ControlNode
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement

/**
 * 控件树构建器：把感知层采集到的元素树（[ScreenSnapshot.elements]）转成
 * 标准化控件数组（[ControlNode]），输出给 AI Agent。
 *
 * 交互流程：
 * Agent 请求界面控件 → 软件端返回完整 JSON 控件数组 → AI 返回“控件 id + 动作指令”
 * → 软件端根据 id 定位控件执行操作。
 *
 * id 策略：固定前缀 "ctl_" + 控件在数组中的序号（0 基），保证稳定、可回溯。
 * 执行层用 [ControlNode.extra.elementIndex] 把 id 映射回原始元素，按 id 快速定位。
 */
object ControlTreeBuilder {

    /** 生成控件 id：ctl_ + 序号 */
    fun idOf(index: Int): String = "ctl_$index"

    /**
     * 从屏幕快照构建控件数组。仅输出可交互或可见有价值控件，降低上下文体积。
     * @param includeAll false 时只保留可交互/有外显名的控件，减少 AI 上下文负担；
     *                   true 时全量输出（调试/测试用）。
     */
    fun build(snapshot: ScreenSnapshot, includeAll: Boolean = false): List<ControlNode> {
        val controls = ArrayList<ControlNode>(snapshot.elements.size)
        var outIndex = 0
        snapshot.elements.forEach { elem ->
            if (!includeAll && !worthKeeping(elem)) return@forEach
            val node = toControlNode(elem, outIndex)
            controls.add(node)
            outIndex++
        }
        return controls
    }

    /** 判断元素是否值得输出给 AI：可交互、有文字/描述、可编辑、或可见 */
    private fun worthKeeping(e: UiElement): Boolean =
        e.clickable || e.longClickable || e.scrollable || e.editable ||
            !e.text.isNullOrBlank() || !e.contentDescription.isNullOrBlank() ||
            e.isVisibleToUser

    private fun toControlNode(e: UiElement, index: Int): ControlNode {
        val label = e.text?.takeIf { it.isNotBlank() }
            ?: e.contentDescription?.takeIf { it.isNotBlank() }
            ?: e.className.substringAfterLast('.')
        val type = e.type.ifBlank {
            when {
                e.editable -> "EditText"
                e.clickable -> "Button"
                e.scrollable -> "ScrollView"
                else -> e.className.substringAfterLast('.')
            }
        }
        return ControlNode(
            id = idOf(index),
            name = label,
            type = type,
            visibleText = e.text?.takeIf { it.isNotBlank() },
            isSelected = e.isSelected,
            currentValue = e.currentValue,
            index = index,
            comment = e.semanticId?.let { "semantic=$it" },
            extra = ControlExtra(
                className = e.className,
                x = e.centerX,
                y = e.centerY,
                bounds = "(${e.left},${e.top})-(${e.right},${e.bottom})",
                visible = e.isVisibleToUser,
                enabled = e.isEnabled,
                semantic = e.semanticId,
                viewId = e.viewId,
                scrollable = e.scrollable,
                longClickable = e.longClickable,
                editable = e.editable,
                priority = e.priority,
                elementIndex = e.index,
            ),
        )
    }

    /** 生成供 AI 阅读的控件数组文本（JSON 风格，逐行） */
    fun toAiText(controls: List<ControlNode>): String =
        if (controls.isEmpty()) "[]" else controls.joinToString(",\n", "[", "]") { it.toAiLine() }
}