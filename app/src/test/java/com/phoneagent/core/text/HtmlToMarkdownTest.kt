package com.phoneagent.core.text

import com.phoneagent.feature.browser.BrowserScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研 HTML → Markdown 转换器单测。
 *
 * 覆盖三层：
 * 1. **嗅探**（[HtmlToMarkdown.isHtml]）——必须挡住 JSON、纯文本、`uiautomator dump` 的 XML；
 * 2. **转换**（[HtmlToMarkdown.convert]）——标题/列表/引用/代码/表格/链接/实体等结构是否真的转出来了；
 * 3. **两侧同源**——浏览器侧脚本 [BrowserScripts.READ] 的规则表必须由本侧常量插值生成，
 *    且**不能**把 `html`/`body` 当噪声丢掉（丢它们等于正文全空）。
 */
class HtmlToMarkdownTest {

    // ==================== 一、嗅探 ====================

    @Test
    fun `嗅探_强信号HTML判真`() {
        assertTrue(HtmlToMarkdown.isHtml("<!DOCTYPE html><html><body><p>正文内容</p></body></html>"))
        assertTrue(HtmlToMarkdown.isHtml("<html lang=\"zh\"><body>正文内容在此</body></html>"))
        assertTrue(HtmlToMarkdown.isHtml("<body><div>正文内容在此</div></body>"))
        assertTrue(HtmlToMarkdown.isHtml("<head><title>标题标题标题</title></head>"))
    }

