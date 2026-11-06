// 本文件里的数字多为超时阈值、评分权重与检索窗口，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.engine.docs

import android.content.Context
import android.util.Log
import com.phoneagent.data.prefs.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/**
 * 训练线任务文档仓库（离线优先）。
 *
 * 数据流与云端执行文档（[com.phoneagent.feature.agentdoc.AgentDocRepository]）不同：
 * 任务文档只有几十份、整包约 200 KB，因此**整包落本地、检索全部在本地做**，
 * 网络只用于「首次拉整包」和「按服务端水位增量同步」。好处是检索零网络延迟，
 * 离线（飞行模式、服务端挂了）行为与在线完全一致。
 *
 * 同步水位以服务端返回的 `next_since` 为准，绝不用手机本地时钟——手机时间不准时
 * 用本地时间当水位会漏文档或反复重拉。
 *
 * 失败一律静默：调用方拿不到文档就走通用探索，不让同步问题阻断任务。
 */
class TaskDocRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 首次整包：给一个较短的上限，避免首任务被网络卡住（检索入口外层还有 4s 超时） */
    private val bundleHttp = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 增量同步在后台跑，可以给宽一点的上限 */
    private val syncHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dir = File(context.filesDir, "task-docs")
    private val storeFile = File(dir, "bundle.json")

    /** 磁盘读写串行化：规划、每步决策、后台同步可能并发进来 */
    private val mutex = Mutex()

    /** 后台增量同步用（不占决策延迟） */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var memory: TaskDocStore? = null

    @Volatile
    private var lastAttemptAt = 0L

    /** 最近一次网络失败：一分钟内不再尝试，避免每步决策都撞一次超时 */
    @Volatile
    private var lastFailureAt = 0L

    @Volatile
    private var lastSuccessAt = 0L

    /**
     * 保证本地包尽量新。
     *
     * - 本地已有包：立即返回，过期时把增量同步丢到后台（检索先用本地，不受网络影响）。
     * - 本地没有包：仅当 [blockForFirstFetch] 时同步拉一次整包；失败按"没有文档"处理。
     */
    suspend fun ensureReady(s: AppSettings.Settings, blockForFirstFetch: Boolean) {
        if (s.docBaseUrl.isBlank()) return
        val now = System.currentTimeMillis()
        val store = memory ?: readStore()
        val ttlMs = s.docTtlMinutes.coerceIn(5, 24 * 60).toLong() * 60_000L
        if (store.docs.isNotEmpty()) {
            if (now - lastSuccessAt >= ttlMs) syncScope.launch { refresh(s, force = false) }
            return
        }
        if (blockForFirstFetch) refresh(s, force = true)
    }

    /**
     * 本地检索：中文二元组 + IDF + 短语/别名/App 加成（与训练线 `taskdoc.search` 同一套规则）。
     *
     * @param foregroundPackage 执行期前台包名；能识别出 App 时给对应文档加分（不做硬过滤，
     *   否则任务中途跳到别的应用就一条都查不到了）
     */
    suspend fun search(task: String, foregroundPackage: String?, limit: Int = 3): List<MethodDoc> {
        if (task.isBlank()) return emptyList()
        val docs = readStore().docs
        if (docs.isEmpty() || limit <= 0) return emptyList()
        return withContext(Dispatchers.Default) { rank(docs, task, appKeyOf(foregroundPackage), limit) }
    }

    // ==================== 同步 ====================

    private suspend fun refresh(s: AppSettings.Settings, force: Boolean): Boolean {
        if (s.docBaseUrl.isBlank()) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < ATTEMPT_INTERVAL_MS) return false
        if (now - lastFailureAt < FAILURE_COOLDOWN_MS) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val started = System.currentTimeMillis()
                if (!force && started - lastAttemptAt < ATTEMPT_INTERVAL_MS) return@withLock false
                lastAttemptAt = started
                val current = memory ?: readStore()
                val base = s.docBaseUrl.trim().trimEnd('/')
                val fetched = runCatching {
                    if (current.docs.isEmpty() || current.nextSince <= 0.0) {
                        fetchBundle(base)
                    } else {
                        fetchSync(base, current.nextSince, current)
                    }
                }.getOrNull()
                if (fetched == null) {
                    lastFailureAt = System.currentTimeMillis()
                    Log.w(TAG, "任务文档同步失败，继续使用本地包（${current.docs.size} 份）")
                    return@withLock false
                }
                writeStore(fetched)
                lastSuccessAt = System.currentTimeMillis()
                true
            }
        }
    }

    private fun fetchBundle(base: String): TaskDocStore? {
        val raw = httpGet("$base/api/taskdocs/bundle", bundleHttp) ?: return null
        val resp = runCatching { json.decodeFromString(BundleResponse.serializer(), raw) }.getOrNull() ?: return null
        val docs = resp.docs.mapNotNull { decodeDoc(it) }
        if (docs.isEmpty()) return null
        // 用服务端时间当水位：手机时钟不准时也不会漏文档
        val waterline = resp.serverTime.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000.0
        Log.i(TAG, "任务文档整包同步：${docs.size} 份")
        return TaskDocStore(nextSince = waterline, docs = dedupe(docs))
    }

    private fun fetchSync(base: String, since: Double, current: TaskDocStore): TaskDocStore? {
        val raw = httpGet("$base/api/taskdocs/sync?since=$since", syncHttp) ?: return null
        val resp = runCatching { json.decodeFromString(SyncResponse.serializer(), raw) }.getOrNull() ?: return null
        val incoming = resp.docs.mapNotNull { decodeDoc(it) }
        if (incoming.isEmpty() && resp.nextSince <= 0) return null
        val merged = LinkedHashMap<String, TaskDocV2>()
        current.docs.forEach { merged[it.taskId.ifBlank { it.taskName }] = it }
        resp.deleted.forEach { merged.remove(it) }
        incoming.forEach { merged[it.taskId.ifBlank { it.taskName }] = it }
        val waterline = resp.nextSince.takeIf { it > 0 } ?: resp.serverTime.takeIf { it > 0 } ?: since
        Log.i(TAG, "任务文档增量同步：新增/更新 ${incoming.size} 份，本地共 ${merged.size} 份")
        return TaskDocStore(nextSince = waterline, docs = merged.values.toList())
    }

    private fun httpGet(url: String, client: OkHttpClient): String? = runCatching {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private fun decodeDoc(el: JsonElement): TaskDocV2? =
        runCatching { json.decodeFromJsonElement<TaskDocV2>(el) }.getOrNull()
            ?.takeIf { it.taskId.isNotBlank() || it.taskName.isNotBlank() }

    private fun dedupe(docs: List<TaskDocV2>): List<TaskDocV2> = docs
        .associateBy { it.taskId.ifBlank { it.taskName } }
        .values
        .toList()

    // ==================== 磁盘 ====================

    private fun readStore(): TaskDocStore {
        memory?.let { return it }
        val loaded = runCatching {
            if (!storeFile.exists()) null else json.decodeFromString(TaskDocStore.serializer(), storeFile.readText())
        }.getOrNull() ?: TaskDocStore()
        memory = loaded
        if (loaded.docs.isNotEmpty() && lastSuccessAt == 0L) lastSuccessAt = System.currentTimeMillis()
        return loaded
    }

    private fun writeStore(store: TaskDocStore) {
        memory = store
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            storeFile.writeText(json.encodeToString(TaskDocStore.serializer(), store))
        }.onFailure { Log.w(TAG, "任务文档落盘失败：${it.message}") }
    }

    // ==================== 本地检索 ====================

    private fun rank(
        docs: List<TaskDocV2>,
        query: String,
        appKey: String?,
        limit: Int,
    ): List<MethodDoc> {
        val qTokens = tokens(query).toSet()
        if (qTokens.isEmpty()) return emptyList()
        // IDF 用本地全集现算，与在线检索口径一致
        val docTokens = docs.map { tokens(docText(it)).toSet() }
        val df = HashMap<String, Int>()
        docTokens.forEach { set -> qTokens.forEach { t -> if (t in set) df.merge(t, 1, Int::plus) } }
        val n = docs.size
        val idf = qTokens.associateWith { t -> ln((n + 1.0) / ((df[t] ?: 0) + 1.0)) + 1.0 }
        val qPhrase = normalizePhrase(query)

        val hits = docs.mapIndexedNotNull { i, doc ->
            val set = docTokens[i]
            var score = 0.0
            var matched = 0
            qTokens.forEach { t ->
                if (t in set) {
                    score += idf.getValue(t)
                    matched++
                }
            }
            if (matched == 0) return@mapIndexedNotNull null
            // 完整短语命中（去掉标点后整句包含在任务名里）
            if (qPhrase.length >= 4 && normalizePhrase(doc.taskName).contains(qPhrase)) score += 6.0
            // 别名加成：纯 App 名（如「支付宝」）不加，避免同 App 文档雨露均沾
            if (doc.aliases.any { a ->
                    val an = normalizePhrase(a)
                    an.length >= 3 && an !in APP_ONLY_WORDS && qPhrase.contains(an)
                }
            ) {
                score += 3.0
            }
            if (appKey != null && appKey.equals(doc.app, ignoreCase = true)) score += 2.0
            score += when (doc.evidenceStatus) {
                "proven" -> 0.6
                "unproven" -> -0.2
                else -> 0.0
            }
            if (score < MIN_SCORE) null else Hit(doc, score)
        }
        return hits
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { updatedScore(it.doc.updatedAt) })
            .take(limit)
            .map { it.doc.toMethodDoc() }
    }

    /** 文档侧参与检索的文本：任务名 + 别名 + 摘要 + task_id（点分转空格）+ app */
    private fun docText(doc: TaskDocV2): String = buildString {
        append(doc.taskName).append(' ')
        if (doc.catalogTaskName.isNotBlank()) append(doc.catalogTaskName).append(' ')
        if (doc.aliases.isNotEmpty()) append(doc.aliases.joinToString(" ")).append(' ')
        append(doc.summary).append(' ')
        append(doc.taskId.replace('.', ' ')).append(' ')
        append(doc.app)
    }

    /** 归一化：去标点、转小写；中文切 CJK 二元组，ASCII 按词切（与在线检索口径一致） */
    private fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val cjkRun = StringBuilder()
        val word = StringBuilder()

        fun flushCjk() {
            val run = cjkRun.toString()
            when (run.length) {
                0 -> Unit
                1 -> out.add(run)
                else -> for (i in 0 until run.length - 1) out.add(run.substring(i, i + 2))
            }
            cjkRun.clear()
        }

        fun flushWord() {
            if (word.isNotEmpty()) {
                out.add(word.toString().lowercase())
                word.clear()
            }
        }

        text.forEach { ch ->
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    flushWord()
                    cjkRun.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    word.append(ch)
                }
                else -> {
                    flushCjk()
                    flushWord()
                }
            }
        }
        flushCjk()
        flushWord()
        return out.filter { it.isNotBlank() }
    }

    /** 去标点、转小写，保留 CJK 与字母数字，用于整句/别名包含判断 */
    private fun normalizePhrase(text: String): String = buildString {
        text.forEach { ch ->
            when {
                ch.code in 0x4E00..0x9FFF -> append(ch)
                ch.isLetterOrDigit() -> append(ch.lowercaseChar())
            }
        }
    }

    /** 前台包名 → 文档里的归一化 App 名；认不出就返回 null（只影响加分，不影响检索） */
    private fun appKeyOf(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return PACKAGE_APP_KEYS[pkg]
    }

    private fun TaskDocV2.toMethodDoc(): MethodDoc = MethodDoc(
        taskId = taskId,
        summary = summary.ifBlank { taskName },
        app = app,
        evidenceStatus = evidenceStatus,
        preconditions = preconditions,
        successChecks = goal?.successCriteria.orEmpty(),
        notDoneWhen = goal?.notDoneWhen.orEmpty(),
        decisionPoints = decisionPoints.map {
            MethodDoc.DecisionPoint(trigger = it.trigger, thinking = it.thinking, options = it.options)
        },
        pitfalls = pitfalls.map { p ->
            when {
                p.avoid.isNotBlank() && p.phenomenon.isNotBlank() -> "${p.phenomenon} → ${p.avoid}"
                p.avoid.isNotBlank() -> p.avoid
                p.phenomenon.isNotBlank() -> p.phenomenon
                else -> p.cause
            }
        }.filter { it.isNotBlank() },
        wrapUpChecks = verification,
        branches = branches.map {
            MethodDoc.Branch(name = it.name, whenToUse = it.whenToUse, approach = it.approach)
        },
        actions = referenceSteps.mapNotNull { referenceActionText(it) }.take(8),
    )

    /** `updated_at` 字符串/数字都见过，统一折算成可比较的数值；解析不了当 0（不参与优先排序） */
    private fun updatedScore(el: JsonElement?): Double {
        val raw = (el as? JsonPrimitive)?.contentOrNull ?: return 0.0
        return raw.toDoubleOrNull() ?: 0.0
    }

    private fun referenceActionText(el: JsonElement): String? {
        when (el) {
            is JsonPrimitive -> return el.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> {
                val action = (el["action"] as? JsonPrimitive)?.contentOrNull
                val targetEl = el["target"] ?: el["text"] ?: el["desc"] ?: el["name"]
                val target = when (targetEl) {
                    is JsonPrimitive -> targetEl.contentOrNull
                    else -> targetEl?.toString()
                }
                return listOfNotNull(
                    action?.take(24),
                    target?.take(40),
                ).joinToString(" ").ifBlank { null }
            }
            else -> return null
        }
    }

    private companion object {
        const val TAG = "TaskDocRepo"

        /** 两次同步尝试的最小间隔：每步决策都会调检索，不能每步都发请求 */
        const val ATTEMPT_INTERVAL_MS = 5 * 60_000L

        /** 网络失败后的冷却：这期间直接按"没有文档"处理，不拖慢任务 */
        const val FAILURE_COOLDOWN_MS = 60_000L

        /** 低于这个分数视为"没查到你问的这件事"，宁可返回空也不拿噪声文档骗模型 */
        const val MIN_SCORE = 1.0

        /** 纯 App 名（别名命中它们不算"说法匹配"） */
        val APP_ONLY_WORDS = setOf(
            "支付宝", "微信", "微博", "抖音", "快手", "淘宝", "京东", "拼多多", "美团", "饿了么",
            "高德", "地图", "百度", "b站", "bilibili", "备忘录", "备忘录app", "qq", "小红书",
        )

        /** 常见包名 → 训练线归一化 App 名（只用于加分；认不出不影响检索） */
        val PACKAGE_APP_KEYS = mapOf(
            "com.eg.android.AlipayGphone" to "alipay",
            "com.tencent.mm" to "wechat",
            "com.ss.android.ugc.aweme" to "douyin",
            "com.smile.gifmaker" to "kuaishou",
            "tv.danmaku.bili" to "bilibili",
            "com.sina.weibo" to "weibo",
            "com.xingin.xhs" to "xiaohongshu",
            "com.taobao.taobao" to "taobao",
            "com.jingdong.app.mall" to "jd",
            "com.xunmeng.pinduoduo" to "pinduoduo",
            "com.sankuai.meituan" to "meituan",
            "me.ele" to "eleme",
            "com.autonavi.minimap" to "amap",
            "com.baidu.BaiduMap" to "baidumap",
            "com.baidu.searchbox" to "baidu",
            "com.netease.cloudmusic" to "netease_cloudmusic",
            "com.tencent.qqlive" to "tencent_video",
            "com.qiyi.video" to "iqiyi",
            "com.youku.phone" to "youku",
            "com.alibaba.android.rimet" to "dingtalk",
            "com.tencent.wework" to "wework",
            "com.android.notes" to "notes",
            "com.miui.notes" to "notes",
            "com.coloros.note" to "notes",
            "com.huawei.notepad" to "notes",
        )
    }
}

