package com.phoneagent.core.text

import com.phoneagent.feature.browser.BrowserGuard
import com.phoneagent.feature.browser.script.BrowserJs
import com.phoneagent.feature.browser.script.InteractScripts
import com.phoneagent.feature.browser.script.ReadScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转换器与浏览器侧脚本的**同源**护栏（原 HtmlToMarkdownTest 的第三层）。
 *
 * 为什么这一半留在 :app：它要比对 [ReadScript.READ] / [InteractScripts] 生成的脚本文本，
 * 那些类在 :app 的 feature/browser 下，纯 JVM 的 :core:text 看不到它们。
 * 另一半（嗅探与转换本身）已经跟着被测包搬进 :core:text 的 HtmlToMarkdownTest。
 */
class HtmlToMarkdownScriptContractTest {

    @Test
    fun `浏览器脚本内嵌的规则表与本侧常量同源`() {
        val read = ReadScript.READ
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

    @Test
    fun `清单与点击共用同一套选择器与同一个人话取名函数`() {
        // 读侧清单（browse_read）与写侧点击（browse_click）必须共享选择器与 labelOf，
        // 否则会出现"清单里看得见的按钮，点下去却说找不到"。
        val read = ReadScript.READ
        assertTrue(read, read.contains(HtmlToMarkdown.jsStr(BrowserJs.LIST_SELECTOR)))
        assertTrue(read, read.contains(BrowserJs.HELPERS))

        val click = InteractScripts.click("text", "下一页", guard = true)
        assertTrue(click, click.contains(HtmlToMarkdown.jsStr(BrowserJs.CLICK_SELECTOR)))
        assertTrue(click, click.contains(BrowserJs.HELPERS))
        // 候选面必须以清单选择器为前缀（先真控件、再文字宽网），保证"看得见就点得到"
        assertTrue(BrowserJs.CLICK_SELECTOR.startsWith(BrowserJs.LIST_SELECTOR))
    }

    @Test
    fun `只读护栏词表在脚本侧与端侧同源`() {
        // 词表只在 BrowserGuard 定义一次：Kotlin 判定用它，注入脚本的探针也用它
        val click = InteractScripts.click("text", "确认支付", guard = true)
        assertTrue(click, click.contains(BrowserGuard.jsWords()))
    }
}
