package com.phoneagent.engine.prompt

/**
 * 浇筑区块时可用变量。默认值使区块即使缺变量也能渲染出可读内容。
 *
 * 默认值一律为空串：占位符没有对应变量时**保留原文**（见 [PromptTemplateEngine.renderOnce]），
 * 这样"漏填变量"会在提示词里以 `{xxx}` 的形式直接暴露，而不是静默变成空字符串。
 */
data class PromptVars(
    /** 当前任务描述 */
    val task: String = "",
    /** 上一步执行结果 */
    val lastResult: String = "",

    // ---- 系统提示区块 ----
    /** 铁律第 2 条：随动作模式切换（自由模式放开自写命令与直调端点） */
    val ironRule2: String = "",
    /** 国产应用速查表 */
    val commonCnApps: String = "",
    /** 不可逆动作词表（端侧判定所用，同一份，避免提示词与门控漂移） */
    val irreversibleWords: String = "",

    // ---- 动作模式区块 ----
    val modeLabel: String = "",
    val modeSummary: String = "",
    val modeLabelEn: String = "",
    val modeKey: String = "",
    val modeSummaryEn: String = "",
    /** 低风险意图清单（斜杠分隔） */
    val lowRisk: String = "",
    /** 友好命令表 */
    val shellCommands: String = "",
    /** 无障碍端点表（Markdown 表格行，无表头） */
    val a11yTable: String = "",

    // ---- 技能区块 ----
    /** MCP 技能表（Markdown 表格行，无表头） */
    val mcpTable: String = "",
    /** 已停用技能名（顿号分隔） */
    val disabledNames: String = "",

    // ---- 规划 / 决策区块 ----
    val profile: String = "",
    val installedApps: String = "",
    val stepIndex: String = "",
    val totalSteps: String = "",
    val currentStep: String = "",
    val failures: String = "",
    val contextHint: String = "",
    /** 记忆简报（见 [com.phoneagent.engine.MemoryBrief]） */
    val memory: String = "",
    /** 命中的经验规则（见 RuleScoper） */
    val evolvedRules: String = "",

    // ---- 记忆提炼区块 ----
    val outcome: String = "",
    val stepsSummary: String = "",
) {
    val map: Map<String, String> = mapOf(
        "task" to task,
        "lastResult" to lastResult,

        "iron_rule_2" to ironRule2,
        "common_cn_apps" to commonCnApps,
        "irreversible_words" to irreversibleWords,

        "mode_label" to modeLabel,
        "mode_summary" to modeSummary,
        "mode_label_en" to modeLabelEn,
        "mode_key" to modeKey,
        "mode_summary_en" to modeSummaryEn,
        "low_risk" to lowRisk,
        "shell_commands" to shellCommands,
        "a11y_table" to a11yTable,

        "mcp_table" to mcpTable,
        "disabled_names" to disabledNames,

        "profile" to profile,
        "installed_apps" to installedApps,
        "step_index" to stepIndex,
        "total_steps" to totalSteps,
        "current_step" to currentStep,
        "failures" to failures,
        "context_hint" to contextHint,
        "memory" to memory,
        "evolved_rules" to evolvedRules,
        "last_result" to lastResult,

        "outcome" to outcome,
        "steps_summary" to stepsSummary,
    )
}

/** 渲染可变模板 */
object PromptTemplateEngine {
    /** `{name}` 形式的占位符：只认标识符，天然避开正文里的 JSON 花括号 */
    private val PLACEHOLDER = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")

    /** 把模板中的 {key} 占位符替换为变量值；未在变量表中的占位符保留原样 */
    fun render(template: String, vars: PromptVars): String = renderOnce(template, vars.map)

    /**
     * 单趟替换：**先扫出全部占位符再一次性替换**。
     *
     * 不能用"逐个变量反复 replace"——那样当某个变量值里恰好含有另一个占位符文本时
     * （例如用户任务里写了 `{task}`），第二轮替换会把它当成占位符二次展开。
     */
    fun renderOnce(template: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> vars[m.groupValues[1]] ?: m.value }

    /** 若模板为空白，返回 null 以触发默认回退 */
    fun effectiveBody(body: String): String? = body.trim().ifBlank { null }
}