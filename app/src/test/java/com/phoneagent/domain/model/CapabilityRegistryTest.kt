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
 * 核心命题：**能力只在一个地方声明**，下游（运行时门控 / 技能目录 / App 列表 / 提示词一致性）
 * 都从它派生。这里不写死任何数量——数量变化是正常的，结构变化才需要红。
 *
 * 结构性拦截由 [CapabilityConventions] 承担；本测试只负责把真实注册表与真实通道阶梯接进去，
 * 再用「虚构成一条新能力」证明新增确实只需要两处登记。
 */
class CapabilityRegistryTest {

    private fun channelHasGoal(action: String): Boolean = ChannelGoal.of(action) != null

    // ---- 注册表 ↔ 意图全集 ----

    @Test
    fun 注册表覆盖意图全集_无遗漏无多余() {
        val registered = CapabilityRegistry.all.map { it.intent }.toSet()
        assertEquals("注册表存在重复 intent", CapabilityRegistry.all.size, registered.size)
        assertEquals("注册表未覆盖的意图：${IntentType.ALL - registered}", IntentType.ALL, registered)
    }

    @Test
    fun 能力声明全部满足结构约定() {
        CapabilityConventions.requireClean(CapabilityRegistry.all, ::channelHasGoal)
    }

    // ---- 门控同源 ----

    @Test
    fun 动作模式三档与注册表风险档完全一致() {
        assertEquals("保守档与 LOW 不一致", CapabilityRegistry.ofRisk(RiskTier.LOW), ActionPolicy.lowRisk)
        assertEquals("均衡档补充与 MID 不一致", CapabilityRegistry.ofRisk(RiskTier.MID), ActionPolicy.midRisk)
        assertEquals("高风险档与 HIGH 不一致", CapabilityRegistry.ofRisk(RiskTier.HIGH), ActionPolicy.highRisk)
        assertEquals("自由专属与 EXTENDED 不一致", CapabilityRegistry.ofRisk(RiskTier.EXTENDED), ActionPolicy.freeOnly)
    }

    // ---- 通道策略同源 ----

    @Test
    fun 每个通道都有非空降级策略且至少被一项能力使用() {
        CapabilityChannel.values().forEach { channel ->
            assertTrue("通道 ${channel.key} 缺少降级策略", channel.fallback.isNotBlank())
            assertTrue("通道 ${channel.key} 没有任何能力使用", CapabilityRegistry.ofChannel(channel).isNotEmpty())
        }
    }

    @Test
    fun 时钟通道对应独立降级链而非通用阶梯() {
        val clock = CapabilityRegistry.ofChannel(CapabilityChannel.CLOCK)
        assertEquals(setOf(IntentType.SET_ALARM, IntentType.SET_TIMER), clock)
        clock.forEach { intent ->
            val action = CapabilityRegistry.specOf(intent)!!.action
            assertTrue("$intent 的时钟动作异常：$action", action in CapabilityConventions.CLOCK_ACTIONS)
        }
        assertTrue(
            "时钟通道策略必须点名 ClockFallbackPolicy",
            CapabilityChannel.CLOCK.fallback.contains("ClockFallbackPolicy"),
        )
    }

    /**
     * 「新增能力 = 注册表一条 + 通道策略一行」的契约测试。
     *
     * 场景：新增一个 SCREEN 能力但先忘了在 ChannelGoal 加一行 —— 必须报错；
     * 补上那一行后，同一份声明零问题。这证明收敛没有靠硬编码数量假装成立。
     */
    @Test
    fun 新增能力只需注册表一条加通道策略一行() {
        val newSpec = CapabilitySpec(
            intent = "new_screen_capability",
            name = "新能力",
            category = "操作",
            summary = "仅用于契约测试的虚拟能力",
            params = listOf(CapabilityParam("target", "目标", required = true)),
            risk = RiskTier.MID,
            channel = CapabilityChannel.SCREEN,
            action = "new_screen_action",
        )
        val specs = CapabilityRegistry.all + newSpec

        val missingGoal = CapabilityConventions.check(specs) { action ->
            action != "new_screen_action" && channelHasGoal(action)
        }
        assertEquals(
            "漏接通道策略时应该只有这一条问题",
            listOf(newSpec.intent),
            missingGoal.map { it.intent }.distinct(),
        )
        assertTrue(
            "错误信息必须指出要补哪一处",
            missingGoal.single().message.contains("通道策略表加一行"),
        )

        val complete = CapabilityConventions.check(specs) { action ->
            action == "new_screen_action" || channelHasGoal(action)
        }
        assertTrue("补上通道策略后应零问题：$complete", complete.isEmpty())
    }

    @Test
    fun 新增时钟能力不需要通用通道目标() {
        val newClock = CapabilitySpec(
            intent = IntentType.SET_ALARM,
            name = "定闹钟",
            category = "时钟",
            summary = "验证时钟能力不依赖通用通道阶梯",
            risk = RiskTier.LOW,
            channel = CapabilityChannel.CLOCK,
            action = "set_alarm",
        )
        assertTrue(CapabilityConventions.check(listOf(newClock) + restOfChannels(), ::channelHasGoal).isEmpty())
    }

    /** 给「只校验一条新能力」时补齐通道覆盖，避免死通道噪声 */
    private fun restOfChannels(): List<CapabilitySpec> =
        CapabilityRegistry.all.filter { it.intent != IntentType.SET_ALARM && it.intent != IntentType.SET_TIMER }

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
