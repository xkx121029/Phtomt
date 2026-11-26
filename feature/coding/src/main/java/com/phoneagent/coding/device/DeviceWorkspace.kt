package com.phoneagent.coding.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.phoneagent.coding.workspaces.Workspace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备工作区（#29 #30 #31 #34 的文件底座）。
 *
 * 编辑器和预览直接读写设备上同一个目录——本地模式下它就是 agent 的工作区，
 * 所以不需要 agent 在线也能看文件、改文件、预览页面。
 *
 * 路径一律相对工作区，且必须落在工作区内：绝对路径、`..` 越过工作区一律拒绝。
 * 这不是防君子不防小人的摆设——配错一个路径就能把用户 SD 卡整个删掉。
 */
// 路径判据、读写、原子写、预览差异都在同一份工作区语义里；拆开会让「先归一再比前缀」
// 这条主线散到多个文件，评审时反而看不出边界。
@Suppress("TooManyFunctions")
object DeviceWorkspace {

    /** 编辑器一次最多读这么多字节，超出部分截断并在返回值里标出来。 */
    const val MAX_READ_BYTES = 512 * 1024

    /** 这些目录属于工具产物或版本控制内部数据，不进文件树——树要给人看，不是给机器看。 */
    private val SKIP_DIRS = setOf("node_modules", ".git", ".haca", "build", ".gradle", ".idea", ".dart_tool")

    fun root(context: Context): File = Workspace.resolve(context)

    fun resolveWithin(context: Context, rel: String): File = resolveWithin(root(context), rel)

    /**
     * 纯判据（不碰 Context）：给单测一个可测的接缝，也是 [resolveWithin] 的唯一实现。
     *
     * 语义必须与 Node 侧的 `resolveWithin`（`agent-host/tools/fs-tools.js`）一致：
     * **先把路径归一到真实路径，再比真实前缀**。
     *
     * 注意不能用 `canonicalFile` 做这一步——实测（DeviceWorkspacePathTest）Java 的
     * `getCanonicalPath` **不解析 Windows junction**，一条 `mklink /J photos D:\DCIM`
     * 就能让编辑器与 agent 一起读到工作区外，而读文件的 risk 是 read，risky 模式下
     * 连审批都不弹。这里用 `Path.toRealPath()`（走 GetFinalPathNameByHandle，符号链接
     * 与 junction 都解析）。两侧各有一条「必须拒绝」的反面测试，改一边就要改另一边。
     */
    // 三种拒绝理由（空 / 绝对 / 越界）各有各的下一步，合并成一句话会让用户不知道该改哪
    @Suppress("ThrowsCount")
    internal fun resolveWithin(root: File, rel: String): File {
        val clean = rel.trim().replace('\\', '/')
        if (clean.isEmpty()) throw IllegalArgumentException("路径为空")
        // 绝对路径没有任何合法用途：Windows 上拼出 "C:\ws\C:\x" 这种非法路径（报的错
        // 跟越界毫无关系）；POSIX 上若先把前导斜杠 trim 掉，`/sdcard/x` 会被静默当成工作区
        // 内的相对路径——Android 本身就是 Linux，这正是这条判据要挡的那类误配，所以必须在
        // 归一之前拒掉，而不是先 trimStart('/') 再看。
        if (clean.startsWith("/") || DRIVE_ABSOLUTE.containsMatchIn(clean)) {
            throw IllegalArgumentException("路径越界：$rel")
        }

        val rootReal = realPathOrSelf(root)
        val f = File(rootReal, clean)
        val real = shallowRealPath(f)
        if (real != rootReal && !real.path.startsWith(rootReal.path + File.separator)) {
            throw IllegalArgumentException("路径越界：$rel")
        }
        return f
    }

    /** Windows 盘符绝对路径（`C:/x`、`C:\x`）：Java 的 isAbsolute 在反斜杠形态上不一定认得。 */
    private val DRIVE_ABSOLUTE = Regex("^[A-Za-z]:")

    private fun realPathOrSelf(f: File): File = runCatching { f.toPath().toRealPath().toFile() }.getOrDefault(f)

