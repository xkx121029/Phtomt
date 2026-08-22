package com.phoneagent.task

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoneagent.model.TaskPlan
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 长线任务基础设施（v2.2 5.3 检查点 + 6 任务模板库）：
 * - 检查点：任务进度持久化，支持中断后查询/续传
 * - 模板库：任务目标 → 已生成脚本（TaskPlan）的复用，命中可零云端调用
 *
 * 采用 DataStore（项目约定：不用 Room，避免构建风险）。模板用 JSON 整体序列化。
 */
private val Context.taskStore by androidx.datastore.preferences.preferencesDataStore(name = "agent_tasks")

/** 近期任务检查点 */
data class Checkpoint(
    val task: String,
    val planJson: String?,          // TaskPlan 的 JSON，null = 无计划
    val completedSteps: Int,
    val totalPlannedSteps: Int,
    val currentTaskId: Long,
    val updatedAt: Long,
)

/** 任务模板：goal=用户目标，plan=AI 生成的执行脚本 */
@Serializable
data class TaskTemplate(
    val id: String,
    val goal: String,
    val plan: TaskPlan,
    val executionCount: Int = 0,
    val successCount: Int = 0,
    val failedStreak: Int = 0,
    val enabled: Boolean = true,
)

/** 执行策略（v2.2 7）：script=按脚本自主推进 | react=每步云端决策 | auto=智能选择 */
enum class ExecutionStrategy { AUTO, SCRIPT, REACT }

object TaskStore {

    private val KEY_CHECKPOINT_TASK = stringPreferencesKey("ck_task")
    private val KEY_CHECKPOINT_PLAN = stringPreferencesKey("ck_plan")
    private val KEY_CHECKPOINT_DONE = intPreferencesKey("ck_done")
    private val KEY_CHECKPOINT_TOTAL = intPreferencesKey("ck_total")
    private val KEY_CHECKPOINT_ID = longPreferencesKey("ck_id")
    private val KEY_CHECKPOINT_TS = longPreferencesKey("ck_ts")
    private val KEY_TEMPLATES = stringPreferencesKey("templates")
    private val KEY_STRATEGY = stringPreferencesKey("strategy")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ==================== 检查点 ====================

    suspend fun saveCheckpoint(
        context: Context,
        task: String,
        plan: TaskPlan?,
        completedSteps: Int,
        totalPlannedSteps: Int,
        currentTaskId: Long,
    ) {
        context.taskStore.edit { p ->
            p[KEY_CHECKPOINT_TASK] = task
            val planJson = plan?.let { json.encodeToString(TaskPlan.serializer(), it) }
            if (planJson != null) p[KEY_CHECKPOINT_PLAN] = planJson else p.remove(KEY_CHECKPOINT_PLAN)
            p[KEY_CHECKPOINT_DONE] = completedSteps
            p[KEY_CHECKPOINT_TOTAL] = totalPlannedSteps
            p[KEY_CHECKPOINT_ID] = currentTaskId
            p[KEY_CHECKPOINT_TS] = System.currentTimeMillis()
        }
    }

    suspend fun loadCheckpoint(context: Context): Checkpoint? {
        val p = context.taskStore.data.first()
        val task = p[KEY_CHECKPOINT_TASK] ?: return null
        val planJson = p[KEY_CHECKPOINT_PLAN]
        val plan = planJson?.let { runCatching { json.decodeFromString(TaskPlan.serializer(), it) }.getOrNull() }
        return Checkpoint(
            task = task,
            planJson = planJson,
            completedSteps = p[KEY_CHECKPOINT_DONE] ?: 0,
            totalPlannedSteps = p[KEY_CHECKPOINT_TOTAL] ?: 0,
            currentTaskId = p[KEY_CHECKPOINT_ID] ?: -1,
            updatedAt = p[KEY_CHECKPOINT_TS] ?: 0,
        )
    }

