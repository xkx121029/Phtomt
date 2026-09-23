package com.phoneagent.ui.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行内样式解析与"是否像 Markdown"的自动识别 */
class MarkdownTest {

    // ---------------- looksLikeMarkdown ----------------

    @Test
    fun `纯中文段落不算 markdown`() {
        assertFalse(looksLikeMarkdown("我准备先打开设置，再定位到显示项，随后把亮度调到一半。"))
        assertFalse(looksLikeMarkdown(""))
        assertFalse(looksLikeMarkdown("   \n  "))
    }

    @Test
    fun `块级标记命中`() {
        assertTrue(looksLikeMarkdown("# 标题"))
        assertTrue(looksLikeMarkdown("### 三级标题"))
        assertTrue(looksLikeMarkdown("- 第一项\n- 第二项"))
        assertTrue(looksLikeMarkdown("1. 第一步"))
        assertTrue(looksLikeMarkdown("> 引用一句"))
        assertTrue(looksLikeMarkdown("```kotlin\nval a = 1\n```"))
        assertTrue(looksLikeMarkdown("---"))
    }

    @Test
    fun `行内标记命中`() {
        assertTrue(looksLikeMarkdown("这是**重点**内容"))
        assertTrue(looksLikeMarkdown("这是~~删掉~~的内容"))
        assertTrue(looksLikeMarkdown("执行 `adb devices` 看看"))
        assertTrue(looksLikeMarkdown("见 [帮助](https://example.com/a)"))
        assertTrue(looksLikeMarkdown("![图](a.png)"))
    }

    @Test
    fun `正文里的单个星号与连字符不误判`() {
        // 行首要求有空白跟随，`*斜体*` 与句中 `-` 都不该被当成列表
        assertFalse(looksLikeMarkdown("这个 3 * 4 等于 12"))
        assertFalse(looksLikeMarkdown("型号是 Pixel-8-Pro，很流畅"))
        assertFalse(looksLikeMarkdown("当前进度 60% - 还差一点"))
    }

    // ---------------- rich ----------------

    @Test
    fun `粗体斜体删除线行内代码各自成段`() {
        val bold = rich("**粗**")
        assertEquals("粗", bold.text)
        assertEquals(1, bold.spanStyles.size)
        assertEquals(FontWeight.Bold, bold.spanStyles[0].item.fontWeight)

        val italic = rich("*斜*")
        assertEquals("斜", italic.text)
        assertEquals(FontStyle.Italic, italic.spanStyles[0].item.fontStyle)

        val strike = rich("~~删~~")
        assertEquals("删", strike.text)
        assertEquals(TextDecoration.LineThrough, strike.spanStyles[0].item.textDecoration)
    }

    @Test
    fun `行内代码里的标记按字面量处理`() {
        val code = rich("`**not bold**`")
        assertEquals("**not bold**", code.text)
        assertEquals(1, code.spanStyles.size)
        assertEquals(null, code.spanStyles[0].item.fontWeight)
    }

    @Test
    fun `链接解析为 LinkAnnotation 且只留文字`() {
        val link = rich("见 [帮助](https://example.com/a)")
        assertEquals("见 帮助", link.text)
        val annotations = link.getLinkAnnotations(0, link.text.length)
        assertEquals(1, annotations.size)
        val url = (annotations[0].item as LinkAnnotation.Url).url
        assertEquals("https://example.com/a", url)
    }

    @Test
    fun `未闭合的链接标记不吞字符`() {
        // 流式半截文本里 `[文字](` 很常见，必须原样吐出
        assertEquals("[文字](", rich("[文字](").text)
        assertEquals("[文字", rich("[文字").text)
        assertEquals("[文字](未闭合", rich("[文字](未闭合").text)
    }

    @Test
    fun `纯文本不产生多余 span`() {
        val plain = rich("就是一句普通的话")
        assertEquals("就是一句普通的话", plain.text)
        assertEquals(0, plain.spanStyles.size)
    }
}