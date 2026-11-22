package com.phoneagent.core.text

/** [HtmlScanner.stripNoise] 顺手抽出的页面元信息（标题与链接绝对化基准） */
internal class Meta {
    var title: String? = null
    var base: String? = null
    var canonical: String? = null
    var ogUrl: String? = null
}

/** 手写扫描器解析出来的一个标签 */
internal class Tag(
    val name: String,
    val attrs: Map<String, String>,
    /** 标签结束位置（`>` 之后） */
    val end: Int,
    val selfClosing: Boolean,
    val endTag: Boolean,
)

/** 清洗后的线性词元：文本 / 开始标签 / 结束标签 */
internal sealed class Tok {
    class Text(val s: String) : Tok()
    class Start(val tag: Tag) : Tok()
    class End(val name: String) : Tok()
}

/**
 * 第一、二段：清洗（[stripNoise]）与分词（[tokenize]）。
 *
 * 全程显式游标、无递归、无回溯正则，畸形页面也不会栈溢出。
 */
internal object HtmlScanner {

    // ==================== 第一段：stripNoise ====================

    /**
     * 单遍扫描：删注释 / DOCTYPE / CDATA / 处理指令，删 [DROP_RULE] 整棵子树，
     * 顺带把 `title/base/canonical/og:url` 抽进 [meta]。
     * 保留所有非丢弃标签（含结束标签）的原样文本，交给第二段分词。
     */
    internal fun stripNoise(html: String, meta: Meta): String {
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
            if (!tag.endTag && tag.name in HtmlToMarkdown.DROP_TAGS) {
                // script/style/noscript 的内容是原样文本（JS/样式里的伪标签会干扰子树配平），
                // 单独整段跳过：绝不能让脚本/样式内容被当正文吐给 AI
                if (tag.name in RAW_TEXT_TAGS) {
                    i = skipRawText(html, tag)
                    continue
                }
                val end = skipSubtree(html, tag)
                captureMeta(html, tag, end, meta)
                i = end
                continue
            }
            out.append(html, lt, tag.end)
            i = tag.end
        }
        return out.toString()
    }

    /**
     * 从被丢弃的子树里抢救元信息：title / base href / canonical / og:url。
     * `head` 整棵会被丢掉，所以它的元信息必须在丢之前扫一遍——`<title>` 只可能出现在里面，
     * 不扫这一遍就永远拿不到网页标题。
     */
    private fun captureMeta(html: String, tag: Tag, end: Int, meta: Meta) {
        if (tag.name == "head") {
            captureHead(html, tag.end, end, meta)
            return
        }
        when (tag.name) {
            "title" -> if (meta.title == null) {
                // 未闭合的 <title> 以前会一直吞到文末（indexOfEndTag 找不到闭合返回 src.length）：
                // 最多取起始标签后 200 字符，保住标题的同时不吃掉正文
                val close = indexOfEndTag(html, "title", tag.end)
                val e = minOf(close, tag.end + TITLE_MAX_CHARS)
                val raw = html.substring(tag.end, e)
                meta.title = HtmlEntities.decodeEntities(raw).replace(WS_RE, " ").trim().ifBlank { null }
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

    /** 扫一遍即将被丢弃的 head 子树，逐个标签交给 [captureMeta]（`head` 自身不再递归） */
    private fun captureHead(html: String, from: Int, end: Int, meta: Meta) {
        var i = from
        while (i < end) {
            val lt = html.indexOf('<', i)
            if (lt < 0 || lt >= end) return
            if (html.startsWith("<!--", lt)) {
                val e = html.indexOf("-->", lt + 4)
                i = if (e < 0 || e >= end) end else e + 3
                continue
            }
            val t = parseTag(html, lt)
            if (t == null) {
                i = lt + 1
                continue
            }
            if (!t.endTag && t.name != "head") captureMeta(html, t, end, meta)
            i = if (t.end >= lt + 1) t.end else lt + 1
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
        if (tag.selfClosing || tag.name in HtmlToMarkdown.VOID_TAGS) return tag.end
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

    /**
     * script/style/noscript 专用整段跳过（内容按原样文本处理，不做标签配平）。
     * 以前走 [skipSubtree] 的"找不到闭合退回不丢"兜底，超长脚本（超出窗口）或未闭合时，
     * 内容会留在清洗结果里被当成正文；这里改为：找得到闭合就连结束标签一起整段跳过，
     * 找不到就保守只丢到下一个标签起点，不把未闭合脚本之后的正文一起吞掉。
     */
    private fun skipRawText(html: String, tag: Tag): Int {
        if (tag.selfClosing) return tag.end
        val close = indexOfEndTag(html, tag.name, tag.end)
        if (close < html.length) {
            val gt = html.indexOf('>', close)
            return if (gt < 0) html.length else gt + 1
        }
        var j = tag.end
        while (j < html.length) {
            val lt = html.indexOf('<', j)
            if (lt < 0) return html.length
            if (lt + 1 < html.length) {
                val c = html[lt + 1]
                if (c.isLetter() || c == '/' || c == '!') return lt
            }
            j = lt + 1
        }
        return html.length
    }

    /** 原样文本类丢弃标签：内容是脚本/样式代码，不按子树结构配平 */
    private val RAW_TEXT_TAGS = setOf("script", "style", "noscript")

    private const val SUBTREE_WINDOW = 500_000

    /** 未闭合 `<title>` 的兜底取字上限 */
    private const val TITLE_MAX_CHARS = 200

    private val WS_RE = Regex("\\s+")

    // ==================== 第二段：tokenize ====================

    internal fun tokenize(clean: String): List<Tok> {
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
                attrs[aname] = HtmlEntities.decodeEntities(value)
            }
        }
        if (!closed) return null
        return Tag(name, attrs, i, selfClosing, endTag)
    }
}
