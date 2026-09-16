package com.phoneagent.skill

import com.phoneagent.domain.model.IntentType
import com.phoneagent.feature.skill.McpSkillTarget
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillCatalog
import com.phoneagent.feature.skill.SkillCompat
import com.phoneagent.feature.skill.SkillInvocation
import com.phoneagent.feature.skill.SkillParam
import com.phoneagent.feature.skill.SkillRegistry
import com.phoneagent.feature.skill.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCompatTest {

    @Test
    fun 旧命令意图解析为内置技能() {
        val intent = com.phoneagent.domain.model.AgentIntent(intent = IntentType.OPEN_APP, app = "微信")
        val skill = SkillCompat.skillForLegacyIntent(intent)
        assertEquals("skill_open_app", skill?.id)
    }

    @Test
    fun 按技能名调用回翻旧命令open_app() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val r = SkillCompat.resolveByName("skill_open_app", mapOf("app" to "微信"), registry)
        assertTrue(r is SkillCompat.Resolution.LegacyIntent)
        val intent = (r as SkillCompat.Resolution.LegacyIntent).intent
        assertEquals(IntentType.OPEN_APP, intent.intent)
        assertEquals("微信", intent.app)
    }

    @Test
    fun 按意图名回翻tap带target() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        // ctl_3 → by=id
        val r1 = SkillCompat.resolveByName("tap", mapOf("target" to "ctl_3"), registry)
        val i1 = (r1 as SkillCompat.Resolution.LegacyIntent).intent
        assertEquals(IntentType.TAP, i1.intent)
        assertEquals("id", i1.target?.by)
        assertEquals("ctl_3", i1.target?.value)
        // 直接文字 → by=text
        val r2 = SkillCompat.resolveByName("tap", mapOf("target" to "确认"), registry)
        assertEquals("text", (r2 as SkillCompat.Resolution.LegacyIntent).intent.target?.by)
        assertEquals("确认", r2.intent.target?.value)
    }

    @Test
    fun MCP技能分流() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.add(Skill(id = "m_query", name = "查余额", source = SkillSource.MCP,
            mcp = McpSkillTarget("bank", "query_balance", "{\"account\":\"{{acct}}\"}"),
            params = listOf(SkillParam("acct", "账号", "text", true))))
        val r = SkillCompat.resolve(SkillInvocation("m_query", mapOf("acct" to "6222")), registry)
        assertTrue(r is SkillCompat.Resolution.Mcp)
        val m = (r as SkillCompat.Resolution.Mcp)
        assertEquals("bank", m.target.server)
        assertEquals("query_balance", m.target.tool)
        assertEquals("6222", m.args["acct"])
    }

    @Test
    fun 缺少必填参数报错() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val r = SkillCompat.resolve(SkillInvocation("skill_open_app", mapOf()), registry)
        assertTrue("应报缺参错误", r is SkillCompat.Resolution.Error)
        val e = (r as SkillCompat.Resolution.Error)
        assertTrue(e.reason.contains("缺少必填参数"))
    }

    @Test
    fun 停用技能报错() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.setEnabled("skill_tap", false)
        val r = SkillCompat.resolve(SkillInvocation("skill_tap", mapOf("target" to "x")), registry)
        assertTrue(r is SkillCompat.Resolution.Error)
    }

    @Test
    fun 未知技能返回Unknown() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val r = SkillCompat.resolveByName("不存在的技能", emptyMap(), registry)
        assertTrue(r is SkillCompat.Resolution.Unknown)
    }

    @Test
    fun 高层语义技能无需参数() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val r = SkillCompat.resolveByName("back", emptyMap(), registry)
        assertTrue(r is SkillCompat.Resolution.LegacyIntent)
        assertEquals(IntentType.BACK, (r as SkillCompat.Resolution.LegacyIntent).intent.intent)
    }
}