    /**
     * 解析到真实路径，但允许目标本身还不存在（写新文件必须能过）：
     * 把最近的存在祖先解析掉，再把剩下的段拼回去。存在的部分里若有链接，一定会被解析。
     */
    private fun shallowRealPath(f: File): File {
        val rest = ArrayDeque<String>()
        var cur: File? = f
        while (cur != null) {
            if (cur.exists()) {
                var real = runCatching { cur.toPath().toRealPath().toFile() }.getOrDefault(cur)
                rest.forEach { real = File(real, it) }
                return real
            }
            if (cur.name.isNotEmpty()) rest.addFirst(cur.name)
            cur = cur.parentFile
        }
        return f
    }

    fun relative(context: Context, file: File): String {
        val root = root(context).canonicalFile
        val f = file.canonicalFile
        return if (f.path.startsWith(root.path + File.separator)) {
            f.path.substring(root.path.length + 1).replace('\\', '/')
        } else {
            f.path
        }
    }

    /**
     * 目录树。扁平返回 + depth，由前端自己拼层级——嵌套结构在 JSON 里转来转去
     * 只会让两边都更难写，深度这一列信息量已经够了。
     */
    fun tree(context: Context, maxDepth: Int = 6, limit: Int = 800): JSONArray {
        val out = JSONArray()
        val root = root(context)
        if (!root.isDirectory) return out
        var count = 0

        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || count >= limit) return
            val children = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (c in children) {
                if (count >= limit) return
                if (c.isDirectory && c.name in SKIP_DIRS) continue
                val isDir = c.isDirectory
                out.put(
                    JSONObject()
                        .put("path", relative(context, c))
                        .put("name", c.name)
                        .put("dir", isDir)
                        .put("depth", depth)
                        .put("size", if (isDir) 0L else c.length()),
                )
                count++
                if (isDir) walk(c, depth + 1)
            }
        }
        walk(root, 1)
        return out
    }

    fun read(context: Context, rel: String): JSONObject {
        val f = resolveWithin(context, rel)
        if (!f.exists()) throw IllegalArgumentException("文件不存在：$rel")
        if (f.isDirectory) throw IllegalArgumentException("$rel 是一个目录")
        val bytes = f.readBytes()
        val truncated = bytes.size > MAX_READ_BYTES
        val slice = if (truncated) bytes.copyOf(MAX_READ_BYTES) else bytes
        return JSONObject()
            .put("path", relative(context, f))
            .put("content", slice.toString(Charsets.UTF_8))
            .put("bytes", bytes.size.toLong())
            .put("truncated", truncated)
    }

    fun write(context: Context, rel: String, content: String): JSONObject {
        val f = resolveWithin(context, rel)
        if (f.isDirectory) throw IllegalArgumentException("$rel 是一个目录，不能写入")
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return JSONObject().put("path", relative(context, f)).put("bytes", f.length())
    }

    fun create(context: Context, rel: String): JSONObject {
        val f = resolveWithin(context, rel)
        if (f.exists()) throw IllegalArgumentException("$rel 已存在")
        f.parentFile?.mkdirs()
        f.createNewFile()
        return JSONObject().put("path", relative(context, f))
    }

    /** 工作区里的 SQLite 库文件（#34 的浏览器用）。 */
    fun databaseFiles(context: Context, limit: Int = 100): JSONArray {
        val out = JSONArray()
        val root = root(context)
        if (!root.isDirectory) return out
        var count = 0
        root.walkTopDown().maxDepth(5).forEach { f ->
            if (count >= limit) return@forEach
            if (f.isFile && f.extension.lowercase() in DB_EXTS) {
                out.put(JSONObject().put("path", relative(context, f)).put("size", f.length()))
                count++
            }
        }
        return out
    }

    private val DB_EXTS = setOf("db", "sqlite", "sqlite3", "db3")

    /**
     * 工作区里的产物目录（浏览器截图等落这里）。
     *
     * 说明：原先这个函数和下面的 timestamped 住在 CaptureController（截图/录屏服务）里，
     * 那属于手机操控层，已随该层一起移除；它们本身只是「在产物目录里起个不重名的文件名」，
     * 与系统权限无关，所以搬到这里继续给浏览器截图用。
     */
    fun artifactDir(context: Context): File {
        val dir = File(root(context), ".haca/captures")
        dir.mkdirs()
        return dir
    }

    /** 按时间戳生成不重名的产物文件名：prefix-20261020_153012_123.png。 */
    fun timestamped(dir: File, prefix: String, ext: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "$prefix-$stamp.$ext")
    }
}
