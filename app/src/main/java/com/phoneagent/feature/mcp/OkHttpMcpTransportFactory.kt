package com.phoneagent.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的 MCP 传输工厂：为已配置的服务器建立真实 [McpClient]。
 * 用于应用内接入 HTTP 类 MCP 服务器。
 */
object OkHttpMcpTransportFactory {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 为单个服务器配置建立可调用的 [McpTransport] */
    fun transportOf(config: McpServerConfig): McpTransport {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
        val httpCall: suspend (url: String, token: String, body: String) -> String = { url, token, bodyStr ->
            val req = Request.Builder().url(url)
                .apply {
                    if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
                    addHeader("Content-Type", "application/json")
                }
                .post(bodyStr.toRequestBody(jsonMedia))
                .build()
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalArgumentException("HTTP ${resp.code}")
                    resp.body?.string() ?: ""
                }
            }
        }
        return HttpMcpTransport(config, httpCall)
    }

    /** 为单个服务器配置建立可直接调用的 [McpClient] */
    fun clientOf(config: McpServerConfig): McpClient = McpClient(transportOf(config), config.name, config)

    /** 便捷：为一批服务器建立 [McpManager]（生产 DI 用） */
    fun managerOf(servers: List<McpServerConfig>): McpManager =
        McpManager(servers) { transportOf(it) }
}