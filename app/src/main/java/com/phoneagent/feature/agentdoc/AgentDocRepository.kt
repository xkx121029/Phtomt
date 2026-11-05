// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.feature.agentdoc

import android.content.Context
import android.util.Log
import com.phoneagent.data.prefs.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 云端执行文档仓库（客户端）。
 *
 * 数据流：`index.json`（元信息）+ 按需 `doc/<id>.md`（正文），磁盘缓存在 App 私有目录，
 * 带 ETag 与 TTL：内容没变就不会重复下载，离线时继续用上次的缓存。
 *
 * 调用点不需要关心同步状态：规划与每步决策各调一次 [docsFor]，
 * 由这里按 TTL 自动刷新索引、只拉命中的正文，并把同步状态写回设置页展示。
 */
class AgentDocRepository(
    private val context: Context,
    private val settings: AppSettings,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dir = File(context.filesDir, "agent-docs")
    private val indexFile = File(dir, "index.json")
    private val indexEtagFile = File(dir, "index.etag")
    private val bodyDir = File(dir, "docs")

    /** 索引与正文的读写都串行化：规划与执行可能并发调用，避免半写文件被读到 */
    private val mutex = Mutex()

    @Volatile private var memoryIndex: DocIndex? = null
    @Volatile private var memoryIndexAt = 0L

    /**
     * 最近一次网络失败的时间。
     *
     * 每步决策都会调 [docsFor]，若设备离线或服务端挂了，没有这个冷却就会**每一步都卡一次超时**；
     * 失败后一分钟内直接按"无文档"处理，用户侧只表现为"这篇手册暂时没注入"，不会拖慢任务。
     */
    @Volatile private var lastFailureAt = 0L

    /**
     * 取本次任务应注入的文档。
     *
     * @param foregroundPackage 执行阶段的前台应用包名（规划阶段传 null，只按任务文本匹配）
     */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    suspend fun docsFor(
        task: String,
        foregroundPackage: String?,
        s: AppSettings.Settings,
    ): List<AgentDoc> {
        if (!s.docSyncEnabled || task.isBlank() || s.docBaseUrl.isBlank()) return emptyList()
        if (System.currentTimeMillis() - lastFailureAt < FAILURE_COOLDOWN_MS) return emptyList()
        return withContext(Dispatchers.IO) {
            val ttlMs = s.docTtlMinutes.coerceIn(5, 24 * 60).toLong() * 60_000L
            val index = indexOrRefresh(s, ttlMs) ?: return@withContext emptyList()
            val hits = DocMatcher.select(index.docs, task, foregroundPackage)
            if (hits.isEmpty()) return@withContext emptyList()
            val loaded = hits.mapNotNull { hit -> bodyOf(hit.meta, s, ttlMs) }
            budget(loaded, s.docMaxChars)
        }
    }

    /** 设置页「立即同步」：跳过 TTL 强制拉一次索引（用页面当前的编辑态，避免与异步落库抢跑） */
    suspend fun refreshNow(s: AppSettings.Settings): Boolean = withContext(Dispatchers.IO) {
        if (!s.docSyncEnabled) return@withContext false
        val cached = readDiskIndex()
        val fresh = fetchIndex(s, cached)
        if (fresh != null) {
            memoryIndex = fresh
            memoryIndexAt = System.currentTimeMillis()
        }
        fresh != null
    }

    // ==================== 索引 ====================

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun indexOrRefresh(s: AppSettings.Settings, ttlMs: Long): DocIndex? {
        val mem = memoryIndex
        if (mem != null && System.currentTimeMillis() - memoryIndexAt < ttlMs) return mem

        val disk = readDiskIndex()
        if (disk != null && System.currentTimeMillis() - indexFile.lastModified() < ttlMs) {
            memoryIndex = disk
            memoryIndexAt = System.currentTimeMillis()
            return disk
        }
        val fresh = fetchIndex(s, disk)
        if (fresh != null) {
            memoryIndex = fresh
            memoryIndexAt = System.currentTimeMillis()
            return fresh
        }
        // 网络失败：过期索引也好过没有——离线时仍按上次同步的文档执行
        return disk
    }

    private fun readDiskIndex(): DocIndex? = runCatching {
        if (!indexFile.isFile) null else json.decodeFromString<DocIndex>(indexFile.readText())
    }.getOrNull()

    private suspend fun fetchIndex(s: AppSettings.Settings, cached: DocIndex?): DocIndex? = mutex.withLock {
        val url = "${s.docBaseUrl.trim().trimEnd('/')}/api/agent-docs/index.json"
        val etag = indexEtagFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        if (cached != null && etag.isNotBlank()) builder.header("If-None-Match", "\"$etag\"")
        runCatching {
            http.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.code == 304 && cached != null -> {
                        // 未变更：把磁盘 mtime 刷新，TTL 重新计时
                        indexFile.setLastModified(System.currentTimeMillis())
                        lastFailureAt = 0L
                        cached
                    }
                    resp.isSuccessful -> {
                        val text = resp.body?.string().orEmpty()
                        if (text.isBlank()) throw IOException("索引为空")
                        val parsed = json.decodeFromString<DocIndex>(text)
                        writeAtomic(indexFile, text)
                        normalizeEtag(resp.header("ETag"))?.let { writeAtomic(indexEtagFile, it) }
                        lastFailureAt = 0L
                        settings.updateDocStatus(System.currentTimeMillis(), parsed.docs.size, "")
                        parsed
                    }
                    else -> throw IOException("HTTP ${resp.code}")
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "执行文档索引同步失败：${err.message}")
            lastFailureAt = System.currentTimeMillis()
            runCatching {
                settings.updateDocStatus(0L, cached?.docs?.size ?: 0, err.message ?: "同步失败")
            }
        }.getOrNull()
    }

    // ==================== 正文 ====================

    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod")
    private suspend fun bodyOf(meta: DocMeta, s: AppSettings.Settings, ttlMs: Long): Pair<DocMeta, String>? =
        mutex.withLock {
            // 文件名只允许白名单字符，杜绝索引里塞 ../ 造成越界写入
            val id = meta.id
            if (id.isBlank() || !ID_RE.matches(id)) return@withLock null
            val file = File(bodyDir, "$id.md")
            val etagFile = File(bodyDir, "$id.etag")
            val disk = file.takeIf { it.isFile }?.readText()
            val diskEtag = etagFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()

            // 正文与索引里的 etag 一致 → 内容没变，直接用
            if (disk != null && diskEtag.isNotBlank() && diskEtag == meta.etag) return@withLock meta to disk
            // TTL 内也直接用，避免每步决策都打一次网
            if (disk != null && System.currentTimeMillis() - file.lastModified() < ttlMs) return@withLock meta to disk

            val url = "${s.docBaseUrl.trim().trimEnd('/')}/api/agent-docs/doc/$id.md"
            val builder = Request.Builder().url(url).get().header("Accept", "text/markdown")
            if (disk != null && diskEtag.isNotBlank()) builder.header("If-None-Match", "\"$diskEtag\"")
            val fresh = runCatching {
                http.newCall(builder.build()).execute().use { resp ->
                    when {
                        resp.code == 304 && disk != null -> {
                            file.setLastModified(System.currentTimeMillis())
                            disk
                        }
                        resp.isSuccessful -> {
                            val text = resp.body?.string().orEmpty()
                            if (text.isBlank()) throw IOException("文档为空")
                            writeAtomic(file, text)
                            normalizeEtag(resp.header("ETag"))?.let { writeAtomic(etagFile, it) }
                            text
                        }
                        else -> throw IOException("HTTP ${resp.code}")
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "执行文档 ${meta.id} 拉取失败：${err.message}")
                lastFailureAt = System.currentTimeMillis()
            }.getOrNull()

            (fresh ?: disk)?.let { meta to it }
        }

    // ==================== 预算裁剪 ====================

    /**
     * 注入预算：按匹配顺序累计，超出 [maxChars] 的文档截断正文并标注。
     * 预算存在的意义是防止一篇超长手册把每步决策的上下文挤爆。
     */
    private fun budget(pairs: List<Pair<DocMeta, String>>, maxChars: Int): List<AgentDoc> {
        if (pairs.isEmpty()) return emptyList()
        var left = maxChars.coerceIn(200, 4000)
        val out = mutableListOf<AgentDoc>()
        for ((meta, raw) in pairs) {
            if (left <= 80) break
            val clipped = if (raw.length <= left) {
                raw
            } else {
                raw.take(left - 24).trimEnd() + "\n\n…（本条文档超出本次注入预算，已截断）"
            }
            out += AgentDoc(meta.id, meta.title, clipped)
            left -= clipped.length + meta.title.length + 8
        }
        return out
    }

    /** ETag 统一成裸值存盘（去弱校验前缀与引号），这样能和索引里的 etag 直接比对 */
    private fun normalizeEtag(raw: String?): String? = raw
        ?.trim()
        ?.removePrefix("W/")
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // 某些设备上跨目录 rename 会失败：退化为直接覆盖，缓存丢一次不致命
            file.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "AgentDocRepo"
        /** 网络失败后的冷却窗口：期间不重试，避免每步决策都卡一次超时 */
        const val FAILURE_COOLDOWN_MS = 60_000L
        val ID_RE = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
