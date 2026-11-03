package com.phoneagent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具卡片原子层的口径测试（纯 JVM，不渲染 Compose）。
 *
 * 钉住三件事：
 * 1. **谁按等宽排**：只有命令类工具的参数/输出需要原文照排，其余走 Markdown，
 *    避免页面自己再维护一份 run_command/bash/shell 名单；
 * 2. **协议名不外露**：未知工具回落「其他工具」，空名回落「工具」；
 * 3. **耗时口径**：未完成（null / 0 / 负值）不给数字，避免运行中每帧跳数。
 */
class ToolAtomsTest {

    @Test
    fun `命令类工具标记为等宽排版`() {
        listOf("run_command", "bash", "shell").forEach {
            assertTrue(it, codingToolIdentity(it).monospace)
        }
    }

    @Test
    fun `文件与检索类工具不按等宽排版`() {
        listOf("read_file", "grep", "git_read", "js_eval", null).forEach {
            assertFalse("$it", codingToolIdentity(it).monospace)
        }
    }

    @Test
    fun `协议名不外露`() {
        assertEquals("执行命令", codingToolIdentity("run_command").label)
        assertEquals("读取工作区文件", codingToolIdentity("workspace_read").label)
        assertEquals("其他工具", codingToolIdentity("some_future_tool").label)
        assertEquals("工具", codingToolIdentity(null).label)
    }

    @Test
    fun `耗时只在跑完后给出`() {
        assertNull(toolTimingText(null))
        assertNull(toolTimingText(0))
        assertNull(toolTimingText(-1))
        assertEquals("450 毫秒", toolTimingText(450))
        assertEquals("2.0 秒", toolTimingText(2000))
        assertEquals("1 分 5 秒", toolTimingText(65_000))
    }
}
