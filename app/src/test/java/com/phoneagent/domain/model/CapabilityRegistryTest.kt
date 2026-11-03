package com.phoneagent.domain.model

import com.phoneagent.engine.execution.ActionPolicy
import com.phoneagent.engine.execution.ChannelGoal
import com.phoneagent.engine.prompt.PromptBodies
import com.phoneagent.feature.skill.SkillCatalog
import com.phoneagent.feature.skill.SkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声明式能力注册表的收敛性自检。
 *
 * 核心命题只有一句：**能力只在一个地方声明**，下游三处（运行时门控 / 技能目录 / App 技能列表）
 * 都从它派生，提示词里的静态意图表不得出现在注册表之外。
 *
 * 因此本测试不是"跑一遍看有没有崩"，而是对"新增能力忘了接线"的结构性拦截：
 * - 注册表与 [IntentType.ALL] 互相闭合；
 * - [ActionPolicy] 三档与注册表 risk 完全一致（不再各自维护）；
 * - [SkillCatalog] 44 项内置技能全部来自注册表，参数逐字段一致；
 * - App 内技能列表（[SkillRegistry] 注入内置技能）默认全量可见；
 * - 提示词意图表出现的每个 intent 都能在注册表里找到（拼错/删除未清理会红）。
 */
class CapabilityRegistryTest {

    // ---- 注册表 ↔ 意图全集 ----

    @Test
    fun 注册表覆盖意图全集_无遗漏无多余() {
        val registered = CapabilityRegistry.all.map { it.intent }.toSet()
        assertEquals("注册表存在重复 intent", CapabilityRegistry.all.size, registered.size)
        assertEquals("注册表未覆盖的意图：${IntentType.ALL - registered}", IntentType.ALL, registered)
        assertTrue("存在空名称/空分类的能力", CapabilityRegistry.all.all { it.name.isNotBlank() && it.category.isNotBlank() })
        assertTrue("存在空说明的能力", CapabilityRegistry.all.all { it.summary.isNotBlank() })
    }

    @Test
    fun 每个能力都有明确的通道与动作登记() {
        CapabilityRegistry.all.forEach { spec ->
            // 不产生设备动作的能力必须显式声明为 null（VIACapability 派生依赖这条事实）
            val expectedOtherChannel = spec.channel == CapabilityChannel.BROWSER || spec.channel == CapabilityChannel.VISION
            assertEquals(
                "${spec.intent} 的动作登记与通道不一致（action=${spec.action}, channel=${spec.channel}）",
                expectedOtherChannel, spec.action == null,
            )
        }
    }

    // ---- 门控同源 ----

    @Test
    fun 动作模式三档与注册表风险档完全一致() {
        assertEquals("保守档与 LOW 不一致", CapabilityRegistry.ofRisk(RiskTier.LOW), ActionPolicy.lowRisk)
        assertEquals("均衡档补充与 MID 不一致", CapabilityRegistry.ofRisk(RiskTier.MID), ActionPolicy.midRisk)
        assertEquals("高风险档与 HIGH 不一致", CapabilityRegistry.ofRisk(RiskTier.HIGH), ActionPolicy.highRisk)
        assertEquals("自由专属与 EXTENDED 不一致", CapabilityRegistry.ofRisk(RiskTier.EXTENDED), ActionPolicy.freeOnly)
        assertEquals("三档数量变化：低/中/高/自由专属", listOf(30, 20, 5, 4), listOf(
            ActionPolicy.lowRisk.size, ActionPolicy.midRisk.size, ActionPolicy.highRisk.size, ActionPolicy.freeOnly.size,
        ))
    }

    @Test
    fun 高风险能力都要求用户确认() {
        CapabilityRegistry.all.filter { it.risk == RiskTier.HIGH }.forEach {
            assertTrue("高风险能力 ${it.intent} 未声明 needsConfirmation", it.needsConfirmation)
        }
    }

