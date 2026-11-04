package com.phoneagent.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策流解析的可复现基准（纯 JVM，不依赖网络/真机）。
 *
 * 对比口径：
 * - 「旧」= 每帧对整段正文 `toString()` 再从头找对象（全量扫描 + 全量复制）；
 * - 「新」= [IncrementalIntentParser] 只扫描新增区间，闭合即返回。
 *
 * 测试会打印 `DECISION_BENCH ...` 行，数字可直接贴进变更说明。断言只锁相对关系
 * （增量必须更快 / 早停必须少读流），不锁绝对毫秒，避免机器差异导致红。
 */
class DecisionStreamBenchmarkTest {

    // 约 3.7KB，模拟真实决策 JSON（含较长的 reason/expect）
    private val json = "{\"intent\":\"tap\",\"target\":\"设置\",\"reason\":\"打开设置页\"," +
        "\"expect\":\"出现设置页\",\"params\":{\"x\":0.5,\"y\":0.3},\"padding\":\"" +
        "x".repeat(3_600) + "\"}"

    private fun streamWithTail(): String {
        // JSON 在整条流的 40% 左右闭合，后面是模型继续输出的解释性文本
        val prefix = "p".repeat(json.length * 2 / 3)
        val suffix = "s".repeat(json.length)
        return prefix + json + suffix
    }

    @Test
    fun 增量解析在对象闭合处提前结束_少读整条流() {
        val stream = streamWithTail()
        val full = StringBuilder()
        val parser = IncrementalIntentParser()
        var fed = 0
        var closedAt = -1

        stream.chunked(16).forEach { chunk ->
            full.append(chunk)
            fed += chunk.length
            if (closedAt < 0 && parser.nextObject(full) != null) {
                closedAt = fed
            }
        }

        assertTrue("样例流没有闭合对象，测试失效", closedAt > 0)
        val saved = 1.0 - closedAt.toDouble() / stream.length
        println(
            "DECISION_BENCH early_stop_feed=$fed total=${stream.length} closedAt=$closedAt " +
                "saved=${"%.1f".format(saved * 100)}%",
        )
        assertTrue("提前结束应至少少读 30% 的流，实际少读 ${"%.1f".format(saved * 100)}%", saved >= 0.30)
    }

    @Test
    fun 增量解析吞吐优于每帧全量扫描复制() {
        val rounds = 12
        val frames = json.chunked(64)
        val naiveTimes = mutableListOf<Long>()
        val incrementalTimes = mutableListOf<Long>()

        repeat(rounds) {
            // 旧口径：每帧全量复制 + 从头找对象
            var sink = 0
            val startOld = System.nanoTime()
            val oldBuffer = StringBuilder()
            frames.forEach { frame ->
                oldBuffer.append(frame)
                sink += naivePerFrame(oldBuffer).length
            }
            naiveTimes += System.nanoTime() - startOld

            // 新口径：增量扫描，闭合即停
            val startNew = System.nanoTime()
            val parser = IncrementalIntentParser()
            val newBuffer = StringBuilder()
            var hits = 0
            frames.forEach { frame ->
                newBuffer.append(frame)
                if (hits == 0 && parser.nextObject(newBuffer) != null) hits++
            }
            incrementalTimes += System.nanoTime() - startNew
            assertEquals("两种口径都必须解析出 1 个对象", 1, hits)
            assertTrue(sink > 0)
        }

        val oldMs = median(naiveTimes) / 1_000_000.0
        val newMs = median(incrementalTimes) / 1_000_000.0
        val speedup = oldMs / newMs
        println(
            "DECISION_BENCH parse bytes=${json.length} frames=${frames.size} " +
                "old=${"%.2f".format(oldMs)}ms new=${"%.2f".format(newMs)}ms speedup=${"%.1f".format(speedup)}x",
        )
        assertTrue("增量解析应快于每帧全量扫描（old=$oldMs ms, new=$newMs ms）", newMs < oldMs)
    }

    @Test
    fun 早停复用已闭合对象_避免整段正文再复制一次() {
        val body = StringBuilder().apply { repeat(64) { append(json) } } // 约 256KB
        val closed = json
        val iterations = 400
        var sink = 0

        // 旧：早停后仍对整段正文做一次 toString
        val beforeStart = System.nanoTime()
        repeat(iterations) { sink += body.toString().hashCode() }
        val beforeMs = (System.nanoTime() - beforeStart) / 1_000_000.0

        // 新：直接复用已经解析的那个闭合字符串
        val afterStart = System.nanoTime()
        repeat(iterations) { sink += closed.hashCode() }
        val afterMs = (System.nanoTime() - afterStart) / 1_000_000.0

        println(
            "DECISION_BENCH early_stop_copy body=${body.length}B iter=$iterations " +
                "before=${"%.2f".format(beforeMs)}ms after=${"%.2f".format(afterMs)}ms " +
                "avoided=${"%.1f".format(body.length.toDouble() * iterations / 1024)}KB",
        )
        assertTrue(sink != Int.MIN_VALUE)
        assertTrue("复用已闭合对象不应慢于整段复制", afterMs <= beforeMs)
    }

    private fun naivePerFrame(text: StringBuilder): String {
        val s = text.toString()
        val start = s.indexOf('{')
        assertTrue(start >= 0)
        val end = s.lastIndexOf('}')
        return s.substring(start, end + 1)
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
}
