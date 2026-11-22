package com.phoneagent.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转换四个阶段的单测：直接盯着每个阶段自己的输入输出，而不是只靠 [HtmlToMarkdown.convert] 的端到端结果。
 *
 * 为什么要有这一层：端到端断言只能说明"整体结果对"，某个阶段被搬动或改坏时，
 * 差别可能被其他阶段糊平（例如清洗少删一棵子树、分词多吞一个字符）。
 * 这些用例把每个阶段的边界行为单独钉住，改坏任一处都会直接红。
 */
class HtmlToMarkdownStagesTest {

    // ==================== 一、清洗：stripNoise ====================

    @Test
    fun `清洗_丢弃脚本子树与注释但保留正文`() {
        val html = "<html><body><!-- 注释 --><p>正文</p><script>var a = '<p>假标签</p>';</script></body></html>"
        val cleaned = HtmlScanner.stripNoise(html, Meta())
        assertTrue("正文必须保留", cleaned.contains("<p>正文</p>"))
        assertTrue("注释必须整段消失", !cleaned.contains("注释"))
        assertTrue("脚本内容不能被当正文吐出来", !cleaned.contains("假标签"))
    }

    @Test
    fun `清洗_从head抢救标题与链接基准`() {
        val html = "<head><title> 页面 标题 </title>" +
            "<base href=\"https://e.com/a/\">" +
            "<link rel=\"canonical\" href=\"https://e.com/c\">" +
            "<meta property=\"og:url\" content=\"https://e.com/o\">" +
            "</head><body><p>正文</p></body>"
        val meta = Meta()
        val cleaned = HtmlScanner.stripNoise(html, meta)
        assertEquals("标题必须去空白后抽出来", "页面 标题", meta.title)
        assertEquals("https://e.com/a/", meta.base)
        assertEquals("https://e.com/c", meta.canonical)
        assertEquals("https://e.com/o", meta.ogUrl)
        assertTrue("head 整棵不该进正文", !cleaned.contains("<title>"))
        assertTrue("正文必须保留", cleaned.contains("<p>正文</p>"))
    }

    @Test
    fun `清洗_未闭合脚本不会吞掉后面的正文`() {
        val html = "<body><script>var x = 1;<p>正文在脚本后面</p></body>"
        val cleaned = HtmlScanner.stripNoise(html, Meta())
        assertTrue("脚本之后的内容必须留下", cleaned.contains("正文在脚本后面"))
    }

    // ==================== 二、分词：parseTag / tokenize ====================

    @Test
    fun `分词_属性里的尖括号不截断标签`() {
        val src = "<a title=\"a > b\" href=\"/x\">文字</a>"
        val tag = HtmlScanner.parseTag(src, 0)
        assertNotNull(tag)
        assertEquals("a", tag!!.name)
        assertEquals("a > b", tag.attrs["title"])
        assertEquals("/x", tag.attrs["href"])
    }

    @Test
    fun `分词_裸尖括号按文本处理不吞字`() {
        val tokens = HtmlScanner.tokenize("a < b 且 c")
        val text = tokens.joinToString("") { if (it is Tok.Text) it.s else "" }
        assertEquals("a < b 且 c", text)
    }

    @Test
    fun `分词_开始与结束标签各自识别`() {
        val tokens = HtmlScanner.tokenize("<div><p>x</p></div>")
        assertEquals(5, tokens.size)
        assertEquals("div", (tokens[0] as Tok.Start).tag.name)
        assertEquals("p", (tokens[1] as Tok.Start).tag.name)
        assertEquals("x", (tokens[2] as Tok.Text).s)
        assertEquals("p", (tokens[3] as Tok.End).name)
        assertEquals("div", (tokens[4] as Tok.End).name)
    }

    // ==================== 三、实体解码 ====================

    @Test
    fun `实体_命名与十进制十六进制都能解`() {
        assertEquals("a & b < c", HtmlEntities.decodeEntities("a &amp; b &lt; c"))
        assertEquals("A—B", HtmlEntities.decodeEntities("A&mdash;B"))
        assertEquals("中", HtmlEntities.decodeEntities("&#20013;"))
        assertEquals("中", HtmlEntities.decodeEntities("&#x4E2D;"))
    }

    @Test
    fun `实体_表外的与非法码点原样保留`() {
        assertEquals("&notlisted;", HtmlEntities.decodeEntities("&notlisted;"))
        assertEquals("&#xD800;", HtmlEntities.decodeEntities("&#xD800;"))
        assertEquals("&#x110000;", HtmlEntities.decodeEntities("&#x110000;"))
    }

    // ==================== 四、累积器：MdBuilder ====================

    @Test
    fun `累积器_空白后接头CJK不插空格西文插一个`() {
        val b = MdBuilder()
        b.appendText("你好 ")
        b.appendText("世界")
        assertEquals("你好世界", b.curText())

        b.resetInline()
        b.appendText("hello ")
        b.appendText("world")
        assertEquals("hello world", b.curText())
    }

    @Test
    fun `累积器_块之间用空行分隔尾部空白被去掉`() {
        val b = MdBuilder()
        b.emit("第一段   ")
        b.emit("第二段")
        assertEquals("第一段\n\n第二段", b.render())
    }
}
