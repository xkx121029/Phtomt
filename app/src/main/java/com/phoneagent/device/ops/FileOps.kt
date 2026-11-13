@file:Suppress("MagicNumber")
package com.phoneagent.device.ops

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件读写能力（`file_read` / `file_write` 两个能力族）。
 *
 * 从 [DeviceOpsChannel] 拆出来的原因：这两个族占了约 150 行，与「设备设置 / 闹钟 / 日历」
 * 没有任何共享状态，唯一依赖是 [Context]（为了算应用私有目录的可写根）。
 * 拆开后：
 * - 路径越界防护（[resolvePath]）只有一个实现，不会再出现「某族忘了校验」；
 * - glob / 遍历 / 计数这些纯逻辑可供单测直接调用。
 *
 * 安全边界：[resolvePath] 把 AI 给出的任意路径收敛到 [fileRoots] 之内，
 * 任何解析到系统目录的请求都返回 null 而不是抛出——调用方据此回报中文原因。
 */
internal class FileOps(private val context: Context) {

    private val results = OpsResults

    /** 单文件扫描上限：超过这个大小不读内容，避免 grep 拖垮内存 */
    private val maxScanBytes = 512 * 1024L

    /** 允许文件能力操作的根目录（其余路径一律拒绝，防止 AI 误伤系统目录） */
    private val fileRoots: List<String> by lazy {
        listOf("/sdcard", "/storage", context.filesDir.path, context.cacheDir.path) +
            listOfNotNull(context.externalCacheDir?.path, context.getExternalFilesDir(null)?.path)
    }

    /** 绝对路径直接用；相对路径按 /sdcard 解析；解析后必须落在 [fileRoots] 之内 */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun resolvePath(raw: String, default: String? = null): File? {
        val p = raw.trim().trim('"').ifEmpty { default ?: return null }
        val f = if (p.startsWith("/")) File(p) else File("/sdcard", p)
        val canonical = runCatching { f.canonicalFile }.getOrNull() ?: return null
        val path = canonical.path
        return canonical.takeIf { fileRoots.any { root -> path == root || path.startsWith("$root/") } }
    }

