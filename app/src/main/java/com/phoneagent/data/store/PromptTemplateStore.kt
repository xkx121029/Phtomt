package com.phoneagent.data.store

import com.phoneagent.engine.prompt.PromptTemplate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 提示词模板库：管理内置 + 用户自定义模板，支持增删改、导入导出、持久化。
 * 纯 Kotlin 可单测，持久化由外层用 JSON 归档落盘。
 */
class PromptTemplateStore(
    source: List<PromptTemplate> = emptyList(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _byId = LinkedHashMap<String, PromptTemplate>()

    init { reset(source) }

    fun all(): List<PromptTemplate> = _byId.values.toList()

    fun byId(id: String): PromptTemplate? = _byId[id]

    /** 启用有效模板正文：空白则返回 null（回退内置默认） */
    fun bodyOf(id: String): String? = _byId[id]?.body?.trim()?.ifBlank { null }

    fun upsert(t: PromptTemplate): Boolean {
        val existing = _byId[t.id]
        if (existing?.isBuiltIn == true) {
            // 内置模板允许改写正文，但保持内置标记
            _byId[t.id] = existing.copy(body = t.body)
            return true
        }
        _byId[t.id] = t
        return true
    }

    fun remove(id: String): Boolean {
        val t = _byId[id] ?: return false
        if (t.isBuiltIn) return false
        _byId.remove(id)
        return true
    }

    fun reset(source: List<PromptTemplate>) {
        _byId.clear()
        source.forEach { _byId[it.id] = it }
    }

    fun resetToDefaults(defaults: List<PromptTemplate>) = reset(defaults)

    fun exportJson(): String = json.encodeToString(ListSerializerProxy.serializer(), ListSerializerProxy(_byId.values.toList()))

    fun importJson(text: String): Int = runCatching {
        val imported = json.decodeFromString<ListSerializerProxy>(text).items
        upsertAll(imported)
    }.getOrElse { 0 }

    private fun upsertAll(items: List<PromptTemplate>): Int {
        var n = 0
        items.forEach { if (upsert(it)) n++ }
        return n
    }

    @Serializable
    private data class ListSerializerProxy(@kotlinx.serialization.SerialName("templates") val items: List<PromptTemplate>)

    /** 内置默认模板（与现有 AgentPrompts 配合；body 可让用户改写成含 {skills}/{controls} 等变量） */
    companion object {
        fun defaults(): List<PromptTemplate> = listOf(
            PromptTemplate("system", "系统提示", "{situational}\n你是 Phantom，一个 Android 手机操控智能体，可调用 Skill 与 MCP 工具。\n掌握控件：{controls}\n可用技能：{skills}\nMCP 工具：{mcpTools}", isBuiltIn = true),
            PromptTemplate("decision", "每步决策", "当前应用：{currentApp}\n上一步结果：{lastResult}\n目标：{goal}\n请基于控件 {controls} 决策下一步。", isBuiltIn = true),
            PromptTemplate("planning", "任务规划", "用户目标：\n{task}\n请规划 3~8 步可执行计划。", isBuiltIn = true),
            PromptTemplate("verify", "执行验证", "上一步结果：{lastResult}\n审核拒绝理由：{auditRejection}", isBuiltIn = true),
        )
    }
}