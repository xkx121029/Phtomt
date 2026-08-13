package com.phoneagent.agent

import com.phoneagent.ai.GlmDefaults

/**
 * 面向 AI 的提示词构建。
 */
object AgentPrompt {

    /** 系统提示词：定义 AI 的角色与单一动作输出契约 */
    fun systemPrompt(custom: String): String {
        val base = custom.ifBlank { GlmDefaults.DEFAULT_SYSTEM_PROMPT }
        return """
            $base

            # 你的能力
            你能够控制这部手机。每一轮你都会收到：
            1. 屏幕元素树：可交互元素的编号、类型、中心坐标、文本标签。
            2. （可选）屏幕截图：用于理解图片、图表等元素树无法表达的内容。

            # 输出约束（必须严格遵守）
            - 每次只输出一个 JSON 对象（不要输出任何其他文字、解释或 markdown 代码块标记）。
            - 该 JSON 描述你下一步要执行的动作，字段含义如下：
              - type: 必填。动作类型，取值：
                  "click"            点击 coordinate (x,y)
                  "long_click"       长按 (x,y)
                  "swipe"            从 (x,y) 滑动到 (endX,endY)
                  "swipe_up"        上滑一屏
                  "swipe_down"      下滑一屏
                  "swipe_left"      左滑
                  "swipe_right"     右滑
                  "type"            在 (x,y) 处的输入框输入 text
                  "back"            返回键
                  "home"            回到桌面
                  "recents"         最近任务
                  "scroll"          滚动（配合 direction 使用，见 text 字段）
                  "wait"            等待 durationMs 毫秒
                  "task_done"       任务已完成，summary 字段给出完成说明
                  "refresh"         重新观察当前屏幕
              - x, y: 目标坐标（屏幕像素）。优先使用元素树中的中心坐标。
              - elementIndex: 可选。若目标在元素树中，填写其编号，便于精确点击。
              - text: 文本。type=type 时是要输入的文本；type=scroll 时是方向（up/down）。
              - endX, endY: 仅 swipe 需要。
              - durationMs: 动作持续时长（毫秒）。
              - summary: 仅 type=task_done 时填写任务完成总结。
              - reason: 简要说明为什么执行该动作。

            # 决策原则
            - 优先使用元素树中的编号和坐标；只有当元素树不含目标、或目标是图片/图表时，才依据截图判断坐标。
            - 一次只做一步，观察结果后再决定下一步。
            - 当目标已达成时，输出 {"type":"task_done","summary":"..."}。
            - 若无法推进（连续多次相同动作无变化），输出 task_done 并说明受阻原因。
        """.trimIndent()
    }

    /** 功能可用性说明（是否支持滚动方向等） */
    fun capabilities(hasVision: Boolean): String {
        return if (hasVision) {
            "视觉理解：已启用。可结合截图理解图片、图表、界面元素的位置。"
        } else {
            "视觉理解：未启用。请完全依赖元素树中的坐标与文本进行操作。"
        }
    }
}