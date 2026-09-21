package com.phoneagent.core.text

/**
 * 自研 HTML → Markdown 转换器：让 AI 读网页时拿到结构化正文，而不是一坨压平的纯文本。
 *
 * 为什么自研：项目里没有任何 HTML 处理能力（无 jsoup），而 AI 读网页只拿到 `innerText` 压平后的
 * 一片文字——标题层级、列表、表格、代码块全丢。AI 本身极熟 Markdown（`write_doc` 产出的就是
 * Markdown），所以把网页转成 Markdown 是让 AI"读得懂"的最高性价比手段。
 *
 * 设计要点：
 * - **全程无递归**（显式标签栈），畸形页面 / 千层 div 都不会栈溢出；
 * - **无回溯正则**，一次线性扫描；[MAX_INPUT] 切片 + [MAX_DEPTH] 深度 + [HARD_CAP] 生成上限三重兜底；
 * - 输出与浏览器侧 JS 版（`BrowserScripts.READ`）**同构**：规则表就是下面这几张常量，
 *   JS 脚本由 [jsSet]/[jsArr]/[jsStr] 插值生成，从根上杜绝"两侧规则漂移"。
 *
 * 与 JS 侧**唯一允许的两处差异**（其余必须逐条一致）：
 * 1. JS 能看 `getComputedStyle`，能识别"被样式表藏起来"的元素；Kotlin 只能看属性
 *    （`hidden` / `aria-hidden` / `type=hidden` / `style=` / class token）→ 被 CSS 藏的文本可能漏出；
 * 2. JS 用浏览器原生能力解码实体（是所有命名实体的超集），Kotlin 只认下面这 32 个。
 */
object HtmlToMarkdown {

    // ==================== 对外常量 ====================

    /** AI 可见预算：Markdown 超过这个长度就按块边界截断 */
    const val BUDGET = 4000

    /** 生成上限：转换阶段最多产出多少字符（留出截断提示行的余量） */
    const val HARD_CAP = 8000

    /** 输入切片上限：再大的 HTML 只取前段，防超长页面拖垮决策 */
    const val MAX_INPUT = 1_500_000

    /** 标签嵌套深度上限：超过即整层跳过（防畸形页面） */
    const val MAX_DEPTH = 256

    /** 表格最多保留行数（超出追加省略行） */
    const val MAX_TABLE_ROWS = 30

    /** 表格列数上限（按第一个 tr 的单元格数取，clamp 到这个值） */
    const val MAX_TABLE_COLS = 12

    /** 单元格内文本上限 */
    const val MAX_CELL_CHARS = 120

    /** 列表最多缩进层数（超出拍平） */
    const val MAX_LIST_LEVEL = 6

    /** 截断提示行：让 AI 明确知道"没读完"，避免它以为页面就这么点内容 */
    const val TRUNCATED_NOTE = "> 内容过长，已截断"

    /** 代码块围栏字面量（两侧同源） */
    const val FENCE = "```"

    // ==================== 共享规则表（两侧同源） ====================

    /**
     * 整块丢弃（含子树）：脚本样式、嵌入物、表单控件、导航噪声。
     * `head` 也在其中——它只有元信息，正文转换前会先把 title/base/canonical/og:url 抽走。
     */
    const val DROP_RULE =
        "script style noscript template svg canvas iframe object embed audio video map area " +
            "meta link base head title input select option textarea button label nav footer aside"

    /** 块级标签：进入/离开都要断开当前行内片段（"透明分段"，不产标记） */
    const val BLOCK_RULE =
        "address article body blockquote caption dd details div dl dt fieldset figcaption figure " +
            "form h1 h2 h3 h4 h5 h6 header hr html li main ol p pre section summary table tbody " +
            "td tfoot th thead tr ul"

    /** 行内标签：`a/img` 另有专用处理，其余按 [MARK_RULE] 加标记或透明穿过 */
    const val INLINE_RULE =
        "a abbr b big cite code del em font i img ins kbd mark q s samp small span strike strong " +
            "sub sup time u var"

