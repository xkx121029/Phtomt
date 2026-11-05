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
import java.io.InputStream
import java.util.zip.ZipFile

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
        /** docx / xlsx / pptx / zip 的纯文本摘录（同目录，见 [FileExcerpt]）；没有就是 null */
        val excerptPath: String? = null,
    )

    private val _pending = MutableStateFlow<List<Imported>>(emptyList())

    /** 摘录文件的后缀：容器格式解析出的纯文本放在原文件旁边，agent 用 read_file 直接读 */
    private const val EXCERPT_SUFFIX = ".text.txt"

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
            excerptPath = writeExcerpt(entry.root, target),
        )
    }

    /** 容器格式的纯文本摘录写在原文件旁边：解析或落盘失败都不该影响文件本身 */
    private fun writeExcerpt(root: File, file: File): String? = runCatching {
        val text = FileExcerpt.of(file) ?: return@runCatching null
        uniqueFile(root, file.nameWithoutExtension + EXCERPT_SUFFIX).apply { writeText(text) }.name
    }.getOrNull()

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

/**
 * 容器格式 → 纯文本摘录。
 *
 * docx / xlsx / pptx 本质是 zip + XML，文字都躺在固定的 XML 段里；不抽出来，
 * agent 的 read_file 读到的是二进制，等于什么都没读。zip 则给出条目清单。
 * PDF / 图片 / 音视频没有无依赖的解析方式，保持原样（prompt 里会提醒 agent 别臆测内容）。
 */
private object FileExcerpt {

    /** 一个 docx / xlsx / pptx 最多解析多少段 XML（PPT 一页一段）：再多会把对话上下文挤满 */
    private const val MAX_PACKAGE_PARTS = 40
    /** 压缩包清单最多列多少条：再多对「这个包里有什么」已经没有信息量 */
    private const val MAX_ZIP_ENTRIES = 200
    /** 超过这个大小的容器不解析：解压 + 正则在大文件上会把内存吃光 */
    private const val MAX_BYTES = 20L * 1024 * 1024

    /** 段落 / 行 / 换行标签：先换成真换行，摘录读起来才有分段 */
    private val BlockEnd = Regex("</(?:[A-Za-z0-9]+:)?(?:p|tr|row|br)>")
    private val AnyTag = Regex("<[^>]+>")
    private val Whitespace = Regex("\\s+")

    fun of(file: File): String? = runCatching {
        if (file.length() > MAX_BYTES) return@runCatching null
        when (file.extension.lowercase()) {
            "docx" -> officeText(file) { name -> name == "word/document.xml" }
            "xlsx" -> officeText(file) { name -> name == "xl/sharedStrings.xml" }
            "pptx" -> officeText(file) { name -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml") }
            "zip" -> zipListing(file)
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 从容器里挑出承载文字的 XML 段，拼成一段纯文本 */
    private fun officeText(file: File, pick: (String) -> Boolean): String? = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && pick(it.name) }
                .sortedBy { it.name }
                .take(MAX_PACKAGE_PARTS)
                .map { xmlToText(zip.getInputStream(it)) }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
    }.getOrNull()

    /** OOXML 片段 → 可读文本：标签换行/去壳，实体还原，连续空白压成一个空格 */
    private fun xmlToText(input: InputStream): String = input.use { stream ->
        val xml = stream.readBytes().toString(Charsets.UTF_8)
        xml.replace(BlockEnd, "\n")
            .replace(AnyTag, " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .lines()
            .map { it.replace(Whitespace, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** 压缩包条目清单：只用名字与大小，不递归解包——解读「包里有什么」就够了 */
    private fun zipListing(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val all = zip.entries().asSequence().toList()
            buildString {
                append("压缩包条目（共 ").append(all.size).append(" 条）：\n")
                all.take(MAX_ZIP_ENTRIES).forEach { entry ->
                    append("- ").append(entry.name)
                    if (!entry.isDirectory) append("  ").append(entry.size).append(" B")
                    append('\n')
                }
                if (all.size > MAX_ZIP_ENTRIES) {
                    append("…（只列前 ").append(MAX_ZIP_ENTRIES).append(" 条）")
                }
            }
        }
    }.getOrNull()
}
