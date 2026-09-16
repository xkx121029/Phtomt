package com.phoneagent.feature.skill.SkillCompat

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType

/**
 * Skill 兼容转译层（Shim）。
 *
 * 职责：在【原硬编码命令体系】与【Skill 体系】之间做双向无损映射，保证向下兼容：
 *
 * 1. 旧命令 → Skill：给定的旧 [AgentIntent] 解析为对应内置 Skill（[skillForLegacyIntent]），
 *    使“AI 叫出旧命令名”依然可达、可被注册表管理、可展示为 Skill。
 *
 * 2. Skill → 旧命令：把“按技能名 + 参数”的调用（[SkillInvocation]）翻译回一条 [AgentIntent]，
 *    交还原有 IntentTranslator 走原逻辑转译执行（[toIntent]）。原逻辑一字不改，因此旧链路永不失效。
 *
 * 3. MCP 分流：source=MCP 的 Skill 不走旧命令，直接解析为 [Resolution.Mcp] 交给 MCP 执行器。
 */
object SkillCompat {

    sealed class Resolution {
        /** 命中内置/意图类技能 → 翻译回旧命令意图，交还原有转译层 */
        data class LegacyIntent(val intent: AgentIntent) : Resolution()
        /** 命中 MCP 技能 → 交给 MCP 客户端调用 */
        data class Mcp(val target: McpSkillTarget, val args: Map<String, String>) : Resolution()
        /** 未知技能 */
        data class Unknown(val skillId: String) : Resolution()
        /** 语法/参数错误，带中文提示 */
        data class Error(val skillId: String, val reason: String) : Resolution()
    }

    /** 旧命令意图 → 对应内置 Skill（向后兼容：旧命令名等价一个内置技能） */
    fun skillForLegacyIntent(intent: AgentIntent): Skill? =
        SkillCatalog.byLegacyIntent(intent.intent)

    /**
     * 解析一条 [SkillInvocation]（AI 按技能名调用）为可执行映射。
     * @param registry Skill 注册表（含内置/自定义/MCP）
     */
    fun resolve(invocation: SkillInvocation, registry: SkillRegistry): Resolution {
        val skill = registry.byId(invocation.skillId) ?: registry.byName(invocation.skillId)
            ?: return Resolution.Unknown(invocation.skillId)
        if (!skill.enabled) {
            return Resolution.Error(skill.id, "技能「${skill.name}」已停用，无法调用。")
        }
        val args = invocation.args
        // 必填参数校验
        val missing = skill.params.filter { it.required && args[it.name].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString("、") { "「${it.label ?: it.name}」" }
            return Resolution.Error(skill.id, "技能「${skill.name}」缺少必填参数：$names。")
        }
        return when (skill.source) {
            SkillSource.MCP -> {
                val target = skill.mcp ?: return Resolution.Error(skill.id, "MCP 技能「${skill.name}」缺少调用目标配置。")
                Resolution.Mcp(target, args)
            }
            SkillSource.INTENT -> {
                val intent = toIntent(skill, args)
                    ?: return Resolution.Error(skill.id, "技能「${skill.name}」无法映射到任何执行命令。")
                Resolution.LegacyIntent(intent)
            }
        }
    }

    /** 按用户给的“技能名/意图名”解析（兼容层入口，供引擎决策循环调用） */
    fun resolveByName(name: String, args: Map<String, String>, registry: SkillRegistry): Resolution {
        val skill = registry.byName(name)
            ?: registry.byLegacyIntent(name)
            ?: return Resolution.Unknown(name)
        return resolve(SkillInvocation(skillId = skill.id, args = args), registry)
    }

    /**
     * 内置意图技能 → 旧 AgentIntent。
     * 把参数 [args] 回填到 AgentIntent 对应字段，交还原有 IntentTranslator。
     * 该映射仅把“技能调用”规范化为“旧命令意图”，不改变原有转译逻辑。
     */
    fun toIntent(skill: Skill, args: Map<String, String>): AgentIntent? {
        val legacy = skill.legacyIntent ?: skill.id.removePrefix("skill_")
        val t = args["target"]?.takeIf { it.isNotBlank() }
        val target = t?.let { parseTarget(it) }
        val agent = AgentIntent(intent = legacy, target = target)
        return when (legacy) {
            IntentType.OPEN_APP -> agent.copy(app = args["app"]?.takeIf { it.isNotBlank() })
            IntentType.OPEN -> agent.copy(
                uri = args["uri"]?.takeIf { it.isNotBlank() },
                app = args["app"]?.takeIf { it.isNotBlank() },
                page = args["page"]?.toIntOrNull(),
            )
            IntentType.TAP -> agent
            IntentType.LONG_PRESS -> agent.copy(durationMs = args["duration_ms"]?.toLongOrNull())
            IntentType.INPUT -> agent.copy(text = args["text"])
            IntentType.SWIPE -> agent.copy(direction = args["direction"]?.takeIf { it.isNotBlank() })
            IntentType.PRESS -> agent.copy(key = args["key"]?.takeIf { it.isNotBlank() })
            IntentType.WAIT -> agent.copy(waitMs = args["wait_ms"]?.toLongOrNull())
            IntentType.SCROLL_TO -> agent
            IntentType.WRITE_DOC -> agent.copy(text = args["text"], summary = args["summary"])
            IntentType.FINISH -> agent.copy(summary = args["summary"])
            IntentType.GIVE_UP -> agent.copy(reason = args["reason"])
            // 高层语义接口（无需参数/可选 target）
            IntentType.BACK, IntentType.HOME, IntentType.REFRESH, IntentType.SEARCH,
            IntentType.SEND, IntentType.CONFIRM, IntentType.CLOSE, IntentType.SHARE,
            IntentType.COLLECT, IntentType.COPY, IntentType.DELETE, IntentType.DOWNLOAD,
            IntentType.ADD, IntentType.SWITCH, IntentType.CLEAR_INPUT -> agent
            else -> null
        }
    }

    /** 把参数里的 target 文本解析为 [AgentIntentTarget]；形如 "ctl_3" → by=id；"文字" → by=text */
    fun parseTarget(raw: String): AgentIntentTarget = when {
        raw.trim().startsWith("ctl_") -> AgentIntentTarget(by = "id", value = raw.trim())
        raw.contains(":") && raw.startsWith("by:") -> {
            val idx = raw.indexOf(":")
            val by = raw.substring(3, idx).trim()
            AgentIntentTarget(by = by, value = raw.substring(idx + 1).trim())
        }
        else -> AgentIntentTarget(by = "text", value = raw.trim())
    }
}