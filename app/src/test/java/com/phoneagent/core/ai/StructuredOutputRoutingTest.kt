package com.phoneagent.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构化输出三态 + 端点引导解码的判定单测。
 *
 * 这里钉住的是「默认不改变旧行为」这条底线：AUTO + 没测出 = 不发 response_format。
 */
class StructuredOutputRoutingTest {

    private val endpointId = ModelCatalogCodec.endpointId("https://api.example.com/v1")

    @Test
    fun `脏数据回落自动档`() {
        assertEquals(StructuredOutputMode.AUTO, StructuredOutputRouting.modeFromKey(null))
        assertEquals(StructuredOutputMode.AUTO, StructuredOutputRouting.modeFromKey(""))
        assertEquals(StructuredOutputMode.AUTO, StructuredOutputRouting.modeFromKey("whatever"))
        assertEquals(StructuredOutputMode.ON, StructuredOutputRouting.modeFromKey(" on "))
        assertEquals(StructuredOutputMode.OFF, StructuredOutputRouting.modeFromKey("OFF"))
    }

    @Test
    fun `自动档只听探测结果_没测出不发`() {
        assertFalse(StructuredOutputRouting.resolve(StructuredOutputMode.AUTO, null))
        assertFalse(StructuredOutputRouting.resolve(StructuredOutputMode.AUTO, false))
        assertTrue(StructuredOutputRouting.resolve(StructuredOutputMode.AUTO, true))
    }

    @Test
    fun `强制档忽略探测`() {
        assertTrue(StructuredOutputRouting.resolve(StructuredOutputMode.ON, null))
        assertTrue(StructuredOutputRouting.resolve(StructuredOutputMode.ON, false))
        assertFalse(StructuredOutputRouting.resolve(StructuredOutputMode.OFF, true))
    }

    @Test
    fun `探测结果按端点加模型名精确匹配`() {
        val catalog = listOf(
            CatalogModel(endpointId = endpointId, name = "gpt-x", jsonSchema = true),
            CatalogModel(endpointId = endpointId, name = "gpt-y", jsonSchema = false),
            CatalogModel(endpointId = "https://other/v1", name = "gpt-x", jsonSchema = null),
        )
        // 地址带尾斜杠也要命中（endpointId 会归一化）
        assertTrue(
            StructuredOutputRouting.probedOf(catalog, "https://api.example.com/v1/", "gpt-x") == true,
        )
        assertFalse(StructuredOutputRouting.probedOf(catalog, "https://api.example.com/v1", "gpt-y") == true)
        assertEquals(null, StructuredOutputRouting.probedOf(catalog, "https://unknown/v1", "gpt-x"))
        assertEquals(null, StructuredOutputRouting.probedOf(catalog, "https://api.example.com/v1", " "))
    }

    @Test
    fun `端点引导解码_未入库端点按关闭`() {
        val endpoints = listOf(
            Endpoint(id = endpointId, baseUrl = "https://api.example.com/v1", apiKey = "k",
                guidedDecoding = "VLLM"),
            Endpoint(id = "https://sglang/v1", baseUrl = "https://sglang/v1", apiKey = "k",
                guidedDecoding = "SGLANG"),
        )
        assertEquals(GuidedDecoding.VLLM, StructuredOutputRouting.guidedOf(endpoints, "https://api.example.com/v1"))
        assertEquals(GuidedDecoding.SGLANG, StructuredOutputRouting.guidedOf(endpoints, "https://sglang/v1/"))
        assertEquals(GuidedDecoding.OFF, StructuredOutputRouting.guidedOf(endpoints, "https://nope/v1"))
        assertEquals(GuidedDecoding.OFF, GuidedDecoding.fromKey("garbage"))
    }

    @Test
    fun `只有点名字段的400才允许降级重试`() {
        assertTrue(
            StructuredOutputRouting.isStructuredRejection(
                400, """{"error":{"message":"response_format is not supported"}}""",
            ),
        )
        assertTrue(StructuredOutputRouting.isStructuredRejection(422, "unknown field json_schema"))
        assertTrue(StructuredOutputRouting.isStructuredRejection(400, "guided_json: invalid"))
        // 不点名字段的 400 不能当成"可以摘约束"，否则会把真正的参数错误吞掉
        assertFalse(StructuredOutputRouting.isStructuredRejection(400, "invalid api key"))
        assertFalse(StructuredOutputRouting.isStructuredRejection(500, "response_format"))
        assertFalse(StructuredOutputRouting.isStructuredRejection(400, null))
    }
}
