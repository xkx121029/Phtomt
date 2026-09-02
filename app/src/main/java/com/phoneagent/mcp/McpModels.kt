package com.phoneagent.mcp

import kotlinx.serialization.Serializable

/**
 * MCP（Model Context Protocol）接入所需的轻量数据模型。
 * 基于 JSON-RPC 2.0，与 MCP spec 的核心语义一致：
 * initialize / tools/list / tools/call。
 */

/** 一个已配置的 MCP 服务器 */
@Serializable
data class McpServerConfig(
    /** 服务器名（注册到 Skill 时作 server 名） */
    val name: String,
    /** 服务器地址，如 http://127.0.0.1:3000/mcp */
    val url: String,
    /** 可选鉴权头（Authorization: Bearer xxx），为空则不携带 */
    val token: String = "",
    /** 协议版本，默认 2025-03-26 */
    val protocolVersion: String = "2025-03-26",
    /** 是否启用 */
    val enabled: Boolean = true,
)

/** MCP 工具元信息 */
@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    /** JSON Schema 输入参数（JsonObject 序列化字符串），可为空 */
    val inputSchemaJson: String = "{}",
)

/** MCP 工具调用结果 */
data class McpCallResult(
    val isError: Boolean,
    val content: String,
)

/** 连接校验 / 工具枚举结果 */
data class McpCheckResult(
    val ok: Boolean,
    val message: String,
    val tools: List<McpTool> = emptyList(),
)