    @Test
    fun 通道声明与执行通道阶梯可对齐() {
        CapabilityRegistry.all.forEach { spec ->
            val goal = spec.action?.let { ChannelGoal.of(it) }
            val needsLadder = spec.channel == CapabilityChannel.SCREEN || spec.channel == CapabilityChannel.SHELL
            assertEquals(
                "${spec.intent} 声明了 ${spec.channel} 通道，但动作 ${spec.action} 没有对应的通道目标（新增动作忘了接 ChannelGoal）",
                needsLadder, goal != null,
            )
        }
        // 通道 → 能力数量（改动通道归属时先看这里，再确认门控/阶梯）
        assertEquals(24, CapabilityRegistry.ofChannel(CapabilityChannel.SCREEN).size)
        assertEquals(2, CapabilityRegistry.ofChannel(CapabilityChannel.SHELL).size)
        assertEquals(6, CapabilityRegistry.ofChannel(CapabilityChannel.BROWSER).size)
        assertEquals(1, CapabilityRegistry.ofChannel(CapabilityChannel.VISION).size)
        assertEquals(1, CapabilityRegistry.ofChannel(CapabilityChannel.A11Y).size)
        assertEquals(2, CapabilityRegistry.ofChannel(CapabilityChannel.CLOCK).size)
    }

    @Test
    fun 依赖设备权限的能力都声明了权限需求() {
        CapabilityRegistry.all.forEach { spec ->
            when (spec.channel) {
                CapabilityChannel.A11Y, CapabilityChannel.SHELL ->
                    assertTrue(
                        "${spec.intent} 走 ${spec.channel} 通道却未声明权限需求",
                        spec.permission != CapabilityPermission.NONE,
                    )
                else -> Unit
            }
        }
        CapabilityRegistry.all.filter { it.permission == CapabilityPermission.NOTIFICATION }.forEach {
            assertEquals("${it.intent} 依赖通知使用权", CapabilityChannel.LOCAL, it.channel)
        }
    }

    // ---- 技能目录同源 ----

    @Test
    fun 内置技能全部由注册表派生_参数逐字段一致() {
        val builtins = SkillCatalog.builtins()
        assertEquals("内置技能数量 != 能力数量", CapabilityRegistry.all.size, builtins.size)
        builtins.forEach { skill ->
            val spec = CapabilityRegistry.specOf(skill.legacyIntent) ?: throw AssertionError(
                "技能 ${skill.id} 的 legacyIntent=${skill.legacyIntent} 不在注册表里",
            )
            assertEquals("技能名与注册表不一致：${spec.intent}", spec.name, skill.name)
            assertEquals("技能说明与注册表不一致：${spec.intent}", spec.summary, skill.description)
            assertEquals("技能分类与注册表不一致：${spec.intent}", spec.category, skill.category)
            assertEquals("技能 id 约定应为 skill_<intent>", "skill_${spec.intent}", skill.id)
            assertEquals("参数数量不一致：${spec.intent}", spec.params.size, skill.params.size)
            spec.params.zip(skill.params).forEach { (a, b) ->
                assertEquals("参数名不一致：${spec.intent}", a.name, b.name)
                assertEquals("参数必填标记不一致：${spec.intent}.${a.name}", a.required, b.required)
                assertEquals("参数类型不一致：${spec.intent}.${a.name}", a.type, b.type)
                assertEquals("参数选项不一致：${spec.intent}.${a.name}", a.options, b.options)
            }
        }
    }

    @Test
    fun App技能列表默认全量可见() {
        val registry = SkillRegistry(SkillCatalog.builtins())
        assertEquals(CapabilityRegistry.all.size, registry.all().size)
        assertEquals(CapabilityRegistry.all.size, registry.enabled().size)
        CapabilityRegistry.all.forEach { spec ->
            assertNotNull("按旧意图名解析不到技能：${spec.intent}", registry.byLegacyIntent(spec.intent))
        }
    }

    // ---- 提示词静态表不漂移 ----

    @Test
    fun 提示词意图表不出现注册表之外的能力() {
        val promptIntents = promptTableIntents()
        assertTrue("未解析到提示词意图（解析失效，下面的断言会假绿）", promptIntents.size >= 36)
        val stale = promptIntents - CapabilityRegistry.byIntent.keys
        assertTrue("提示词里出现了注册表没有的意图（拼错或已删除未清理）：$stale", stale.isEmpty())
    }
}

/** 从提示词静态意图表里抽取 intent 名（表格首列 + 语义意图段落的首词） */
private fun promptTableIntents(): Set<String> {
    val result = mutableSetOf<String>()
    listOf(PromptBodies.SYS_INTENTS_CN, PromptBodies.SYS_INTENTS_EN).forEach { body ->
        body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").first().trim() }
            .filter { it != "intent" && it.isNotBlank() && !it.startsWith("-") }
            .forEach { result += it }
    }
    listOf(PromptBodies.SYS_INTENTS_SEMANTIC_CN, PromptBodies.SYS_INTENTS_SEMANTIC_EN).forEach { body ->
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.split("/")
            ?.map { it.trim().split(" ").first().trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { result += it }
    }
    return result
}
