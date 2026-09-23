package com.phoneagent.feature.browser

import com.phoneagent.core.text.HtmlToMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器不可逆词表护栏测试。
 *
 * 这张表的取值口径直接决定只读模式可用性：
 * - 该拦的（支付/下单/删除/发送…）必须拦，否则只读模式形同虚设；
 * - **不该拦的（搜索/翻页/登录/确定/提交/继续…）必须放行**，否则只读模式下网页基本没法用。
 * 所以第二类用"反例清单"逐词钉住。
 */
class BrowserGuardTest {

    // ---- 该拦的 ----

    @Test
    fun match_中文不可逆词_命中且回报命中的词() {
        assertEquals("支付", BrowserGuard.match("确认支付 23.00"))
        assertEquals("立即购买", BrowserGuard.match("立即购买"))
        assertEquals("删除", BrowserGuard.match("删除该条评论"))
        assertEquals("发送", BrowserGuard.match("发送验证码"))
    }

    @Test
    fun match_英文不可逆词_大小写无关() {
        assertEquals("checkout", BrowserGuard.match("Checkout"))
        assertEquals("place order", BrowserGuard.match("PLACE ORDER NOW"))
        assertEquals("delete", BrowserGuard.match("Delete Account"))
    }

    @Test
    fun match_空串与null_不命中() {
        assertNull(BrowserGuard.match(null))
        assertNull(BrowserGuard.match(""))
        assertNull(BrowserGuard.match("   "))
    }

    /**
     * 反例清单：这些是"只读模式也必须放行"的普通网页操作。
     * 一旦有人往词表里加「确认/提交/继续」这类泛词，这条会立刻红。
     */
    @Test
    fun match_普通网页操作_一律放行() {
        val allowed = listOf(
            "搜索", "查询", "筛选", "下一页", "上一页", "登录", "注册", "同意并继续",
            "确定", "取消", "提交查询", "查看更多", "展开全部", "复制链接", "下载",
            "Search", "Next", "Previous", "Sign in", "Continue", "Show more",
        )
        allowed.forEach { assertNull("「$it」不该被不可逆词表拦住", BrowserGuard.match(it)) }
    }

    // ---- isIrreversible ----

    @Test
    fun isIrreversible_AI自报为真_直接判不可逆() {
        assertTrue(BrowserGuard.isIrreversible("下一页", needsConfirmation = true))
    }

    @Test
    fun isIrreversible_自报与词表都为假_放行() {
        assertFalse(BrowserGuard.isIrreversible("下一页"))
        assertFalse(BrowserGuard.isIrreversible(null))
    }

    // ---- selectorSuspicious ----

    @Test
    fun selectorSuspicious_危险命名的选择器_判可疑() {
        assertTrue(BrowserGuard.selectorSuspicious("#pay-btn"))
        assertTrue(BrowserGuard.selectorSuspicious(".checkout-submit"))
        assertTrue(BrowserGuard.selectorSuspicious("#deleteComment"))
    }

    @Test
    fun selectorSuspicious_普通选择器_不判可疑() {
        assertFalse(BrowserGuard.selectorSuspicious("#search-input"))
        assertFalse(BrowserGuard.selectorSuspicious(".list-item"))
        assertFalse(BrowserGuard.selectorSuspicious(null))
        assertFalse(BrowserGuard.selectorSuspicious(""))
    }

    // ---- 提示词同源 ----

    @Test
    fun promptWords_与词表同源且可读() {
        assertEquals(BrowserGuard.WORDS_CN.joinToString("/"), BrowserGuard.promptWords())
        assertEquals(BrowserGuard.WORDS_EN.joinToString("/"), BrowserGuard.promptWordsEn())
        assertTrue(BrowserGuard.promptWords().contains("支付"))
        assertTrue(BrowserGuard.promptWordsEn().contains("checkout"))
    }

    /** JS 词表必须与端侧判定完全同一份（脚本探针与 Kotlin 护栏不能各写一半） */
    @Test
    fun jsWords_包含全部中英词且为JS字面量() {
        val js = BrowserGuard.jsWords()
        assertTrue(js.startsWith("["))
        assertTrue(js.endsWith("]"))
        (BrowserGuard.WORDS_CN + BrowserGuard.WORDS_EN).forEach {
            assertTrue("JS 词表缺少「$it」：$js", js.contains(HtmlToMarkdown.jsStr(it)))
        }
    }
}