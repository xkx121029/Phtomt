package com.phoneagent.coding.agent

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 编码工作区与会话存储。
 *
 * 目录约定（用户可见的"一个工作区就是一个项目目录"）：
 * ```
 * <工作区根>            ← agent 实际动手的位置（私有目录或用户选择的设备目录）
 *   └── .happyac/       ← HPA 自己的工作区元数据
 *         sessions.json        会话索引（活动会话 + 标题/时间/条数）
 *         sessions/<id>.json   每个会话的完整消息
 *         agent/               agent 进程的 state 目录（会话上下文、记忆、用量账本）
 * ```
 * 会话与 agent 记忆都在工作区内，因此"切工作区 = 切一套记忆"，互不串线。
 */
object CodingWorkspaces {

    const val META_DIR = ".happyac"
    private const val DIR_DEFAULT = "default"
    private const val REGISTRY = "registry.json"
    private const val SESSION_INDEX = "sessions.json"
    private const val MIGRATED_MARKER = "state-imported"

    /** 系统分享文件落地的临时编码区（固定 id/目录，见 [ensureTempWorkspace]） */
    const val TEMP_ID = "w_shared"
    private const val DIR_TEMP = "shared"

    data class Entry(
        val id: String,
        val name: String,
        val path: String,
        /** true = 用户在系统选择器里挑的目录；false = HPA 私有目录 */
        val external: Boolean,
    ) {
        val root: File get() = File(path)
        val metaDir: File get() = File(root, META_DIR)
    }

    data class SessionMeta(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val count: Int,
    )

    data class WorkspaceState(
        val activeSessionId: String?,
        val sessions: List<SessionMeta>,
    )

    private data class Registry(
        val activeId: String,
        val items: List<Entry>,
    )

    // ---------- 工作区 ----------

    /** 首次使用/升级后调用：建默认工作区、登记旧目录，并让 [Workspace] 指向活动工作区。 */
    @Synchronized
    fun ensureInitialized(context: Context): Entry {
        val registry = readRegistry(context)
        registry?.items?.firstOrNull { it.id == registry.activeId && it.root.isDirectory }?.let {
            Workspace.setActive(it.root)
            ensureMeta(it)
            return it
        }

        val existing = registry?.items.orEmpty().toMutableList()
        val default = Entry(
            id = "w_default",
            name = "默认工作区",
            path = privateRoot(context).absolutePath,
            external = false,
        )
        existing.removeAll { it.id == default.id }
        existing.add(0, default)
        default.root.mkdirs()
        ensureMeta(default)
        importLegacyState(context, default)

        // 老版本的工作区目录不搬（可能是几个 GB 的项目），登记成可选工作区即可
        legacyDirs(context).forEach { (name, dir) ->
            if (existing.none { it.path == dir.absolutePath }) {
                existing.add(Entry("w_legacy_${existing.size}", name, dir.absolutePath, true))
                ensureMeta(existing.last())
            }
        }

        val activeId = registry?.activeId?.takeIf { id -> existing.any { it.id == id } } ?: default.id
        writeRegistry(context, Registry(activeId, existing))
        Workspace.setActive(existing.first { it.id == activeId }.root)
        return existing.first { it.id == activeId }
    }

    fun list(context: Context): List<Entry> =
        readRegistry(context)?.items ?: listOf(ensureInitialized(context))

    fun active(context: Context): Entry {
        ensureInitialized(context)
        val registry = readRegistry(context)
        return registry?.let { r -> r.items.firstOrNull { it.id == r.activeId } }
            ?: list(context).first()
    }

    /** 切换活动工作区；只改指针，进程重启由调用方负责。 */
    @Synchronized
    fun activate(context: Context, id: String): Entry? {
        val registry = readRegistry(context) ?: return null
        val entry = registry.items.firstOrNull { it.id == id } ?: return null
        if (!isUsable(entry)) return null
        writeRegistry(context, registry.copy(activeId = id))
        Workspace.setActive(entry.root)
        return entry
    }

    @Synchronized
    fun createPrivate(context: Context, name: String): Entry? {
        val safe = name.trim().ifBlank { return null }
        val dir = File(privateParent(context), "w_${System.currentTimeMillis().toString(36)}")
        if (!dir.mkdirs()) return null
        val entry = Entry("w_${UUID.randomUUID().toString().take(8)}", safe, dir.absolutePath, false)
        addEntry(context, entry) ?: return null
        return entry
    }

    /** 登记用户从系统选择器挑的目录。返回 null 表示目录不可直接读写。 */
    @Synchronized
    fun addExternal(context: Context, path: String, name: String): Entry? {
        val dir = File(path)
        val entry = Entry(
            id = "w_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { dir.name },
            path = dir.absolutePath,
            external = true,
        )
        if (!isUsable(entry)) return null
        return addEntry(context, entry)
    }

