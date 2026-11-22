package com.phoneagent.core.text

/**
 * 第三段：结构转换。
 *
 * 显式帧栈遍历词元，把 HTML 结构落成 Markdown 块；**全程无递归**，
 * 畸形页面 / 千层 div 都不会栈溢出。规则表见 [HtmlToMarkdown] 的常量。
 */
private const val K_PARA = 1
private const val K_HEAD = 2
private const val K_LIST = 3
private const val K_ITEM = 4
private const val K_QUOTE = 5
private const val K_PRE = 6
private const val K_TABLE = 7
private const val K_CELL = 8
private const val K_ROW = 9
private const val K_MARK = 10
private const val K_SKIP = 11

/** 被隐藏的子树：结构性跳过，且其内文本一律不产出 */
private const val K_MUTE = 12

/** 隐式闭合时的"容器边界"：碰到就先停手，保证只闭合同级元素 */
private val STOP_CONTAINER = setOf(K_LIST, K_TABLE, K_QUOTE)
private val STOP_ROW = setOf(K_ROW, K_TABLE)
private val STOP_TABLE = setOf(K_TABLE)

private class Frame(
    val tag: String,
    val kind: Int,
    /** 标题级别 / 引用深度 / 列表缩进 */
    var level: Int = 0,
    val ordered: Boolean = false,
    var counter: Int = 1,
    var bulletUsed: Boolean = false,
)

internal class Converter(private val baseUrl: String?, private val b: MdBuilder) {

    private val stack = ArrayList<Frame>(32)
    private var preBuf: StringBuilder? = null
    private var preLang: String? = null
    private var rows: ArrayList<ArrayList<String>>? = null
    private var row: ArrayList<String>? = null
    /** 单元格内被"块化"的文本（`<td><p>x</p></td>` 的 p 会走 emit，得先收在这里） */
    private val cellBufs = ArrayList<StringBuilder>(4)
    private var stop = false

    /** 当前是否身处表格单元格内（含嵌套表格的外层单元格） */
    private fun inCell(): Boolean = cellBufs.isNotEmpty()

    /** 被隐藏子树的深度：>0 时任何文本都不产出 */
    private var mute = 0

    fun run(tokens: List<Tok>) {
        for (t in tokens) {
            if (stop) break
            when (t) {
                is Tok.Text -> text(t.s)
                is Tok.Start -> start(t.tag)
                is Tok.End -> popTo(t.name)
            }
            if (b.truncated) stop = true
        }
        // EOF 收尾：栈内未闭合元素按序关闭，保证输出结构完整
        while (stack.isNotEmpty()) popTop()
        b.flushList()
    }

    /** 关闭栈顶：**先 closeFrame 再出栈**——closeFrame 要靠栈找到所属列表项/引用深度 */
    private fun popTop() {
        val f = stack[stack.size - 1]
        closeFrame(f)
        stack.removeAt(stack.size - 1)
    }

    // ---------- 开始标签 ----------

    /** 开始标签的守卫分档：自闭合 / 深度超限 / 隐藏子树 / pre 内部四条互相独立，命中即返回 */
    private fun start(t: Tag) {
        val name = t.name
        if (name in HtmlToMarkdown.VOID_TAGS) {
            startVoid(name, t)
            return
        }
        // 深度兜底：超限时只占位不展开，保证结束标签配平
        if (stack.size >= HtmlToMarkdown.MAX_DEPTH) {
            stack.add(Frame(name, K_SKIP))
            return
        }
        // 被属性/类名藏起来的元素（JS 侧还多看一层计算样式）：整棵子树既不进结构也不进正文
        if (hiddenTag(t)) {
            mute++
            stack.add(Frame(name, K_MUTE))
            return
        }
        // pre 内部：只认 <code class="language-x"> 取语言串，其余标签一律不加标记
        if (preBuf != null) {
            if (name == "code" && preLang == null) preLang = codeLang(t)
            stack.add(Frame(name, K_SKIP))
            return
        }
        implicitClose(name)
        startStructure(name, t)
    }

    /** 自闭合标签：`br`/`img`/`hr` 各有专用产出，其余（meta/link/input…）在清洗阶段已丢，这里无事可做 */
    private fun startVoid(name: String, t: Tag) {
        when (name) {
            // pre 里的 <br> 是真实换行，不能折叠成空格
            "br" -> preBuf?.append('\n') ?: b.br()
            "img" -> image(t)
            "hr" -> {
                flushInline()
                emitBlock("---")
            }
            else -> {}
        }
    }

