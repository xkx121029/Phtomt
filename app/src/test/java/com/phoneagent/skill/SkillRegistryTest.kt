package com.phoneagent.skill

import com.phoneagent.feature.skill.McpSkillTarget
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillCatalog
import com.phoneagent.feature.skill.SkillParam
import com.phoneagent.feature.skill.SkillRegistry
import com.phoneagent.feature.skill.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {

    @Test
    fun 内置技能目录完整加载() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val all = registry.all()
        assertTrue("内置技能数量不足", all.size >= 20)
        assertTrue("内置技能全部是内置", all.all { it.isBuiltIn })
        assertNotNull("tap 技能可查", registry.byLegacyIntent("tap"))
        assertNotNull("open_app 技能可查", registry.byLegacyIntent("open_app"))
    }

    @Test
    fun 新增编辑删除批量() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val skill = Skill(id = "s1", name = "查余额", source = SkillSource.MCP,
            mcp = McpSkillTarget("bank", "query_balance", "{}"))
        assertTrue(registry.add(skill))
        assertEquals("s1", registry.byName("查余额")?.id)
        // 重复添加失败
        assertTrue(!registry.add(skill))
        // 编辑
        assertTrue(registry.edit(skill.copy(name = "查询余额")))
        assertEquals("查询余额", registry.byId("s1")?.name)
        // 删除
        assertTrue(registry.remove("s1"))
        assertEquals(null, registry.byId("s1"))
        // 批量新增
        val n = registry.addAll(listOf(
            Skill(id = "a1", name = "a", source = SkillSource.MCP, mcp = McpSkillTarget("s","t")),
            Skill(id = "a2", name = "b", source = SkillSource.MCP, mcp = McpSkillTarget("s","t")),
        ))
        assertEquals(2, n)
        // 批量删除（含内置，内置应跳过）
        val removed = registry.removeAll(setOf("a1", "skill_tap"))
        assertEquals(1, removed)
        assertEquals(null, registry.byId("a1"))
        assertNotNull(registry.byId("skill_tap"))
    }

    @Test
    fun 内置技能不可删除不可被add覆盖() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        assertTrue(!registry.remove("skill_tap"))
        val replaced = Skill(id = "skill_tap", name = "篡改", source = SkillSource.INTENT,
            isBuiltIn = true, legacyIntent = "tap")
        assertTrue(registry.upsert(replaced).isFailure)
    }

    @Test
    fun 导入导出回环() {
        val a = SkillRegistry(SkillCatalog.builtins())
        a.add(Skill(id = "m1", name = "读文件", source = SkillSource.MCP,
            mcp = McpSkillTarget("filesystem", "read_file", "{\"path\":\"{{path}}\"}"),
            params = listOf(SkillParam("path", "路径", "text", true))))
        a.add(Skill(id = "m2", name = "写文件", source = SkillSource.MCP,
            mcp = McpSkillTarget("filesystem", "write_file")))
        val exported = a.exportJson()
        assertTrue(exported.contains("m1") && exported.contains("m2"))

        val b = SkillRegistry(SkillCatalog.builtins())
        val report = b.importJson(exported)
        assertEquals(2, report.imported)
        assertNotNull(b.byId("m1"))
        assertEquals("filesystem", b.byId("m1")?.mcp?.server)
        // 内置不随导出走（export 只导出自定义）
        assertTrue(!exported.contains("skill_tap"))
    }

    @Test
    fun 自定义清空() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.addAll(listOf(
            Skill(id = "x1", name = "x1", source = SkillSource.MCP, mcp = McpSkillTarget("s","t")),
            Skill(id = "x2", name = "x2", source = SkillSource.MCP, mcp = McpSkillTarget("s","t")),
        ))
        val cleared = registry.clearCustom()
        assertEquals(2, cleared)
        assertTrue(registry.custom().isEmpty())
        assertTrue(registry.all().isNotEmpty()) // 内置仍在
    }

    /**
     * 目录与意图全集必须一一对应：AI 看到的"可用技能"和转译层能执行的意图不能有缺口，
     * 否则会出现"技能页有、AI 调不动"或"意图合法、技能页查不到"的错位。
     */
    @Test
    fun 内置目录与意图全集一一对应() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val missing = com.phoneagent.domain.model.IntentType.ALL.filter { registry.byLegacyIntent(it) == null }
        assertTrue("以下意图缺少对应内置技能：$missing", missing.isEmpty())
    }
}
