package com.phoneagent.mcp

import com.phoneagent.skill.McpSkillTarget
import com.phoneagent.skill.Skill
import com.phoneagent.skill.SkillParam
import com.phoneagent.skill.SkillRegistry
import com.phoneagent.skill.SkillSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * MCP 管理器：管理一批已配置的 MCP 服务器，把服务器工具接入 Skill 体系，并提供有效性测试。
 *
 * 传输层通过 [transportFor] 注入，生产环境用 OkHttp 的 HttpMcpTransport，单测注入假传输。
 * MCP 与 Skill 共存：每个已注册工具自动生成一个 source=MCP 的 Skill，供 [SkillCompat] 分流调用。
 *
 * 服务器列表为**可变**（add/remove/setEnabled/replaceAll），并通过 [serversFlow] 暴露给 UI 观察。
 * 持久化由外部 [McpStore] 负责（ViewModel 在变更后调用 save）。
 */
class McpManager(
    initialServers: List<McpServerConfig> = emptyList(),
    private val transportFor: (McpServerConfig) -> McpTransport,
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private val _servers = MutableStateFlow<List<McpServerConfig>>(initialServers)
    val serversFlow: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()
    val servers: List<McpServerConfig> get() = _servers.value

    fun enabledServers(): List<McpServerConfig> = servers.filter { it.enabled }

    fun byName(name: String): McpServerConfig? = servers.find { it.name == name }

    private fun client(config: McpServerConfig): McpClient = McpClient(transportFor(config), config.name, config)

    // ==================== 可变管理 ====================

    /** 校验并新增一台服务器；返回校验结果（失败时不加入） */
    fun addServer(cfg: McpServerConfig): McpValidation {
        val v = McpRules.validateAdd(cfg, servers)
        if (!v.ok) return v
        _servers.value = servers + cfg
        return McpValidation(true)
    }

    /** 删除服务器；成功返回 true */
    fun removeServer(name: String): Boolean {
        val existed = byName(name) != null
        if (!existed) return false
        _servers.value = servers.filterNot { it.name == name }
        return true
    }

    /** 启停某服务器 */
    fun setServerEnabled(name: String, enabled: Boolean): Boolean {
        val cfg = byName(name) ?: return false
        _servers.value = servers.map { if (it.name == name) it.copy(enabled = enabled) else it }
        return true
    }

    /** 整体替换（启动加载持久化配置时调用） */
    fun replaceAll(list: List<McpServerConfig>) {
        _servers.value = list
    }

    // ==================== 信息获取 ====================

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

    /** 握手 + 枚举，返回结构化服务器信息（能力/协议/工具/参数） */
    suspend fun describe(serverName: String): McpServerInfo? {
        val cfg = byName(serverName) ?: return null
        return runCatching { client(cfg).describe() }.getOrNull()
    }

    /** 传输层最近请求/响应原文（供 UI 展示 JSON，需求 5） */
    fun lastRequestJson(serverName: String): String = (byName(serverName)?.let { client(it) } as? McpClient)?.lastRequestJson ?: ""
    fun lastResponseJson(serverName: String): String = (byName(serverName)?.let { client(it) } as? McpClient)?.lastResponseJson ?: ""

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
            val params = tool.params.map { p ->
                SkillParam(
                    name = p.name, label = p.label.ifBlank { p.name },
                    type = p.type, required = p.required,
                    description = p.description, options = p.options, defaultValue = p.defaultValue,
                )
            }
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

    /** 调用一个 MCP 工具（参数按 argsTemplate 模板替换；调用前按规则校验工具名与必填参数） */
    suspend fun callTarget(target: McpSkillTarget, args: Map<String, String>): McpCallResult {
        val cfg = byName(target.server) ?: return McpCallResult(isError = true, content = "未配置 MCP 服务器「${target.server}」")
        McpRules.validateToolName(target.tool).takeIf { !it.ok }?.let {
            return McpCallResult(isError = true, content = it.reason)
        }
        val filled = fillTemplate(target.argsTemplate, args)
        val arguments = runCatching { json.parseToJsonElement(filled).jsonObject as JsonObject }.getOrElse { JsonObject(emptyMap()) }
        return client(cfg).callTool(target.tool, arguments)
    }

    private fun fillTemplate(template: String, args: Map<String, String>): String =
        args.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }
}
