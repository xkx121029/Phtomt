package com.phoneagent.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 模型库：端点（API 地址 + Key）→ 可用模型（带能力）→ 职责分配（主 / 视觉 / 思考）。
 *
 * 与「职责槽」解耦：一个模型可同时担任多个职责，职责行只记"指哪个模型"，
 * 不再重复填地址与 Key。职责槽本身仍以旧的扁平字段持久化（引擎契约），
 * 本文件只负责"端点 + 模型"这两层。
 */

/** 一个 API 端点。URL 天然唯一，故 [id] 直接用归一化 URL，不另设显示名 */
@Serializable
data class Endpoint(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
)

/**
 * 模型库条目。
 * [vision] / [tools] 为 `null` 表示**未测出**（服务端 400/5xx、网关改写、网络故障），
 * `false` 才表示测出"确实不支持" —— 两者不能混，否则断网会把模型标成不支持。
 */
@Serializable
data class CatalogModel(
    val endpointId: String,
    val name: String,
    val vision: Boolean? = null,
    val tools: Boolean? = null,
    /** 未测出时的原文摘要，UI 折叠展示 */
    val note: String = "",
    /** 上次探测时间（仅展示，不做自动过期重探） */
    val probedAt: Long = 0L,
)

/** 一次能力探测的结果 */
@Serializable
data class ModelAbility(
    val vision: Boolean?,
    val tools: Boolean?,
    val note: String = "",
)

object ModelCatalogCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val endpointsSerializer = ListSerializer(Endpoint.serializer())
    private val modelsSerializer = ListSerializer(CatalogModel.serializer())

    /** 端点 id：归一化后的 URL（补协议、去尾斜杠），与探测/请求实际用的地址一致 */
    fun endpointId(baseUrl: String): String = AiClient.normalizeBaseUrl(baseUrl.trim())

    fun encodeEndpoints(list: List<Endpoint>): String =
        json.encodeToString(endpointsSerializer, list)

    /** 坏数据（空串 / 历史格式 / 手工改坏）一律当空表，不让设置页崩 */
    fun decodeEndpoints(raw: String): List<Endpoint> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString(endpointsSerializer, raw) }.getOrDefault(emptyList())
            .filter { it.id.isNotBlank() }

    fun encodeModels(list: List<CatalogModel>): String =
        json.encodeToString(modelsSerializer, list)

    fun decodeModels(raw: String): List<CatalogModel> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString(modelsSerializer, raw) }.getOrDefault(emptyList())
            .filter { it.endpointId.isNotBlank() && it.name.isNotBlank() }

    /**
     * 老配置 → 端点 + 模型库。**纯函数、不写回**（DataStore 的 `map` 里不能写，且读时写会有竞态）。
     *
     * 三组扁平槽位按归一化 URL 分组成端点；同一模型名只留一条（主槽优先）；
     * 视觉 / 思考槽的地址或 Key 为空时回退主槽，与引擎 visionConfig / reasoningConfig 的取值一致。
     */
    fun migrateFromLegacy(
        apiBaseUrl: String,
        apiKey: String,
        model: String,
        visionBaseUrl: String,
        visionApiKey: String,
        visionModel: String,
        reasonBaseUrl: String,
        reasonApiKey: String,
        reasonModel: String,
    ): Pair<List<Endpoint>, List<CatalogModel>> {
        val mainUrl = apiBaseUrl.trim()
        val mainKey = apiKey.trim()
        val slots = listOf(
            Triple(mainUrl, mainKey, model),
            Triple(visionBaseUrl.trim().ifBlank { mainUrl }, visionApiKey.trim().ifBlank { mainKey }, visionModel),
            Triple(reasonBaseUrl.trim().ifBlank { mainUrl }, reasonApiKey.trim().ifBlank { mainKey }, reasonModel),
        )

        val endpoints = LinkedHashMap<String, Endpoint>()
        val models = LinkedHashMap<String, CatalogModel>()
        for ((url, key, name) in slots) {
            val id = endpointId(url)
            if (id.isBlank()) continue
            endpoints.putIfAbsent(id, Endpoint(id = id, baseUrl = url, apiKey = key))
            val trimmed = name.trim()
            if (trimmed.isBlank()) continue
            models.putIfAbsent("$id/$trimmed", CatalogModel(endpointId = id, name = trimmed))
        }
        return endpoints.values.toList() to models.values.toList()
    }
}