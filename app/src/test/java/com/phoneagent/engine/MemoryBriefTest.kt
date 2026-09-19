package com.phoneagent.engine

import com.phoneagent.data.store.AiMemoryEntry
import com.phoneagent.data.store.ProfileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆简报拼装单测。
 *
 * 逐步骤决策每轮都要带上记忆，长度失控会挤掉页面元素树，
 * 所以这里重点断言两件事：**总量不超上限**、**排序稳定且按使用频次优先**。
 */
class MemoryBriefTest {

    private fun memory(content: String, useCount: Int = 0, updatedAt: Long = 0L) = AiMemoryEntry(
        id = content.hashCode().toLong(),
        content = content,
        useCount = useCount,
        updatedAt = updatedAt,
    )

    @Test
    fun `没有任何记忆时返回空串`() {
        assertEquals("", MemoryBrief.build())
        assertEquals("", MemoryBrief.build(profile = emptyList(), memories = emptyList()))
    }

    @Test
    fun `画像与记忆都注入`() {
        val brief = MemoryBrief.build(
            profile = listOf(ProfileEntry(key = "语言", category = "preference", value = "中文")),
            memories = listOf(memory("用户常在美团点黄焖鸡米饭")),
        )
        assertTrue(brief.contains("用户画像："))
        assertTrue(brief.contains("语言:中文"))
        assertTrue(brief.contains("已知记忆："))
        assertTrue(brief.contains("黄焖鸡米饭"))
    }

    @Test
    fun `高使用次数排在前面`() {
        val brief = MemoryBrief.build(
            memories = listOf(
                memory("很少用的记忆", useCount = 0),
                memory("经常用的记忆", useCount = 9),
            ),
        )
        assertTrue(
            "高频记忆应排在低频之前",
            brief.indexOf("经常用的记忆") < brief.indexOf("很少用的记忆"),
        )
    }

    @Test
    fun `总量不超过上限`() {
        // 20 条长记忆，远超 600 字上限
        val many = (1..20).map { memory("这是一条很长的记忆内容用来测试截断行为编号$it" + "填充".repeat(10)) }
        val brief = MemoryBrief.build(memories = many)
        assertTrue("简报长度 ${brief.length} 超过上限", brief.length <= MemoryBrief.MAX_CHARS)
    }

    @Test
    fun `单条过长会被截断`() {
        val brief = MemoryBrief.build(memories = listOf(memory("很长的记忆".repeat(50))))
        assertTrue(brief.length <= MemoryBrief.MAX_CHARS)
        assertTrue("应保留开头内容", brief.contains("很长的记忆"))
    }

    @Test
    fun `空内容条目被跳过`() {
        val brief = MemoryBrief.build(memories = listOf(memory(""), memory("   ")))
        assertEquals("", brief)
    }
}