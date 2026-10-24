package com.phoneagent.coding.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.phoneagent.coding.agent.Workspace
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
object DeviceWorkspace {

    /** 编辑器一次最多读这么多字节，超出部分截断并在返回值里标出来。 */
    const val MAX_READ_BYTES = 512 * 1024

    /** 这些目录属于工具产物或版本控制内部数据，不进文件树——树要给人看，不是给机器看。 */
    private val SKIP_DIRS = setOf("node_modules", ".git", ".haca", "build", ".gradle", ".idea", ".dart_tool")

    fun root(context: Context): File = Workspace.resolve(context)

    fun resolveWithin(context: Context, rel: String): File {
        val root = root(context).canonicalFile
        val clean = rel.trim().replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) throw IllegalArgumentException("路径为空")
        val f = File(root, clean).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("路径越界：$rel")
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
