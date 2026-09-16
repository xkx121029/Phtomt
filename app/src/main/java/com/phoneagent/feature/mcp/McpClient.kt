package com.phoneagent.feature.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP 传输层抽象：屏蔽具体网络（HTTP / stdio / 测试替身）。
 * 返回整 JSON-RPC result 或抛出带用户可读中文信息的 [McpException]。
 */
interface McpTransport {
    suspend fun call(method: String, params: JsonObject?): JsonElement
}

/** JSON-RPC 层错误，携带用户可读中文消息 */
class McpException(message: String, val code: Int = -32603) : Exception(message)

/**
 * 真机 HTTP 传输：POST JSON-RPC 到 MCP 服务器（OkHttp）。
 * 支持 MCP 兼容的流式 HTTP 端点（一次性返回 result/error）。
 * 记录最近一次请求/响应原文，供 UI 展示（需求 5：添加 MCP 时的请求信息 JSON）。
 */
class HttpMcpTransport(
    private val config: McpServerConfig,
    private val httpCall: suspend (url: String, token: String, body: String) -> String,
) : McpTransport {
    private var id = 0

    /** 最近一次请求原文（JSON-RPC），供 UI 展示 */
    @Volatile
    var lastRequest: String = ""
        private set

    /** 最近一次响应原文（JSON-RPC），供 UI 展示 */
    @Volatile
    var lastResponse: String = ""
        private set

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        val req = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(++id))
            put("method", JsonPrimitive(method))
            params?.let { put("params", it) }
        }
        lastRequest = req.toString()
        val raw = try {
            httpCall(config.url, config.token, req.toString())
        } catch (e: Exception) {
            lastResponse = ""
            throw McpException("无法连接 MCP 服务器「${config.name}」(${config.url}): ${e.message}", -32000)
        }
        lastResponse = raw
        if (raw.isBlank()) throw McpException("MCP 服务器「${config.name}」返回空响应", -32001)
        val obj = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw McpException("MCP 服务器「${config.name}」返回了非 JSON 响应", -32603)
        }
        obj["error"]?.jsonObject?.let { err ->
            val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "JSON-RPC 错误"
            throw McpException(msg, err["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -32603)
        }
        val result = obj["result"] ?: throw McpException("MCP 服务器「${config.name}」响应缺少 result 字段", -32603)
        return result
    }
}

/**
 * MCP 客户端：封装 JSON-RPC 方法为 Kotlin 调用。
 * 与传输层解耦，可在 JVM 单测中替换为假传输验证协议逻辑。
 */
