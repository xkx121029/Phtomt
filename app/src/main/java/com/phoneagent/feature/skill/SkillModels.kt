package com.phoneagent.feature.skill.SkillModels

import kotlinx.serialization.Serializable

/**
 * Skill 数据模型。
 *
 * Skill = 一个命名、可参数化、可复用的“手机能力”。HPA 迭代将原硬编码命令（open_app、tap、input …）
 * 升级为 Skill 体系：内置 Skill 继承原命令的等价行为，兼容层保证旧命令照常工作；同时支持用户深度自定义、
 * 批量增删、导入导出，并与 MCP 工具共存。
 *
 * 三种来源（[SkillSource]）：
 * - INTENT：端侧意图能力（底层映射回旧命令，走原有 IntentTranslator 转译，保留全部原逻辑）
 * - MCP：调用约定的 MCP 工具
 */
@Serializable
data class Skill(
    /** 全局唯一 id，如 "skill_open_app" / "skill_user_xxx" */
    val id: String,
    /** 中文名，AI 与用户可见 */
    val name: String,
    /** 用途说明（喂给 AI 以决定何时调用） */
    val description: String = "",
    /** 来源：INTENT / MCP */
    val source: SkillSource = SkillSource.INTENT,
    /** 是否内置（内置不可改 id/不可删除，可启停） */
    val isBuiltIn: Boolean = false,
    /** 分类（分组展示用） */
    val category: String = "通用",
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 参数定义（自定义技能采集用户输入） */
    val params: List<SkillParam> = emptyList(),
    /** 兼容层映射：该 Skill 对应的一条旧命令意图名（如 "open_app"）；INTENT 内置技能必填 */
    val legacyIntent: String? = null,
    /** source=MCP 时的调用目标 */
    val mcp: McpSkillTarget? = null,
    /** 由谁创建（内置 = "system"，自定义 = 用户 id/名） */
    val createdBy: String = "system",
    /** 版本 */
    val version: Int = 1,
)

@Serializable
enum class SkillSource { INTENT, MCP }

/** Skill 参数定义 */
@Serializable
data class SkillParam(
    /** 参数名（模板占位符 key） */
    val name: String,
    /** 展示标签 */
    val label: String,
    /** 类型：text / number / boolean / select */
    val type: String = "text",
    /** 是否必填 */
    val required: Boolean = false,
    /** 说明 */
    val description: String? = null,
    /** 默认值 */
    val defaultValue: String? = null,
    /** type=select 时的候选项 */
    val options: List<String> = emptyList(),
)

/** MCP 调用目标：server 上的某个 tool */
@Serializable
data class McpSkillTarget(
    /** MCP 服务器名，如 "filesystem" */
    val server: String,
    /** 工具名，如 "read_file" */
    val tool: String,
    /** 参数模板 JSON，如 {"path":"{{filePath}}"}，{{param}} 由调用时替换 */
    val argsTemplate: String = "{}",
    /** 工具简介（喂给 AI） */
    val description: String = "",
)

/** 一次 Skill 调用（AI 决策产物之一） */
@Serializable
data class SkillInvocation(
    /** 目标 Skill id */
    val skillId: String,
    /** 调用参数：param 名 → 值 */
    val args: Map<String, String> = emptyMap(),
)

/** 控件定位目标（skill/compat 共用） */
@Serializable
data class SkillTargetRef(
    /** by=id|text|hint|coordinate */
    val by: String = "id",
    /** id 控件编号 或 文本 或 语义提示 */
    val value: String = "",
)

/** 批量导入导出用的清单容器 */
@Serializable
data class SkillManifest(
    val schema: String = "hpa-skill/v1",
    val skills: List<Skill> = emptyList(),
)