package com.phoneagent.execution

import com.phoneagent.engine.execution.ShellRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 执行链纯逻辑的回归用例（唯一定义点 ShellRules）。
 *
 * 覆盖三件事：
 * 1. Termux 工具链命令识别：白名单内命中、`raw ` 前缀 / 绝对路径 / `a && b` 组合都要认得出，
 *    系统命令（am/pm/settings）与撞名的 `curlx` 必须挡掉——判错会把 adb shell 里不存在的命令丢给 Shizuku；
 * 2. 通道选择：显式偏好只认自己那条，AUTO 按 无线ADB → Shizuku → Termux 顺位；
 * 3. 输出整理：非 HTML 原样（只按预算截断），HTML 先转 Markdown 再加表头，空输入原样返回。
 */
class ShellRulesTest {

    // ---- isTermuxToolCommand：命令分类 ----

    @Test
    fun isTermuxToolCommand_白名单内的工具命令全部命中() {
        val tools = listOf(
            "curl", "wget", "python", "python3", "pip", "pip3", "jq", "sed", "awk",
            "grep", "tr", "base64", "openssl", "git", "node", "npm", "npx", "ffmpeg",
        )
        for (t in tools) {
            assertTrue("应识别为 Termux 工具命令: $t", ShellRules.isTermuxToolCommand("$t --version"))
        }
    }

    @Test
    fun isTermuxToolCommand_raw前缀与绝对路径都要认得出() {
        assertTrue(ShellRules.isTermuxToolCommand("raw curl https://example.com"))
        assertTrue(ShellRules.isTermuxToolCommand("/data/data/com.termux/files/usr/bin/python3 -c 'print(1)'"))
        assertTrue(ShellRules.isTermuxToolCommand("/usr/bin/jq .name f.json"))
    }

    @Test
    fun isTermuxToolCommand_组合命令只看首段() {
        assertTrue(ShellRules.isTermuxToolCommand("curl -s https://a.example.com && echo done"))
        assertTrue(ShellRules.isTermuxToolCommand("jq .a f.json | head -5"))
    }

    @Test
    fun isTermuxToolCommand_大小写不敏感() {
        assertTrue(ShellRules.isTermuxToolCommand("CURL https://example.com"))
        // raw 前缀只认小写（原实现如此），命令名本身大小写不敏感
        assertTrue(ShellRules.isTermuxToolCommand("raw GIT status"))
    }

    @Test
    fun isTermuxToolCommand_系统命令与撞名一律不命中() {
        // am / pm / settings 在 adb shell 里有，走 Termux 反而会失败
        assertFalse(ShellRules.isTermuxToolCommand("am start -n com.a/.B"))
        assertFalse(ShellRules.isTermuxToolCommand("pm list packages"))
        assertFalse(ShellRules.isTermuxToolCommand("settings get global airplane_mode_on"))
        assertFalse(ShellRules.isTermuxToolCommand("input tap 100 200"))
        // 撞名不能误判：curlx / grep2 不是白名单命令
        assertFalse(ShellRules.isTermuxToolCommand("curlx https://example.com"))
        assertFalse(ShellRules.isTermuxToolCommand("grep2 foo f.txt"))
    }

    @Test
    fun isTermuxToolCommand_空与空白输入返回false() {
        assertFalse(ShellRules.isTermuxToolCommand(""))
        assertFalse(ShellRules.isTermuxToolCommand("   "))
        assertFalse(ShellRules.isTermuxToolCommand("raw   "))
    }

    // ---- pickChannel：通道选择真值表 ----

    private fun pick(
        preference: String,
        adb: Boolean = false,
        shizuku: Boolean = false,
        termux: Boolean = false,
    ) = ShellRules.pickChannel(preference, adb, shizuku, termux)

    @Test
    fun pickChannel_显式偏好只认自己那条() {
        assertEquals(ShellRules.Channel.ADB, pick("ADB", adb = true, shizuku = true, termux = true))
        assertEquals(ShellRules.Channel.SHIZUKU, pick("SHIZUKU", adb = true, shizuku = true, termux = true))
        assertEquals(ShellRules.Channel.TERMUX, pick("TERMUX", adb = true, shizuku = true, termux = true))
    }

