package com.phoneagent.feature.skill

import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.domain.model.ScreenSnapshot

/**
 * Skill/MCP 执行网关：把一次技能调用解析并归一化为可执行结果。
 *
 * 链路：
 * 1. [SkillCompat.resolve] 解析 [SkillInvocation]：
 *    - LegacyIntent → 交回原有 IntentTranslator 转译（保留全部旧逻辑）
 *    - Mcp → 调 [McpManager.callTarget]
 *    - Error / Unknown → 归一化为带中文提示的失败
 * 2. 返回统一 [SkillDispatch] 结果，供引擎决策回路 / 测试模块统一处理。
 *
 * @param mcpManager 可为空（未配置 MCP 时 MCP 类技能报“未配置”）
 */
class SkillExecutionGateway(
    private val registry: SkillRegistry,
    private val mcpManager: McpManager? = null,
) {
    sealed class Dispatch {
        /** 翻译回旧命令意图，等待智能体按其原有通道执行 */
        data class Legacy(val intent: com.phoneagent.domain.model.AgentIntent, val skill: Skill) : Dispatch()
        /** 交由 MCP 客户端实时调用 */
        data class McpInvoke(
            val server: String,
            val tool: String,
            val args: Map<String, String>,
        ) : Dispatch()
        /** 解析失败，携带用户可读中文提示 */
        data class Error(val reason: String) : Dispatch()
    }

    /** 通过技能名/技能 id 解析一次调用 */
    fun resolve(invocation: SkillInvocation): Dispatch = when (val r = SkillCompat.resolve(invocation, registry)) {
        is SkillCompat.Resolution.LegacyIntent -> {
            val skill = registry.byId(invocation.skillId) ?: registry.byName(invocation.skillId)
            Dispatch.Legacy(r.intent, skill ?: Skill(id = invocation.skillId, name = invocation.skillId))
        }
        is SkillCompat.Resolution.Mcp -> {
            if (mcpManager == null) {
                Dispatch.Error("MCP 未配置：技能「${r.target.tool}」无法调用（未启用任何 MCP 服务器）")
            } else {
                Dispatch.McpInvoke(r.target.server, r.target.tool, r.args)
            }
        }
        is SkillCompat.Resolution.Unknown -> Dispatch.Error("未知技能「${invocation.skillId}」，请检查技能名称")
        is SkillCompat.Resolution.Error -> Dispatch.Error(r.reason)
    }

    /** 通过旧命令 / 技能名直接解析（兼容 AI 既输出旧命令又输出技能名的场景） */
    fun resolveByName(name: String, args: Map<String, String>): Dispatch =
        resolve(SkillInvocation(skillId = name, args = args))

    /**
     * AI 意图归一化：技能名 / 技能 id（含 MCP 技能）→ 标准意图 / MCP 调用 / 中文错误。
     * 这是"技能"进入实际执行链路的收口点，见 [SkillCompat.normalize]。
     */
    fun normalize(intent: com.phoneagent.domain.model.AgentIntent): SkillCompat.Normalized =
        SkillCompat.normalize(intent, registry)

    /** 已启用的 MCP 技能（供提示词注入；只有这些技能可被 AI 调用） */
    fun enabledMcpSkills(): List<Skill> = registry.enabled().filter { it.source == SkillSource.MCP }

    /** 已被用户停用的技能（提示词中声明不可调用，避免 AI 白试） */
    fun disabledSkills(): List<Skill> = registry.all().filter { !it.enabled }

    /** 是否存在已启用的 MCP 服务器：用于提示"已配置但尚未绑定技能" */
    fun hasEnabledMcpServer(): Boolean = mcpManager?.enabledServers()?.isNotEmpty() == true

    /** 一行式 MCP 技能说明（技能 id | 名称 | 说明 | 参数），供提示词表格注入 */
    fun mcpSkillLine(skill: Skill): String {
        val params = if (skill.params.isEmpty()) {
            "无参数"
        } else {
            skill.params.joinToString("、") { p ->
                val required = if (p.required) "必填" else "可选"
                val options = if (p.options.isNotEmpty()) "[${p.options.joinToString("/")}]" else ""
                "${p.name}($required,${p.type}$options)"
            }
        }
        return "${skill.id} | ${skill.name} | ${skill.description.ifBlank { skill.name }} | $params"
    }

    /** 就地调用 MCP 技能；未配置 MCP 或调用抛错时返回中文失败结果（不抛出，交引擎按普通失败处理） */
    suspend fun invokeMcp(target: McpSkillTarget, args: Map<String, String>): com.phoneagent.feature.mcp.McpCallResult {
        val mcp = mcpManager
            ?: return com.phoneagent.feature.mcp.McpCallResult(
                isError = true,
                content = "MCP 未配置：技能「${target.tool}」无法调用（未启用任何 MCP 服务器）",
            )
        return runCatching { mcp.callTarget(target, args) }
            .getOrElse {
                com.phoneagent.feature.mcp.McpCallResult(isError = true, content = "MCP 调用异常：${it.message ?: "未知错误"}")
            }
    }

    /** 判定一条意图是否可归类为内置技能（向后兼容展示用） */
    fun skillForLegacy(intent: com.phoneagent.domain.model.AgentIntent): Skill? =
        SkillCompat.skillForLegacyIntent(intent)

    /** 从快照生成结构化控件 JSON 文本（供提示词注入） */
    fun controlsJson(snapshot: ScreenSnapshot?, includeAll: Boolean = false): String {
        if (snapshot == null) return "[]"
        val controls = com.phoneagent.engine.perception.ControlTreeBuilder.build(snapshot, includeAll)
        return com.phoneagent.engine.perception.ControlTreeBuilder.toAiText(controls)
    }

    /** 可用技能清单摘要（供提示词注入） */
    fun skillListSummary(showDisabled: Boolean = false): String {
        val list = if (showDisabled) registry.all() else registry.enabled()
        if (list.isEmpty()) return "（无可用技能）"
        return list.joinToString("、") { s ->
            "${s.name}(${s.id})${if (s.source == SkillSource.MCP) "[MCP]" else ""}"
        }
    }
}