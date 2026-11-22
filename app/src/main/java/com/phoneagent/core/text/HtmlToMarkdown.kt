package com.phoneagent.core.text

import com.phoneagent.core.AgentBudgets

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
 * - 输出与浏览器侧 JS 版（`feature/browser/script/ReadScript.kt`）**同构**：规则表就是下面这几张常量，
 *   JS 脚本由 [jsSet]/[jsArr]/[jsStr] 插值生成，从根上杜绝"两侧规则漂移"。
 *
 * 结构：本文件是**门面**——对外常量、两侧同源的规则表、嗅探与预算截断都在这里；
 * 转换本身按阶段拆在同包的四个文件里，每个阶段都是纯函数或纯数据结构、可单独测试：
 * - `HtmlScanner.kt` 第一、二段：清洗（`stripNoise`）与分词（`tokenize`）；
 * - `HtmlEntities.kt`：实体解码；
 * - `HtmlConverter.kt` 第三段：显式帧栈把词元落成块；
 * - `HtmlMdBuilder.kt` 第四段：Markdown 累积器（空白折叠 / CJK 不插空格 / 转义）。
 *
 * 与 JS 侧**唯一允许的两处差异**（其余必须逐条一致）：
 * 1. JS 能看 `getComputedStyle`，能识别"被样式表藏起来"的元素；Kotlin 只能看属性
 *    （`hidden` / `aria-hidden` / `type=hidden` / `style=` / class token）→ 被 CSS 藏的文本可能漏出；
 * 2. JS 用浏览器原生能力解码实体（是所有命名实体的超集），Kotlin 只认下面这 32 个。
 */
object HtmlToMarkdown {

    // ==================== 对外常量 ====================

    /** AI 可见预算：Markdown 超过这个长度就按块边界截断（数值唯一来源：AgentBudgets.REINJECT_MAX_CHARS） */
    const val BUDGET = AgentBudgets.REINJECT_MAX_CHARS

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
     *
     * **`html` 与 `body` 绝不在此表**：它们是正文的容器，丢它们等于丢整篇（曾被误列入，
     * 导致整页转出空 Markdown，`ReadScript.READ` 从 `body` 起步会当场空手而归）。
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

    internal val DROP_TAGS = setOfRule(DROP_RULE)
    internal val BLOCK_TAGS = setOfRule(BLOCK_RULE)
    internal val VOID_TAGS = setOfRule(VOID_RULE)
    internal val HIDDEN_CLASSES = setOfRule(HIDDEN_CLASS_RULE)

    /** 除 `p` 之外的块级开始标签，出现时关闭最近一个未闭合的 `p` */
    internal val BLOCK_CLOSES_P: Set<String> = BLOCK_TAGS - "p" - "body" - "html"

    private fun marksOf(rule: String): Map<String, String> =
        rule.split(' ')
            .filter { it.isNotBlank() && it.contains(':') }
            .associate { it.substringBefore(':') to it.substringAfter(':') }

    internal val MARK_TAGS = marksOf(MARK_RULE)

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
        val input = if (html.length > MAX_INPUT) safeSubstring(html, MAX_INPUT) else html
        val meta = Meta()
        val cleaned = HtmlScanner.stripNoise(input, meta)
        val absBase = meta.base ?: meta.canonical ?: meta.ogUrl ?: baseUrl
        val builder = MdBuilder()
        Converter(absBase, builder).run(HtmlScanner.tokenize(cleaned))
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
        val head = safeSubstring(md, limit)
        val cut = head.lastIndexOf("\n\n").takeIf { it > 0 }
            ?: head.lastIndexOf('\n').takeIf { it > 0 }
            ?: head.length
        val body = repairCut(head.substring(0, cut)).trimEnd()
        if (body.isEmpty()) return TRUNCATED_NOTE
        return body + "\n\n" + TRUNCATED_NOTE
    }

    /**
     * 代理对安全截断：落点前一个字符若是高代理项（emoji/生僻字等增补字符被切成两半）则回退一位，
     * 保证截断结果不含残缺字符。
     */
    private fun safeSubstring(s: String, index: Int): String {
        if (index >= s.length) return s
        if (index <= 0) return ""
        return if (s[index - 1].isHighSurrogate()) s.substring(0, index - 1) else s.substring(0, index)
    }

    /** 落点修复：末尾处在 `](...)`、未配平的 `[` 或行内标记中间时向前回退到安全位置 */
    private fun repairCut(s: String): String {
        var res = s
        val open = s.lastIndexOf("](")
        if (open >= 0 && s.indexOf(')', open) < 0) {
            val b = maxOf(s.lastIndexOf('\n', open), s.lastIndexOf(' ', open))
            res = if (b > 0) s.substring(0, b) else s.substring(0, open)
        }
        // 尾部零散的强调符会让 Markdown 渲染错乱，一并去掉
        res = res.trimEnd('*', '`', '~', '=')
        // 剩下的孤立 `[` 同样别留给 AI（半个链接语法比没有更误导）
        val lb = res.lastIndexOf('[')
        if (lb >= 0 && res.count { it == '[' } > res.count { it == ']' }) res = res.substring(0, lb)
        return res
    }
}
