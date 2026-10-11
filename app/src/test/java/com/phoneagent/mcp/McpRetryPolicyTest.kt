package com.phoneagent.mcp

import com.phoneagent.feature.mcp.McpRetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 重试策略：只对瞬时故障重试，确定性失败一律不重试——
 * 错判方向比漏判更糟（重试参数错只会白耗一次 20s 超时预算）。
 */
class McpRetryPolicyTest {

    @Test
    fun 端侧超时一律视为瞬时故障() {
        assertTrue(McpRetryPolicy.shouldRetry("任意文本", timedOut = true))
    }

    @Test
    fun 连接类错误重试() {
        assertTrue(McpRetryPolicy.shouldRetry("Connection refused: localhost:3000", timedOut = false))
        assertTrue(McpRetryPolicy.shouldRetry("connection reset by peer", timedOut = false))
        assertTrue(McpRetryPolicy.shouldRetry("MCP 调用异常：Unable to resolve host", timedOut = false))
        assertTrue(McpRetryPolicy.shouldRetry("ECONNRESET", timedOut = false))
        assertTrue(McpRetryPolicy.shouldRetry("SSL handshake failed", timedOut = false))
        assertTrue(McpRetryPolicy.shouldRetry("connect timed out", timedOut = false))
    }

    @Test
    fun 服务端5xx重试_客户端4xx不重试() {
        assertTrue(McpRetryPolicy.shouldRetry("HTTP 502 Bad Gateway", timedOut = false))
        assertTrue(McpRetryPolicy.shouldRetry("500 Internal Server Error", timedOut = false))
        assertFalse(McpRetryPolicy.shouldRetry("HTTP 400 Bad Request", timedOut = false))
        assertFalse(McpRetryPolicy.shouldRetry("401 unauthorized", timedOut = false))
    }

    @Test
    fun 参数校验与未配置类中文拒绝不重试() {
        assertFalse(McpRetryPolicy.shouldRetry("技能「查余额」缺少必填参数：「账号」。", timedOut = false))
        assertFalse(McpRetryPolicy.shouldRetry("MCP 未配置：技能「read_file」无法调用（未启用任何 MCP 服务器）", timedOut = false))
        assertFalse(McpRetryPolicy.shouldRetry("未知工具 nope", timedOut = false))
    }

    @Test
    fun 未知错误默认不重试() {
        assertFalse(McpRetryPolicy.shouldRetry("神秘错误", timedOut = false))
    }
}