    @Test
    fun pickChannel_显式偏好自己那条不可用_即使别的通道可用也不顶替() {
        assertEquals(ShellRules.Channel.NONE, pick("ADB", adb = false, shizuku = true, termux = true))
        assertEquals(ShellRules.Channel.NONE, pick("SHIZUKU", adb = true, shizuku = false, termux = true))
        assertEquals(ShellRules.Channel.NONE, pick("TERMUX", adb = true, shizuku = true, termux = false))
    }

    @Test
    fun pickChannel_AUTO按无线ADB到Shizuku到Termux顺位() {
        assertEquals(ShellRules.Channel.ADB, pick("AUTO", adb = true, shizuku = true, termux = true))
        assertEquals(ShellRules.Channel.SHIZUKU, pick("AUTO", adb = false, shizuku = true, termux = true))
        assertEquals(ShellRules.Channel.TERMUX, pick("AUTO", adb = false, shizuku = false, termux = true))
        assertEquals(ShellRules.Channel.NONE, pick("AUTO"))
    }

    @Test
    fun pickChannel_未知偏好值回落AUTO() {
        assertEquals(ShellRules.Channel.ADB, pick("GARBAGE", adb = true))
        assertEquals(ShellRules.Channel.TERMUX, pick("", termux = true))
        assertEquals(ShellRules.Channel.NONE, pick(""))
    }

    // ---- 失败原因：两条执行链各用一版 ----

    @Test
    fun noChannelReason_按偏好给出没有真实通道的原因() {
        assertEquals("无线 ADB 未连接，无真实 shell 通道", ShellRules.noChannelReason("ADB"))
        assertEquals("Shizuku 不可用，无真实 shell 通道", ShellRules.noChannelReason("SHIZUKU"))
        assertEquals("Termux 不可用（未安装或未授予 RUN_COMMAND 权限）", ShellRules.noChannelReason("TERMUX"))
        assertEquals("无可用 shell 通道", ShellRules.noChannelReason("AUTO"))
    }

    @Test
    fun noChannelReasonShort_端侧自发命令用简短版() {
        assertEquals("无线 ADB 未连接", ShellRules.noChannelReasonShort("ADB"))
        assertEquals("Shizuku 不可用", ShellRules.noChannelReasonShort("SHIZUKU"))
        assertEquals("Termux 不可用", ShellRules.noChannelReasonShort("TERMUX"))
        assertEquals("无可用 shell 通道", ShellRules.noChannelReasonShort("AUTO"))
    }

    // ---- renderShellOutput：嗅探、转换、预算截断 ----

    @Test
    fun renderShellOutput_空输入原样返回() {
        assertEquals("", ShellRules.renderShellOutput("", null))
    }

    @Test
    fun renderShellOutput_纯文本与JSON原样返回_绝不瞎转() {
        val text = "total 12\ndrwxr-xr-x 2 root root 4096 f"
        assertEquals(text, ShellRules.renderShellOutput(text, null))
        val json = """{"ok":true,"items":[1,2,3]}"""
        assertEquals(json, ShellRules.renderShellOutput(json, null))
    }

    @Test
    fun renderShellOutput_非HTML超预算时按预算截断() {
        val long = "a".repeat(ShellRules.SHELL_OUTPUT_BUDGET + 500)
        val out = ShellRules.renderShellOutput(long, null)
        assertEquals(ShellRules.SHELL_OUTPUT_BUDGET, out.length)
    }

    @Test
    fun renderShellOutput_HTML转成Markdown并加正文表头() {
        val html = "<html><body><p>你好世界</p></body></html>"
        val out = ShellRules.renderShellOutput(html, null)
        assertTrue(out.startsWith("网页正文（已自动转为 Markdown）："))
        assertTrue(out.contains("你好世界"))
    }

    @Test
    fun renderShellOutput_HTML带标题时先给网页标题() {
        val html = "<html><head><title>测试页面</title></head><body><p>正文内容</p></body></html>"
        val out = ShellRules.renderShellOutput(html, null)
        assertTrue(out.startsWith("网页标题：测试页面\n"))
        assertTrue(out.contains("网页正文（已自动转为 Markdown）："))
        assertTrue(out.contains("正文内容"))
    }
}
