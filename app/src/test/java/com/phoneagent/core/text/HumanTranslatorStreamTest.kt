package com.phoneagent.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流式决策 JSON → 人话预览；核心约束是**单调性**（打字机依赖它，倒退会看到文字倒吸） */
class HumanTranslatorStreamTest {

    private val fullTap = """
        {"intent":"tap","target":{"id":"search_box","value":"搜索框"},"confidence":0.94,"reasoning":"页面顶部就是搜索入口"}
    """.trimIndent()

    @Test
    fun `空输入返回空串`() {
        assertEquals("", HumanTranslator.humanStream(""))
        assertEquals("", HumanTranslator.humanStream("   "))
    }

    @Test
    fun `完整 JSON 译成一句人话`() {
        assertEquals(
            "点击「搜索框」 · 目的：页面顶部就是搜索入口",
            HumanTranslator.humanStream(fullTap),
        )
    }

    @Test
    fun `每个前缀的输出都是完整输出的前缀`() {
        val full = HumanTranslator.humanStream(fullTap)
        for (end in 1..fullTap.length) {
            val prefixOut = HumanTranslator.humanStream(fullTap.substring(0, end))
            assertTrue(
                "前缀 ${fullTap.substring(0, end)} 译出了非前缀内容：[$prefixOut]",
                full.startsWith(prefixOut),
            )
        }
    }

    @Test
    fun `intent 未闭合时不吐动词`() {
        assertEquals("", HumanTranslator.humanStream("""{"intent":"ta"""))
        assertEquals("点击", HumanTranslator.humanStream("""{"intent":"tap","""))
    }

    @Test
    fun `技术字段不出现在人话里`() {
        val raw = """
            {"intent":"launch","target":{"id":"x","type":"app","by":"label"},"page_fingerprint":"a1b2","confidence":0.8}
        """.trimIndent()
        val out = HumanTranslator.humanStream(raw)
        assertEquals("启动应用", out)
    }

    @Test
    fun `半截的 reasoning 能吐出已读部分`() {
        assertEquals(
            "点击 · 目的：因为页面",
            HumanTranslator.humanStream("""{"intent":"tap","reasoning":"因为页面"""),
        )
    }

    @Test
    fun `target value 闭合后补上右引号`() {
        assertEquals("点击「搜索", HumanTranslator.humanStream("""{"intent":"tap","target":{"value":"搜索"""))
        assertEquals("点击「搜索框」", HumanTranslator.humanStream("""{"intent":"tap","target":{"value":"搜索框"}"""))
    }

    @Test
    fun `say 的正文不加附注前缀`() {
        assertEquals("说：我先看一眼当前页面", HumanTranslator.humanStream("""{"intent":"say","text":"我先看一眼当前页面"}"""))
    }

    @Test
    fun `含转义引号的字段不会提前断句`() {
        val out = HumanTranslator.humanStream("""{"intent":"say","text":"他说\"好的\"就继续"}""")
        assertEquals("""说：他说"好的"就继续""", out)
    }
}