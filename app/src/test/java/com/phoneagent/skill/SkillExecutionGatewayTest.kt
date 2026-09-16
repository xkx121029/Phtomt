package com.phoneagent.skill

import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.feature.skill.McpSkillTarget
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillCatalog
import com.phoneagent.feature.skill.SkillExecutionGateway
import com.phoneagent.feature.skill.SkillInvocation
import com.phoneagent.feature.skill.SkillParam
import com.phoneagent.feature.skill.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillExecutionGatewayTest {

    @Test
    fun 旧命令技能解析为Legacy() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val gateway = SkillExecutionGateway(registry)
        val d = gateway.resolve(SkillInvocation("skill_open_app", mapOf("app" to "微信")))
        assertTrue(d is SkillExecutionGateway.Dispatch.Legacy)
        val intent = (d as SkillExecutionGateway.Dispatch.Legacy).intent
        assertEquals(IntentType.OPEN_APP, intent.intent)
        assertEquals("微信", intent.app)
    }

    @Test
    fun 自定义MCP技能解析为McpInvoke() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.add(Skill(id = "m", name = "查单", source = SkillSource.MCP,
            mcp = McpSkillTarget("erp", "query", "{}"), params = listOf(SkillParam("id","单号","text",true))))
        // 提供 McpManager（空服务器列表，仅用于使网关分路正确）
        val mcp = McpManager(emptyList()) { throw IllegalStateException() }
        val gateway = SkillExecutionGateway(registry, mcp)
        val d = gateway.resolve(SkillInvocation("m", mapOf("id" to "1")))
        assertTrue(d is SkillExecutionGateway.Dispatch.McpInvoke)
        val m = d as SkillExecutionGateway.Dispatch.McpInvoke
        assertEquals("erp", m.server)
        assertEquals("query", m.tool)
        assertEquals("1", m.args["id"])
    }

    @Test
    fun 未知技能报中文提示() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val gateway = SkillExecutionGateway(registry)
        val d = gateway.resolve(SkillInvocation("no_such", emptyMap()))
        assertTrue(d is SkillExecutionGateway.Dispatch.Error)
        assertTrue((d as SkillExecutionGateway.Dispatch.Error).reason.contains("未知技能"))
    }

    @Test
    fun 缺必填报错() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val gateway = SkillExecutionGateway(registry)
        val d = gateway.resolve(SkillInvocation("skill_open_app", emptyMap()))
        assertTrue(d is SkillExecutionGateway.Dispatch.Error)
        assertTrue((d as SkillExecutionGateway.Dispatch.Error).reason.contains("缺少必填参数"))
    }

    @Test
    fun 未配置MCP时MCP技能报未配置() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        registry.add(Skill(id = "m", name = "查单", source = SkillSource.MCP,
            mcp = McpSkillTarget("erp", "query")))
        val gateway = SkillExecutionGateway(registry, mcpManager = null)
        val d = gateway.resolve(SkillInvocation("m", emptyMap()))
        assertTrue(d is SkillExecutionGateway.Dispatch.Error)
        assertTrue((d as SkillExecutionGateway.Dispatch.Error).reason.contains("MCP 未配置"))
    }

    @Test
    fun 控件JSON生成() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val gateway = SkillExecutionGateway(registry)
        val snap = ScreenSnapshot(packageName = "com.a", screenWidth = 1080, screenHeight = 2400,
            elements = listOf(UiElement(index = 0, className = "android.widget.Button", type = "Button",
                text = "确定", x = 50, y = 100, left = 0, top = 80, right = 100, bottom = 120, clickable = true)))
        val s = gateway.controlsJson(snap)
        assertTrue(s.contains("\"id\":\"ctl_0\""))
        assertTrue(s.contains("\"name\":\"确定\""))
        assertEquals("[]", gateway.controlsJson(null))
    }

    @Test
    fun 技能清单摘要() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        val gateway = SkillExecutionGateway(registry)
        val summary = gateway.skillListSummary()
        assertTrue(summary.contains("打开应用"))
        assertTrue(summary.contains("skill_open_app"))
    }
}
