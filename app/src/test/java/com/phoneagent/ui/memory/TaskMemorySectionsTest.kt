package com.phoneagent.ui.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务记忆卡片的去重契约（UX-09）。
 *
 * 旧实现把标题（taskName）、目标（goal）、用户要求（requirements[0]）各渲染一遍，
 * 一张卡里同一句话出现三遍。新规则：抬头已经写了任务原文，正文只补真正新的内容，
 * 一条不剩的分区整块不出现。
 */
class TaskMemorySectionsTest {

    private val phrase = "打开相册，看看最近拍的照片"

    /** 正文里出现该句的次数（分区标签不算，只看内容行） */
    private fun occurrences(sections: List<TaskMemorySectionModel>, text: String): Int =
        sections.sumOf { section -> section.lines.count { normalizeMemoryText(it) == normalizeMemoryText(text) } }

    @Test
    fun `标题与目标与首条要求同文时正文一句都不重复`() {
        val sections = taskMemorySections(
            taskName = phrase,
            goal = phrase,
            // requirements 的首条就是任务原文（见 TaskMemoryEntry 注释），此处复现真实数据
            requirements = listOf(phrase),
            methods = emptyList(),
        )
        assertEquals("同一句在正文里不该再出现：$sections", 0, occurrences(sections, phrase))
        assertTrue("没有独立内容时不应留下空壳分区：$sections", sections.isEmpty())
    }

    @Test
    fun `目标与标题不同时作为次要信息出现一次`() {
        val sections = taskMemorySections(
            taskName = "看看最近的照片",
            goal = phrase,
            requirements = listOf(phrase, "只要最近三张"),
            methods = listOf("点击「相册」→ 点击「最近」"),
        )
        assertEquals("目标只应出现一次", 1, occurrences(sections, phrase))
        assertEquals(listOf("目标", "用户要求", "完成方法"), sections.map { it.label })
        assertEquals(listOf("只要最近三张"), sections[1].lines)
        assertEquals(listOf("点击「相册」→ 点击「最近」"), sections[2].lines)
    }

    @Test
    fun `只差标点与空白的重复按同一句处理`() {
        val sections = taskMemorySections(
            taskName = phrase,
            goal = " 打开相册 看看最近拍的照片。",
            requirements = listOf("打开相册，看看最近拍的照片！"),
            methods = emptyList(),
        )
        assertEquals("只差标点/空白不该被当成第二句：$sections", 0, occurrences(sections, phrase))
        assertTrue(sections.none { it.label == "目标" })
        assertTrue(sections.none { it.label == "用户要求" })
    }

    @Test
    fun `要求里与前文重复的后半句也不会重复渲染`() {
        val sections = taskMemorySections(
            taskName = phrase,
            goal = "帮用户找到最近的照片",
            requirements = listOf(phrase, "帮用户找到最近的照片", "顺便清理缓存"),
            methods = emptyList(),
        )
        val userRequirements = sections.first { it.label == "用户要求" }
        assertEquals(listOf("顺便清理缓存"), userRequirements.lines)
        // 任务原文只活在抬头里，正文一遍都不该再抄
        assertEquals(0, occurrences(sections, phrase))
        assertEquals(1, occurrences(sections, "帮用户找到最近的照片"))
    }

    @Test
    fun `空白要求不渲染成空行`() {
        val sections = taskMemorySections(
            taskName = phrase,
            goal = "",
            requirements = listOf("", "   ", "再点一下确认"),
            methods = emptyList(),
        )
        assertEquals(listOf("用户要求"), sections.map { it.label })
        assertEquals(listOf("再点一下确认"), sections.single().lines)
        assertFalse(sections.any { it.lines.any { line -> line.isBlank() } })
    }

    @Test
    fun `完成方法与前文同文时只留第一次出现`() {
        val sections = taskMemorySections(
            taskName = phrase,
            goal = "",
            requirements = emptyList(),
            methods = listOf(phrase, "点击「最近」标签"),
        )
        assertEquals(listOf("完成方法"), sections.map { it.label })
        assertEquals(listOf("点击「最近」标签"), sections.single().lines)
    }
}
