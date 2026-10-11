package com.phoneagent.feature.skill

/**
 * 宏步骤参数渲染器：把步骤 args 里的占位符在执行现场展开为真实值。
 *
 * 两种占位符（纯函数，零 Android 依赖，可独立单测）：
 * - `{{参数名}}`：宏的入参（AI 调宏时 args 里给的值，经 normalize 缺参校验后必填项必有值）
 * - `{{stepN}}`：第 N 步（1 起）的执行结果文本（截 200 字），供后续步骤引用前步产出
 *
 * 展开规则：
 * - 引用未来步骤（N 大于当前已执行步数）或越界 → 替换为空串（模板语法绝不透传给执行层）；
 * - 未提供的宏参数（可选项缺省）→ 替换为空串；
 * - 结果文本按 [STEP_RESULT_MAX] 截断，防止前步长输出把后步参数撑爆。
 */
object MacroStepExpander {

    /** 步骤结果引用上限：超过该长度的前步产出在渲染时截断 */
    const val STEP_RESULT_MAX = 200

    private val PLACEHOLDER = Regex("\\{\\{(.+?)\\}\\}")

    /** `{{stepN}}` 的编号提取：命中且 N≥1 返回 N，否则 null（按普通参数名处理） */
    private fun stepIndexOf(name: String): Int? =
        name.removePrefix("step").toIntOrNull()?.takeIf { name.startsWith("step") && it >= 1 }

    /**
     * 渲染一个步骤的参数表。
     * @param stepArgs 步骤声明的固定参数（值可含占位符）
     * @param macroArgs 宏入参（AI 调宏时给的 args）
     * @param stepResults 已执行步骤的结果文本（下标 0 = 第 1 步）
     */
    fun expand(stepArgs: Map<String, String>, macroArgs: Map<String, String>, stepResults: List<String>): Map<String, String> =
        stepArgs.mapValues { (_, raw) -> expandValue(raw, macroArgs, stepResults) }

    /** 渲染单个值：占位符整体替换；无占位符原样返回 */
    fun expandValue(raw: String, macroArgs: Map<String, String>, stepResults: List<String>): String =
        PLACEHOLDER.replace(raw) { m ->
            val name = m.groupValues[1].trim()
            val stepIdx = stepIndexOf(name)
            when {
                stepIdx != null -> stepResults.getOrNull(stepIdx - 1).orEmpty().take(STEP_RESULT_MAX)
                else -> macroArgs[name].orEmpty()
            }
        }
}