// ==================== 服务端 JSON 结构（v2） ====================

/** 检索命中：文档 + 得分（只在本地排序时用） */
private data class Hit(val doc: TaskDocV2, val score: Double)

@Serializable
internal data class TaskDocStore(
    val nextSince: Double = 0.0,
    val docs: List<TaskDocV2> = emptyList(),
)

@Serializable
internal data class BundleResponse(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    @SerialName("server_time") val serverTime: Double = 0.0,
    val count: Int = 0,
    val docs: List<JsonElement> = emptyList(),
)

@Serializable
internal data class SyncResponse(
    val since: Double = 0.0,
    @SerialName("server_time") val serverTime: Double = 0.0,
    @SerialName("next_since") val nextSince: Double = 0.0,
    val count: Int = 0,
    val deleted: List<String> = emptyList(),
    val docs: List<JsonElement> = emptyList(),
)

@Serializable
internal data class TaskDocV2(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    @SerialName("task_id") val taskId: String = "",
    @SerialName("task_name") val taskName: String = "",
    @SerialName("catalog_task_name") val catalogTaskName: String = "",
    val app: String = "",
    val suite: String = "",
    val difficulty: String = "",
    val aliases: List<String> = emptyList(),
    val summary: String = "",
    @SerialName("evidence_status") val evidenceStatus: String = "",
    val goal: GoalDto? = null,
    val preconditions: List<String> = emptyList(),
    val entry: EntryDto? = null,
    @SerialName("decision_points") val decisionPoints: List<DecisionPointDto> = emptyList(),
    val pitfalls: List<PitfallDto> = emptyList(),
    val verification: List<String> = emptyList(),
    val branches: List<BranchDto> = emptyList(),
    /** 形状不稳定（字符串或对象数组都见过），按元素容错解析 */
    @SerialName("reference_steps") val referenceSteps: List<JsonElement> = emptyList(),
    @SerialName("updated_at") val updatedAt: JsonElement? = null,
) {
    @Serializable
    data class GoalDto(
        val description: String = "",
        @SerialName("success_criteria") val successCriteria: List<String> = emptyList(),
        @SerialName("not_done_when") val notDoneWhen: List<String> = emptyList(),
    )

    @Serializable
    data class EntryDto(
        val app: String = "",
        @SerialName("start_path") val startPath: String = "",
        @SerialName("start_hint") val startHint: String = "",
    )

    @Serializable
    data class DecisionPointDto(
        val trigger: String = "",
        val thinking: String = "",
        val options: List<String> = emptyList(),
    )

    @Serializable
    data class PitfallDto(
        val phenomenon: String = "",
        val cause: String = "",
        val avoid: String = "",
    )

    @Serializable
    data class BranchDto(
        val name: String = "",
        @SerialName("when") val whenToUse: String = "",
        val approach: String = "",
    )
}
