package com.phoneagent.feature.skill

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.skillHistoryStore by preferencesDataStore(name = "skill_call_history")

/** 一次技能调用的留档（成功与失败都记，供参数推断与「上次教训」注入） */
@Serializable
data class SkillCallRecord(
    val id: String,
    val skillId: String,
    val skillName: String,
    /** 调用参数 JSON（推断器从中取同名参数的历史成功值） */
    val argsJson: String = "{}",
    val success: Boolean,
    val elapsedMs: Long = 0,
    /** 失败原因（success=false 时有值） */
    val failReason: String = "",
    val taskId: Long = -1,
    val ts: Long = 0,
)

/**
 * 技能调用历史持久化：DataStore + JSON，环形上限 [MAX_RECORDS] 条按时间戳淘汰。
 *
 * 写入一律 fire-and-forget（[recordAsync]），不阻塞引擎主循环；
 * 纯逻辑（淘汰 / 摘要）收口在 [SkillCallOps]，不依赖 Android 可单测。
 */
class SkillCallHistory(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private val KEY = stringPreferencesKey("skill_call_history_json")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val serializer = ListSerializer(SkillCallRecord.serializer())

        /** 环形上限：按 ts 保留最近 N 条 */
        const val MAX_RECORDS = 200
    }

    /** fire-and-forget 写入一条调用记录（失败静默，不打扰主流程） */
    fun recordAsync(record: SkillCallRecord) {
        scope.launch {
            runCatching {
                val current = load()
                save(SkillCallOps.append(current, record, MAX_RECORDS))
            }
        }
    }

    /** 本技能最近 n 条记录（新在前） */
    suspend fun recentFor(skillId: String, n: Int = 3): List<SkillCallRecord> =
        SkillCallOps.recentFor(load(), skillId, n)

    /** 全部记录（新在前） */
    suspend fun all(): List<SkillCallRecord> = SkillCallOps.byRecency(load())

    private suspend fun load(): List<SkillCallRecord> {
        val raw = context.skillHistoryStore.data.first()[KEY] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    private suspend fun save(records: List<SkillCallRecord>) {
        val raw = json.encodeToString(serializer, records)
        context.skillHistoryStore.edit { prefs -> prefs[KEY] = raw }
    }
}

/** 调用历史纯逻辑：追加淘汰 / 查询 / 摘要（无 Android 依赖，可单测） */
object SkillCallOps {

    /** 追加一条并按 ts 淘汰到上限（新记录排最前） */
    fun append(current: List<SkillCallRecord>, record: SkillCallRecord, maxRecords: Int): List<SkillCallRecord> =
        (listOf(record) + current).sortedByDescending { it.ts }.take(maxRecords)

    /** 按时间戳新在前排序 */
    fun byRecency(records: List<SkillCallRecord>): List<SkillCallRecord> = records.sortedByDescending { it.ts }

    /** 本技能最近 n 条（新在前） */
    fun recentFor(records: List<SkillCallRecord>, skillId: String, n: Int): List<SkillCallRecord> =
        byRecency(records).filter { it.skillId == skillId }.take(n)

    /** 全局最近 n 条失败记录（供提示词注入「上次教训」；新在前） */
    fun recentFailures(records: List<SkillCallRecord>, n: Int): List<SkillCallRecord> =
        byRecency(records).filter { !it.success }.take(n)

    /**
     * 人话摘要：每条一行 ≤200 字，形如
     * `上次成功 · 耗时 320ms · 参数 path=/a/b` ／ `上次失败 · 服务器连接超时`
     */
    fun summarize(records: List<SkillCallRecord>): String =
        records.joinToString("\n") { r ->
            buildString {
                if (r.success) {
                    append("上次成功")
                    if (r.elapsedMs > 0) append(" · 耗时 ${r.elapsedMs}ms")
                    val args = r.argsJson.takeIf { it.isNotBlank() && it != "{}" }
                    if (args != null) append(" · 参数 ${args.take(120)}")
                } else {
                    append("上次失败")
                    if (r.failReason.isNotBlank()) append(" · ${r.failReason.take(160)}")
                }
            }.take(200)
        }
}
