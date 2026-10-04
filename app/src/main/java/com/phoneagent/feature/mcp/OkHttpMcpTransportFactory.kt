package com.phoneagent.feature.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的 MCP 传输工厂：为已配置的服务器建立真实 [McpClient]。
 * 用于应用内接入 HTTP 类 MCP 服务器。
 *
 * 按 MCP 流式 HTTP 规范（2025-03-26）对齐三件事：
 * 1. 请求带 `Accept: application/json, text/event-stream`（缺了会被规范实现的服务器直接拒掉）；
 * 2. 响应可能是 SSE（`text/event-stream`）：解析出 data 载荷，取最后一个能解析为 JSON 的作为响应体；
 * 3. 服务器在响应头下发 `Mcp-Session-Id` 时捕获，后续请求原样带回，否则会话会被服务端拒绝。
 */
object OkHttpMcpTransportFactory {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 全部服务器共享的 OkHttp 客户端（复用连接池）；读超时 15s，引擎侧 20s 调用超时应能覆盖 OkHttp 读超时 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 每条传输一份的会话状态：会话 id 属于单条连接，不能放在 object 级共享 */
    private class SessionState {
        @Volatile
        var sessionId: String = ""
    }

    /** 为单个服务器配置建立可调用的 [McpTransport] */
    fun transportOf(config: McpServerConfig): McpTransport {
        val state = SessionState()
        val httpCall: suspend (url: String, token: String, body: String) -> String = { url, token, bodyStr ->
            val req = Request.Builder().url(url)
                .addHeader("Accept", "application/json, text/event-stream")
                .apply {
                    if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
                    if (state.sessionId.isNotBlank()) addHeader("Mcp-Session-Id", state.sessionId)
                }
                .post(bodyStr.toRequestBody(jsonMedia))
                .build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(req).execute().use { resp ->
                    // 会话 id 在抛 HTTP 错误前捕获：部分服务器在 4xx 响应里也会下发新 id
                    resp.header("Mcp-Session-Id")?.trim()?.takeIf { it.isNotBlank() }?.let { state.sessionId = it }
                    if (!resp.isSuccessful) throw IllegalArgumentException("HTTP ${resp.code}")
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    val text = resp.body?.string() ?: ""
                    if (contentType.contains("text/event-stream")) parseSse(text) else text
                }
            }
        }
        return HttpMcpTransport(config, httpCall)
    }

    /**
     * 解析 SSE 响应：按事件累积 `data:` 行（事件以空行结尾，多行 data 按规范用换行拼接），
     * 取最后一个能解析为 JSON 的载荷作为响应体（JSON-RPC 响应/通知都是 JSON，注释行与 [DONE] 忽略）。
     */
    private fun parseSse(raw: String): String {
        var buf = StringBuilder()
        var lastJson: String? = null
        fun flush() {
            val payload = buf.toString().trim()
            buf = StringBuilder()
            if (payload.isEmpty() || payload == "[DONE]") return
            if (runCatching { Json.parseToJsonElement(payload) }.isSuccess) lastJson = payload
        }
        for (line in raw.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith("data:") -> {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(l.substring(5).removePrefix(" "))
                }
                l.isEmpty() -> flush()
            }
        }
        flush()
        return lastJson ?: ""
    }

    /** 为单个服务器配置建立可直接调用的 [McpClient] */
    fun clientOf(config: McpServerConfig): McpClient = McpClient(transportOf(config), config.name, config)

    /** 便捷：为一批服务器建立 [McpManager]（生产 DI 用） */
    fun managerOf(servers: List<McpServerConfig>): McpManager =
        McpManager(servers) { transportOf(it) }
}
