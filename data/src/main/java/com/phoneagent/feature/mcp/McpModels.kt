package com.phoneagent.feature.mcp

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

/** MCP 工具的一个入参（由 JSON Schema properties 解析而来） */
@Serializable
data class McpParam(
    /** 参数名（schema 里的 key） */
    val name: String,
    /** 展示标签：优先取 description */
    val label: String = "",
    /** 类型：text / number / boolean / select（enum 归并为 select） */
    val type: String = "text",
    /** 是否必填（出现在 schema.required 中） */
    val required: Boolean = false,
    /** 说明 */
    val description: String = "",
    /** type=select 时的候选项（enum 值） */
    val options: List<String> = emptyList(),
    /** 默认值（字符串形式） */
    val defaultValue: String? = null,
)

/** MCP 工具元信息 */
@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    /** JSON Schema 输入参数（JsonObject 序列化字符串），可为空 */
    val inputSchemaJson: String = "{}",
    /** 由 inputSchemaJson 解析出的结构化参数（运行时填充，供 UI/提示词展示） */
    val params: List<McpParam> = emptyList(),
    /** 工具标题（可空） */
    val title: String = "",
    /** 是否只读类工具（由输入 schema 判断：无必填写字段且语义偏向查询/读取） */
    val isReadOnly: Boolean = false,
)

/** MCP 服务器握手/枚举后得到的结构化信息（供 UI 展示与提示词注入） */
data class McpServerInfo(
    /** 服务器名 */
    val name: String,
    /** 服务器地址 */
    val url: String,
    /** 协议版本 */
    val protocolVersion: String,
    /** 是否启用 */
    val enabled: Boolean,
    /** 握手返回的 capabilities（键 → 值字符串） */
    val capabilities: Map<String, String> = emptyMap(),
    /** 服务器元信息（serverInfo 字段，如 name/version） */
    val serverInfo: Map<String, String> = emptyMap(),
    /** 枚举到的工具 */
    val tools: List<McpTool> = emptyList(),
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

/** MCP 规则引擎的校验结果 */
data class McpValidation(
    val ok: Boolean,
    val reason: String = "",
)
