package com.phoneagent.memory

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
 * 记忆系统：异常经验记忆 + 用户画像，基于 DataStore 持久化。
 * 对应文档“第 10 层 记忆系统”。
 */
class MemoryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val anomaliesKey = stringPreferencesKey("anomaly_memory")
    private val profileKey = stringPreferencesKey("user_profile")

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