    private fun startStructure(name: String, t: Tag) {
        when (name) {
            "p" -> {
                flushInline()
                stack.add(Frame(name, K_PARA))
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flushInline()
                stack.add(Frame(name, K_HEAD, level = name[1] - '0'))
            }
            "ul", "ol" -> {
                flushInline()
                stack.add(
                    Frame(
                        name, K_LIST,
                        level = listDepth(),
                        ordered = name == "ol",
                        counter = orderedStart(t),
                    ),
                )
                b.inList++
            }
            "li" -> {
                flushInline()
                stack.add(Frame(name, K_ITEM, level = nearestList()?.level ?: 0))
            }
            "blockquote" -> {
                flushInline()
                stack.add(Frame(name, K_QUOTE, level = (nearestQuote()?.level ?: 0) + 1))
            }
            "pre" -> {
                flushInline()
                preBuf = StringBuilder()
                preLang = null
                stack.add(Frame(name, K_PRE))
            }
            "table" -> {
                flushInline()
                rows = ArrayList()
                row = null
                stack.add(Frame(name, K_TABLE))
            }
            // 表题不进管道表（否则会被当成第一个单元格），整段丢弃
            "caption" -> {
                flushInline()
                stack.add(Frame(name, K_SKIP))
            }
            "tr" -> {
                row = ArrayList()
                stack.add(Frame(name, K_ROW))
            }
            "td", "th" -> {
                cellBufs.add(StringBuilder())
                stack.add(Frame(name, K_CELL))
            }
            "a" -> {
                val url = absolute(t.attrs["href"])
                if (url == null) b.push("", "") else b.push("[", "]($url)")
                stack.add(Frame(name, K_MARK))
            }
            else -> {
                val mark = HtmlToMarkdown.MARK_TAGS[name]
                if (mark != null) {
                    b.push(mark, mark)
                    stack.add(Frame(name, K_MARK))
                } else {
                    if (name in HtmlToMarkdown.BLOCK_TAGS) flushInline()
                    stack.add(Frame(name, K_SKIP))
                }
            }
        }
    }

    private fun codeLang(t: Tag): String =
        t.attrs["class"].orEmpty().split(' ', '\t', '\n')
            .firstOrNull { it.startsWith("language-") }
            ?.removePrefix("language-")
            ?.take(16)
            .orEmpty()

    private fun orderedStart(t: Tag): Int =
        t.attrs["start"]?.trim()?.toIntOrNull()?.takeIf { it in 1..99_999 } ?: 1

    private fun image(t: Tag) {
        val src = absolute(t.attrs["src"]) ?: return
        val alt = t.attrs["alt"].orEmpty().replace("[", "\\[").replace("]", "\\]")
        b.raw("![$alt]($src)")
    }

    // ---------- 结束标签：一律走"弹出到该标签"，游离结束标签天然被忽略 ----------

    private fun popTo(name: String) {
        var idx = -1
        for (k in stack.indices.reversed()) {
            if (stack[k].tag == name) {
                idx = k
                break
            }
        }
        if (idx < 0) return
        while (stack.size > idx) popTop()
    }

    private fun closeFrame(f: Frame) {
        when (f.kind) {
            K_PARA -> flushInline()
            K_HEAD -> flushInline("#".repeat(f.level.coerceIn(1, 6)) + " ")
            K_ITEM -> {
                flushInline()
            }
            K_LIST -> {
                flushInline()
                b.inList--
                if (b.inList <= 0) {
                    b.inList = 0
                    b.flushList()
                }
            }
            K_QUOTE -> flushInline()
            K_PRE -> closePre()
            K_TABLE -> closeTable()
            K_CELL -> closeCell()
            K_ROW -> closeRow()
            K_MARK -> b.pop()
            K_MUTE -> {
                mute--
                b.resetInline()
            }
            else -> {
                // 表题内容已进 cur，出栈时清掉，避免被算进第一个单元格
                if (f.tag == "caption") b.resetInline()
            }
        }
    }

