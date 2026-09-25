package com.phoneagent.engine

import com.phoneagent.data.store.EvolvedRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 经验规则（阶段 3）单测：作用域推断、命中判定、护栏上界、提示词注入。
 *
 * 这个特性的风险是"自动注入且无用户确认"，所以护栏必须被钉死：
 * 任何调高门槛上限、放宽置信度、去掉去重的改动，都应该在这里变红。
 */
class PromptRuleTest {

    private fun rule(
        id: Long,
        content: String,
        kind: String = EvolvedRule.SCOPE_GLOBAL,
        value: String = "",
        confidence: Double = 0.8,
        hitCount: Int = 0,
    ) = EvolvedRule(
        id = id,
        content = content,
        scopeKind = kind,
        scopeValue = value,
        confidence = confidence,
        hitCount = hitCount,
    )

    // ==================== 作用域推断 ====================

    @Test
    fun `前台应用出现次数最多的包成为作用域`() {
        val scope = RuleScoper.inferScope(
            task = "帮我点一份黄焖鸡",
            foregroundPkgCounts = mapOf("com.sankuai.meituan" to 7, "com.tencent.mm" to 2),
            selfPackage = "com.phoneagent",
        )
        assertEquals(EvolvedRule.SCOPE_PACKAGE, scope.kind)
        assertEquals("com.sankuai.meituan", scope.value)
    }

    @Test
    fun `本App自己不算作用域_全是自己的界面时退化到关键词`() {
        // 任务全程只在 Happy Agent 界面内完成（写文档等），本 App 绝不能被钉成适用范围
        val scope = RuleScoper.inferScope(
            task = "帮我在美团写一份点餐清单",
            foregroundPkgCounts = mapOf("com.phoneagent" to 12),
            selfPackage = "com.phoneagent",
            keywords = listOf("美团", "微信"),
        )
        assertEquals(EvolvedRule.SCOPE_KEYWORD, scope.kind)
        assertEquals("美团", scope.value)
    }

    @Test
    fun `次数相同时按包名定序_保证同样输入得到同样结果`() {
        val scope = RuleScoper.inferScope(
            task = "随便",
            foregroundPkgCounts = mapOf("com.b.app" to 3, "com.a.app" to 3),
            selfPackage = "com.phoneagent",
        )
        assertEquals("com.a.app", scope.value)
    }

    @Test
    fun `两个信号都没有时退化为全局`() {
        val scope = RuleScoper.inferScope(
            task = "帮我把这段话记下来",
            foregroundPkgCounts = mapOf("com.phoneagent" to 4),
            selfPackage = "com.phoneagent",
            keywords = listOf("美团", "微信"),
        )
        assertEquals(EvolvedRule.SCOPE_GLOBAL, scope.kind)
        assertEquals("", scope.value)
    }

    @Test
    fun `空包名不参与计数`() {
        val scope = RuleScoper.inferScope(
            task = "某任务",
            foregroundPkgCounts = mapOf("" to 99, "com.sankuai.meituan" to 1),
            selfPackage = "com.phoneagent",
        )
        assertEquals("com.sankuai.meituan", scope.value)
    }

    // ==================== 命中判定 ====================

    @Test
    fun `三种作用域各自的成立条件`() {
        val pkg = rule(1, "美团里首页弹窗要先关掉", EvolvedRule.SCOPE_PACKAGE, "com.sankuai.meituan")
        assertTrue(RuleScoper.match(pkg, "com.sankuai.meituan", "任意任务"))
        assertFalse("换个应用就不该成立", RuleScoper.match(pkg, "com.tencent.mm", "任意任务"))

        val keyword = rule(2, "点黄焖鸡要选微辣", EvolvedRule.SCOPE_KEYWORD, "黄焖鸡")
        assertTrue(RuleScoper.match(keyword, "", "帮我再点一份黄焖鸡"))
        assertFalse(RuleScoper.match(keyword, "", "帮我查一下明天天气"))

        val global = rule(3, "用户偏好简洁回复")
        assertTrue(RuleScoper.match(global, "", ""))
    }

    // ==================== 护栏：置信度 / 条数 / 字数 / 去重 ====================

    @Test
    fun `置信度低于门槛不注入_等于门槛才注入`() {
        val rules = listOf(
            rule(1, "这条刚好够门槛", confidence = RuleScoper.MIN_CONFIDENCE),
            rule(2, "这条差一点", confidence = RuleScoper.MIN_CONFIDENCE - 0.01),
        )
        val hit = RuleScoper.select(rules, "", "任务")
        assertEquals(listOf(1L), hit.map { it.id })
    }

