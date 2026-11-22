package com.phoneagent.core.text

/**
 * Markdown 累积器：块序列 + 空白折叠 + CJK 不插空格 + 转义 + 行内标记栈 + 列表缓冲。
 * 超过 [HARD_CAP] 立刻置 [truncated]，由 Converter 早停。
 */
internal class MdBuilder {

    private val out = StringBuilder(4096)
    private val listBuf = StringBuilder(512)
    private val marks = ArrayList<Mark>(8)

    /** 当前行内片段 */
    private val cur = StringBuilder(256)

    /** 上一段文本以空白结尾：等下一个非空白出现时再决定插不插空格 */
    private var pendSpace = false

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
        pendSpace = false
    }

    fun raw(s: String) {
        flushPend()
        cur.append(s)
    }

    fun push(open: String, close: String) {
        flushPend()
        marks.add(Mark(open, close, cur.length))
        cur.append(open)
    }

    /**
     * 把"待定空格"落盘：文本以空白结尾时不能立刻加空格（下一个可能还是空白），
     * 但遇到行内标记 / 图片 / 换行这类非文本片段就说明空白确实该保留了。
     * 少了这一步 `你好<strong>加粗</strong>` 会输出 `你好**加粗**` —— 相邻两字被粘成一个词。
     */
    private fun flushPend() {
        if (!pendSpace) return
        pendSpace = false
        if (cur.isNotEmpty() && cur.last() != '\n') cur.append(' ')
    }

    fun pop() {
        if (marks.isEmpty()) return
        val m = marks.removeAt(marks.size - 1)
        if (cur.length > m.anchor + m.open.length) cur.append(m.close) else cur.setLength(m.anchor)
    }

    /** `<br>`：列表内退化成空格（保缩进），普通段落里换行 */
    fun br() {
        // 换行本身就是"空白该落地"的证明，先把它结清再处理换行
        flushPend()
        if (inList > 0) {
            if (cur.isNotEmpty() && cur.last() != ' ') cur.append(' ')
            return
        }
        while (cur.isNotEmpty() && cur.last() == ' ') cur.setLength(cur.length - 1)
        if (cur.isNotEmpty()) cur.append('\n')
    }

    /** 空白折叠 + CJK 规则 + 转义 */
    fun appendText(s: String) {
        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i]
            if (ch.isWhitespace() || ch == '\u00A0' || ch == '\u200B') {
                // 空白不立刻落盘：可能后面还有空白，且紧邻的是行内标记时要等标记先决定
                pendSpace = true
                i++
                continue
            }
            if (pendSpace) {
                if (cur.isNotEmpty() && cur.last() != '\n' && needsSpace(cur.last(), ch)) cur.append(' ')
                pendSpace = false
            }
            appendChar(ch, s, i)
            i++
        }
        if (cur.length > HtmlToMarkdown.HARD_CAP + 1024) truncated = true
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
        if (emitted > HtmlToMarkdown.HARD_CAP) truncated = true
    }

    fun flushList() {
        if (listBuf.isEmpty()) return
        val block = listBuf.toString()
        listBuf.setLength(0)
        if (out.isNotEmpty()) out.append("\n\n")
        out.append(block)
        emitted = out.length
        if (emitted > HtmlToMarkdown.HARD_CAP) truncated = true
    }

    fun render(): String {
        flushList()
        return out.toString().trim()
    }
}
