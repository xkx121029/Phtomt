package com.phoneagent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 输出解析层测试。
 *
 * 这些用例锁住的是"模型的三种正常去向 + 两种失败"的判定顺序与容错口径——
 * 拆分前它们埋在 AgentEngine 里、没法单独跑；现在解析与副作用解耦，
 * 可以不启动引擎直接断言"这段话会被解析成什么"。
 */
class AgentResponseParserTest {

    // ---- 澄清 ----

    @Test
    fun parsePlan_需要澄清_返回澄清问题与选项() {
        val raw = """
            {
              "needs_clarification": true,
              "clarification": {
                "question": "你要订哪一天的？",
                "options": [
                  {"id": "today", "label": "今天", "description": "当天送达", "is_default": true},
                  {"id": "tomorrow", "label": "明天"}
                ]
              }
            }
        """.trimIndent()

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.Clarifying)
        val clarification = (phase as PlanPhase.Clarifying).clarification
        assertEquals("你要订哪一天的？", clarification.question)
        assertEquals(2, clarification.options.size)
        assertEquals("today", clarification.options[0].id)
        assertEquals("当天送达", clarification.options[0].description)
        assertTrue(clarification.options[0].isDefault)
        // 缺省字段不应炸：description 缺省为空串、is_default 缺省为 false
        assertEquals("", clarification.options[1].description)
        assertEquals(false, clarification.options[1].isDefault)
    }

    @Test
    fun parsePlan_需要澄清但选项字段缺失_仍是澄清而不是报错() {
        val raw = """{"needs_clarification": true}"""

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.Clarifying)
        val clarification = (phase as PlanPhase.Clarifying).clarification
        assertEquals("", clarification.question)
        assertTrue(clarification.options.isEmpty())
    }

    // ---- 纯对话 ----

    @Test
    fun parsePlan_reply为对象_判为纯对话() {
        val raw = """{"reply": {"text": "你好，有什么可以帮你？"}}"""

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.Reply)
        assertEquals("你好，有什么可以帮你？", (phase as PlanPhase.Reply).text)
    }

    @Test
    fun parsePlan_reply为裸字符串_同样判为纯对话() {
        val raw = """{"reply": "我在的"}"""

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.Reply)
        assertEquals("我在的", (phase as PlanPhase.Reply).text)
    }

    @Test
    fun parsePlan_reply为空串_不判纯对话而是继续找计划() {
        // 空回复不能当成"AI 有话要说"，否则用户会收到一条空气泡、且计划被吞掉
        val raw = """{"reply": "   ", "plan": {"steps": ["打开设置"]}}"""

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.AwaitingApproval)
        assertEquals(1, (phase as PlanPhase.AwaitingApproval).plan.steps.size)
    }

    @Test
    fun parsePlan_reply为对象但缺text_继续找计划而不是报错() {
        val raw = """{"reply": {"voice": "x"}, "plan": {"steps": ["打开设置"]}}"""

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.AwaitingApproval)
    }

    @Test
    fun parsePlan_字段类型与预期不符_收敛为Error而不是抛出() {
        // 调用方在协程里：解析层一抛，会被外层当成"规划异常"走另一套文案与流程
        assertTrue(AgentResponseParser.parsePlan("""{"plan": "本来该是对象"}""") is PlanPhase.Error)
        assertTrue(AgentResponseParser.parsePlan("""["这是个数组不是对象"]""") is PlanPhase.Error)
    }

    // ---- 计划 ----

    @Test
    fun parsePlan_步骤为对象数组_解析出描述与意图() {
        val raw = """
            {
              "plan": {
                "steps": [
                  {"description": "打开设置", "intent": "启动设置应用"},
                  {"description": "进入显示"}
                ],
                "estimated_time_seconds": 25,
                "confidence": 0.8
              }
            }
        """.trimIndent()

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.AwaitingApproval)
        val plan = (phase as PlanPhase.AwaitingApproval).plan
        assertEquals(2, plan.steps.size)
        assertEquals("打开设置", plan.steps[0].description)
        assertEquals("启动设置应用", plan.steps[0].intent)
        assertEquals("", plan.steps[1].intent)
        assertEquals(25, plan.estimatedTimeSeconds)
        assertEquals(0.8, plan.confidence, 0.0001)
    }

    @Test
    fun parsePlan_步骤为字符串数组_兼容解析() {
        val raw = """{"plan": {"steps": ["打开设置", "点击显示"]}}"""

        val phase = AgentResponseParser.parsePlan(raw)

        val plan = (phase as PlanPhase.AwaitingApproval).plan
        assertEquals(2, plan.steps.size)
        assertEquals("打开设置", plan.steps[0].description)
        // 没给就是 0 / 0.0，而不是抛异常
        assertEquals(0, plan.estimatedTimeSeconds)
        assertEquals(0.0, plan.confidence, 0.0001)
    }

    @Test
    fun parsePlan_计划无步骤字段_得到零步计划() {
        // "有 plan 对象但没 steps" 仍是计划（可能本就无需步骤），不是解析失败
        val raw = """{"plan": {}}"""

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.AwaitingApproval)
        assertTrue((phase as PlanPhase.AwaitingApproval).plan.steps.isEmpty())
    }

    // ---- 失败 ----

    @Test
    fun parsePlan_既无澄清也无回复也无计划_报无法解析() {
        val phase = AgentResponseParser.parsePlan("""{"foo": 1}""")

        assertTrue(phase is PlanPhase.Error)
        assertEquals("规划结果无法解析", (phase as PlanPhase.Error).message)
    }

    @Test
    fun parsePlan_非法JSON_报解析失败且带原因() {
        val phase = AgentResponseParser.parsePlan("这不是 JSON")

        assertTrue(phase is PlanPhase.Error)
        assertTrue((phase as PlanPhase.Error).message.startsWith("规划解析失败："))
    }

    @Test
    fun parsePlan_带代码块与前文解释_仍能取到JSON() {
        // 真实场景：模型爱先解释一句再把 JSON 放进 ```json 代码块
        val raw = """
            好的，我理解为「打开设置」。
            ```json
            {"plan": {"steps": ["打开设置"]}}
            ```
        """.trimIndent()

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.AwaitingApproval)
        assertEquals(1, (phase as PlanPhase.AwaitingApproval).plan.steps.size)
    }

    @Test
    fun parsePlan_JSON后还有解释文字_反向搜索仍能取到JSON() {
        val raw = """{"plan": {"steps": ["打开设置"]}} 以上就是我的计划，请批准。"""

        val phase = AgentResponseParser.parsePlan(raw)

        assertTrue(phase is PlanPhase.AwaitingApproval)
        assertEquals(1, (phase as PlanPhase.AwaitingApproval).plan.steps.size)
    }

    // ---- 记忆提炼 ----

    @Test
    fun parseDistilledMemories_正常解析出条目() {
        val raw = """
            {"memories": [
              {"content": "用户常订瑞幸", "category": "profile", "confidence": 0.9},
              {"content": "设置入口在顶部", "category": "rule"}
            ]}
        """.trimIndent()

        val items = AgentResponseParser.parseDistilledMemories(raw)

        assertEquals(2, items.size)
        assertEquals("用户常订瑞幸", items[0].content)
        assertEquals("profile", items[0].category)
        assertEquals(0.9, items[0].confidence, 0.0001)
        // 没给 confidence 时用默认 0.7，而不是 0（0 会被下游当成"不可信"）
        assertEquals(0.7, items[1].confidence, 0.0001)
    }

    @Test
    fun parseDistilledMemories_超过三条只取前三条() {
        val raw = """{"memories": [
            {"content": "a"}, {"content": "b"}, {"content": "c"}, {"content": "d"}
        ]}"""

        val items = AgentResponseParser.parseDistilledMemories(raw)

        assertEquals(3, items.size)
        assertEquals("a", items[0].content)
        assertEquals("c", items[2].content)
    }

    @Test
    fun parseDistilledMemories_跳过内容为空的条目() {
        val raw = """{"memories": [{"content": "   "}, {"content": "有效"}, {"category": "rule"}]}"""

        val items = AgentResponseParser.parseDistilledMemories(raw)

        assertEquals(1, items.size)
        assertEquals("有效", items[0].content)
    }

    @Test
    fun parseDistilledMemories_前后有解释文字_取最外层花括号() {
        val raw = """提炼结果如下：{"memories": [{"content": "有效"}]} 完毕"""

        val items = AgentResponseParser.parseDistilledMemories(raw)

        assertEquals(1, items.size)
        assertEquals("有效", items[0].content)
    }

    @Test
    fun parseDistilledMemories_无花括号或非法JSON_返回空列表() {
        assertTrue(AgentResponseParser.parseDistilledMemories("没有花括号").isEmpty())
        assertTrue(AgentResponseParser.parseDistilledMemories("{不是合法json}").isEmpty())
    }

    @Test
    fun parseDistilledMemories_缺memories字段_返回空列表() {
        assertTrue(AgentResponseParser.parseDistilledMemories("""{"other": []}""").isEmpty())
    }
}