class McpClient(
    private val transport: McpTransport,
    private val serverName: String,
    private val serverConfig: McpServerConfig? = null,
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
    }

    /** 最近一次握手成功解析出的 capabilities / serverInfo（供 describe 复用） */
    private var lastCapabilities: Map<String, String> = emptyMap()
    private var lastServerInfo: Map<String, String> = emptyMap()

    /** 传输层最近请求/响应原文（供 UI 展示 JSON） */
    val lastRequestJson: String get() = (transport as? HttpMcpTransport)?.lastRequest ?: ""
    val lastResponseJson: String get() = (transport as? HttpMcpTransport)?.lastResponse ?: ""

    /** initialize 握手：返回服务器 capabilities */
    suspend fun initialize(clientName: String = "happy-phone-agent"): JsonElement =
        transport.call("initialize", buildJsonObject {
            put("protocolVersion", JsonPrimitive(serverConfig?.protocolVersion ?: PROTOCOL_VERSION))
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", JsonPrimitive(clientName))
                put("version", JsonPrimitive("0.1"))
            })
        })

    /** tools/list：枚举可用工具（解析出结构化参数） */
    suspend fun listTools(): List<McpTool> {
        val result = transport.call("tools/list", null)
        val tools = result.jsonObject["tools"]?.jsonArray ?: return emptyList()
        return tools.mapNotNull { t ->
            runCatching {
                val o = t.jsonObject
                val schemaJson = o["inputSchema"]?.toString() ?: "{}"
                val params = parseParamsFromSchema(schemaJson)
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                McpTool(
                    name = name,
                    description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    inputSchemaJson = schemaJson,
                    params = params,
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    isReadOnly = McpRules.isReadOnlyTool(
                        McpTool(name = name, inputSchemaJson = schemaJson, params = params)
                    ),
                )
            }.getOrNull()
        }
    }

    /** 握手 + 枚举：返回结构化的服务器信息（含 capabilities、serverInfo、工具及参数） */
    suspend fun describe(): McpServerInfo {
        val initResult = initialize()
        parseCapabilities(initResult)
        val tools = listTools()
        return McpServerInfo(
            name = serverName,
            url = serverConfig?.url ?: "",
            protocolVersion = serverConfig?.protocolVersion ?: PROTOCOL_VERSION,
            enabled = serverConfig?.enabled ?: true,
            capabilities = lastCapabilities,
            serverInfo = lastServerInfo,
            tools = tools,
        )
    }

    /** tools/call：调用工具 */
    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val result = transport.call("tools/call", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", arguments)
        })
        return parseCallResult(result)
    }

    private fun parseCallResult(result: JsonElement): McpCallResult {
        val contentSb = StringBuilder()
        var isError = false
        (result.jsonObject["content"] as? JsonArray)?.forEach { item ->
            val type = item.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            val text = item.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            when (type) {
                "text" -> if (!text.isNullOrBlank()) contentSb.appendLine(text)
                else -> if (!text.isNullOrBlank()) contentSb.appendLine(text)
            }
        }
        isError = result.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        return McpCallResult(isError = isError, content = contentSb.toString().trim())
    }

    /** 连接有效性测试：握手 + 枚举工具，并记录最后一次请求/响应原文 */
    suspend fun check(): McpCheckResult = try {
        initialize()
        val tools = listTools()
        McpCheckResult(ok = true, message = "连接成功，工具数=${tools.size}", tools = tools)
    } catch (e: McpException) {
        McpCheckResult(ok = false, message = e.message ?: "连接失败")
    } catch (e: Exception) {
        McpCheckResult(ok = false, message = "连接失败: ${e.message}")
    }

    /** 从 initialize 结果解析 capabilities 与 serverInfo */
    private fun parseCapabilities(initResult: JsonElement) {
        lastCapabilities = stringMap(initResult.jsonObject["capabilities"]?.jsonObject)
        lastServerInfo = stringMap(initResult.jsonObject["serverInfo"]?.jsonObject)
    }

    private fun stringMap(o: JsonObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.mapNotNull { (k, v) ->
            val s = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            k to s
        }.toMap()
    }

    /** 从 JSON Schema 提取属性转成结构化 Skill 参数（含 type/required/enum/default/description） */
    fun parseParamsFromSchema(schemaJson: String): List<McpParam> {
        return runCatching {
            val root = Json.parseToJsonElement(schemaJson).jsonObject
            val props = root["properties"]?.jsonObject ?: return@runCatching emptyList()
            val required = (root["required"] as? JsonArray)
                ?.map { it.toString().removeSurrounding("\"") } ?: emptyList()
            props.map { (name, schemaObj) ->
                val o = schemaObj.jsonObject
                val type = o["type"]?.toString()?.removeSurrounding("\"") ?: "string"
                val desc = o["description"]?.toString()?.removeSurrounding("\"") ?: ""
                val opts = (o["enum"] as? JsonArray)?.map { it.toString().removeSurrounding("\"") } ?: emptyList()
                val def = o["default"]?.toString()?.removeSurrounding("\"")
                McpParam(
                    name = name,
                    label = desc.ifBlank { name },
                    type = if (opts.isNotEmpty()) "select" else normType(type),
                    required = name in required,
                    description = desc,
                    options = opts,
                    defaultValue = def,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun normType(t: String): String = when (t.lowercase()) {
        "string" -> "text"
        "integer", "number" -> "number"
        "boolean" -> "boolean"
        else -> "text"
    }
}

/** 便捷创建基于 OkHttp 的 MCP 客户端（调用方注入 httpCall） */
fun createHttpMcpClient(
    config: McpServerConfig,
    httpCall: suspend (url: String, token: String, body: String) -> String,
): McpClient =
    McpClient(HttpMcpTransport(config, httpCall), serverName = config.name, serverConfig = config)
