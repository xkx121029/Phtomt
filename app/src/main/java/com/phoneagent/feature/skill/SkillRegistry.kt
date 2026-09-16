package com.phoneagent.feature.skill.SkillRegistry

import kotlinx.serialization.json.Json

/**
 * Skill 注册表：管理【内置 + 用户自定义 + MCP】三类 Skill。
 *
 * 能力：增删改查、批量增删、启停、导入导出（JSON 清单）、按名称/意图/id 解析。
 * 纯 Kotlin、无 Android 依赖，便于单元测试与持久化替换。
 *
 * 内置 Skill 不可删/不可改 id，但可启用停用；用户自定义/MCP Skill 完全可编辑。
 */
class SkillRegistry(
    sourceSkills: List<Skill> = emptyList(),
    private val customIdPrefix: String = "skill_user",
) {
    private val _all = LinkedHashMap<String, Skill>()
    private val _ordered = mutableListOf<String>()

    init {
        replaceAll(sourceSkills)
    }

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- 查询 ----

    fun all(): List<Skill> = _ordered.mapNotNull { _all[it] }

    fun custom(): List<Skill> = all().filter { !it.isBuiltIn }

    fun byId(id: String): Skill? = _all[id]

    fun byName(name: String): Skill? =
        all().firstOrNull { it.name == name || it.id == name }

    fun byLegacyIntent(intentName: String): Skill? =
        all().firstOrNull { it.legacyIntent == intentName }

    fun enabled(): List<Skill> = all().filter { it.enabled }

    // ---- 增删改 ----

    /** 新增或覆盖一个 Skill；内置不允许覆盖非相关字段的安全校验由调用方负责 */
    fun upsert(skill: Skill): Result<Unit> {
        if (skill.id.isEmpty()) return Result.failure(IllegalArgumentException("Skill id 不能为空"))
        val existing = _all[skill.id]
        if (existing?.isBuiltIn == true && existing != skill) {
            // 内置 Skill 的 id/key 由系统管理，禁止外部篡改
            return Result.failure(IllegalArgumentException("内置 Skill 不可用 add 直接覆盖"))
        }
        _all[skill.id] = skill
        if (skill.id !in _ordered) _ordered.add(skill.id)
        return Result.success(Unit)
    }

    /** 新增（若 id 已存在则跳过返回 false） */
    fun add(skill: Skill): Boolean {
        if (skill.id in _all) return false
        _all[skill.id] = skill
        _ordered.add(skill.id)
        return true
    }

    fun edit(skill: Skill): Boolean {
        if (_all[skill.id]?.isBuiltIn == true) return false
        // 保留顺序与旧值缺失字段：直接用新的覆盖
        return upsert(skill).isSuccess
    }

    /** 删除一个 Skill；内置不可删 */
    fun remove(id: String): Boolean {
        val s = _all[id] ?: return false
        if (s.isBuiltIn) return false
        _all.remove(id)
        _ordered.remove(id)
        return true
    }

    /** 批量新增：返回成功数量 */
    fun addAll(skills: List<Skill>): Int = skills.count { add(it) }

    /** 批量删除（跳过内置）：返回成功数量 */
    fun removeAll(ids: Set<String>): Int = ids.count { remove(it) }

    /** 批量清除自定义 Skill（保留内置） */
    fun clearCustom(): Int {
        val ids = custom().map { it.id }
        return removeAll(ids.toSet())
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val s = _all[id] ?: return false
        _all[id] = s.copy(enabled = enabled)
        return true
    }

    // ---- 导入导出 ----

    fun exportManifest(): SkillManifest {
        val custom = custom().map { stripRuntimeFields(it) }
        return SkillManifest(schema = "hpa-skill/v1", skills = custom)
    }

    fun importManifest(manifest: SkillManifest): ImportReport {
        val count = addAll(manifest.skills.map { normalizeImported(it) })
        return ImportReport(imported = count, total = manifest.skills.size)
    }

    fun importJson(text: String): ImportReport = runCatching {
        importManifest(json.decodeFromString<SkillManifest>(text))
    }.getOrElse { ImportReport(0, 0, error = it.message ?: "解析失败") }

    fun exportJson(): String = json.encodeToString(SkillManifest.serializer(), exportManifest())

    fun replaceAll(skills: List<Skill>) {
        _all.clear()
        _ordered.clear()
        skills.forEach { s ->
            _all[s.id] = s
            _ordered.add(s.id)
        }
    }

    // ---- 私有 ----

    /** 导出前去除运行时/状态字段（id 保留，enabled 保留，去 createdBy/version 噪音） */
    private fun stripRuntimeFields(s: Skill): Skill = s.copy()
    private fun normalizeImported(s: Skill): Skill =
        s.copy(id = s.id.ifBlank { "${customIdPrefix}_${System.currentTimeMillis()}" })

    data class ImportReport(
        val imported: Int,
        val total: Int,
        val error: String? = null,
    )
}