    /** 自闭合（无子树）标签 */
    const val VOID_RULE = "area base br col embed hr img input link meta param source track wbr"

    /** 行内标记：`标签:Markdown 包裹符`（用冒号分隔，因为包裹符本身含 `=`） */
    const val MARK_RULE =
        "strong:** b:** em:* i:* del:~~ s:~~ strike:~~ mark:== code:` kbd:` samp:`"

    /** 文本中需要加反斜杠转义的字符 */
    const val ESCAPE_RULE = "\\ ` * _ [ ]"

    /** 隐藏元素判定的 class token（命中即整棵子树丢弃） */
    const val HIDDEN_CLASS_RULE = "hidden hide sr-only visually-hidden d-none invisible"

    // ---------------- 由规则表派生的查询结构 ----------------

    private fun setOfRule(rule: String): Set<String> =
        rule.split(' ').filter { it.isNotBlank() }.toHashSet()

    private val DROP_TAGS = setOfRule(DROP_RULE)
    private val BLOCK_TAGS = setOfRule(BLOCK_RULE)
    private val VOID_TAGS = setOfRule(VOID_RULE)
    private val HIDDEN_CLASSES = setOfRule(HIDDEN_CLASS_RULE)

    /** 除 `p` 之外的块级开始标签，出现时关闭最近一个未闭合的 `p` */
    private val BLOCK_CLOSES_P: Set<String> = BLOCK_TAGS - "p" - "body" - "html"

    private fun marksOf(rule: String): Map<String, String> =
        rule.split(' ')
            .filter { it.isNotBlank() && it.contains(':') }
            .associate { it.substringBefore(':') to it.substringAfter(':') }

    private val MARK_TAGS = marksOf(MARK_RULE)

