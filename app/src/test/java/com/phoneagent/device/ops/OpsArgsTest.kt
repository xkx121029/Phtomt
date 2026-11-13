package com.phoneagent.device.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统直连的参数解析与格式化工具。
 *
 * 这些函数原先内联在 [DeviceOpsChannel] 的 14 个领域分支里，纯逻辑却无法单测。
 * 抽成 [OpsArgs] / [ShellQuote] / [OpsFormat] / [GlobPattern] 后在这里钉住契约。
 */
class OpsArgsTest {

    // ---------------- OpsArgs.int / long ----------------

    @Test
    fun `整数解析_正常值与首尾空白`() {
        val args = mapOf("a" to "42", "b" to " 7 ", "c" to "")
        assertEquals(42, OpsArgs.int(args, "a"))
        assertEquals(7, OpsArgs.int(args, "b"))
    }

    @Test
    fun `整数解析_缺省或非法一律返回null而不是抛异常`() {
        val args = mapOf("blank" to "   ", "text" to "abc", "float" to "3.14")
        assertNull(OpsArgs.int(args, "missing"))
        assertNull(OpsArgs.int(args, "blank"))
        assertNull(OpsArgs.int(args, "text"))
        assertNull(OpsArgs.int(args, "float"))
    }

    @Test
    fun `长整数解析_支持超出Int范围的毫秒时间戳`() {
        val args = mapOf("ts" to "1793817324000")
        assertEquals(1793817324000L, OpsArgs.long(args, "ts"))
        assertNull(OpsArgs.long(mapOf("ts" to "x"), "ts"))
    }

    // ---------------- OpsArgs.bool ----------------

    @Test
    fun `布尔解析_接受英文与中文写法`() {
        listOf("true", "1", "on", "yes", "开", "开启").forEach {
            assertEquals("应识别为 true: $it", true, OpsArgs.bool(mapOf("k" to it), "k"))
        }
        listOf("false", "0", "off", "no", "关", "关闭").forEach {
            assertEquals("应识别为 false: $it", false, OpsArgs.bool(mapOf("k" to it), "k"))
        }
    }

    @Test
    fun `布尔解析_大小写与空白不敏感`() {
        assertEquals(true, OpsArgs.bool(mapOf("k" to "  ON  "), "k"))
        assertEquals(false, OpsArgs.bool(mapOf("k" to "Off"), "k"))
    }

    @Test
    fun `布尔解析_无法识别时返回null交给调用方决定默认值`() {
        assertNull(OpsArgs.bool(mapOf("k" to "maybe"), "k"))
        assertNull(OpsArgs.bool(mapOf("k" to ""), "k"))
        assertNull(OpsArgs.bool(emptyMap(), "k"))
    }

    // ---------------- ShellQuote ----------------

    @Test
    fun `shell 单引号包裹普通文本`() {
        assertEquals("'abc'", ShellQuote.of("abc"))
    }

    @Test
    fun `shell 转义内部单引号_这是命令注入的边界`() {
        // 单引号内的单引号必须写成 '\''，否则模型给出的文字能闭合引号再拼接命令
        assertEquals("'a'\\''b'", ShellQuote.of("a'b"))
    }

    @Test
    fun `shell 转义后仍是单个参数_分号不会变成命令分隔符`() {
        val quoted = ShellQuote.of("x; rm -rf /")
        assertTrue("分号被包在引号内", quoted == "'x; rm -rf /'")
    }

    // ---------------- OpsFormat ----------------

    @Test
    fun `字节格式化_按MB与GB四舍五入`() {
        assertEquals("1MB", OpsFormat.mb(1024L * 1024))
        // 512KB 在 %.0f 下会进位成 1MB（不是 0），这是既有的展示口径
        assertEquals("1MB", OpsFormat.mb(512L * 1024))
        assertEquals("1.5GB", OpsFormat.gb((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `字节格式化_使用固定小数点不受系统语言影响`() {
        // 某些 locale 会把小数点渲染成逗号，导致 AI 解析失败；必须固定 Locale.US
        assertFalse(OpsFormat.gb((2.5 * 1024 * 1024 * 1024).toLong()).contains(','))
    }

    // ---------------- GlobPattern ----------------

    @Test
    fun `glob单星号不跨目录`() {
        val re = GlobPattern.toRegex("*.txt")
        assertTrue(re.matches("a.txt"))
        assertFalse("不应跨目录匹配", re.matches("dir/a.txt"))
    }

    @Test
    fun `glob双星号跨目录`() {
        val re = GlobPattern.toRegex("**.log")
        assertTrue(re.matches("a/b/c.log"))
    }

    @Test
    fun `glob问号匹配单个非分隔符字符`() {
        val re = GlobPattern.toRegex("a?c")
        assertTrue(re.matches("abc"))
        assertFalse(re.matches("a/c"))
    }

    @Test
    fun `glob正则元字符被转义而非当成语法`() {
        val re = GlobPattern.toRegex("a.b")
        assertTrue("点应匹配字面量", re.matches("a.b"))
        assertFalse("点不应匹配任意字符", re.matches("axb"))
    }
}