    /**
     * 「临时编码区」：系统分享进来的文件都落在这里，与用户的正式工作区隔离。
     *
     * 固定 id + 固定目录：同一个用户反复分享文件时复用同一块地方（会话与记忆也延续），
     * 而不是每分享一次就堆一个新的工作区。目录在私有区，不占用用户的存储目录。
     */
    @Synchronized
    fun ensureTempWorkspace(context: Context): Entry {
        val existing = readRegistry(context)?.items?.firstOrNull { it.id == TEMP_ID }
        if (existing != null && existing.root.isDirectory) return existing
        ensureInitialized(context)
        val dir = File(privateRoot(context).parentFile, DIR_TEMP).apply { mkdirs() }
        val entry = Entry(TEMP_ID, "临时编码区", dir.absolutePath, false)
        ensureMeta(entry)
        addEntry(context, entry)
        return entry
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val registry = readRegistry(context) ?: return false
        if (id == "w_default") return false
        val entry = registry.items.firstOrNull { it.id == id } ?: return false
        val remaining = registry.items.filterNot { it.id == id }
        if (remaining.isEmpty()) return false
        // 只解除登记，不删用户目录里的文件
        val activeId = if (registry.activeId == id) remaining.first().id else registry.activeId
        writeRegistry(context, Registry(activeId, remaining))
        if (registry.activeId == id) Workspace.setActive(remaining.first().root)
        return true
    }

    private fun addEntry(context: Context, entry: Entry): Entry? {
        val registry = readRegistry(context)
        val items = (registry?.items.orEmpty() + entry).toMutableList()
        if (registry == null) {
            items.add(
                0,
                Entry("w_default", "默认工作区", privateRoot(context).absolutePath, false)
                    .also { it.root.mkdirs(); ensureMeta(it) },
            )
        }
        ensureMeta(entry)
        val activeId = registry?.activeId ?: items.first().id
        writeRegistry(context, Registry(activeId, items))
        return entry
    }

