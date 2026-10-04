package com.phoneagent.engine.execution

import com.phoneagent.core.text.HtmlToMarkdown

/**
 * shell 执行链的纯逻辑判据（Executor 角色）。
 *
 * 从 [com.phoneagent.engine.AgentEngine] 下沉而来，只保留"看得见的判定"：
 * 命令分类、通道选择、输出整理与预算截断。真正的副作用（Shizuku / 无线 ADB / Termux /
 * 无障碍的真实调用、`lastShellOutput` 状态写入）仍留在引擎里，这里不碰任何设备或状态。
 *
 * 抽出来的理由：通道选择原先在 [com.phoneagent.engine.AgentEngine.runRealShell] 与
 * [com.phoneagent.engine.AgentEngine.execShellViaChannel] 里各写了一遍，改一处漏一处就会
 * 出现"那条命令走的通道和用户选的不一样"。这里做成唯一判据，可单测。
 */
internal object ShellRules {

    /** 执行通道偏好字面量（与设置里的 [com.phoneagent.data.prefs.AppSettings.Settings.executionChannel] 取值一致） */
    const val CHANNEL_ADB = "ADB"
    const val CHANNEL_SHIZUKU = "SHIZUKU"
    const val CHANNEL_TERMUX = "TERMUX"

    /**
     * shell 输出回注 AI 的字符预算。
     * 与内置浏览器的 `BrowserBridge.MAX_RESULT_CHARS` 同量级：网页 `curl` 回来要先转成
     * Markdown 再回传，原来的 1200 只够看到 `<head>` 开头。
     */
    const val SHELL_OUTPUT_BUDGET = 4000

    /** 转 Markdown 时给"网页标题 / 网页正文（已自动转为 Markdown）"两行表头留的余量 */
    const val SHELL_MD_HEADER_RESERVE = 300

    /**
     * Termux 工具链命令白名单：这些命令在 adb shell 中通常不存在（Android 只带 toybox），
     * 故命中时一律交给 Termux 通道执行，不受执行通道偏好影响。
     * 注意：sed/grep/tr 是 toybox 自带的，adb 里就能跑，不进此名单（误入会把简单命令
     * 错误地赶到 Termux 通道）；只有 awk 及网络/脚本类工具真正需要 Termux。
     */
    private val TERMUX_TOOL_COMMANDS = setOf(
        "curl", "wget", "python", "python3", "pip", "pip3", "jq", "awk",
        "base64", "openssl", "git", "node", "npm", "npx", "ffmpeg",
    )

    /** 真实 shell 通道。NONE = 当前偏好下没有任何可用通道 */
    enum class Channel(val label: String) {
        ADB("无线 ADB"),
        SHIZUKU("Shizuku"),
        TERMUX("Termux"),
        NONE("无"),
    }

    /**
     * 判断是否为 Termux 工具链命令（curl / python / jq 等）。
     * 取首个 token 的命令名并去掉绝对路径；`a && b` 这类组合只看首段。
     */
    fun isTermuxToolCommand(cmd: String): Boolean {
        val t = cmd.trim()
        // raw 前缀大小写不敏感，命中后按固定长度剥前缀（removePrefix 只认小写，会漏掉 RAW 前缀）
        val body = if (t.startsWith("raw ", ignoreCase = true)) t.substring(4).trim() else t
        if (body.isBlank()) return false
        val head = body.split(Regex("[\\s;&|]+")).firstOrNull()
            ?.substringAfterLast('/')
            ?.lowercase()
            .orEmpty()
        return head in TERMUX_TOOL_COMMANDS
    }

    /**
     * 按执行通道偏好与三条通道的实时可用性，选出真正要用的那条。
     * AUTO（偏好为其他值）：无线 ADB → Shizuku → Termux；显式偏好只认自己那条，不可用即 [Channel.NONE]。
     */
    fun pickChannel(
        preference: String,
        adbReady: Boolean,
        shizukuReady: Boolean,
        termuxReady: Boolean,
    ): Channel = when (preference) {
        CHANNEL_ADB -> if (adbReady) Channel.ADB else Channel.NONE
        CHANNEL_SHIZUKU -> if (shizukuReady) Channel.SHIZUKU else Channel.NONE
        CHANNEL_TERMUX -> if (termuxReady) Channel.TERMUX else Channel.NONE
        // AUTO：无线 ADB → Shizuku → Termux（普通应用权限，仅作第三顺位兜底）
        else -> when {
            adbReady -> Channel.ADB
            shizukuReady -> Channel.SHIZUKU
            termuxReady -> Channel.TERMUX
            else -> Channel.NONE
        }
    }

    /** [com.phoneagent.engine.AgentEngine.runRealShell] 的失败原因：说明"为什么没有真实通道" */
    fun noChannelReason(preference: String): String = when (preference) {
        CHANNEL_ADB -> "无线 ADB 未连接，无真实 shell 通道"
        CHANNEL_SHIZUKU -> "Shizuku 不可用，无真实 shell 通道"
        CHANNEL_TERMUX -> "Termux 不可用（未安装或未授予 RUN_COMMAND 权限）"
        else -> "无可用 shell 通道"
    }

    /** [com.phoneagent.engine.AgentEngine.execShellViaChannel] 的失败原因（端侧自发命令，不进 AI 上下文，简短即可） */
    fun noChannelReasonShort(preference: String): String = when (preference) {
        CHANNEL_ADB -> "无线 ADB 未连接"
        CHANNEL_SHIZUKU -> "Shizuku 不可用"
        CHANNEL_TERMUX -> "Termux 不可用"
        else -> "无可用 shell 通道"
    }

    /**
     * shell 输出整理成给 AI 读的文本。
     *
     * **顺序很关键：先嗅探 + 转 Markdown，再按预算截断**。反过来的话（先 take 再转），
     * 4000 字符的 HTML 前缀常常停在 `<head>`/`<nav>` 中段，转换器要么拿不到正文、
     * 要么把 `<div class="` 残片当成正文，等于白转。
     */
    fun renderShellOutput(raw: String, baseUrl: String?): String {
        // 不是 HTML（JSON / 纯文本 / dumpsys 的 XML）一律原样回传，绝不瞎转；空输入走这条也等价于原样返回
        if (!HtmlToMarkdown.isHtml(raw)) return raw.take(SHELL_OUTPUT_BUDGET)
        val r = HtmlToMarkdown.convert(raw, baseUrl, SHELL_OUTPUT_BUDGET - SHELL_MD_HEADER_RESERVE)
        val title = if (r.title.isNotBlank()) "网页标题：${r.title}\n" else ""
        return if (r.markdown.isBlank()) raw.take(SHELL_OUTPUT_BUDGET)
        else title + "网页正文（已自动转为 Markdown）：\n" + r.markdown
    }
}
