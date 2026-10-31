package com.phoneagent.coding

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.phoneagent.coding.agent.CodingWorkspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 系统分享进来的文件：落到「临时编码区」并把交接信息交给编码页。
 *
 * 为什么要另开一个工作区而不是直接塞进用户当前的工作区：
 * 分享是「临时看一眼这个文件」，不是「往我的项目里加文件」。混进项目目录后
 * 用户还得自己去删；而临时编码区是独立目录、独立会话与独立记忆，
 * 编码 agent 在里面读文件、写脚本都不会碰到用户的项目。
 */
object SharedFiles {

    /** 一次导入的结果：文件落在 [workspace] 根目录下的 [relativePath]。 */
    data class Imported(
        val workspace: CodingWorkspaces.Entry,
        val relativePath: String,
        val displayName: String,
        val bytes: Long,
    )

    private val _pending = MutableStateFlow<List<Imported>>(emptyList())

    /**
     * 已导入、还没发出去的附件。它是附件区唯一的真值来源：跨 Tab 切换/页面重建后
     * 附件仍在（`remember` 里的临时列表做不到），发出去或用户摘掉才清。
     */
    val pending: StateFlow<List<Imported>> = _pending.asStateFlow()

    /** 发出去之后清空 */
    fun consume() {
        _pending.value = emptyList()
    }

    /** 用户从输入区摘掉某个附件：文件留在临时编码区，只是不再随消息发出 */
    fun remove(item: Imported) {
        _pending.value = _pending.value - item
    }

    /**
     * 把分享进来的 content:// 文件复制进临时编码区。
     *
     * 复制而不是留 URI：agent 是 node 子进程，只认文件系统路径，content:// 它读不到；
     * 而且分享方给的临时读权限在我们落盘之后就失效了，留着 URI 等于留个死链。
     */
    suspend fun import(context: Context, uris: List<Uri>): Result<List<Imported>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(uris.isNotEmpty()) { "没有收到文件" }
                val entry = CodingWorkspaces.ensureTempWorkspace(context)
                val imported = uris.mapNotNull { uri -> copyInto(context, entry, uri) }
                if (imported.isEmpty()) error("文件读取失败，没能导入任何文件")
                // 追加而不是覆盖：上一次分享的文件还没发出去时，两份都该挂在附件区里
                _pending.value = _pending.value + imported
                imported
            }
        }

    private fun copyInto(context: Context, entry: CodingWorkspaces.Entry, uri: Uri): Imported? {
        val name = displayName(context, uri)
        val target = uniqueFile(entry.root, name)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            target.delete()
            return null
        }
        return Imported(
            workspace = entry,
            relativePath = target.name,
            displayName = name,
            bytes = target.length(),
        )
    }

    /** 分享方登记的文件名；拿不到就退回 URI 末段，再不行给个通用名。 */
    private fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val raw = queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared-file"
        return sanitize(raw)
    }

    /** 文件名只取末段并去掉路径分隔符：分享方给的 DISPLAY_NAME 并不可信。 */
    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "")
            .trim()
        return cleaned.ifBlank { "shared-file" }
    }

    /** 同名不覆盖：第二次分享同一个名字的文件时加 -1、-2 后缀。 */
    private fun uniqueFile(root: File, name: String): File {
        val direct = File(root, name)
        if (!direct.exists()) return direct
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(root, "$base-$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }
}