    suspend fun clearCheckpoint(context: Context) {
        context.taskStore.edit { p ->
            p.remove(KEY_CHECKPOINT_TASK)
            p.remove(KEY_CHECKPOINT_PLAN)
            p.remove(KEY_CHECKPOINT_DONE)
            p.remove(KEY_CHECKPOINT_TOTAL)
            p.remove(KEY_CHECKPOINT_ID)
            p.remove(KEY_CHECKPOINT_TS)
        }
    }

    // ==================== 模板库 ====================

    private val templateListSerializer = ListSerializer(TaskTemplate.serializer())

    /** 记录一个模板（同一 goal 合并更新） */
    suspend fun upsertTemplate(context: Context, template: TaskTemplate) {
        val list = loadTemplates(context).toMutableList()
        val existing = list.indexOfFirst { it.id == template.id || it.goal == template.goal }
        if (existing >= 0) list[existing] = template.copy(id = template.id)
        else list.add(template)
        context.taskStore.edit { it[KEY_TEMPLATES] = json.encodeToString(templateListSerializer, list) }
    }

    suspend fun loadTemplates(context: Context): List<TaskTemplate> {
        val raw = context.taskStore.data.first()[KEY_TEMPLATES] ?: return emptyList()
        return runCatching {
            json.decodeFromString(templateListSerializer, raw)
        }.getOrElse { emptyList() }
    }

    /**
     * 模板匹配：按目标相似度（Jaccard，中文双字切分）找到可用模板。
     * 要求：相似度 > [MIN_SIM]、enabled 且 failedStreak < 3（健康）。
     */
    suspend fun matchTemplate(context: Context, goal: String): TaskTemplate? {
        if (goal.isBlank()) return null
        val g = biGrams(goal)
        return loadTemplates(context)
            .filter { it.enabled && it.failedStreak < 3 }
            .map { it to jaccard(g, biGrams(it.goal)) }
            .filter { it.second >= MIN_SIM }
            .maxByOrNull { it.second }
            ?.first
    }

    /** 删除指定模板 */
    suspend fun deleteTemplate(context: Context, id: String) {
        val list = loadTemplates(context).filterNot { it.id == id }
        context.taskStore.edit { it[KEY_TEMPLATES] = json.encodeToString(templateListSerializer, list) }
    }

    /** 匹配命中后：increment 执行计数 */
    suspend fun bumpExecution(context: Context, id: String) {
        val list = loadTemplates(context)
        val t = list.firstOrNull { it.id == id } ?: return
        upsertTemplate(context, t.copy(executionCount = t.executionCount + 1))
    }

    /** 健康状态更新：成功 → failedStreak 清零；失败 → +1 */
    suspend fun updateTemplateHealth(context: Context, id: String, success: Boolean) {
        val list = loadTemplates(context)
        val t = list.firstOrNull { it.id == id } ?: return
        upsertTemplate(
            context,
            t.copy(
                successCount = if (success) t.successCount + 1 else t.successCount,
                failedStreak = if (success) 0 else t.failedStreak + 1,
            ),
        )
    }

    // ==================== 执行策略（v2.2 7） ====================

    suspend fun setStrategy(context: Context, s: ExecutionStrategy) {
        context.taskStore.edit { it[KEY_STRATEGY] = s.name }
    }

    suspend fun getStrategy(context: Context): ExecutionStrategy =
        runCatching {
            ExecutionStrategy.valueOf(
                context.taskStore.data.first()[KEY_STRATEGY] ?: ExecutionStrategy.AUTO.name,
            )
        }.getOrDefault(ExecutionStrategy.AUTO)

    // ==================== 相似度 ====================

    private const val MIN_SIM = 0.5

    private fun biGrams(s: String): Set<String> {
        val chars = s.filter { it.isLetterOrDigit() }
        if (chars.length < 2) return setOf(chars)
        return (0 until chars.length - 1).map { chars.substring(it, it + 2) }.toSet()
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }
}