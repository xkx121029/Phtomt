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

    // ---- 归一化：AI 输出的 intent 字段可能是技能 id / 技能名 / 标准意图名 ----

    @Test
    fun 归一化_标准意图名原样放行() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val intent = com.phoneagent.domain.model.AgentIntent(intent = IntentType.TAP, text = "确认")
        val n = SkillCompat.normalize(intent, registry)
        assertTrue(n is SkillCompat.Normalized.Intent)
        assertEquals(IntentType.TAP, (n as SkillCompat.Normalized.Intent).intent.intent)
        assertEquals("确认", n.intent.text)
    }

    @Test
    fun 归一化_技能id翻回等价意图并保留其它字段() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val intent = com.phoneagent.domain.model.AgentIntent(intent = "skill_open_app", app = "微信")
        val n = SkillCompat.normalize(intent, registry)
        assertTrue(n is SkillCompat.Normalized.Intent)
        val i = (n as SkillCompat.Normalized.Intent).intent
        assertEquals(IntentType.OPEN_APP, i.intent)
        assertEquals("微信", i.app)
    }

    @Test
    fun 归一化_技能中文名同样可调用() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val intent = com.phoneagent.domain.model.AgentIntent(intent = "打开应用", app = "支付宝")
        val n = SkillCompat.normalize(intent, registry)
        assertTrue(n is SkillCompat.Normalized.Intent)
        assertEquals(IntentType.OPEN_APP, (n as SkillCompat.Normalized.Intent).intent.intent)
    }

    @Test
    fun 归一化_新增内置技能remember与fetch可映射() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val remember = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_remember", text = "用户偏好简洁界面"),
            registry,
        )
        assertTrue(remember is SkillCompat.Normalized.Intent)
        assertEquals(IntentType.REMEMBER, (remember as SkillCompat.Normalized.Intent).intent.intent)
        assertEquals("用户偏好简洁界面", remember.intent.text)

        val fetch = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_fetch", uri = "https://example.com"),
            registry,
        )
        assertTrue(fetch is SkillCompat.Normalized.Intent)
        assertEquals(IntentType.FETCH, (fetch as SkillCompat.Normalized.Intent).intent.intent)
        assertEquals("https://example.com", fetch.intent.uri)
    }

    @Test
    fun 归一化_未知名字拒绝并给中文原因() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val n = SkillCompat.normalize(com.phoneagent.domain.model.AgentIntent(intent = "不存在的技能"), registry)
        assertTrue(n is SkillCompat.Normalized.Error)
        assertTrue((n as SkillCompat.Normalized.Error).reason.contains("未知意图或技能"))
    }

    @Test
    fun 归一化_已停用技能拒绝() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.setEnabled("skill_tap", false)
        val n = SkillCompat.normalize(com.phoneagent.domain.model.AgentIntent(intent = "skill_tap"), registry)
        assertTrue(n is SkillCompat.Normalized.Error)
        assertTrue((n as SkillCompat.Normalized.Error).reason.contains("已停用"))
    }

    @Test
    fun 归一化_MCP技能分流并把args带出() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.add(Skill(id = "m_query", name = "查余额", source = SkillSource.MCP,
            mcp = McpSkillTarget("bank", "query_balance", "{\"account\":\"{{acct}}\"}"),
            params = listOf(SkillParam("acct", "账号", "text", true))))
        val n = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "m_query", args = mapOf("acct" to "6222")),
            registry,
        )
        assertTrue(n is SkillCompat.Normalized.Mcp)
        val m = n as SkillCompat.Normalized.Mcp
        assertEquals("bank", m.target.server)
        assertEquals("query_balance", m.target.tool)
        assertEquals("6222", m.args["acct"])
    }

    @Test
    fun 归一化_MCP技能缺必填参数拒绝() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.add(Skill(id = "m_query", name = "查余额", source = SkillSource.MCP,
            mcp = McpSkillTarget("bank", "query_balance"),
            params = listOf(SkillParam("acct", "账号", "text", true))))
        val n = SkillCompat.normalize(com.phoneagent.domain.model.AgentIntent(intent = "m_query"), registry)
        assertTrue(n is SkillCompat.Normalized.Error)
        assertTrue((n as SkillCompat.Normalized.Error).reason.contains("缺少必填参数"))
    }

    // ---- 技能声明的参数必须真正驱动执行：args 回填到意图字段，否则技能只是个名字（空壳） ----

    @Test
    fun 归一化_技能args回填到意图字段() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val swipe = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_swipe", args = mapOf("direction" to "up")),
            registry,
        )
        assertTrue(swipe is SkillCompat.Normalized.Intent)
        val si = (swipe as SkillCompat.Normalized.Intent).intent
        assertEquals(IntentType.SWIPE, si.intent)
        assertEquals("up", si.direction)

        val openApp = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_open_app", args = mapOf("app" to "美团")),
            registry,
        )
        assertEquals("美团", (openApp as SkillCompat.Normalized.Intent).intent.app)

        val press = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_press", args = mapOf("key" to "BACK")),
            registry,
        )
        assertEquals("BACK", (press as SkillCompat.Normalized.Intent).intent.key)

        val wait = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_wait", args = mapOf("wait_ms" to "1500")),
            registry,
        )
        assertEquals(1500L, (wait as SkillCompat.Normalized.Intent).intent.waitMs)

        val remember = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_remember", args = mapOf("text" to "偏好简洁界面", "summary" to "preference")),
            registry,
        )
        val ri = (remember as SkillCompat.Normalized.Intent).intent
        assertEquals("偏好简洁界面", ri.text)
        assertEquals("preference", ri.summary)
    }

    @Test
    fun 归一化_技能args的target按写法解析() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        fun targetOf(raw: String) = (SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_tap", args = mapOf("target" to raw)),
            registry,
        ) as SkillCompat.Normalized.Intent).intent.target

        assertEquals("id", targetOf("ctl_3")?.by)
        assertEquals("ctl_3", targetOf("ctl_3")?.value)
        assertEquals("text", targetOf("确认")?.by)
        assertEquals("确认", targetOf("确认")?.value)
        // by: 显式写法不应越界崩溃（此前 substring(3, 2) 会抛 StringIndexOutOfBounds）
        assertEquals("id", targetOf("by:id:ctl_9")?.by)
        assertEquals("ctl_9", targetOf("by:id:ctl_9")?.value)
    }

    @Test
    fun 归一化_扁平字段优先于缺失的args() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        // args 里没给的字段不能被清空
        val n = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(
                intent = "skill_open_app", app = "支付宝", args = mapOf("summary" to "无关参数"),
            ),
            registry,
        )
        val i = (n as SkillCompat.Normalized.Intent).intent
        assertEquals(IntentType.OPEN_APP, i.intent)
        assertEquals("支付宝", i.app)
    }

    @Test
    fun 归一化_高层语义技能可用args传target兜底() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        assertTrue("高层语义技能应声明可选 target 参数", registry.byId("skill_share")!!.params.any { it.name == "target" })
        val n = SkillCompat.normalize(
            com.phoneagent.domain.model.AgentIntent(intent = "skill_share", args = mapOf("target" to "ctl_7")),
            registry,
        )
        val i = (n as SkillCompat.Normalized.Intent).intent
        assertEquals(IntentType.SHARE, i.intent)
        assertEquals("id", i.target?.by)
        assertEquals("ctl_7", i.target?.value)
    }
}