    /**
     * 隐式闭合：`li`/`td`/`tr` 关同类；块级开始标签关最近的 `p`。
     *
     * [stopAt] 是关键：只有"同级"才隐式闭合。`<li>父<ul><li>子</li></ul></li>` 里内层 `li`
     * 不能把外层的 `li`（还有刚压进来的内层 `ul`）一起弹掉，所以在遇到列表/表格/引用这类
     * 容器帧时就该停手。
     */
    private fun implicitClose(name: String) {
        when (name) {
            "li" -> closeNearest(setOf("li"), STOP_CONTAINER)
            "td", "th" -> closeNearest(setOf("td", "th"), STOP_ROW)
            "tr" -> closeNearest(setOf("tr"), STOP_TABLE)
            "dt", "dd" -> closeNearest(setOf("dt", "dd"))
            else -> {}
        }
        if (name in HtmlToMarkdown.BLOCK_CLOSES_P) closeNearest(setOf("p"), STOP_CONTAINER)
    }

    private fun closeNearest(tags: Set<String>, stopAt: Set<Int> = emptySet()) {
        for (k in stack.indices.reversed()) {
            val f = stack[k]
            if (f.tag in tags) {
                while (stack.size > k) popTop()
                return
            }
            if (f.kind in stopAt) return
        }
    }

    // ---------- 文本与表格 ----------

    private fun text(s: String) {
        if (mute > 0) return
        val pre = preBuf
        if (pre != null) {
            pre.append(HtmlEntities.decodeEntities(s))
            return
        }
        // 正文同样要解实体：HTML 解析器早把 `&amp;` 还原成 `&`，这里等价还原
        b.appendText(HtmlEntities.decodeEntities(s))
    }

    /**
     * 单元格内的块：
     * `<td><p>x</p></td>` 的 `<p>` 离开时会走"落块"，直接 emit 会把单元格文字甩到表格外面
     * （顺序还会乱），所以单元格内一律先把块收进当前单元格缓冲，`</td>` 时再并入。
     */
    private fun emitBlock(block: String) {
        if (!inCell()) {
            b.emit(block)
            return
        }
        val buf = cellBufs[cellBufs.size - 1]
        if (buf.isNotEmpty()) buf.append(' ')
        buf.append(block)
    }

    private fun closeCell() {
        val extra = if (cellBufs.isEmpty()) null else cellBufs.removeAt(cellBufs.size - 1)
        val cur = b.curText().trim()
        b.resetInline()
        val head = extra?.toString()?.trim().orEmpty()
        val t = when {
            head.isEmpty() -> cur
            cur.isEmpty() -> head
            else -> head + " " + cur
        }
        row?.add(t)
    }

    private fun closeRow() {
        val r = row ?: return
        row = null
        // 全空行没有信息量（隐藏子树里的空表格也靠这条挡住），不进表格
        if (r.isNotEmpty() && r.any { it.isNotBlank() }) rows?.add(r)
    }

    private fun closeTable() {
        val rs = rows ?: return
        rows = null
        if (rs.isEmpty()) return
        val cols = rs.first().size.coerceIn(1, HtmlToMarkdown.MAX_TABLE_COLS)
        val sb = StringBuilder()
        sb.append('|')
        for (c in 0 until cols) {
            val h = cellText(rs.first().getOrNull(c)).ifBlank { "列${c + 1}" }
            sb.append(' ').append(h).append(" |")
        }
        sb.append('\n').append('|')
        for (c in 0 until cols) sb.append(" --- |")
        val limit = minOf(rs.size, HtmlToMarkdown.MAX_TABLE_ROWS)
        for (r in 1 until limit) {
            sb.append('\n').append('|')
            val cells = rs[r]
            for (c in 0 until cols) sb.append(' ').append(cellText(cells.getOrNull(c))).append(" |")
        }
        if (rs.size > HtmlToMarkdown.MAX_TABLE_ROWS) {
            // 省略行占满整行：首格给提示，其余留空，避免列数错乱
            sb.append('\n').append('|').append(" ...（表格过长，仅保留前 ").append(HtmlToMarkdown.MAX_TABLE_ROWS).append(" 行） |")
            for (c in 1 until cols) sb.append("  |")
        }
        emitBlock(sb.toString())
    }

    private fun cellText(s: String?): String =
        s.orEmpty().replace('\n', ' ').replace("|", "\\|").take(HtmlToMarkdown.MAX_CELL_CHARS)

    private fun closePre() {
        val buf = preBuf ?: return
        preBuf = null
        val lang = preLang.orEmpty()
        preLang = null
        val body = buf.toString().replace("\r\n", "\n").replace('\r', '\n').trim('\n')
        if (body.isBlank()) return
        // 正文内含围栏时加长围栏，避免提前闭合代码块
        var fence = HtmlToMarkdown.FENCE
        while (body.contains(fence)) fence += "`"
        emitBlock(fence + lang + "\n" + body + "\n" + fence)
    }

