package com.phoneagent.domain.model.ScreenSnapshot

import kotlinx.serialization.Serializable

/**
 * 一次屏幕状态快照：元素树 + 可选截图。
 * 这是 Agent 每一轮观察到的“世界状态”，交给 AI 进行决策。
 */
@Serializable
data class ScreenSnapshot(
    /** 前台应用包名 */
    val packageName: String? = null,
    /** 是否缺少无障碍服务（元素树不可用） */
    val missingAccessibility: Boolean = false,
    /** 屏幕逻辑宽高（像素） */
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    /** 可交互元素列表（按层级/顺序排列） */
    val elements: List<UiElement> = emptyList(),
    /** 是否附带截图（用于视觉理解） */
    val hasScreenshot: Boolean = false,
) {
    /** 生成面向 AI 的文本描述 */
    fun toAiText(): String {
        val sb = StringBuilder()
        sb.appendLine("当前前台应用: ${packageName ?: "未知"}")
        sb.appendLine("屏幕分辨率: ${screenWidth}x$screenHeight")
        if (elements.isEmpty()) {
            sb.appendLine("未检测到可交互元素。")
        } else {
            sb.appendLine("可交互元素（共 ${elements.size} 个）：")
            elements.forEach { sb.appendLine("  " + it.describe()) }
        }
        return sb.toString()
    }
}