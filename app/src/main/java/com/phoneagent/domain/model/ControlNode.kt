package com.phoneagent.domain.model.ControlNode

import kotlinx.serialization.Serializable

/**
 * 控件树标准化 JSON 节点。
 *
 * 这是软件端输出给 AI Agent 的“完整结构化控件数组”中的单个对象，字段严格对齐 HPA 迭代需求：
 * - id：软件端分配的控件唯一编号（AI 通过 id + 动作指令来定位控件执行操作）
 * - name：控件名称
 * - type：控件类型（Button / EditText / ImageView / CheckBox ...）
 * - visibleText：外显文字，可为空
 * - isSelected：是否选中（布尔），未知时为空
 * - currentValue：控件当前值（如输入框内容/滑块值），可为空
 * - index：控件在数组中的索引
 * - comment：控件注释，可为空（端侧标注的语义提示）
 * - extra：其他控件附属信息（包名、布局边界、中心坐标、可见性、优先级等）
 */
@Serializable
data class ControlNode(
    /** 软件端分配的唯一编号，如 "ctl_3"。AI 决策时用 id 引用，执行层按 id 定位控件。 */
    val id: String,
    /** 控件名称（优先取文本，其次内容描述，再退 className） */
    val name: String,
    /** 控件类型（Button / EditText / ImageView / CheckBox / ...，语义化） */
    val type: String,
    /** 外显文字，可为空 */
    val visibleText: String? = null,
    /** 是否选中（布尔），未知为空 */
    val isSelected: Boolean? = null,
    /** 控件当前值（输入框内容/滑块值），可为空 */
    val currentValue: String? = null,
    /** 控件在控件数组中的索引 */
    val index: Int,
    /** 控件注释，可为空（端侧语义标注） */
    val comment: String? = null,
    /** 其他控件附属信息 */
    val extra: ControlExtra? = null,
) {
    /** 序列化为可供 AI 阅读的单行描述 */
    fun toAiLine(): String {
        val sb = StringBuilder()
        sb.append("{\"id\":\"$id\",\"name\":\"$name\",\"type\":\"$type\"")
        if (!visibleText.isNullOrBlank()) sb.append(",\"visibleText\":\"${escape(visibleText)}\"")
        if (isSelected != null) sb.append(",\"isSelected\":$isSelected")
        if (!currentValue.isNullOrBlank()) sb.append(",\"currentValue\":\"${escape(currentValue)}\"")
        if (!comment.isNullOrBlank()) sb.append(",\"comment\":\"${escape(comment)}\"")
        if (extra != null) sb.append(",\"extra\":${extra.toJsonText()}")
        sb.append("}")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/** 控件附属信息（extra） */
@Serializable
data class ControlExtra(
    /** 无障碍元素类名，如 android.widget.Button */
    val className: String? = null,
    /** 中心坐标，AI 不理解时端侧用于按 id 定位的辅助 */
    val x: Int? = null,
    val y: Int? = null,
    /** 布局边界 left,top,right,bottom */
    val bounds: String? = null,
    /** 是否可见 */
    val visible: Boolean = true,
    /** 是否可交互 */
    val enabled: Boolean = true,
    /** 端侧语义标注（dlg_allow / search_box / ...） */
    val semantic: String? = null,
    /** 布局 viewId 资源名，如 button_send */
    val viewId: String? = null,
    /** 是否可滚动 / 可长按 / 可编辑 */
    val scrollable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    /** 端侧标注优先级 high/medium/low */
    val priority: String = "medium",
    /** 关联的原元素树索引（端侧执行时按它映射回 UiElement） */
    val elementIndex: Int? = null,
) {
    fun toJsonText(): String {
        val sb = StringBuilder("{")
        className?.let { sb.append("\"className\":\"${it}\",") }
        if (x != null) sb.append("\"x\":$x,")
        if (y != null) sb.append("\"y\":$y,")
        bounds?.let { sb.append("\"bounds\":\"${it}\",") }
        sb.append("\"visible\":$visible,\"enabled\":$enabled,")
        semantic?.let { sb.append("\"semantic\":\"${it}\",") }
        viewId?.let { sb.append("\"viewId\":\"${it}\",") }
        sb.append("\"scrollable\":$scrollable,\"longClickable\":$longClickable,\"editable\":$editable,")
        sb.append("\"priority\":\"$priority\"")
        elementIndex?.let { sb.append(",\"elementIndex\":$it") }
        sb.append("}")
        return sb.toString()
    }
}