package com.phoneagent.feature.skill

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
     * AI 一条意图的归一化结果：把"技能调用"收口为可执行映射。
     */
    sealed class Normalized {
        /** 已归一化为标准意图（内置技能 → 等价旧意图），交回原有转译链路执行 */
        data class Intent(val intent: AgentIntent) : Normalized()
        /** MCP 技能：由 MCP 客户端就地调用，不产生设备动作 */
        data class Mcp(val skill: Skill, val target: McpSkillTarget, val args: Map<String, String>) : Normalized()
        /** 拒绝执行，附中文原因（未知技能 / 已停用 / 缺必填参数） */
        data class Error(val reason: String) : Normalized()
    }

    /**
     * 把 AI 输出的一条意图归一化为"可执行映射"。AI 的 intent 字段有三种写法，端侧在此统一收口：
     *
     * 1. 标准意图名（tap / open_app …）→ 原样放行；若其对应内置技能已被用户停用则拒绝；
     * 2. 内置技能 id / 技能名（skill_open_app / 打开应用）→ 归一化为等价的旧意图，
     *    其余字段（target/app/text/…）原样保留，因此后续转译逻辑一字不改；
     * 3. MCP 技能 id → 交 MCP 客户端就地调用，参数取 `intent.args`。
     *
     * 归一化的意义：技能页的启停开关从此对运行时真正生效，AI 也能用技能名表达"做什么"。
     */
    fun normalize(intent: AgentIntent, registry: SkillRegistry): Normalized {
        val name = intent.intent
        val skill = registry.byId(name) ?: registry.byName(name) ?: registry.byLegacyIntent(name)
        // 没有对应技能的合法意图（如端侧决策直出的意图）原样放行；非法名字则明确拒绝，不让它落到"未知意图"
        if (skill == null) {
            return if (name in IntentType.ALL) Normalized.Intent(intent)
            else Normalized.Error("未知意图或技能「$name」：请改用系统提示中列出的意图或已启用技能。")
        }
        if (!skill.enabled) {
            return Normalized.Error("技能「${skill.name}」已停用，无法调用；请换用其他方式，或让用户在「技能与能力」页启用后重试。")
        }
        return when (skill.source) {
            SkillSource.MCP -> {
                val target = skill.mcp
                    ?: return Normalized.Error("MCP 技能「${skill.name}」缺少调用目标配置。")
                val args = intent.args ?: emptyMap()
                val missing = skill.params.filter { it.required && args[it.name].isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    val names = missing.joinToString("、") { "「${it.label.ifBlank { it.name }}」" }
                    Normalized.Error(
                        "技能「${skill.name}」缺少必填参数：$names。" +
                            "请用 args 对象补全，例如 {\"intent\":\"${skill.id}\",\"args\":{...}}。",
                    )
                } else {
                    Normalized.Mcp(skill, target, args)
                }
            }
            SkillSource.INTENT -> {
                val legacy = skill.legacyIntent ?: skill.id.removePrefix("skill_")
                if (legacy !in IntentType.ALL) {
                    Normalized.Error("技能「${skill.name}」未映射到可执行的意图（$legacy）。")
                } else {
                    Normalized.Intent(applyArgs(intent.copy(intent = legacy), skill, intent.args))
                }
            }
        }
    }

    /**
     * 把技能声明的参数（`args`）回填到意图字段上，让「按技能参数接口调用」真正生效。
     *
     * 背景：内置技能的 `params` 是它在技能页展示的正式接口，但意图本身用的是扁平字段
     * （app / target / text / direction / key / wait_ms …）。若只重写意图名而不读 `args`，
     * 这些参数就被静默丢弃 —— 技能只剩一个名字，等于空壳（如 `skill_swipe` 收到 direction=up
     * 却滑不动）。此处按参数名回填，使两种写法都可用：
     *
     * - `{"intent":"skill_swipe","args":{"direction":"up"}}`（技能参数接口）
     * - `{"intent":"swipe","direction":"up"}`（意图扁平字段）
     *
     * 只覆盖 `args` 里**真正给出**的字段，AI 直接写在扁平字段上的值不受影响。
     */
    private fun applyArgs(intent: AgentIntent, skill: Skill, args: Map<String, String>?): AgentIntent {
        if (args.isNullOrEmpty()) return intent
        val fromArgs = toIntent(skill, args) ?: return intent
        fun given(key: String) = !args[key].isNullOrBlank()
        return intent.copy(
            target = if (given("target")) fromArgs.target else intent.target,
            app = if (given("app")) fromArgs.app else intent.app,
            uri = if (given("uri")) fromArgs.uri else intent.uri,
            page = if (given("page")) fromArgs.page else intent.page,
            text = if (given("text")) fromArgs.text else intent.text,
            summary = if (given("summary")) fromArgs.summary else intent.summary,
            reason = if (given("reason")) fromArgs.reason else intent.reason,
            direction = if (given("direction")) fromArgs.direction else intent.direction,
            key = if (given("key")) fromArgs.key else intent.key,
            waitMs = if (given("wait_ms")) fromArgs.waitMs else intent.waitMs,
            durationMs = if (given("duration_ms")) fromArgs.durationMs else intent.durationMs,
            kind = if (given("kind")) fromArgs.kind else intent.kind,
            filter = if (given("filter")) fromArgs.filter else intent.filter,
            command = if (given("command")) fromArgs.command else intent.command,
            endpoint = if (given("endpoint")) fromArgs.endpoint else intent.endpoint,
        )
    }

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
            IntentType.REMEMBER -> agent.copy(text = args["text"], summary = args["summary"])
            IntentType.DEVICE_QUERY -> agent.copy(
                kind = args["kind"]?.takeIf { it.isNotBlank() } ?: args["text"]?.takeIf { it.isNotBlank() },
                filter = args["filter"]?.takeIf { it.isNotBlank() } ?: args["summary"]?.takeIf { it.isNotBlank() },
            )
            IntentType.SAY -> agent.copy(text = args["text"])
            IntentType.FETCH -> agent.copy(uri = args["uri"])
            // 内置浏览器：打开网址 / 抓正文 / 点元素 / 填表单 / 滚动 / 后退
            IntentType.BROWSE_OPEN -> agent.copy(uri = args["uri"])
            IntentType.BROWSE_READ, IntentType.BROWSE_BACK -> agent
            IntentType.BROWSE_CLICK -> agent
            IntentType.BROWSE_INPUT -> agent.copy(text = args["text"])
            IntentType.BROWSE_SCROLL -> agent.copy(direction = args["direction"]?.takeIf { it.isNotBlank() })
            IntentType.FINISH -> agent.copy(summary = args["summary"])
            IntentType.GIVE_UP -> agent.copy(reason = args["reason"])
            // 高层语义接口（无需参数/可选 target）
            IntentType.BACK, IntentType.HOME, IntentType.REFRESH, IntentType.SEARCH,
            IntentType.SEND, IntentType.CONFIRM, IntentType.CLOSE, IntentType.SHARE,
            IntentType.COLLECT, IntentType.COPY, IntentType.DELETE, IntentType.DOWNLOAD,
            IntentType.ADD, IntentType.SWITCH, IntentType.CLEAR_INPUT -> agent
            // 自由模式专属：AI 自写命令 / 直接调无障碍端点（端点参数保留在 intent.args 里原样透传）
            IntentType.SHELL -> agent.copy(command = args["command"]?.takeIf { it.isNotBlank() })
            IntentType.A11Y -> agent.copy(
                endpoint = args["endpoint"]?.takeIf { it.isNotBlank() },
                args = args.filterKeys { it != "endpoint" }.ifEmpty { null },
            )
            else -> null
        }
    }

    /**
     * 把参数里的 target 文本解析为 [AgentIntentTarget]。
     * 支持三种写法：`ctl_3` → by=id；`by:text:确认` → 显式指定 by；其余 → by=text。
     */
    fun parseTarget(raw: String): AgentIntentTarget {
        val s = raw.trim()
        if (s.startsWith("ctl_")) return AgentIntentTarget(by = "id", value = s)
        if (s.startsWith("by:")) {
            // 分隔符要跳过前缀本身的冒号，否则 substring(3, 2) 会越界崩溃
            val idx = s.indexOf(':', 3)
            if (idx > 3) {
                val by = s.substring(3, idx).trim()
                val value = s.substring(idx + 1).trim()
                if (by.isNotBlank() && value.isNotBlank()) return AgentIntentTarget(by = by, value = value)
            }
        }
        return AgentIntentTarget(by = "text", value = s)
    }
}