    /** 固定 32 个命名实体；未列入表的一律原样输出（不猜） */
    private val ENTITIES: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026", "laquo" to "\u00AB",
        "raquo" to "\u00BB", "ldquo" to "\u201C", "rdquo" to "\u201D", "lsquo" to "\u2018",
        "rsquo" to "\u2019", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "deg" to "\u00B0", "plusmn" to "\u00B1", "times" to "\u00D7", "divide" to "\u00F7",
        "middot" to "\u00B7", "bull" to "\u2022", "sect" to "\u00A7", "para" to "\u00B6",
        "dagger" to "\u2020", "frac12" to "\u00BD", "larr" to "\u2190", "rarr" to "\u2192",
        "harr" to "\u2194", "permil" to "\u2030",
    )

    /** 弱信号用的 HTML 特征标签名（用于把 XML/UI dump 排除掉） */
    private val HTML_HINT_TAGS = listOf("div", "span", "a", "p", "li", "td", "ul", "table", "h1", "br", "img")

    // ==================== JS 侧生成辅助（规则表同源的落点） ====================

    /** 生成 JS 集合字面量 `{'script':1,'style':1}`，供浏览器侧脚本按标签名查表 */
    fun jsSet(rule: String): String =
        "{" + rule.split(' ').filter { it.isNotBlank() }.joinToString(",") { jsStr(it) + ":1" } + "}"

    /** 生成 JS 数组字面量 `['\\','`','*']` */
    fun jsArr(rule: String): String =
        "[" + rule.split(' ').filter { it.isNotBlank() }.joinToString(",") { jsStr(it) } + "]"

    /** 生成 JS 的 `[['strong','**'],...]` 查表数组（沿用 [MARK_RULE] 的 `tag:包裹符` 记法） */
    fun jsMarkPairs(rule: String): String =
        "[" + rule.split(' ')
            .filter { it.isNotBlank() && it.contains(':') }
            .joinToString(",") {
                "[" + jsStr(it.substringBefore(':')) + "," + jsStr(it.substringAfter(':')) + "]"
            } + "]"

    /** 生成 JS 单引号字符串字面量（转义反斜杠 / 单引号 / 换行） */
    fun jsStr(s: String): String = buildString {
        append('\'')
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
        append('\'')
    }

    // ==================== 对外 API ====================

    /**
     * 转换结果。
     * @param title 页面标题（`<title>`），没有则为空串
     * @param markdown 结构化 Markdown（已按 [budget]/[HARD_CAP] 截断）
     * @param truncated 是否发生了截断（AI 据此知道"没读完"）
     */
    data class Result(val title: String, val markdown: String, val truncated: Boolean)

    /**
     * 嗅探：这段文本是不是 HTML。
     *
     * 强信号（开头即判真）：`<!doctype html` / `<html` / `<head` / `<body`。
     * 弱信号（三条同时满足才判真）：`<` 占比 < 15%、至少 3 个完整标签、至少命中 2 个 HTML 特征标签。
     * 三条弱信号是刻意的——`adb shell dumpsys` / `uiautomator dump` 的 XML、JSON 里内嵌的 HTML
     * 字符串、日志里的标签片段都会被它们挡掉，避免"把接口 JSON 转成了 Markdown"。
     */
    fun isHtml(raw: String): Boolean {
        val s = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (s.length < 8) return false
        // JSON 优先排除：`{...}` / `[...]` 开头一律不算 HTML
        if (s[0] == '{' || s[0] == '[') return false
        // 已转换过的 Markdown（含内联链接）不再嗅探，防二次转换
        if (s.contains("](http") && !s.contains("<p") && !s.contains("<div")) return false
        val head = s.take(200).lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html") ||
            head.startsWith("<head") || head.startsWith("<body")
        ) {
            return true
        }
        var lt = 0
        for (c in s) if (c == '<') lt++
        if (lt * 100 / s.length >= 15) return false
        if (countCompleteTags(s) < 3) return false
        var hits = 0
        val lower = s.lowercase()
        for (t in HTML_HINT_TAGS) {
            if (lower.contains("<$t ") || lower.contains("<$t>") || lower.contains("</$t>")) hits++
            if (hits >= 2) return true
        }
        return false
    }

    /** 完整标签计数：`<` 后必须紧跟字母或 `/`，并在 200 字符内闭合 */
    private fun countCompleteTags(s: String): Int {
        var count = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val lt = s.indexOf('<', i)
            if (lt < 0) break
            val c = if (lt + 1 < n) s[lt + 1] else ' '
            if (!(c.isLetter() || c == '/')) { i = lt + 1; continue }
            val gt = s.indexOf('>', lt + 1)
            if (gt < 0 || gt - lt > 200) { i = lt + 1; continue }
            count++
            i = gt + 1
        }
        return count
    }

    /**
     * HTML → Markdown。
     * @param baseUrl 相对链接的兜底基准（fetch 链路传请求的 uri；浏览器链路走 DOM 天然是绝对地址）
     * @param budget AI 可见预算，默认 [BUDGET]
     */
    fun convert(html: String, baseUrl: String? = null, budget: Int = BUDGET): Result {
        if (html.isBlank()) return Result("", "", false)
        val input = if (html.length > MAX_INPUT) html.substring(0, MAX_INPUT) else html
        val meta = Meta()
        val cleaned = stripNoise(input, meta)
        val absBase = meta.base ?: meta.canonical ?: meta.ogUrl ?: baseUrl
        val builder = MdBuilder()
        Converter(absBase, builder).run(tokenize(cleaned))
        val full = builder.render()
        val cut = takeBlocks(full, budget)
        return Result(
            title = meta.title.orEmpty(),
            markdown = cut,
            truncated = builder.truncated || cut.length < full.length,
        )
    }

    /**
     * 按块边界截断——**截断的唯一实现点**（浏览器侧只做 8000 安全上限，不参与 AI 预算截断，
     * 保证两条链路行为一致）。
     *
     * 优先切在空行（块分隔），退而切在换行（行分隔）；若落点仍在 `[文字](网址)` 中间，
     * 继续回退到该链接之前，**绝不产生半个链接**。
     */
    fun takeBlocks(markdown: String, budget: Int): String {
        val md = markdown.trim()
        if (md.length <= budget) return md
        val limit = budget - TRUNCATED_NOTE.length - 2
        if (limit <= 0) return TRUNCATED_NOTE
        val head = md.substring(0, limit)
        val cut = head.lastIndexOf("\n\n").takeIf { it > 0 }
            ?: head.lastIndexOf('\n').takeIf { it > 0 }
            ?: head.length
        val body = repairCut(head.substring(0, cut)).trimEnd()
        if (body.isEmpty()) return TRUNCATED_NOTE
        return body + "\n\n" + TRUNCATED_NOTE
    }

    /** 落点修复：末尾处在 `](...)` 或行内标记中间时向前回退到安全位置 */
    private fun repairCut(s: String): String {
        val open = s.lastIndexOf("](")
        if (open >= 0 && s.indexOf(')', open) < 0) {
            val b = maxOf(s.lastIndexOf('\n', open), s.lastIndexOf(' ', open))
            return if (b > 0) s.substring(0, b) else s.substring(0, open)
        }
        // 尾部零散的强调符会让 Markdown 渲染错乱，一并去掉
        return s.trimEnd('*', '`', '~', '=')
    }

    // ==================== 第一段：stripNoise ====================

    private class Meta {
        var title: String? = null
        var base: String? = null
        var canonical: String? = null
        var ogUrl: String? = null
    }

    /** 解析出来的一个标签 */
    private class Tag(
        val name: String,
        val attrs: Map<String, String>,
        /** 标签结束位置（`>` 之后） */
        val end: Int,
        val selfClosing: Boolean,
        val endTag: Boolean,
    )

    /**
     * 单遍扫描：删注释 / DOCTYPE / CDATA / 处理指令，删 [DROP_RULE] 整棵子树，
     * 顺带把 `title/base/canonical/og:url` 抽进 [meta]。
     * 保留所有非丢弃标签（含结束标签）的原样文本，交给第二段分词。
     */
    private fun stripNoise(html: String, meta: Meta): String {
        val out = StringBuilder(html.length / 2 + 16)
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                out.append(html, i, n)
                break
            }
            if (lt > i) out.append(html, i, lt)
            if (html.startsWith("<!--", lt)) {
                val e = html.indexOf("-->", lt + 4)
                i = if (e < 0) n else e + 3
                continue
            }
            if (html.startsWith("<![CDATA[", lt)) {
                val e = html.indexOf("]]>", lt + 9)
                i = if (e < 0) n else e + 3
                continue
            }
            if (lt + 1 < n && (html[lt + 1] == '!' || html[lt + 1] == '?')) {
                val e = html.indexOf('>', lt + 2)
                i = if (e < 0) n else e + 1
                continue
            }
            val tag = parseTag(html, lt)
            if (tag == null) {
                out.append('<')
                i = lt + 1
                continue
            }
            if (!tag.endTag && tag.name in DROP_TAGS) {
                captureMeta(html, tag, meta)
                i = skipSubtree(html, tag)
                continue
            }
            out.append(html, lt, tag.end)
            i = tag.end
        }
        return out.toString()
    }

    /** 从被丢弃的 head 子树里抢救元信息：title / base href / canonical / og:url */
    private fun captureMeta(html: String, tag: Tag, meta: Meta) {
        when (tag.name) {
            "title" -> if (meta.title == null) {
                val e = indexOfEndTag(html, "title", tag.end)
                val raw = html.substring(tag.end, e)
                meta.title = decodeEntities(raw).replace(WS_RE, " ").trim().ifBlank { null }
            }
            "base" -> if (meta.base.isNullOrBlank()) meta.base = tag.attrs["href"]
            "link" -> {
                val rel = tag.attrs["rel"].orEmpty().lowercase()
                if (meta.canonical.isNullOrBlank() && rel.split(' ', '\t').any { it == "canonical" }) {
                    meta.canonical = tag.attrs["href"]
                }
            }
            "meta" -> {
                val prop = (tag.attrs["property"] ?: tag.attrs["name"]).orEmpty().lowercase()
                if (meta.ogUrl.isNullOrBlank() && prop == "og:url") meta.ogUrl = tag.attrs["content"]
            }
        }
    }

    private fun indexOfEndTag(src: String, name: String, from: Int): Int {
        val needle = "</$name"
        var i = from
        while (i < src.length) {
            val p = src.indexOf(needle, i, ignoreCase = true)
            if (p < 0) return src.length
            val after = if (p + needle.length < src.length) src[p + needle.length] else '>'
            if (after == '>' || after.isWhitespace()) return p
            i = p + needle.length
        }
        return src.length
    }

    /** 跳过整棵子树：同名前缀计数，直到配平；窗口内找不到闭合就退回不丢（防未闭合标签吃掉整篇） */
    private fun skipSubtree(html: String, tag: Tag): Int {
        if (tag.selfClosing || tag.name in VOID_TAGS) return tag.end
        val name = tag.name
        var depth = 1
        var i = tag.end
        val window = minOf(html.length, tag.end + SUBTREE_WINDOW)
        while (i < window && depth > 0) {
            val lt = html.indexOf('<', i)
            if (lt < 0 || lt >= window) break
            if (html.startsWith("<!--", lt)) {
                val e = html.indexOf("-->", lt + 4)
                i = if (e < 0) window else e + 3
                continue
            }
            if (lt + 1 < html.length && (html[lt + 1] == '!' || html[lt + 1] == '?')) {
                val e = html.indexOf('>', lt + 2)
                i = if (e < 0) window else e + 1
                continue
            }
            val t = parseTag(html, lt)
            if (t == null) {
                i = lt + 1
                continue
            }
            if (t.name == name) {
                if (t.endTag) depth-- else if (!t.selfClosing) depth++
            }
            i = if (t.end >= lt + 1) t.end else lt + 1
        }
        return if (depth == 0) i else tag.end
    }

    private const val SUBTREE_WINDOW = 500_000
    private val WS_RE = Regex("\\s+")

    // ==================== 第二段：tokenize ====================

    private sealed class Tok {
        class Text(val s: String) : Tok()
        class Start(val tag: Tag) : Tok()
        class End(val name: String) : Tok()
    }

    private fun tokenize(clean: String): List<Tok> {
        val list = ArrayList<Tok>(256)
        var i = 0
        val n = clean.length
        while (i < n) {
            val lt = clean.indexOf('<', i)
            if (lt < 0) {
                list.add(Tok.Text(clean.substring(i)))
                break
            }
            if (lt > i) list.add(Tok.Text(clean.substring(i, lt)))
            val t = parseTag(clean, lt)
            if (t == null) {
                // `<` 后不是标签起始（如 "a < b"）：按普通文本处理，绝不吞字
                list.add(Tok.Text("<"))
                i = lt + 1
                continue
            }
            if (t.endTag) list.add(Tok.End(t.name)) else list.add(Tok.Start(t))
            i = if (t.end >= lt + 1) t.end else lt + 1
        }
        return list
    }

    /**
     * 手写标签扫描器（属性小状态机）。
     * 关键点：**引号内的 `>` 不当标签结束**（`<a title="a > b">` 是常见坑）。
     */
    private fun parseTag(src: String, lt: Int): Tag? {
        val n = src.length
        var i = lt + 1
        var endTag = false
        if (i < n && src[i] == '/') {
            endTag = true
            i++
        }
        if (i >= n || !src[i].isLetter()) return null
        val nameStart = i
        while (i < n && (src[i].isLetterOrDigit() || src[i] == '-' || src[i] == ':' || src[i] == '_')) i++
        val name = src.substring(nameStart, i).lowercase()
        val attrs = HashMap<String, String>(4)
        var selfClosing = false
        var closed = false
        while (i < n) {
            while (i < n && src[i].isWhitespace()) i++
            if (i >= n) break
            val c = src[i]
            if (c == '>') {
                i++
                closed = true
                break
            }
            if (c == '/') {
                selfClosing = true
                i++
                continue
            }
            val an = i
            while (i < n && !src[i].isWhitespace() && src[i] != '=' && src[i] != '>' && src[i] != '/') i++
            if (i == an) {
                i++
                continue
            }
            val aname = src.substring(an, i).lowercase()
            while (i < n && src[i].isWhitespace()) i++
            var value = ""
            if (i < n && src[i] == '=') {
                i++
                while (i < n && src[i].isWhitespace()) i++
                if (i < n && (src[i] == '"' || src[i] == '\'')) {
                    val q = src[i]
                    i++
                    val vs = i
                    while (i < n && src[i] != q) i++
                    value = src.substring(vs, i)
                    if (i < n) i++
                } else {
                    val vs = i
                    while (i < n && !src[i].isWhitespace() && src[i] != '>') i++
                    value = src.substring(vs, i)
                }
            }
            if (aname.isNotEmpty() && !endTag && !attrs.containsKey(aname)) {
                attrs[aname] = decodeEntities(value)
            }
        }
        if (!closed) return null
        return Tag(name, attrs, i, selfClosing, endTag)
    }

    // ==================== 第三段：Converter（显式栈，无递归） ====================

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

    private class Frame(
        val tag: String,
        val kind: Int,
        /** 标题级别 / 引用深度 / 列表缩进 */
        var level: Int = 0,
        val ordered: Boolean = false,
        var counter: Int = 1,
        var bulletUsed: Boolean = false,
    )

    private class Converter(private val baseUrl: String?, private val b: MdBuilder) {

        private val stack = ArrayList<Frame>(32)
        private var preBuf: StringBuilder? = null
        private var preLang: String? = null
        private var rows: ArrayList<ArrayList<String>>? = null
        private var row: ArrayList<String>? = null
        private var stop = false

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

        private fun start(t: Tag) {
            val name = t.name
            if (name in VOID_TAGS) {
                when (name) {
                    // pre 里的 <br> 是真实换行，不能折叠成空格
                    "br" -> preBuf?.append('\n') ?: b.br()
                    "img" -> image(t)
                    "hr" -> {
                        flushInline()
                        b.emit("---")
                    }
                    else -> {}
                }
                return
            }
            // 深度兜底：超限时只占位不展开，保证结束标签配平
            if (stack.size >= MAX_DEPTH) {
                stack.add(Frame(name, K_SKIP))
                return
            }
            // pre 内部：只认 <code class="language-x"> 取语言串，其余标签一律不加标记
            if (preBuf != null) {
                if (name == "code" && preLang == null) preLang = codeLang(t)
                stack.add(Frame(name, K_SKIP))
                return
            }
            implicitClose(name)
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
                "td", "th" -> stack.add(Frame(name, K_CELL))
                "a" -> {
                    val url = absolute(t.attrs["href"])
                    if (url == null) b.push("", "") else b.push("[", "]($url)")
                    stack.add(Frame(name, K_MARK))
                }
                else -> {
                    val mark = MARK_TAGS[name]
                    if (mark != null) {
                        b.push(mark, mark)
                        stack.add(Frame(name, K_MARK))
                    } else {
                        if (name in BLOCK_TAGS) flushInline()
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
                else -> {
                    // 表题内容已进 cur，出栈时清掉，避免被算进第一个单元格
                    if (f.tag == "caption") b.resetInline()
                }
            }
        }

        /** 隐式闭合：`li`/`td`/`tr` 关同类；块级开始标签关最近的 `p` */
        private fun implicitClose(name: String) {
            when (name) {
                "li" -> closeNearest(setOf("li"))
                "td", "th" -> closeNearest(setOf("td", "th"))
                "tr" -> closeNearest(setOf("tr"))
                "dt", "dd" -> closeNearest(setOf("dt", "dd"))
                else -> {}
            }
            if (name in BLOCK_CLOSES_P) closeNearest(setOf("p"))
        }

        private fun closeNearest(tags: Set<String>) {
            for (k in stack.indices.reversed()) {
                if (stack[k].tag in tags) {
                    while (stack.size > k) popTop()
                    return
                }
            }
        }

        // ---------- 文本与表格 ----------

        private fun text(s: String) {
            val pre = preBuf
            if (pre != null) {
                pre.append(decodeEntities(s))
                return
            }
            b.appendText(s)
        }

        private fun closeCell() {
            val t = b.curText().trim()
            b.resetInline()
            row?.add(t)
        }

        private fun closeRow() {
            val r = row ?: return
            row = null
            if (r.isNotEmpty()) rows?.add(r)
        }

        private fun closeTable() {
            val rs = rows ?: return
            rows = null
            if (rs.isEmpty()) return
            val cols = rs.first().size.coerceIn(1, MAX_TABLE_COLS)
            val sb = StringBuilder()
            sb.append('|')
            for (c in 0 until cols) {
                val h = cellText(rs.first().getOrNull(c)).ifBlank { "列${c + 1}" }
                sb.append(' ').append(h).append(" |")
            }
            sb.append('\n').append('|')
            for (c in 0 until cols) sb.append(" --- |")
            val limit = minOf(rs.size, MAX_TABLE_ROWS)
            for (r in 1 until limit) {
                sb.append('\n').append('|')
                val cells = rs[r]
                for (c in 0 until cols) sb.append(' ').append(cellText(cells.getOrNull(c))).append(" |")
            }
            if (rs.size > MAX_TABLE_ROWS) {
                // 省略行占满整行：首格给提示，其余留空，避免列数错乱
                sb.append('\n').append('|').append(" ...（表格过长，仅保留前 ").append(MAX_TABLE_ROWS).append(" 行） |")
                for (c in 1 until cols) sb.append("  |")
            }
            b.emit(sb.toString())
        }

        private fun cellText(s: String?): String =
            s.orEmpty().replace('\n', ' ').replace("|", "\\|").take(MAX_CELL_CHARS)

        private fun closePre() {
            val buf = preBuf ?: return
            preBuf = null
            val lang = preLang.orEmpty()
            preLang = null
            val body = buf.toString().replace("\r\n", "\n").replace('\r', '\n').trim('\n')
            if (body.isBlank()) return
            // 正文内含围栏时加长围栏，避免提前闭合代码块
            var fence = FENCE
            while (body.contains(fence)) fence += "`"
            b.emit(fence + lang + "\n" + body + "\n" + fence)
        }

        // ---------- 行内收口与前缀 ----------

        private fun flushInline(prefixOverride: String? = null) {
            val txt = b.curText().trim()
            b.resetInline()
            if (txt.isEmpty()) return
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
            val pad = "  ".repeat(list.level.coerceIn(0, MAX_LIST_LEVEL - 1))
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

    // ==================== 第四段：MdBuilder ====================

    /**
     * Markdown 累积器：块序列 + 空白折叠 + CJK 不插空格 + 转义 + 行内标记栈 + 列表缓冲。
     * 超过 [HARD_CAP] 立刻置 [truncated]，由 Converter 早停。
     */
    private class MdBuilder {

        private val out = StringBuilder(4096)
        private val listBuf = StringBuilder(512)
        private val marks = ArrayList<Mark>(8)

        /** 当前行内片段 */
        private val cur = StringBuilder(256)

        /** 已产出字符数（含尚未落盘的列表缓冲） */
        private var emitted = 0
        var truncated = false
        /** 正在列表内的层数：>0 时块写进列表缓冲，外层列表关闭时一次性落盘 */
        var inList = 0

        private class Mark(val open: String, val close: String, val anchor: Int)

        // ---------- 行内 ----------

        fun curText(): String = cur.toString()

        fun resetInline() {
            cur.setLength(0)
            marks.clear()
        }

        fun raw(s: String) {
            cur.append(s)
        }

        fun push(open: String, close: String) {
            marks.add(Mark(open, close, cur.length))
            cur.append(open)
        }

        fun pop() {
            if (marks.isEmpty()) return
            val m = marks.removeAt(marks.size - 1)
            if (cur.length > m.anchor + m.open.length) cur.append(m.close) else cur.setLength(m.anchor)
        }

        /** `<br>`：列表内退化成空格（保缩进），普通段落里换行 */
        fun br() {
            if (inList > 0) {
                if (cur.isNotEmpty() && cur.last() != ' ') cur.append(' ')
                return
            }
            while (cur.isNotEmpty() && cur.last() == ' ') cur.setLength(cur.length - 1)
            if (cur.isNotEmpty()) cur.append('\n')
        }

        /** 空白折叠 + CJK 规则 + 转义 */
        fun appendText(s: String) {
            var pendingSpace = false
            var i = 0
            val n = s.length
            while (i < n) {
                val ch = s[i]
                if (ch.isWhitespace() || ch == '\u00A0' || ch == '\u200B') {
                    pendingSpace = true
                    i++
                    continue
                }
                if (pendingSpace) {
                    if (cur.isNotEmpty() && cur.last() != '\n' && needsSpace(cur.last(), ch)) cur.append(' ')
                    pendingSpace = false
                }
                appendChar(ch, s, i)
                i++
            }
            if (cur.length > HARD_CAP + 1024) truncated = true
        }

        private fun appendChar(ch: Char, src: String, i: Int) {
            if (ch == '\\' || ch == '`' || ch == '*' || ch == '_' || ch == '[' || ch == ']') {
                cur.append('\\').append(ch)
                return
            }
            if (atLineStart()) {
                if (ch in "#-+>~") {
                    cur.append('\\').append(ch)
                    return
                }
                if (ch.isDigit() && looksLikeOrderedMarker(src, i)) {
                    cur.append('\\').append(ch)
                    return
                }
            }
            cur.append(ch)
        }

        private fun atLineStart(): Boolean = cur.isEmpty() || cur.last() == '\n'

        private fun looksLikeOrderedMarker(src: String, i: Int): Boolean {
            var k = i
            while (k < src.length && src[k].isDigit()) k++
            if (k >= src.length || src[k] != '.') return false
            return k + 1 >= src.length || src[k + 1].isWhitespace()
        }

        /** CJK 之间不插空格，其余插一个 */
        private fun needsSpace(prev: Char, next: Char): Boolean = !(isCjk(prev) && isCjk(next))

        private fun isCjk(c: Char): Boolean {
            val x = c.code
            return (x in 0x2E80..0x9FFF) || (x in 0x3000..0x303F) || (x in 0xFF00..0xFFEF)
        }

        // ---------- 块 ----------

        fun emit(block: String) {
            // 只去尾部空白：行首缩进是列表层级的一部分（嵌套 li 靠它保层级）
            val b = block.trimEnd()
            if (b.isBlank()) return
            if (inList > 0) {
                if (listBuf.isNotEmpty()) listBuf.append('\n')
                listBuf.append(b)
            } else {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(b)
            }
            emitted = out.length + listBuf.length
            if (emitted > HARD_CAP) truncated = true
        }

        fun flushList() {
            if (listBuf.isEmpty()) return
            val block = listBuf.toString()
            listBuf.setLength(0)
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(block)
            emitted = out.length
            if (emitted > HARD_CAP) truncated = true
        }

        fun render(): String {
            flushList()
            return out.toString().trim()
        }
    }

    // ==================== 实体解码 ====================

    private fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val amp = s.indexOf('&', i)
            if (amp < 0 || amp == n - 1) {
                sb.append(s, i, n)
                break
            }
            sb.append(s, i, amp)
            val semi = s.indexOf(';', amp + 1)
            if (semi < 0 || semi - amp > 12) {
                sb.append('&')
                i = amp + 1
                continue
            }
            val rep = decodeOne(s.substring(amp + 1, semi))
            if (rep == null) {
                // 未列入表的命名实体原样输出，不猜
                sb.append('&')
                i = amp + 1
                continue
            }
            sb.append(rep)
            i = semi + 1
        }
        return sb.toString()
    }

    private fun decodeOne(body: String): String? = when {
        body.startsWith("#x") || body.startsWith("#X") ->
            codePoint(body.substring(2), 16)
        body.startsWith("#") ->
            codePoint(body.substring(1), 10)
        else -> ENTITIES[body.lowercase()]
    }

    /** 非法码点（越界 / 代理区）原样保留，不崩 */
    private fun codePoint(digits: String, radix: Int): String? {
        val v = digits.toIntOrNull(radix) ?: return null
        if (v !in 1..0x10FFFF) return null
        if (v in 0xD800..0xDFFF) return null
        return String(Character.toChars(v))
    }
}