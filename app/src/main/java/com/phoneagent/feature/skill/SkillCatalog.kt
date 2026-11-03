package com.phoneagent.feature.skill

import com.phoneagent.domain.model.CapabilityRegistry
import com.phoneagent.domain.model.CapabilitySpec

/**
 * 内置 Skill 目录：把声明式能力注册表（[CapabilityRegistry]）适配成 [Skill] 模型。
 *
 * 这里是**纯适配层**：名称、说明、分类、参数、风险都来自注册表，本文件不重复定义任何一项能力的业务内容
 * （此前 44 项技能在这里各写一份 name/description/params，与 ActionPolicy 的风险档、提示词意图表靠人工对齐）。
 *
 * 兼容链路不变：每个内置 Skill 通过 [Skill.legacyIntent] 指回旧命令意图名，由 [SkillCompat] 保证
 * “旧命令 → 对应内置 Skill → 原有转译逻辑”的向下兼容。
 */
object SkillCatalog {

    /** 由注册表派生的内置技能（顺序 = 注册表声明顺序 = App 技能列表展示顺序） */
    private val catalog: Map<String, Skill> =
        CapabilityRegistry.all.associateBy({ it.intent }, { it.toSkill() })

    fun builtins(): List<Skill> = catalog.values.toList()

    fun byLegacyIntent(intentName: String): Skill? = catalog[intentName]

    fun byId(id: String): Skill? = builtins().firstOrNull { it.id == id }
}

/** 能力声明 → 内置技能（参数模型转换在此一处完成） */
private fun CapabilitySpec.toSkill(): Skill = Skill(
    id = "skill_$intent",
    name = name,
    source = SkillSource.INTENT,
    isBuiltIn = true,
    category = category,
    legacyIntent = intent,
    description = summary,
    params = params.map {
        SkillParam(
            name = it.name,
            label = it.label,
            type = it.type,
            required = it.required,
            description = it.description,
            defaultValue = it.defaultValue,
            options = it.options,
        )
    },
)
