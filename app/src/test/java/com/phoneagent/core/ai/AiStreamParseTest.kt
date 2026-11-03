package com.phoneagent.core.ai

import com.phoneagent.domain.model.AgentIntent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策流解析层的低风险优化回归：
 * 1) 边收边扫描闭合 JSON（能正确跳过字符串里的花括号/转义/嵌套对象）
 * 2) 完整正文解析失败时先本地修复，再决定要不要纠错重发
 * 3) 统计口径能给出「重发占比 / 首 token 占比」
 */
class AiStreamParseTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------- 增量扫描 ----------

    @Test
    fun `分帧到达时闭合后才返回对象`() {
        val parser = IncrementalIntentParser()
        val full = StringBuilder()
        var obj: String? = null
        listOf("{", "\"intent\":\"tap\",", "\"target\":{\"by\":\"text\",", "\"value\":\"确定\"}", "}").forEach { delta ->
            full.append(delta)
            obj = obj ?: parser.nextObject(full)
        }
        assertTrue("闭合后应返回对象：$obj", obj != null && obj!!.startsWith("{") && obj!!.endsWith("}"))
        val parsed = json.decodeFromString(AgentIntent.serializer(), obj!!)
        assertEquals("tap", parsed.intent)
        assertEquals("确定", parsed.target?.value)
    }

    @Test
    fun `字符串里的花括号不打断扫描`() {
        val parser = IncrementalIntentParser()
        val full = StringBuilder()
        full.append("{\"intent\":\"say\",\"summary\":\"写代码要写 { 和 } 才对\"}")
        val obj = parser.nextObject(full)
        val parsed = json.decodeFromString(AgentIntent.serializer(), obj!!)
        assertEquals("写代码要写 { 和 } 才对", parsed.summary)
    }

    @Test
    fun `未闭合时继续等待`() {
        val parser = IncrementalIntentParser()
        val full = StringBuilder("{\"intent\":\"tap\"")
        assertNull(parser.nextObject(full))
        full.append(",\"target\":{\"by\":\"text\",\"value\":\"好的\"}}")
        assertTrue(parser.nextObject(full) != null)
    }

    @Test
    fun `reset 后从头扫描`() {
        val parser = IncrementalIntentParser()
        val full = StringBuilder("{\"intent\":\"tap\"}")
        assertTrue(parser.nextObject(full) != null)
        parser.reset()
        full.setLength(0)
        full.append("{\"intent\":\"back\"}")
        val obj = parser.nextObject(full)
        assertEquals("back", json.decodeFromString(AgentIntent.serializer(), obj!!).intent)
    }

    // ---------- 本地修复 ----------

    @Test
    fun `截断的 JSON 能补齐括号与引号`() {
        val repaired = IntentJsonRepair.repair("{\"intent\":\"tap\",\"target\":{\"by\":\"text\",\"value\":\"确定")
        val parsed = json.decodeFromString(AgentIntent.serializer(), repaired!!)
        assertEquals("tap", parsed.intent)
        assertEquals("确定", parsed.target?.value)
    }

    @Test
    fun `尾逗号会被去掉`() {
        val repaired = IntentJsonRepair.repair("{\"intent\":\"back\",}")
        assertEquals("{\"intent\":\"back\"}", repaired)
        assertEquals("back", json.decodeFromString(AgentIntent.serializer(), repaired!!).intent)
    }

    @Test
    fun `围栏与解释文字会被剔除`() {
        val repaired = IntentJsonRepair.repair(
            "```json\n{\"intent\":\"click\",\"target\":{\"by\":\"text\",\"value\":\"搜索\"}}\n```\n以上是这一步的动作。",
        )
        val parsed = json.decodeFromString(AgentIntent.serializer(), repaired!!)
        assertEquals("click", parsed.intent)
    }

    @Test
    fun `字符串里的裸换行会被转义`() {
        val repaired = IntentJsonRepair.repair("{\"intent\":\"say\",\"summary\":\"第一行\n第二行\"}")
        val parsed = json.decodeFromString(AgentIntent.serializer(), repaired!!)
        assertEquals("第一行\n第二行", parsed.summary)
    }

    @Test
    fun `没有 JSON 时不产出修复结果`() {
        assertNull(IntentJsonRepair.repair("我无法完成这个任务。"))
    }

    @Test
    fun `合法 JSON 修复后仍可解析`() {
        val original = "{\"intent\":\"click\",\"target\":{\"by\":\"text\",\"value\":\"设置\"}}"
        val repaired = IntentJsonRepair.repair(original)
        assertEquals(original, repaired)
        assertEquals("click", json.decodeFromString(AgentIntent.serializer(), repaired!!).intent)
    }

    // ---------- 统计口径 ----------

    @Test
    fun `统计能给出重发占比与首 token 占比`() {
        DecisionStreamStats.reset()
        repeat(4) { DecisionStreamStats.onCall() }
        DecisionStreamStats.onResend()
        DecisionStreamStats.onLocalRepair()
        DecisionStreamStats.onEarlyStop()
        DecisionStreamStats.onEarlyStop()
        DecisionStreamStats.onTiming(ttftMs = 100, totalMs = 2_000)
        DecisionStreamStats.onTiming(ttftMs = 200, totalMs = 3_000)

        val summary = DecisionStreamStats.summaryText()
        assertTrue("重发占比应为 25%：$summary", summary.contains("重发占比 25%"))
        assertTrue("提前结束占比应为 50%：$summary", summary.contains("50%"))
        assertTrue("首 token 占比应为 6%：$summary", summary.contains("首 token 占比 6%"))
        val counts = DecisionStreamStats.counts()
        assertEquals(4, counts.calls)
        assertEquals(1, counts.resends)
        assertEquals(1, counts.localRepairs)
        assertEquals(2, counts.earlyStops)
        assertEquals(300L, counts.ttftMsSum)
        assertEquals(5_000L, counts.totalMsSum)
    }
}