    /** 属性层面的隐藏判定（与浏览器侧的 [HIDDEN_CLASS_RULE] + `hidden`/`aria-hidden`/内联样式一致） */
    private fun hiddenTag(t: Tag): Boolean {
        val a = t.attrs
        if (a.containsKey("hidden")) return true
        if (a["aria-hidden"]?.trim()?.equals("true", ignoreCase = true) == true) return true
        if (a["type"]?.trim()?.equals("hidden", ignoreCase = true) == true) return true
        val style = a["style"].orEmpty().filterNot { it.isWhitespace() }.lowercase()
        if (style.contains("display:none") || style.contains("visibility:hidden") ||
            style.contains("opacity:0")
        ) {
            return true
        }
        val cls = a["class"].orEmpty().lowercase().split(' ', '\t', '\n')
        return cls.any { it.isNotBlank() && it in HtmlToMarkdown.HIDDEN_CLASSES }
    }

    // ---------- 行内收口与前缀 ----------

    private fun flushInline(prefixOverride: String? = null) {
        val txt = b.curText().trim()
        b.resetInline()
        if (txt.isEmpty()) return
        // 单元格内不加列表/引用前缀：那些前缀属于正文，不属于单元格
        if (inCell()) {
            emitBlock(txt)
            return
        }
        val prefix = prefixOverride ?: prefixOf()
        b.emit(prefix + txt)
    }

    private fun prefixOf(): String {
        val item = nearestItem()
        if (item != null && !item.bulletUsed) {
            item.bulletUsed = true
            return itemPrefix(item)
        }
        return quotePrefix()
    }

    private fun itemPrefix(item: Frame): String {
        val list = listOf(item) ?: return quotePrefix()
        val pad = "  ".repeat(list.level.coerceIn(0, HtmlToMarkdown.MAX_LIST_LEVEL - 1))
        return if (list.ordered) pad + (list.counter++) + ". " else "$pad- "
    }

    private fun quotePrefix(): String {
        val q = nearestQuote() ?: return ""
        return "> ".repeat(q.level)
    }

    private fun nearestItem(): Frame? {
        for (k in stack.indices.reversed()) {
            val f = stack[k]
            if (f.kind == K_ITEM) return f
            if (f.kind == K_TABLE || f.kind == K_PRE || f.kind == K_CELL) return null
        }
        return null
    }

    private fun nearestList(): Frame? {
        for (k in stack.indices.reversed()) if (stack[k].kind == K_LIST) return stack[k]
        return null
    }

    private fun nearestQuote(): Frame? {
        for (k in stack.indices.reversed()) if (stack[k].kind == K_QUOTE) return stack[k]
        return null
    }

    /** 该 item 归属的列表：栈里离它最近的下层列表帧 */
    private fun listOf(item: Frame): Frame? {
        val idx = stack.indexOfFirst { it === item }
        if (idx < 0) return null
        for (k in idx - 1 downTo 0) if (stack[k].kind == K_LIST) return stack[k]
        return null
    }

    private fun listDepth(): Int {
        var c = 0
        for (f in stack) if (f.kind == K_LIST) c++
        return c
    }

    // ---------- 链接绝对化 ----------

    /** 绝对化链：`<base>` → canonical → og:url → 调用方 baseUrl；都没有就原样输出相对值 */
    private fun absolute(href: String?): String? {
        val h = href?.trim().orEmpty()
        if (h.isEmpty() || h.startsWith("#")) return null
        val lower = h.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("about:")) return null
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:") || lower.startsWith("tel:")
        ) {
            return h
        }
        val base = baseUrl?.trim().orEmpty()
        if (base.isEmpty()) return h
        if (h.startsWith("//")) return schemeOf(base) + ":" + h
        if (h.startsWith("/")) return originOf(base) + h
        return baseDir(base) + h
    }

    private fun schemeOf(url: String): String {
        val i = url.indexOf("://")
        return if (i > 0) url.substring(0, i) else "https"
    }

    private fun originOf(url: String): String {
        val s = url.indexOf("://")
        if (s < 0) return url.trimEnd('/')
        val p = url.indexOf('/', s + 3)
        return if (p < 0) url else url.substring(0, p)
    }

    private fun baseDir(url: String): String {
        val s = url.indexOf("://")
        val from = if (s < 0) 0 else s + 3
        val p = url.lastIndexOf('/')
        return if (p >= from) url.substring(0, p + 1) else url + "/"
    }
}
