package com.phoneagent.core.ai

import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 帧解析回归。
 *
 * `chatForAction`（决策）与 `chatStream`（规划/思考）原先各写一份逐行解析，本轮抽成
 * [AiClient.pumpSse]。用内存中的 SSE 文本把三条口径钉住：
 * 1. 正文只累积 content，展示优先 content、其次 reasoning；
 * 2. `[DONE]` 之后不再消费，空行与非 data 行被忽略；
 * 3. usage 帧只在调用方要求时解析（规划链路传 null，保持原行为）。
 */
class AiSsePumpTest {

    private val client = AiClient(
        okhttp3.OkHttpClient(),
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    private fun chunk(content: String? = null, reasoning: String? = null, usage: String? = null): String {
        val delta = buildString {
            append('{')
            content?.let { append("\"content\":\"$it\"") }
            reasoning?.let {
                if (length > 1) append(',')
                append("\"reasoning_content\":\"$it\"")
            }
            append('}')
        }
        return buildString {
            append("data: {\"choices\":[{\"delta\":$delta}]")
            usage?.let { append(",\"usage\":$it") }
            append("}\n")
        }
    }

    @Test
    fun `正文按增量累积并回调`() {
        val stream = chunk(content = "你") + chunk(content = "好") + "data: [DONE]\n"
        val full = StringBuilder()
        val deltas = mutableListOf<String>()
        val sawReasoning = client.pumpSse(Buffer().writeUtf8(stream), null, full, onDelta = { deltas += it })
        assertEquals("你好", full.toString())
        assertEquals(listOf("你", "好"), deltas)
        assertFalse(sawReasoning)
    }

    @Test
    fun `思考内容优先展示但不进正文`() {
        val stream = chunk(reasoning = "想一下") + chunk(content = "答案") + "data: [DONE]\n"
        val full = StringBuilder()
        val deltas = mutableListOf<String>()
        val sawReasoning = client.pumpSse(Buffer().writeUtf8(stream), null, full, onDelta = { deltas += it })
        assertTrue(sawReasoning)
        assertEquals(listOf("想一下", "答案"), deltas)
        // 正文只累积 content：思考内容不应进入最终解析的 JSON
        assertEquals("答案", full.toString())
    }

    @Test
    fun `DONE 之后不再消费`() {
        val stream = chunk(content = "前") + "data: [DONE]\n" + chunk(content = "后")
        val full = StringBuilder()
        client.pumpSse(Buffer().writeUtf8(stream), null, full, onDelta = {})
        assertEquals("前", full.toString())
    }

    @Test
    fun `空行与非 data 行被忽略`() {
        val stream = "\n: keep-alive\n" + chunk(content = "A") + "\n"
        val full = StringBuilder()
        client.pumpSse(Buffer().writeUtf8(stream), null, full, onDelta = {})
        assertEquals("A", full.toString())
    }

    @Test
    fun `usage 帧只在调用方需要时解析`() {
        val stream = chunk(content = "X", usage = "{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}")

        var usage: Usage? = null
        client.pumpSse(
            Buffer().writeUtf8(stream), null, StringBuilder(), onDelta = {}, onUsage = { usage = it },
        )
        assertEquals(3, usage?.totalTokens)

        // onUsage = null：正文照常累积，usage 帧不参与解析（规划链路原有行为）
        val full = StringBuilder()
        client.pumpSse(Buffer().writeUtf8(stream), null, full, onDelta = {}, onUsage = null)
        assertEquals("X", full.toString())
    }
}
