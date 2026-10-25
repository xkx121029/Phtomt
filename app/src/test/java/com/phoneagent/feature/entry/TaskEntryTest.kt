package com.phoneagent.feature.entry

import android.content.Intent
import com.phoneagent.feature.entry.TaskEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部入口解析器的纯函数单测：深链 / Google App Actions / 分享 / 快捷方式 extras。
 * （Uri/Intent 在 JVM 单测不可实例化，故全部走 TaskEntry 的 parse* 纯函数核心；
 *   对应的 fromXxx 包装只做字段摘取，字段摘取逻辑由 Android 侧集成验证。）
 */
class TaskEntryTest {

    // ---- 深链 ----

    @Test
    fun `deep link task with text`() {
        val t = TaskEntry.parseDeepLink("hpa", "task", "打开微信")
        assertEquals("打开微信", (t as TaskEntry.Target.Task).text)
    }

    @Test
    fun `deep link resume`() {
        val t = TaskEntry.parseDeepLink("hpa", "resume", null)
        assertTrue(t is TaskEntry.Target.Navigate)
        assertTrue((t as TaskEntry.Target.Navigate).resume)
    }

    @Test
    fun `deep link without text is null`() {
        assertNull(TaskEntry.parseDeepLink("hpa", "task", null))
        assertNull(TaskEntry.parseDeepLink("hpa", "task", "   "))
    }

    @Test
    fun `unknown scheme and host are null`() {
        assertNull(TaskEntry.parseDeepLink("https", "task", "a"))
        assertNull(TaskEntry.parseDeepLink("hpa", "unknown", "a"))
        assertNull(TaskEntry.parseDeepLink(null, null, null))
    }

    // ---- Google App Actions（VIEW hpa://task + text extra） ----

    @Test
    fun `google action query lands in text extra`() {
        val t = TaskEntry.parseDeepLink("hpa", "task", null, "查天气")
        assertEquals("查天气", (t as TaskEntry.Target.Task).text)
    }

    @Test
    fun `google action uri query takes precedence`() {
        val t = TaskEntry.parseDeepLink("hpa", "task", "甲", "乙")
        assertEquals("甲", (t as TaskEntry.Target.Task).text)
    }

    @Test
    fun `google action requires task host`() {
        assertNull(TaskEntry.parseDeepLink("hpa", "resume", null, "x"))
        assertNull(TaskEntry.parseDeepLink(null, "task", null, "x"))
    }

    // ---- 分享 / 文本选择 ----

    @Test
    fun `share plain text`() {
        val t = TaskEntry.parseShare(Intent.ACTION_SEND, "https://example.com/article")
        assertEquals(
            "https://example.com/article",
            (t as TaskEntry.Target.Task).text,
        )
    }

    @Test
    fun `process text`() {
        val t = TaskEntry.parseShare(Intent.ACTION_PROCESS_TEXT, "总结这段话")
        assertEquals("总结这段话", (t as TaskEntry.Target.Task).text)
    }

    @Test
    fun `share without text is null`() {
        assertNull(TaskEntry.parseShare(Intent.ACTION_SEND, null))
        assertNull(TaskEntry.parseShare(Intent.ACTION_SEND, ""))
        assertNull(TaskEntry.parseShare(Intent.ACTION_MAIN, "x"))
        assertNull(TaskEntry.parseShare(null, "x"))
    }

    // ---- 快捷方式 / extras ----

    @Test
    fun `shortcut extra task text`() {
        val t = TaskEntry.parseExtras(openMemory = false, resume = false, taskText = "设个明早七点的闹钟")
        assertEquals("设个明早七点的闹钟", (t as TaskEntry.Target.Task).text)
    }

    @Test
    fun `shortcut open memory`() {
        val t = TaskEntry.parseExtras(openMemory = true, resume = false, taskText = null)
        assertTrue((t as TaskEntry.Target.Navigate).memory)
    }

    @Test
    fun `shortcut resume`() {
        val t = TaskEntry.parseExtras(openMemory = false, resume = true, taskText = null)
        assertTrue((t as TaskEntry.Target.Navigate).resume)
    }

    @Test
    fun `memory wins over resume`() {
        val t = TaskEntry.parseExtras(openMemory = true, resume = true, taskText = null)
        assertTrue((t as TaskEntry.Target.Navigate).memory)
    }

    @Test
    fun `no extras is null`() {
        assertNull(TaskEntry.parseExtras(openMemory = false, resume = false, taskText = null))
        assertNull(TaskEntry.parseExtras(openMemory = false, resume = false, taskText = "   "))
    }
}
