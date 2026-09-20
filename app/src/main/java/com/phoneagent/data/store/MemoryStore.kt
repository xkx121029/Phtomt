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
 * 任务记忆条目：一次任务执行期间持续维护的「目标 / 用户要求 / 已验证做法」。
 *
 * 与 AI 记忆（跨任务沉淀经验）不同，它只描述当前这一次任务：
 * 决策历史会被压缩（只留最近几轮）、进度队列会滚动丢弃，长线任务跑到后半程容易忘记最初目标，
 * 任务记忆就是那个「不随上下文压缩而丢失」的锚点，每轮决策都完整注入。
 */
@Serializable
data class TaskMemoryEntry(
    val id: Long = 0L,
    /** 任务 ID（引擎按开始时间生成），落库后据此匹配同一次任务 */
    val taskId: Long,
    val taskName: String,
    /** 既定目标：任务原文 */
    val goal: String,
    /** 用户要求：任务描述原文 + 执行中用户通过悬浮窗补充的指导 */
    val requirements: List<String> = emptyList(),
    /** 已验证有效的做法（步骤摘要，新的在后） */
    val methods: List<String> = emptyList(),
    val status: String = STATUS_RUNNING,
    val completedSteps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_ABORTED = "aborted"

        /** 用户要求上限：超出丢弃最旧的，防长线任务把决策上下文撑爆 */
        private const val MAX_REQUIREMENTS = 8

        /** 已验证做法上限：同上，只留最近的成功经验 */
        private const val MAX_METHODS = 10
    }

    /** 界面用中文状态文案 */
    fun statusLabel(): String = when (status) {
        STATUS_SUCCESS -> "已完成"
        STATUS_FAILED -> "未完成"
        STATUS_ABORTED -> "已中断"
        else -> "进行中"
    }

    /**
     * 追加一条用户要求；只有去掉空白标点后完全重复才跳过。
     *
     * 这里刻意不用 [AiMemoryDedupe.isSame] 的模糊去重：要求清单的首条就是任务原文，
     * 用户中途补充的指令往往与原文措辞相近（"帮我在美团点一份黄焖鸡" → "帮我再点一份黄焖鸡"，
     * 相似度恰好 0.5 会被判为重复），但它是**新的一条要求**，静默丢掉就等于没记住用户需求。
     * 条数上限已能防膨胀，宁可留下近似项也不要漏掉指令。
     *
     * 返回新对象而不是就地修改：引擎里是「内存快照 + 异步落库」，就地修改会让落库内容与快照对不上。
     */
    fun withRequirement(text: String): TaskMemoryEntry {
        val t = text.trim()
        if (t.isEmpty() || requirements.any { AiMemoryDedupe.isExact(it, t) }) return this
        return copy(
            requirements = (requirements + t).takeLast(MAX_REQUIREMENTS),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 追加一条已验证有效的做法（模糊去重，字段语义见 [withRequirement]）。
     * 做法清单是「去重后的有效做法」而非逐步流水账，同一类操作换个说法出现应当合并，
     * 故这里保留 [AiMemoryDedupe.isSame]。
     */
    fun withMethod(text: String): TaskMemoryEntry {
        val t = text.trim()
        if (t.isEmpty() || methods.any { AiMemoryDedupe.isSame(it, t) }) return this
        return copy(
            methods = (methods + t).takeLast(MAX_METHODS),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 任务收尾：写回状态与已完成步数 */
    fun withStatus(status: String, steps: Int): TaskMemoryEntry =
        copy(status = status, completedSteps = steps, updatedAt = System.currentTimeMillis())
}

/**
 * 记忆系统：异常经验记忆 + 用户画像，基于 DataStore 持久化。
 * 对应文档“第 10 层 记忆系统”。
 */
class MemoryStore(private val context: Context) {

    private companion object {
        /** 任务记忆总量上限：记忆页是给人看的列表，超出按时间淘汰 */
        const val MAX_TASK_MEMORIES = 30
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val anomaliesKey = stringPreferencesKey("anomaly_memory")
    private val profileKey = stringPreferencesKey("user_profile")
    private val aiMemoryKey = stringPreferencesKey("ai_memory")
    private val taskMemoryKey = stringPreferencesKey("task_memory")

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

    // ---- 任务记忆（任务执行中的工作记忆）----

    suspend fun loadTaskMemories(): List<TaskMemoryEntry> {
        val raw = context.memoryStore.data.first()[taskMemoryKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<TaskMemoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveTaskMemories(list: List<TaskMemoryEntry>) {
        context.memoryStore.edit { it[taskMemoryKey] = json.encodeToString(ListSerializer(TaskMemoryEntry.serializer()), list) }
    }

    /**
     * 写入一条任务记忆：同 [TaskMemoryEntry.taskId] 视为同一次任务，命中则就地替换（保留原 id 与创建时间）。
     * id 由本方法统一分配，调用方无需关心。
     */
    suspend fun upsertTaskMemory(entry: TaskMemoryEntry): TaskMemoryEntry {
        val list = loadTaskMemories().toMutableList()
        val idx = list.indexOfFirst { it.taskId == entry.taskId }
        val result: TaskMemoryEntry
        if (idx >= 0) {
            val existing = list[idx]
            // 乱序保护：引擎是「内存快照 + fire-and-forget 落库」，旧快照可能后到，
            // 直接写入会把已经记录的新进度覆盖回去，故迟到的写入一律丢弃。
            if (entry.updatedAt < existing.updatedAt) return existing
            result = entry.copy(id = existing.id, createdAt = existing.createdAt)
            list[idx] = result
        } else {
            result = entry.copy(id = (list.maxOfOrNull { it.id } ?: 0L) + 1)
            list.add(result)
        }
        saveTaskMemories(trimTaskMemories(list))
        return result
    }

    /** 删除单条任务记忆，返回是否删掉了东西 */
    suspend fun deleteTaskMemory(id: Long): Boolean {
        val list = loadTaskMemories()
        val rest = list.filterNot { it.id == id }
        if (rest.size == list.size) return false
        saveTaskMemories(rest)
        return true
    }

    suspend fun clearTaskMemories() {
        saveTaskMemories(emptyList())
    }

    /**
     * 库容量兜底：优先淘汰已结束的任务，进行中的留到最后 ——
     * 否则正在跑的任务会被自己累积的历史记录挤掉，记忆页上「当前任务」反而消失。
     */
    private fun trimTaskMemories(list: List<TaskMemoryEntry>): List<TaskMemoryEntry> {
        if (list.size <= MAX_TASK_MEMORIES) return list
        val overflow = list.size - MAX_TASK_MEMORIES
        val victims = (
            list.filter { it.status != TaskMemoryEntry.STATUS_RUNNING }.sortedBy { it.updatedAt } +
                list.filter { it.status == TaskMemoryEntry.STATUS_RUNNING }.sortedBy { it.updatedAt }
            ).take(overflow).map { it.taskId }.toSet()
        return list.filterNot { it.taskId in victims }
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