    private fun isUsable(entry: Entry): Boolean {
        val dir = entry.root
        if (!(dir.isDirectory || dir.mkdirs())) return false
        val meta = entry.metaDir
        if (!(meta.isDirectory || meta.mkdirs())) return false
        return try {
            val probe = File(meta, ".probe")
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureMeta(entry: Entry) {
        entry.metaDir.mkdirs()
        File(entry.metaDir, "sessions").mkdirs()
        File(entry.metaDir, "agent").mkdirs()
    }

    /** 系统选择器 → 真实路径。仅主存储（primary）可解析；其它卷返回 null。 */
    fun resolveTreePath(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val (volume, relative) = docId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (volume != "primary") {
            null
        } else {
            File(Environment.getExternalStorageDirectory(), relative).absolutePath
        }
    } catch (_: Exception) {
        null
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    // ---------- 会话 ----------

    @Synchronized
    fun state(context: Context, entry: Entry): WorkspaceState {
        val file = File(entry.metaDir, SESSION_INDEX)
        if (!file.isFile) return WorkspaceState(null, emptyList())
        return try {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("sessions") ?: JSONArray()
            val sessions = buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val id = s.optString("id")
                    if (id.isBlank()) continue
                    add(
                        SessionMeta(
                            id = id,
                            title = s.optString("title").ifBlank { "新会话" },
                            createdAt = s.optLong("createdAt"),
                            updatedAt = s.optLong("updatedAt"),
                            count = s.optInt("count"),
                        ),
                    )
                }
            }
            WorkspaceState(obj.optString("activeSessionId").takeIf { it.isNotBlank() }, sessions)
        } catch (_: Exception) {
            WorkspaceState(null, emptyList())
        }
    }

    @Synchronized
    fun saveState(context: Context, entry: Entry, state: WorkspaceState) {
        try {
            val arr = JSONArray()
            state.sessions.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("createdAt", s.createdAt)
                        .put("updatedAt", s.updatedAt)
                        .put("count", s.count),
                )
            }
            File(entry.metaDir, SESSION_INDEX).writeText(
                JSONObject()
                    .put("activeSessionId", state.activeSessionId ?: "")
                    .put("sessions", arr)
                    .toString(),
            )
        } catch (_: Exception) {
        }
    }

    /** 新建一个会话：id 同时用作 host 侧 sessionId（host 收到未知 id 会就地创建）。 */
    fun newSession(): SessionMeta {
        val now = System.currentTimeMillis()
        return SessionMeta(
            id = "u_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            title = "新会话",
            createdAt = now,
            updatedAt = now,
            count = 0,
        )
    }

    fun messagesFile(entry: Entry, sessionId: String): File =
        File(File(entry.metaDir, "sessions"), "$sessionId.json")

    @Synchronized
    fun loadMessages(entry: Entry, sessionId: String): List<ChatMessage> {
        val file = messagesFile(entry, sessionId)
        if (!file.isFile) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    add(
                        ChatMessage(
                            key = m.optString("key"),
                            role = m.optString("role"),
                            text = m.optString("text"),
                            toolName = m.optString("toolName").takeIf { it.isNotBlank() },
                            toolOk = if (m.has("toolOk")) m.optBoolean("toolOk") else null,
                            approvalCallId = m.optString("approvalCallId").takeIf { it.isNotBlank() },
                            approvalResolved = if (m.has("approvalResolved")) m.optBoolean("approvalResolved") else null,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 落盘某个会话的消息，并同步索引里的标题/时间/条数。 */
    @Synchronized
    fun saveMessages(
        context: Context,
        entry: Entry,
        sessionId: String,
        messages: List<ChatMessage>,
    ) {
        try {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("key", m.key)
                        .put("role", m.role)
                        .put("text", m.text)
                        .apply {
                            m.toolName?.let { put("toolName", it) }
                            m.toolOk?.let { put("toolOk", it) }
                            m.approvalCallId?.let { put("approvalCallId", it) }
                            m.approvalResolved?.let { put("approvalResolved", it) }
                        },
                )
            }
            messagesFile(entry, sessionId).writeText(arr.toString())
        } catch (_: Exception) {
            return
        }

        val state = state(context, entry)
        val title = messages.firstOrNull { it.role == "user" }?.text
            ?.trim()?.take(24)?.takeIf { it.isNotBlank() } ?: "新会话"
        val now = System.currentTimeMillis()
        val existing = state.sessions.firstOrNull { it.id == sessionId }
        val meta = (existing ?: SessionMeta(sessionId, title, now, now, 0)).copy(
            title = if (existing == null || existing.title == "新会话") title else existing.title,
            updatedAt = now,
            count = messages.size,
        )
        val sessions = if (existing == null) {
            listOf(meta) + state.sessions
        } else {
            state.sessions.map { if (it.id == sessionId) meta else it }
        }.sortedByDescending { it.updatedAt }
        saveState(context, entry, WorkspaceState(state.activeSessionId, sessions))
    }

    @Synchronized
    fun deleteSession(context: Context, entry: Entry, sessionId: String) {
        messagesFile(entry, sessionId).delete()
        val state = state(context, entry)
        saveState(
            context,
            entry,
            WorkspaceState(
                activeSessionId = state.activeSessionId.takeIf { it != sessionId },
                sessions = state.sessions.filterNot { it.id == sessionId },
            ),
        )
    }

    @Synchronized
    fun setActiveSession(context: Context, entry: Entry, sessionId: String?) {
        val state = state(context, entry)
        saveState(context, entry, state.copy(activeSessionId = sessionId))
    }

    // ---------- 位置 ----------

    private fun privateParent(context: Context): File =
        File(context.filesDir, "workspaces").apply { mkdirs() }

    private fun privateRoot(context: Context): File =
        File(privateParent(context), DIR_DEFAULT).apply { mkdirs() }

    private fun legacyDirs(context: Context): List<Pair<String, File>> {
        val dirs = mutableListOf<Pair<String, File>>()
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
            ?.let { File(it, Workspace.DIR_NAME) }
        if (external != null && external.isDirectory && external.listFiles()?.isNotEmpty() == true) {
            dirs += "旧工作区（happy_ACA）" to external
        }
        val internal = File(context.filesDir, "workspace")
        if (internal.isDirectory && internal.listFiles()?.isNotEmpty() == true) {
            dirs += "旧工作区（内置）" to internal
        }
        return dirs
    }

    /**
     * 旧版本把 agent state 放在 filesDir/state；这里一次性拷进默认工作区的 .happyac/agent，
     * 否则升级后会话上下文、记忆、用量账本会看起来"全丢了"。只拷一次，用标记文件防重。
     *
     * 拷贝可能很大（会话里有文件快照），放到后台线程执行，不在 Application.onCreate 上阻塞。
     */
    private val migrationStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val migrationIo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "workspace-state-migration").apply { isDaemon = true }
    }

    private fun importLegacyState(context: Context, entry: Entry) {
        if (!migrationStarted.compareAndSet(false, true)) return
        migrationIo.execute {
            val source = File(context.filesDir, "state")
            val target = File(entry.metaDir, "agent")
            val marker = File(entry.metaDir, MIGRATED_MARKER)
            if (marker.isFile || !source.isDirectory) {
                runCatching { marker.parentFile?.mkdirs(); marker.writeText("1") }
                return@execute
            }
            runCatching { source.copyRecursively(target, overwrite = false) }
            runCatching { marker.writeText("1") }
        }
    }

    private fun readRegistry(context: Context): Registry? {
        val file = File(privateRoot(context).parentFile, REGISTRY)
        if (!file.isFile) return null
        return try {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("items") ?: return null
            val items = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("path")
                    if (path.isBlank()) continue
                    add(
                        Entry(
                            id = o.optString("id"),
                            name = o.optString("name").ifBlank { File(path).name },
                            path = path,
                            external = o.optBoolean("external"),
                        ),
                    )
                }
            }
            if (items.isEmpty()) null else Registry(obj.optString("activeId"), items)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeRegistry(context: Context, registry: Registry) {
        try {
            val file = File(privateRoot(context).parentFile, REGISTRY)
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            registry.items.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                        .put("path", e.path)
                        .put("external", e.external),
                )
            }
            file.writeText(
                JSONObject().put("activeId", registry.activeId).put("items", arr).toString(),
            )
        } catch (_: Exception) {
        }
    }
}
