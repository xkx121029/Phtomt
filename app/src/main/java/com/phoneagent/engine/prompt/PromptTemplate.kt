package com.phoneagent.engine.prompt.PromptTemplate

import kotlinx.serialization.Serializable

/**
 * 可自定义提示词模板。
 *
 * 模板正文支持 `{占位符}` 变量注入（见 [PromptVars]），由 [PromptTemplateEngine] 渲染。
 * 空模板 → 回退内置默认（兼容现有 [com.phoneagent.engine.AgentPrompts] 行为）。
 */
@Serializable
data class PromptTemplate(
    /** 唯一 id，如 "system" / "planning" / "decision" / "verify" */
    val id: String,
    /** 展示名 */
    val name: String,
    /** 模板正文，可含 {占位符} */
    val body: String,
    /** 是否内置（内置模板可被用户改写但不可删除） */
    val isBuiltIn: Boolean = false,
)

/** 浇筑模板时可用变量。默认值使模板即使缺变量也能渲染出可读内容。 */
data class PromptVars(
    /** 当前任务描述 */
    val task: String = "",
    /** 可用 Skill 列表（AI 二值 JSON 摘要） */
    val skills: String = "",
    /** MCP 工具列表 */
    val mcpTools: String = "",
    /** 结构化控件 JSON 数组文本（来自 ControlTreeBuilder） */
    val controlsJson: String = "[]",
    /** 当前应用信息 */
    val currentApp: String = "",
    /** 上一步执行结果 */
    val lastResult: String = "",
    /** 用户目标 */
    val goal: String = "",
    /** 审核拒绝理由（如有） */
    val auditRejection: String = "",
    /** 用户场景化注入（额外参数，如"处于 Happy Agent 应用内、手机已解锁"） */
    val situational: String = "",
) {
    val map: Map<String, String> = mapOf(
        "task" to task,
        "skills" to skills,
        "mcpTools" to mcpTools,
        "controls" to controlsJson,
        "currentApp" to currentApp,
        "lastResult" to lastResult,
        "goal" to goal,
        "auditRejection" to auditRejection,
        "situational" to situational,
    )
}

/** 渲染可变模板 */
object PromptTemplateEngine {
    /** 把模板中的 {key} 占位符替换为变量值；未在变量表中的占位符保留原样 */
    fun render(template: String, vars: PromptVars): String {
        var out = template
        vars.map.forEach { (k, v) ->
            out = out.replace("{$k}", v)
        }
        return out
    }

    /** 若模板为空白，返回 null 以触发默认回退 */
    fun effectiveBody(body: String): String? = body.trim().ifBlank { null }
}