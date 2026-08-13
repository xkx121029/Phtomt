package com.phoneagent.model

import kotlinx.serialization.Serializable

/**
 * 屏幕上的一个可交互元素，由无障碍服务从元素树中提取。
 *
 * 携带坐标、类型、文本标签等结构化信息，供 AI 决策点击/滑动目标。
 */
@Serializable
data class UiElement(
    /** 在元素树中的全局编号，AI 可用它精确定位元素 */
    val index: Int,
    /** 控件类名（如 android.widget.Button） */
    val className: String,
    /** 控件类型（Button / ImageButton / Text / MenuItem 等，语义化） */
    val type: String,
    /** 文本标签 */
    val text: String? = null,
    /** 内容描述（无障碍标签） */
    val contentDescription: String? = null,
    /** 元素中心点 x */
    val x: Int,
    /** 元素中心点 y */
    val y: Int,
    /** 元素边界（左上角） */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** 是否可点击 */
    val clickable: Boolean = false,
    /** 是否可长按 */
    val longClickable: Boolean = false,
    /** 是否可滚动 */
    val scrollable: Boolean = false,
    /** 是否可编辑（输入框） */
    val editable: Boolean = false,
    /** View id 资源名（如 "button_send"） */
    val viewId: String? = null,
    /** 所属应用包名 */
    val packageName: String? = null,
    val isEnabled: Boolean = true,
    /** 是否可见 */
    val isVisibleToUser: Boolean = true,
    /** 子元素数量 */
    val childCount: Int = 0,
    /** 端侧标注的优先级（high/medium/low） */
    val priority: String = "medium",
    /** 中心点比例坐标 x（0~1），由标注器基于屏幕尺寸计算 */
    val ratioX: Float? = null,
    /** 中心点比例坐标 y（0~1） */
    val ratioY: Float? = null,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** 生成供 AI 阅读的一行描述 */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += "[#$index] $className($type)"
        val label = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
        if (!label.isNullOrBlank()) parts += "label=\"$label\""
        parts += "center=(${centerX},${centerY}) bounds=($left,$top)-($right,$bottom)"
        return parts.joinToString(" ")
    }
}