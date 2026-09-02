package com.phoneagent.mcp

import com.phoneagent.skill.McpSkillTarget
import com.phoneagent.skill.Skill
import com.phoneagent.skill.SkillParam
import com.phoneagent.skill.SkillRegistry
import com.phoneagent.skill.SkillSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * MCP 管理器：管理一批已配置的 MCP 服务器，把服务器工具接入 Skill 体系，并提供有效性测试。
 *
 * 传输层通过 [transportFor] 注入，生产环境用 OkHttp 的 HttpMcpTransport，单测注入假传输。
 * MCP 与 Skill 共存：每个已注册工具自动生成一个 source=MCP 的 Skill，供 [SkillCompat] 分流调用。
 */
class McpManager(
    private val servers: List<McpServerConfig> = emptyList(),
    private val transportFor: (McpServerConfig) -> McpTransport,
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    fun enabledServers(): List<McpServerConfig> = servers.filter { it.enabled }

    fun byName(name: String): McpServerConfig? = servers.find { it.name == name }

    private fun client(config: McpServerConfig): McpClient = McpClient(transportFor(config), config.name)

    /** 服务器连接 + 工具枚举有效性测试 */
    suspend fun check(serverName: String): McpCheckResult {
        val cfg = byName(serverName) ?: return McpCheckResult(false, "未配置 MCP 服务器「$serverName」")
        return client(cfg).check()
    }

    /** 枚举某个服务器的全部工具 */
    suspend fun listTools(serverName: String): List<McpTool> {
        val cfg = byName(serverName) ?: return emptyList()
        return client(cfg).listTools()
    }

    /**
     * 把某个服务器枚举到的工具注册为 Skill（source=MCP）。
     * @param skillIdPrefix 生成的 Skill id 前缀，如 "mcp_filesystem"
     * @return 成功注册的 Skill 数
     */
    suspend fun bindToolsToSkills(serverName: String, skillIdPrefix: String, registry: SkillRegistry): Int {
        val cfg = byName(serverName) ?: return 0
        val tools = runCatching { client(cfg).listTools() }.getOrNull() ?: return 0
        var added = 0
        tools.forEach { tool ->
            val params = parseParamsFromSchema(tool.inputSchemaJson)
            val skill = Skill(
                id = "$skillIdPrefix${tool.name}",
                name = "${cfg.name}·${tool.name}",
                description = tool.description.ifBlank { "调用 MCP 工具 ${cfg.name}/${tool.name}" },
                source = SkillSource.MCP,
                category = "MCP",
                enabled = cfg.enabled,
                params = params,
                mcp = McpSkillTarget(server = cfg.name, tool = tool.name, argsTemplate = "{}", description = tool.description),
            )
            if (registry.add(skill)) added++
        }
        return added
    }

    /** 调用一个 MCP 工具（参数按 argsTemplate 模板替换） */
    suspend fun callTarget(target: McpSkillTarget, args: Map<String, String>): McpCallResult {
        val cfg = byName(target.server) ?: return McpCallResult(isError = true, content = "未配置 MCP 服务器「${target.server}」")
        val filled = fillTemplate(target.argsTemplate, args)
        val arguments = runCatching { json.parseToJsonElement(filled).jsonObject as JsonObject }.getOrElse { JsonObject(emptyMap()) }
        return client(cfg).callTool(target.tool, arguments)
    }

    private fun fillTemplate(template: String, args: Map<String, String>): String =
        args.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }

    /** 从 JSON Schema 提取顶层必填属性转成 Skill 参数（简化：取 properties 键） */
    private fun parseParamsFromSchema(schemaJson: String): List<SkillParam> {
        return runCatching {
            val root = json.parseToJsonElement(schemaJson).jsonObject
            val props = root["properties"]?.jsonObject ?: return@runCatching emptyList()
            val required = (root["required"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.toString().removeSurrounding("\"") } ?: emptyList()
            props.map { (name, schemaObj) ->
                val o = schemaObj.jsonObject
                val type = o["type"]?.toString()?.removeSurrounding("\"") ?: "text"
                val desc = o["description"]?.toString()?.removeSurrounding("\"") ?: ""
                val opts = (o["enum"] as? kotlinx.serialization.json.JsonArray)?.map { it.toString().removeSurrounding("\"") } ?: emptyList()
                SkillParam(
                    name = name, label = desc.ifBlank { name },
                    type = if (opts.isNotEmpty()) "select" else normType(type),
                    required = name in required,
                    description = desc, options = opts,
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