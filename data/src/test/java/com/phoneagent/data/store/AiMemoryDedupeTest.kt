package com.phoneagent.data.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 记忆去重的纯函数单测。
 * 目标是：近义改写（同义、添字、标点差异）判定为同一条；不同主题不误并。
 */
class AiMemoryDedupeTest {

    @Test
    fun `相同文本视为同一条`() {
        assertTrue(AiMemoryDedupe.isSame("用户喜欢简洁界面", "用户喜欢简洁界面"))
    }

    @Test
    fun `只有标点差异视为同一条`() {
        assertTrue(AiMemoryDedupe.isSame("用户喜欢简洁界面。", "用户喜欢简洁界面"))
        assertTrue(AiMemoryDedupe.isSame("用户，喜欢简洁界面", "用户喜欢简洁界面"))
    }

    @Test
    fun `近义改写视为同一条`() {
        // 长短关键词 switch 顺序不影响 bigram 判定
        assertTrue(AiMemoryDedupe.isSame("用户常在美团点黄焖鸡米饭", "用户通常在美团买黄焖鸡米饭"))
    }

    @Test
    fun `英文大小写差异视为同一条`() {
        assertTrue(AiMemoryDedupe.isSame("user prefers dark mode", "User prefers dark mode"))
    }

    @Test
    fun `不同主题不误并`() {
        assertFalse(AiMemoryDedupe.isSame("用户喜欢简洁界面", "用户常去菜市场买菜"))
    }

    @Test
    fun `空白内容不误判`() {
        assertFalse(AiMemoryDedupe.isSame("  ", "用户喜欢简洁界面"))
        assertFalse(AiMemoryDedupe.isSame("", ""))
    }
}