    /**
     * 广度优先遍历目录（跳过隐藏目录），onFile 返回 true 表示"够了，停"。
     * 节点数上限兜底，避免 /sdcard 上有超大目录时扫描停不下来。
     */
    // 嵌套结构是协议/匹配逻辑本身，扁平化风险较高，此处按规则豁免
    @Suppress("NestedBlockDepth", "ReturnCount")
    private fun walkFiles(root: File, maxNodes: Int = 20000, onFile: (File) -> Boolean): Boolean {
        val queue = ArrayDeque<File>().apply { add(root) }
        var nodes = 0
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                nodes++
                if (nodes > maxNodes) return false
                if (c.isDirectory) {
                    if (!c.name.startsWith(".")) queue.add(c)
                } else if (onFile(c)) {
                    return true
                }
            }
        }
        return false
    }

    /** 统计 [needle] 在 [text] 中出现次数（edit 要求唯一，靠它判定） */
    fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = text.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = text.indexOf(needle, idx + needle.length)
        }
        return count
    }

    // ---------------------------------------------------------------- file_read

    @Suppress("LongMethod", "CyclomaticComplexMethod", "MaxLineLength")
    suspend fun read(op: String, args: Map<String, String>): OpsResult = withContext(Dispatchers.IO) {
        when (op) {
            "read" -> {
                val raw = args["path"]?.trim().orEmpty()
                if (raw.isEmpty()) return@withContext results.fail("file_read 的 read 需要 path（绝对路径，如 /sdcard/Download/a.txt）")
                val f = resolvePath(raw)
                    ?: return@withContext results.fail("路径不可访问：$raw（仅支持 /sdcard、/storage 与应用私有目录）")
                if (!f.exists()) {
                    return@withContext results.fail(
                        "文件不存在：${f.path}。请先用 file_read（op=list）看一眼所在目录里实际有哪些文件名",
                    )
                }
                if (f.isDirectory) return@withContext results.fail("${f.path} 是目录；列目录请用 op=list")
                val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@withContext results.fail(
                        "读取失败：${f.path} 是二进制文件或不是 UTF-8 文本，读出来也是乱码。" +
                            "若只是想确认它存在，用 op=stat 看大小和时间即可",
                    )
                val cap = 4000
                results.ok(buildString {
                    appendLine("${f.path}（${f.length()} 字节）")
                    append(if (text.length > cap) text.take(cap) + "\n…（已截断，全文 ${text.length} 字符）" else text)
                })
            }
            "list" -> {
                val dir = resolvePath(args["path"]?.trim().orEmpty(), "/sdcard")
                    ?: return@withContext results.fail("路径不可访问；list 支持 /sdcard、/storage 与应用私有目录")
                if (!dir.isDirectory) return@withContext results.fail("不是目录：${dir.path}")
                val limit = (OpsArgs.int(args, "limit") ?: 200).coerceIn(1, 500)
                val all = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                results.ok(buildString {
                    appendLine("${dir.path} 共 ${all.size} 项${if (all.size > limit) "（只列前 $limit 项）" else ""}")
                    all.take(limit).forEach { c ->
                        appendLine(if (c.isDirectory) "- [目录] ${c.name}/" else "- [文件] ${c.name}（${c.length()} 字节）")
                    }
                }.trimEnd())
            }
            "stat" -> {
                val f = resolvePath(args["path"]?.trim().orEmpty())
                    ?: return@withContext results.fail("stat 需要可访问的 path")
                if (!f.exists()) return@withContext results.fail("路径不存在：${f.path}")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(f.lastModified()))
                results.ok(
                    "${f.path}\n类型：${if (f.isDirectory) "目录" else "文件"}\n" +
                        "大小：${f.length()} 字节\n修改时间：$time\n可读：${f.canRead()} 可写：${f.canWrite()}",
                )
            }
            "glob" -> {
                val pattern = args["pattern"]?.trim().orEmpty()
                if (pattern.isEmpty()) return@withContext results.fail("glob 需要 pattern（如 **/*.md）")
                val root = resolvePath(args["path"]?.trim().orEmpty(), "/sdcard")
                    ?: return@withContext results.fail("glob 的搜索根不可访问")
                if (!root.isDirectory) return@withContext results.fail("glob 的 path 必须是目录：${root.path}")
                val limit = (OpsArgs.int(args, "limit") ?: 100).coerceIn(1, 500)
                val regex = GlobPattern.toRegex(pattern)
                val basePath = root.path.trimEnd('/')
                val hits = ArrayList<String>()
                walkFiles(root) { f ->
                    val rel = f.path.trimEnd('/').removePrefix("$basePath/")
                    if (regex.matches(rel)) {
                        hits.add("${f.path}（${f.length()} 字节）")
                        hits.size >= limit
                    } else false
                }
                if (hits.isEmpty()) results.ok("没有匹配「$pattern」的文件（根目录：${root.path}）")
                else results.ok("匹配 ${hits.size} 个文件（根目录：${root.path}）：\n" + hits.joinToString("\n") { "- $it" })
            }
            "grep" -> {
                val pattern = args["pattern"]?.trim().orEmpty()
                if (pattern.isEmpty()) return@withContext results.fail("grep 需要 pattern（正则表达式）")
                val regex = runCatching { Regex(pattern) }.getOrElse {
                    return@withContext results.fail("grep 的正则不合法：${it.message ?: pattern}")
                }
                val root = resolvePath(args["path"]?.trim().orEmpty(), "/sdcard")
                    ?: return@withContext results.fail("grep 的搜索根不可访问")
                if (!root.isDirectory) return@withContext results.fail("grep 的 path 必须是目录：${root.path}")
                val limit = (OpsArgs.int(args, "limit") ?: 100).coerceIn(1, 500)
                val hits = ArrayList<String>()
                walkFiles(root) { f ->
                    if (f.length() > maxScanBytes) return@walkFiles false
                    val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@walkFiles false
                    var added = false
                    text.lineSequence().forEachIndexed { idx, line ->
                        if (hits.size < limit && regex.containsMatchIn(line)) {
                            hits.add("${f.path}:${idx + 1}: ${line.trim().take(160)}")
                            added = true
                        }
                    }
                    if (!added) false else hits.size >= limit
                }
                if (hits.isEmpty()) results.ok("没有匹配「$pattern」的内容（根目录：${root.path}）")
                else results.ok("匹配 ${hits.size} 处（根目录：${root.path}）：\n" + hits.joinToString("\n") { "- $it" })
            }
            else -> results.fail("file_read 只支持 op=read/list/stat/glob/grep")
        }
    }

    // ---------------------------------------------------------------- file_write

    /** 改文件族（write/append/edit）；edit 要求原文在文件中唯一出现，避免误替换。 */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod")
    suspend fun write(op: String, args: Map<String, String>): OpsResult = withContext(Dispatchers.IO) {
        val raw = args["path"]?.trim().orEmpty()
        if (raw.isEmpty()) return@withContext results.fail("file_write 需要 path（目标文件绝对路径）")
        val f = resolvePath(raw) ?: return@withContext results.fail("路径不可访问：$raw（仅支持 /sdcard、/storage 与应用私有目录）")
        when (op) {
            "write", "append" -> {
                val text = args["text"] ?: return@withContext results.fail("file_write 的 $op 需要 text（写入内容）")
                runCatching {
                    f.parentFile?.mkdirs()
                    if (op == "write") f.writeText(text, Charsets.UTF_8) else f.appendText(text, Charsets.UTF_8)
                }.getOrElse {
                    return@withContext results.fail(
                        "写入失败：${it.message ?: "系统拒绝"}。请换到 /sdcard/Download 或 /sdcard/Documents 这类可写目录再试，" +
                            "并确认文件名没有被系统保留",
                    )
                }
                results.ok("已${if (op == "write") "覆盖写入" else "追加"}：${f.path}（现 ${f.length()} 字节）")
            }
            "edit" -> {
                val old = args["old"].orEmpty()
                val new = args["new"].orEmpty()
                if (old.isEmpty()) return@withContext results.fail("file_write 的 edit 需要 old（被替换的原文）")
                if (!f.isFile) return@withContext results.fail("edit 的目标不是已存在的文件：${f.path}")
                val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@withContext results.fail("读取失败（非 UTF-8 文本）：${f.path}")
                when (val count = countOccurrences(text, old)) {
                    0 -> results.fail("edit 失败：文件里没有这段原文（${old.take(40)}…）")
                    1 -> {
                        val idx = text.indexOf(old)
                        val updated = text.replaceRange(idx, idx + old.length, new)
                        runCatching { f.writeText(updated, Charsets.UTF_8) }
                            .getOrElse { return@withContext results.fail("写入失败：${it.message ?: "系统拒绝"}") }
                        results.ok("已替换 1 处：${f.path}（${text.length} → ${updated.length} 字符）")
                    }
                    else -> results.fail("edit 失败：原文出现 $count 次，请给一段更长的唯一片段")
                }
            }
            else -> results.fail("file_write 只支持 op=write/append/edit")
        }
    }
}