    @Test
    fun `最多注入五条_按置信度降序`() {
        val rules = (1..10).map { rule(it.toLong(), "规则$it", confidence = 0.6 + it * 0.01) }
        val hit = RuleScoper.select(rules, "", "任务")
        assertEquals(RuleScoper.MAX_SELECTED, hit.size)
        assertEquals(listOf(10L, 9L, 8L, 7L, 6L), hit.map { it.id })
    }

    @Test
    fun `总字数不超上限_放不下就整体停止`() {
        // 每条 200 字（正好等于单条展示上限），5 条即 1000 > 800，只能放下 4 条
        val rules = (1..5).map { rule(it.toLong(), "经$it".repeat(100), confidence = 0.9) }
        val hit = RuleScoper.select(rules, "", "任务")
        assertEquals(4, hit.size)
        assertTrue(hit.sumOf { it.content.take(RuleScoper.PER_RULE_CHARS).length } <= RuleScoper.MAX_CHARS)
    }

    @Test
    fun `单条展示上限必须大于总上限除以条数_否则总字数上限永远不触发`() {
        assertTrue(
            "PER_RULE_CHARS=${RuleScoper.PER_RULE_CHARS} 必须 > ${RuleScoper.MAX_CHARS}/${RuleScoper.MAX_SELECTED}",
            RuleScoper.PER_RULE_CHARS > RuleScoper.MAX_CHARS / RuleScoper.MAX_SELECTED,
        )
    }

    @Test
    fun `同一件事换种说法只注入一条`() {
        // 与 AiMemoryDedupe 的既有阈值例子同源（相似度约 0.53 > 0.5，判定为同一条）
        val rules = listOf(
            rule(1, "常在美团点黄焖鸡", confidence = 0.9),
            rule(2, "通常在美团买黄焖鸡", confidence = 0.85),
        )
        val hit = RuleScoper.select(rules, "", "任务")
        assertEquals(listOf(1L), hit.map { it.id })
    }

    @Test
    fun `库容量上限六十条_淘汰命中次数最少的`() {
        val rules = (1..65).map { rule(it.toLong(), "规则$it", hitCount = it) }
        val kept = EvolvedRule.trimToLimit(rules)
        assertEquals(EvolvedRule.MAX_RULES, kept.size)
        assertEquals((6L..65L).toList(), kept.map { it.id })
        // 未超限时原样返回，不做无谓的排序与拷贝
        val small = rules.take(3)
        assertEquals(small, EvolvedRule.trimToLimit(small))
    }

    // ==================== 注入文本 ====================

    @Test
    fun `空清单不产出任何文本`() {
        assertEquals("", RuleScoper.brief(emptyList()))
    }

    @Test
    fun `每条一行且带短横线`() {
        val text = RuleScoper.brief(listOf(rule(1, "规则甲"), rule(2, "规则乙")))
        assertEquals("- 规则甲\n- 规则乙", text)
    }

    // ==================== 提示词注入 ====================

    @Test
    fun `命中规则时注入经验规则段并排在记忆段之后`() {
        val text = AgentPrompts.decision(
            PromptLang.CN, "帮我点一份黄焖鸡", 1, 3, "打开美团", "无", 0, "美团首页",
            memory = "已知记忆：\n- 用户偏好少辣",
            evolvedRules = "- 美团首页的弹窗要先关掉",
        )
        assertTrue(text.contains("经验规则（往次任务提炼"))
        assertTrue(text.contains("- 美团首页的弹窗要先关掉"))
        assertTrue("经验规则应排在记忆之后", text.indexOf("经验规则") > text.indexOf("已知记忆"))
    }

    @Test
    fun `没有命中规则时一整段都不注入`() {
        val text = AgentPrompts.decision(PromptLang.CN, "帮我点一份黄焖鸡", 1, 3, "打开美团", "无", 0, "美团首页")
        assertFalse(text.contains("经验规则"))
        assertFalse("不留空标题", text.contains("Learned rules"))
    }

    @Test
    fun `英文提示词使用英文经验规则段`() {
        val text = AgentPrompts.decision(
            PromptLang.EN, "order food", 1, 3, "open app", "none", 0, "home",
            evolvedRules = "- dismiss the popup first",
        )
        assertTrue(text.contains("Learned rules"))
        assertTrue(text.contains("- dismiss the popup first"))
        assertFalse(text.contains("经验规则"))
    }
}