    @Test
    fun `嗅探_JSON与纯文本与XML_dump判假`() {
        // JSON 开头直接否决（哪怕里面内嵌了 HTML 字符串）
        assertFalse(HtmlToMarkdown.isHtml("""{"code":0,"data":"<div>劫持正文</div>"}"""))
        assertFalse(HtmlToMarkdown.isHtml("[{\"html\":\"<p>x</p>\"}]"))
        // 纯文本
        assertFalse(HtmlToMarkdown.isHtml("hello world, this is just plain text."))
        // uiautomator dump 的 XML：完整标签数够、但一个 HTML 特征标签都没有
        val dump = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            |<hierarchy rotation="0">
            |  <node index="0" text="设置" resource-id="com.android.settings:id/title"
            |        class="android.widget.TextView" package="com.android.settings"
            |        clickable="true" enabled="true" bounds="[0,0][1080,200]" />
            |</hierarchy>
        """.trimMargin()
        assertFalse(HtmlToMarkdown.isHtml(dump))
    }

    @Test
    fun `嗅探_已转Markdown不再重复转换`() {
        assertFalse(HtmlToMarkdown.isHtml("这是正文 [示例链接](https://example.com/a) 后续文字"))
        // 真 HTML 含内联链接时仍要判真（不能因为出现 ](http 就放过）
        assertTrue(HtmlToMarkdown.isHtml("<html><body><p>看 <a href=\"https://a.com\">这里</a></p></body></html>"))
    }

    // ==================== 二、块级结构 ====================

    @Test
    fun `标题转井号层级`() {
        val r = HtmlToMarkdown.convert("<h1>标题</h1><h2>二级标题</h2><h6>六级标题</h6>")
        assertEquals("# 标题\n\n## 二级标题\n\n###### 六级标题", r.markdown)
    }

    @Test
    fun `段落内的粗体斜体删除线与行内代码`() {
        val html = "<p>普通文字 <strong>加粗</strong> <em>斜体</em> <del>删除</del> <code>code</code></p>"
        val md = HtmlToMarkdown.convert(html).markdown
        assertTrue(md, md.contains("**加粗**"))
        assertTrue(md, md.contains("*斜体*"))
        assertTrue(md, md.contains("~~删除~~"))
        assertTrue(md, md.contains("`code`"))
    }

    @Test
    fun `无序列表与有序列表及起始序号`() {
        val ul = HtmlToMarkdown.convert("<ul><li>甲</li><li>乙</li></ul>").markdown
        assertEquals("- 甲\n- 乙", ul)

        val ol = HtmlToMarkdown.convert("<ol><li>一</li><li>二</li></ol>").markdown
        assertEquals("1. 一\n2. 二", ol)

        val olStart = HtmlToMarkdown.convert("<ol start=\"3\"><li>三</li><li>四</li></ol>").markdown
        assertEquals("3. 三\n4. 四", olStart)
    }

    @Test
    fun `嵌套列表保留缩进层级`() {
        val md = HtmlToMarkdown.convert("<ul><li>父<ul><li>子</li></ul></li></ul>").markdown
        assertEquals("- 父\n  - 子", md)
    }

    @Test
    fun `引用块加尖括号前缀`() {
        val md = HtmlToMarkdown.convert("<blockquote><p>被引用的话</p></blockquote>").markdown
        assertEquals("> 被引用的话", md)
    }

    // ==================== 三、代码块 ====================

    @Test
    fun `代码块保留原样并取language类名`() {
        val html = "<pre><code class=\"language-kotlin\">val a = 1\n  indented</code></pre>"
        val md = HtmlToMarkdown.convert(html).markdown
        assertEquals("```kotlin\nval a = 1\n  indented\n```", md)
    }

    @Test
    fun `代码块正文含围栏时自动加长围栏`() {
        val md = HtmlToMarkdown.convert("<pre>a```b</pre>").markdown
        assertTrue(md, md.startsWith("````\n"))
        assertTrue(md, md.contains("a```b"))
        assertTrue(md, md.endsWith("\n````"))
    }

    // ==================== 四、链接与图片 ====================

    @Test
    fun `链接按base绝对化并丢弃javascript伪链接`() {
        val html = """<p><a href="/x">点</a> <a href="javascript:void(0)">伪</a>
            |<a href="//cdn.a.com/b">协议相对</a> <a href="next">相对</a></p>
        """.trimMargin()
        val md = HtmlToMarkdown.convert(html, baseUrl = "https://e.com/a/b").markdown
        assertTrue(md, md.contains("[点](https://e.com/x)"))
        assertTrue(md, md.contains("[协议相对](https://cdn.a.com/b)"))
        assertTrue(md, md.contains("[相对](https://e.com/a/next)"))
        assertTrue(md, md.contains("伪"))
        assertFalse(md, md.contains("javascript:"))
    }

    @Test
    fun `图片按base绝对化且alt转义方括号`() {
        val md = HtmlToMarkdown.convert(
            "<p><img src=\"/i.png\" alt=\"图[1]\"></p>",
            baseUrl = "https://e.com/a/b",
        ).markdown
        assertEquals("![图\\[1\\]](https://e.com/i.png)", md)
    }

    // ==================== 五、表格 ====================

    @Test
    fun `表格转GFM管道表`() {
        val html = "<table><tr><th>名称</th><th>值</th></tr><tr><td>甲</td><td>1</td></tr></table>"
        val md = HtmlToMarkdown.convert(html).markdown
        assertEquals("| 名称 | 值 |\n| --- | --- |\n| 甲 | 1 |", md)
    }

    @Test
    fun `表格标题不进管道表`() {
        val html = "<table><caption>表1</caption><tr><td>a</td><td>b</td></tr></table>"
        val md = HtmlToMarkdown.convert(html).markdown
        assertTrue(md, md.contains("| a | b |"))
        assertFalse(md, md.contains("表1"))
    }

    @Test
    fun `表格单元格内竖线转义`() {
        val md = HtmlToMarkdown.convert("<table><tr><td>a|b</td><td>c</td></tr></table>").markdown
        assertTrue(md, md.contains("a\\|b"))
    }

    @Test
    fun `单元格内的块级文本不甩出表格`() {
        val html = "<table><tr><td><p>甲</p></td><td><div>乙</div></td></tr></table>"
        val md = HtmlToMarkdown.convert(html).markdown
        assertEquals("| 甲 | 乙 |\n| --- | --- |", md)
    }

    // ==================== 六、噪声与实体 ====================

    @Test
    fun `噪声子树与隐藏元素被整棵丢弃`() {
        val html = """
            |<html><head><title>页面标题</title><style>.x{color:red}</style></head>
            |<body>
            |<script>var a=1</script>
            |<!-- 这是注释 -->
            |<nav>导航区</nav><footer>页脚区</footer><aside>侧栏区</aside>
            |<div hidden>藏起来</div>
            |<div class="sr-only">也藏起来</div>
            |<span aria-hidden="true">无障碍隐藏</span>
            |<span style="display: none">样式隐藏</span>
            |<p>真正文</p>
            |</body></html>
        """.trimMargin()
        val r = HtmlToMarkdown.convert(html)
        // html/body 是正文容器，绝不能被当噪声丢掉；head 整棵丢弃前要先抢救 title
        assertEquals("页面标题", r.title)
        assertTrue(r.markdown, r.markdown.contains("真正文"))
        assertFalse(r.markdown, r.markdown.contains("var a"))
        assertFalse(r.markdown, r.markdown.contains("color:red"))
        assertFalse(r.markdown, r.markdown.contains("这是注释"))
        assertFalse(r.markdown, r.markdown.contains("导航区"))
        assertFalse(r.markdown, r.markdown.contains("页脚区"))
        assertFalse(r.markdown, r.markdown.contains("侧栏区"))
        assertFalse(r.markdown, r.markdown.contains("藏起来"))
        assertFalse(r.markdown, r.markdown.contains("也藏起来"))
        assertFalse(r.markdown, r.markdown.contains("无障碍隐藏"))
        assertFalse(r.markdown, r.markdown.contains("样式隐藏"))
    }

    @Test
    fun `命名实体与数字实体解码且未知实体原样保留`() {
        val html = "<p>a &amp; b &lt;tag&gt; &#x4e2d; &copy; &unknown; 结束</p>"
        val md = HtmlToMarkdown.convert(html).markdown
        assertTrue(md, md.contains("a & b"))
        assertTrue(md, md.contains("<tag>"))
        assertTrue(md, md.contains("中"))
        assertTrue(md, md.contains("©"))
        assertTrue(md, md.contains("&unknown;"))
        assertTrue(md, md.contains("结束"))
    }

    // ==================== 七、截断与两侧同源 ====================

    @Test
    fun `截断只切块边界且不切半个链接`() {
        val note = HtmlToMarkdown.TRUNCATED_NOTE
        val multi = "AAAAAAAA\n\nBBBBBBBB\n\nCCCCCCCC"
        assertEquals("AAAAAAAA\n\n$note", HtmlToMarkdown.takeBlocks(multi, 20))

        // 落点若在 `[文字](网址)` 中间，必须整条回退，绝不产出半个链接
        val link = "[点击这里](https://example.com/very/long/path)"
        val linkCut = HtmlToMarkdown.takeBlocks(link, 24)
        assertFalse(linkCut, linkCut.contains("]("))
        assertFalse(linkCut, linkCut.contains("["))
        assertEquals(note, linkCut)
    }

    @Test
    fun `浏览器脚本内嵌的规则表与本侧常量同源`() {
        val read = BrowserScripts.READ
        fun table(key: String): String = read.lineSequence().first { it.contains("var $key =") }
        val drop = table("DROP")
        val block = table("BLOCK")
        val hidden = table("HIDDEN_CLASS")
        // 四张表 + 转义字符表 + 围栏字面量，全部由本侧常量插值生成（改一处即两处生效）
        HtmlToMarkdown.DROP_RULE.split(' ').filter { it.isNotBlank() }.forEach {
            assertTrue("丢弃表缺 $it", drop.contains(HtmlToMarkdown.jsStr(it) + ":1"))
        }
        HtmlToMarkdown.BLOCK_RULE.split(' ').filter { it.isNotBlank() }.forEach {
            assertTrue("块级表缺 $it", block.contains(HtmlToMarkdown.jsStr(it) + ":1"))
        }
        HtmlToMarkdown.HIDDEN_CLASS_RULE.split(' ').filter { it.isNotBlank() }.forEach {
            assertTrue("隐藏类名表缺 $it", hidden.contains(HtmlToMarkdown.jsStr(it) + ":1"))
        }
        assertTrue(read, read.contains(HtmlToMarkdown.jsMarkPairs(HtmlToMarkdown.MARK_RULE)))
        assertTrue(read, read.contains(HtmlToMarkdown.jsArr(HtmlToMarkdown.ESCAPE_RULE)))
        assertTrue(read, read.contains(HtmlToMarkdown.jsStr(HtmlToMarkdown.FENCE)))
        // 回归护栏：html/body 是正文容器，一旦被列进丢弃表，整页会转出空 Markdown
        assertFalse(drop, drop.contains(HtmlToMarkdown.jsStr("body") + ":1"))
        assertFalse(drop, drop.contains(HtmlToMarkdown.jsStr("html") + ":1"))
    }
}