package com.phoneagent.feature.agentdoc

import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.PromptLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行文档匹配口径的回归测试。
 *
 * 这里锁住三条最容易退化的规则：
 * 1) 有包名 / 应用名 / 关键词才算命中，priority 单独不足以注入；
 * 2) 包名命中分数高于关键词命中（执行阶段应优先用当前前台应用的手册）；
 * 3) select 的排序与数量上限稳定。
 */
class DocMatcherTest {

    // 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
    @Suppress("LongParameterList")
    private fun meta(
        id: String,
        priority: Int = 50,
        packages: List<String> = emptyList(),
        apps: List<String> = emptyList(),
        keywords: List<String> = emptyList(),
        intents: List<String> = emptyList(),
    ) = DocMeta(
        id = id,
        title = id,
        priority = priority,
        packages = packages,
        apps = apps,
        keywords = keywords,
        intents = intents,
    )

    private val alipay = meta(
        id = "alipay-pay",
        priority = 80,
        packages = listOf("com.eg.android.AlipayGphone"),
        apps = listOf("支付宝"),
        keywords = listOf("付款", "扫一扫"),
        intents = listOf("pay"),
    )

    @Test
    fun `只有 priority 不算命中`() {
        val lone = meta(id = "lone", priority = 100)
        assertEquals(0, DocMatcher.score(lone, "去设置里打开蓝牙", "com.android.settings"))
        assertTrue(DocMatcher.select(listOf(lone), "打开蓝牙", null).isEmpty())
    }

    @Test
    fun `前台包名命中且分数高于关键词`() {
        val byPackage = DocMatcher.score(alipay, "付款 20 元", "com.eg.android.AlipayGphone")
        val byKeyword = DocMatcher.score(alipay, "用支付宝付款 20 元", null)
        assertTrue("包名命中应至少 60 分，实际 $byPackage", byPackage >= 60)
        assertTrue("包名命中应高于纯关键词命中：$byPackage vs $byKeyword", byPackage > byKeyword)
        assertTrue("关键词命中应有效，实际 $byKeyword", byKeyword > 0)
    }

    @Test
    fun `应用名与关键词都能命中，中文不区分大小写以外也有效`() {
        assertTrue(DocMatcher.score(alipay, "打开支付宝", null) > 0)
        assertTrue(DocMatcher.score(alipay, "帮我扫一扫", null) > 0)
    }

    @Test
    fun `单字关键词不计分`() {
        val short = meta(id = "short", keywords = listOf("卡"))
        assertEquals(0, DocMatcher.score(short, "打开卡包", null))
    }

    @Test
    fun `select 按分数排序并限制数量`() {
        val docs = listOf(
            alipay,
            meta(id = "generic-pay", keywords = listOf("付款")),
            meta(id = "unrelated", keywords = listOf("天气")),
        )
        val hits = DocMatcher.select(docs, "用支付宝付款", "com.eg.android.AlipayGphone", limit = 2)
        assertEquals(2, hits.size)
        assertEquals("alipay-pay", hits.first().meta.id)
        assertTrue(hits.none { it.meta.id == "unrelated" })
    }

    @Test
    fun `空任务不命中`() {
        assertEquals(0, DocMatcher.score(alipay, "", "com.eg.android.AlipayGphone"))
        assertTrue(DocMatcher.select(listOf(alipay), "  ", null).isEmpty())
    }

    @Test
    fun `文档块为空列表时整块缺席`() {
        assertEquals("", AgentPrompts.docsBlock(PromptLang.CN, emptyList()))
        val block = AgentPrompts.docsBlock(
            PromptLang.CN,
            listOf(AgentDoc("alipay-pay", "支付宝付款", "1. 打开扫一扫\n2. 核对金额")),
        )
        assertTrue(block.contains("执行文档"))
        assertTrue(block.contains("## 支付宝付款"))
        assertTrue(block.contains("1. 打开扫一扫"))
    }
}
