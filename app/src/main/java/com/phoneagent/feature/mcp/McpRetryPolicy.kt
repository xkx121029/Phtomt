package com.phoneagent.feature.mcp

/**
 * MCP 调用重试策略：只对「瞬时故障」自动重试，确定性失败一律不重试。
 *
 * 分类依据（错误文本小写匹配 + 状态码正则）：
 * - 重试：超时 / 连接失败 / reset / refused / EOF / broken pipe / SSL / 无法解析主机 / 5xx
 * - 不重试：参数校验类中文拒绝语（缺参/未配置服务器/未知工具）、4xx、权限拒绝——
 *   这类错误重试只会得到同样结果，白耗一次 20s 超时预算
 */
object McpRetryPolicy {

    /** 最大自动重试次数（不含首次调用） */
    const val MAX_RETRY = 1

    /** 重试前退避间隔 */
    const val BACKOFF_MS = 500L

    /** 瞬时故障关键词（错误文本小写后匹配） */
    private val RETRYABLE_KEYWORDS = listOf(
        "timeout", "timed out", "超时",
        "connection", "connect", "reset", "refused", "eof", "broken pipe",
        "unreachable", "unavailable", "unable to resolve", "unknownhost",
        "ssl", "握手",
    )

    /** 确定性失败关键词（命中即不重试，优先级高于瞬时关键词） */
    private val NON_RETRYABLE_KEYWORDS = listOf(
        "缺少必填参数", "未配置", "未知工具", "无权限", "已停用",
        "unauthorized", "forbidden", "invalid", "not found", "malformed",
    )

    /** 判定一次失败是否值得重试；[timedOut] 为端侧 withTimeout 主动中断（视为瞬时故障） */
    fun shouldRetry(errText: String, timedOut: Boolean): Boolean {
        if (timedOut) return true
        val text = errText.lowercase()
        if (NON_RETRYABLE_KEYWORDS.any { text.contains(it) }) return false
        if (RETRYABLE_KEYWORDS.any { text.contains(it) }) return true
        // 5xx 服务端错误视为瞬时故障；4xx 客户端错误不重试
        if (Regex("\\b5\\d{2}\\b").containsMatchIn(text)) return true
        if (Regex("\\b4\\d{2}\\b").containsMatchIn(text)) return false
        return false
    }
}
