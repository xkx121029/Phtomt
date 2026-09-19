package com.phoneagent.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.memoryStore by preferencesDataStore(name = "agent_memory")

/** 异常经验条目 */
@Serializable
data class AnomalyMemoryEntry(
    val id: Long,
    val pageFingerprint: String,
    val pageLabels: List<String>,
    val anomalyType: String,
    val anomalyDescription: String,
    val userSolution: String,
    val resolvedAction: String,
    val appPackage: String,
    val hitCount: Int = 0,
    val successCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/** 用户画像条目 */
@Serializable
data class ProfileEntry(
    val key: String,
    val category: String,
    val value: String,
    val confidence: Double = 0.5,
    val useCount: Int = 0,
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/**
 * AI 记忆条目：AI 在任务执行中主动记录 / 任务结束时提炼出的自然语言记忆。
 * 用独立的 DataStore key（ai_memory）与画像/异常经验物理隔离，旧数据反序列化零风险。
 */
@Serializable
data class AiMemoryEntry(
    val id: Long = 0L,
    /** 记忆内容，一句话 */
    val content: String,
    /** 分类：preference | fact | habit | tip | general */
    val category: String = "general",
    /** 来源任务名 */
    val sourceTask: String = "",
    /** 来源：agent(主动) | distill(提炼) | user */
    val source: String = "agent",
    val confidence: Double = 0.7,
    val useCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
)

/**
 * 记忆系统：异常经验记忆 + 用户画像，基于 DataStore 持久化。
 * 对应文档“第 10 层 记忆系统”。
 */
class MemoryStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val anomaliesKey = stringPreferencesKey("anomaly_memory")
    private val profileKey = stringPreferencesKey("user_profile")
    private val aiMemoryKey = stringPreferencesKey("ai_memory")

    suspend fun loadAnomalies(): List<AnomalyMemoryEntry> {
        val raw = context.memoryStore.data.first()[anomaliesKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<AnomalyMemoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveAnomalies(list: List<AnomalyMemoryEntry>) {
        context.memoryStore.edit { it[anomaliesKey] = json.encodeToString(ListSerializer(AnomalyMemoryEntry.serializer()), list) }
    }

    suspend fun loadProfile(): List<ProfileEntry> {
        val raw = context.memoryStore.data.first()[profileKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ProfileEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveProfile(list: List<ProfileEntry>) {
        context.memoryStore.edit { it[profileKey] = json.encodeToString(ListSerializer(ProfileEntry.serializer()), list) }
    }

    // ---- AI 记忆（自然语言条目）----

    suspend fun loadAiMemories(): List<AiMemoryEntry> {
        val raw = context.memoryStore.data.first()[aiMemoryKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<AiMemoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveAiMemories(list: List<AiMemoryEntry>) {
        context.memoryStore.edit { it[aiMemoryKey] = json.encodeToString(ListSerializer(AiMemoryEntry.serializer()), list) }
    }

    /**
     * 写入一条 AI 记忆：内容与已有记忆高度相似时合并更新（保留原 id），否则新增。
     * id 由本方法统一分配，调用方无需关心。
     */
    suspend fun upsertAiMemory(
        content: String,
        category: String,
        sourceTask: String,
        source: String,
        confidence: Double = 0.7,
    ): AiMemoryUpsert? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val list = loadAiMemories().toMutableList()
        val now = System.currentTimeMillis()
        val matched = list.firstOrNull { AiMemoryDedupe.isSame(it.content, text) }
        return if (matched != null) {
            val merged = matched.copy(
                content = text,
                category = category.ifBlank { matched.category },
                sourceTask = sourceTask.ifBlank { matched.sourceTask },
                confidence = maxOf(matched.confidence, confidence),
                updatedAt = now,
            )
            list[list.indexOf(matched)] = merged
            saveAiMemories(list)
            AiMemoryUpsert.Updated(merged)
        } else {
            val entry = AiMemoryEntry(
                id = (list.maxOfOrNull { it.id } ?: 0L) + 1,
                content = text,
                category = category.ifBlank { "general" },
                sourceTask = sourceTask,
                source = source,
                confidence = confidence,
                createdAt = now,
                updatedAt = now,
            )
            list.add(entry)
            saveAiMemories(list)
            AiMemoryUpsert.Added(entry)
        }
    }

    /** 删除单条 AI 记忆，返回是否删掉了东西 */
    suspend fun deleteAiMemory(id: Long): Boolean {
        val list = loadAiMemories()
        val rest = list.filterNot { it.id == id }
        if (rest.size == list.size) return false
        saveAiMemories(rest)
        return true
    }

    /** 记忆被实际引用后累计使用次数 */
    suspend fun touchAiMemories(ids: List<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val idSet = ids.toSet()
        val list = loadAiMemories().map { entry ->
            if (entry.id in idSet) entry.copy(useCount = entry.useCount + 1, lastUsedAt = now) else entry
        }
        saveAiMemories(list)
    }
}

/** AI 记忆写入结果：新增还是合并更新（供界面区分「已记住 / 已更新」） */
sealed interface AiMemoryUpsert {
    /** 写入后的条目（新增即该条本身，更新即合并后的结果） */
    val entry: AiMemoryEntry

    data class Added(override val entry: AiMemoryEntry) : AiMemoryUpsert
    data class Updated(override val entry: AiMemoryEntry) : AiMemoryUpsert
}

/**
 * 异常经验记忆引擎：命中后直接复用解决方案。
 */
class AnomalyMemoryEngine(private val store: MemoryStore) {

    suspend fun findSolution(fingerprint: String, labels: List<String>): AnomalyMemoryEntry? {
        val entries = store.loadAnomalies()
        // 精确匹配指纹；否则按标签重叠度匹配
        val exact = entries.firstOrNull { it.pageFingerprint == fingerprint }
        if (exact != null) return exact
        return entries.maxByOrNull { e -> labels.count { it in e.pageLabels } }
            ?.takeIf { e -> labels.any { it in e.pageLabels } }
    }

    suspend fun recordUse(entry: AnomalyMemoryEntry, success: Boolean) {
        val entries = store.loadAnomalies().toMutableList()
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            val cur = entries[idx]
            entries[idx] = cur.copy(
                hitCount = cur.hitCount + 1,
                successCount = cur.successCount + (if (success) 1 else 0),
                lastUsedAt = System.currentTimeMillis(),
            )
            store.saveAnomalies(entries)
        }
    }

    suspend fun saveSolution(entry: AnomalyMemoryEntry) {
        val entries = store.loadAnomalies().toMutableList()
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
        store.saveAnomalies(entries)
    }
}

/**
 * 用户画像学习：任务失败后用户提供的解决方案自动沉淀为异常记忆。
 */
class ProfileLearner(private val store: MemoryStore) {

    suspend fun learnAnomalySolution(
        fingerprint: String,
        labels: List<String>,
        anomalyType: String,
        description: String,
        solution: String,
        resolvedAction: String,
        appPackage: String,
    ) {
        val engine = AnomalyMemoryEngine(store)
        val entry = AnomalyMemoryEntry(
            id = System.currentTimeMillis(),
            pageFingerprint = fingerprint,
            pageLabels = labels,
            anomalyType = anomalyType,
            anomalyDescription = description,
            userSolution = solution,
            resolvedAction = resolvedAction,
            appPackage = appPackage,
        )
        engine.saveSolution(entry)
    }
}