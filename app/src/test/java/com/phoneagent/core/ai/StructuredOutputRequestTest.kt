package com.phoneagent.core.ai

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策请求体的字段约束：开关关闭时请求体必须与历史版本一致（不出现任何结构化字段）。
 *
 * 直接对请求体文本断言，而不是对布尔值断言——这样"参数算对了但没接进请求体"这类断链也能被发现。
 */
class StructuredOutputRequestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = AiClient(OkHttpClient(), json)

    private val messages = listOf(
        ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "打开设置"))),
    )

    private fun body(structured: Boolean, guided: GuidedDecoding): String {
        val requestBody = client.buildStreamRequestBody(
            messages = messages,
            model = "test-model",
            temperature = 0.4,
            extras = client.decisionBodyExtras(structured, guided),
        )
        val buffer = Buffer()
        requestBody.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `关闭时不发任何结构化字段`() {
        val text = body(structured = false, guided = GuidedDecoding.OFF)
        assertFalse(text, text.contains("response_format"))
        assertFalse(text, text.contains("guided_json"))
        assertFalse(text, text.contains("json_schema"))
        // 流式不能丢：这是决策链主路径的前提
        assertTrue(text, text.contains("\"stream\":true"))
    }

    @Test
    fun `开启时发 json_schema_且 schema 带意图枚举`() {
        val text = body(structured = true, guided = GuidedDecoding.OFF)
        assertTrue(text, text.contains("\"response_format\""))
        assertTrue(text, text.contains("\"json_schema\""))
        assertTrue("schema 应带意图名，避免模型吐非法意图", text.contains("open_app"))
        assertFalse("同一请求不能同时下发引导解码", text.contains("guided_json"))
    }

    @Test
    fun `vLLM 档只发顶层 guided_json`() {
        val text = body(structured = true, guided = GuidedDecoding.VLLM)
        assertTrue(text, text.contains("\"guided_json\""))
        assertFalse("引导解码时不再下发 response_format", text.contains("response_format"))
    }

    @Test
    fun `SGLang 档只发顶层 json_schema`() {
        val text = body(structured = true, guided = GuidedDecoding.SGLANG)
        assertTrue(text, text.contains("\"json_schema\""))
        assertFalse(text, text.contains("response_format"))
        assertFalse(text, text.contains("guided_json"))
    }
}
