package com.phoneagent.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型库编解码与老配置迁移测试。
 * 核心不变量：
 * - `null` 能力值必须原样往返（未测出 ≠ 不支持）；
 * - 坏 JSON 一律当空表，不让设置页崩；
 * - 老的三组扁平槽位迁移后不丢地址、不丢 Key、同名模型只留一条。
 */
class ModelCatalogCodecTest {

    @Test
    fun endpointsRoundTrip() {
        val list = listOf(
            Endpoint("https://a.com/v1", "https://a.com/v1", "k1"),
            Endpoint("http://b:8000/v1", "http://b:8000/v1", "k2"),
        )
        assertEquals(list, ModelCatalogCodec.decodeEndpoints(ModelCatalogCodec.encodeEndpoints(list)))
    }

    @Test
    fun modelsRoundTripKeepsUnknownAbility() {
        val list = listOf(
            CatalogModel("https://a.com/v1", "glm-4.6", vision = true, tools = false, note = "工具：HTTP 400"),
            CatalogModel("https://a.com/v1", "deepseek-chat", vision = null, tools = null),
        )
        val back = ModelCatalogCodec.decodeModels(ModelCatalogCodec.encodeModels(list))
        assertEquals(list, back)
        assertEquals(null, back[1].vision)
        assertEquals(null, back[1].tools)
    }

    @Test
    fun badJsonFallsBackToEmpty() {
        assertEquals(emptyList<Endpoint>(), ModelCatalogCodec.decodeEndpoints("https://a.com/v1"))
        assertEquals(emptyList<Endpoint>(), ModelCatalogCodec.decodeEndpoints(""))
        assertEquals(emptyList<Endpoint>(), ModelCatalogCodec.decodeEndpoints("   "))
        assertEquals(emptyList<CatalogModel>(), ModelCatalogCodec.decodeModels("{\"oops\":1}"))
    }

    @Test
    fun decodeDropsIncompleteRows() {
        assertEquals(
            emptyList<Endpoint>(),
            ModelCatalogCodec.decodeEndpoints("""[{"id":"","baseUrl":"https://a.com/v1","apiKey":"k"}]"""),
        )
        assertEquals(
            emptyList<CatalogModel>(),
            ModelCatalogCodec.decodeModels("""[{"endpointId":"https://a.com/v1","name":"  "}]"""),
        )
    }

    @Test
    fun endpointIdNormalizesAddress() {
        assertEquals("http://192.168.1.5:8000/v1", ModelCatalogCodec.endpointId("  192.168.1.5:8000/v1/ "))
        assertEquals("https://api.deepseek.com/v1", ModelCatalogCodec.endpointId("api.deepseek.com/v1"))
        assertEquals("", ModelCatalogCodec.endpointId("  "))
    }

    @Test
    fun migratesThreeSlotsIntoSingleEndpoint() {
        val (eps, models) = ModelCatalogCodec.migrateFromLegacy(
            apiBaseUrl = "https://open.bigmodel.cn/api/paas/v4/",
            apiKey = "main-key",
            model = "glm-4.6",
            visionBaseUrl = "",
            visionApiKey = "",
            visionModel = "glm-4.6v-flash",
            reasonBaseUrl = "",
            reasonApiKey = "",
            reasonModel = "glm-4.6",
        )
        assertEquals(1, eps.size)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", eps[0].id)
        assertEquals("main-key", eps[0].apiKey)
        // 视觉槽地址为空 → 回退主槽；思考槽同名模型只留一条
        assertEquals(listOf("glm-4.6", "glm-4.6v-flash"), models.map { it.name })
        assertTrue(models.all { it.endpointId == eps[0].id })
    }

    @Test
    fun migratesDistinctEndpointsAndFallsBackKey() {
        val (eps, models) = ModelCatalogCodec.migrateFromLegacy(
            apiBaseUrl = "https://a.com/v1",
            apiKey = "ka",
            model = "m-a",
            visionBaseUrl = "https://b.com/v1",
            visionApiKey = "",
            visionModel = "m-b",
            reasonBaseUrl = "",
            reasonApiKey = "kr",
            reasonModel = "m-r",
        )
        assertEquals(2, eps.size)
        // 视觉槽只有地址没有 Key → Key 回退主槽，与引擎 visionConfig 取值一致
        assertEquals("ka", eps.first { it.id == "https://b.com/v1" }.apiKey)
        assertEquals(3, models.size)
        // 思考槽无地址 → 模型落到主端点
        assertEquals("https://a.com/v1", models.first { it.name == "m-r" }.endpointId)
        assertEquals("https://b.com/v1", models.first { it.name == "m-b" }.endpointId)
    }

    @Test
    fun blankConfigYieldsEmptyCatalog() {
        val (eps, models) = ModelCatalogCodec.migrateFromLegacy("", "", "", "", "", "", "", "", "")
        assertTrue(eps.isEmpty())
        assertTrue(models.isEmpty())
    }
}