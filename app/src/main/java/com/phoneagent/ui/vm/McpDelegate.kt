package com.phoneagent.ui.vm

import com.phoneagent.data.store.McpStore
import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.feature.mcp.McpMarketplace
import com.phoneagent.feature.mcp.McpMarketplaceEntry
import com.phoneagent.feature.mcp.McpServerConfig
import com.phoneagent.feature.mcp.McpServerInfo
import com.phoneagent.feature.skill.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * MCP 服务器域：服务器列表、配置变更后的持久化、握手探测与市场条目。
 *
 * 绑定工具到技能这一处要刷新技能快照（`skill/mcp_*`），因此由构造参数传入
 * [refreshSkills] 回调，指向技能域自己的刷新入口——两个域之间只有这一个方向的一条边。
 */
class McpDelegate(
    private val mcpManager: McpManager,
    private val mcpStore: McpStore,
    private val skillRegistry: SkillRegistry,
    private val scope: CoroutineScope,
    private val refreshSkills: () -> Unit,
) {

    init {
        // 启动时加载持久化的 MCP 服务器配置
        scope.launch {
            val saved = mcpStore.load()
            if (saved.isNotEmpty() || mcpManager.servers.isNotEmpty()) {
                mcpManager.replaceAll(saved)
            }
        }
    }

    // ---- MCP 服务器（观察可变管理器，配置变更后持久化） ----
    val mcpServers: StateFlow<List<McpServerConfig>> = mcpManager.serversFlow

    /** 变更后把服务器列表持久化到本地 */
    private fun persistMcpServers() {
        scope.launch { mcpStore.save(mcpManager.servers) }
    }

    /** 新增 MCP 服务器；返回空串=成功，否则返回校验失败原因 */
    fun addMcpServer(name: String, url: String, token: String = ""): String {
        val v = mcpManager.addServer(McpServerConfig(name = name.trim(), url = url.trim(), token = token.trim()))
        if (!v.ok) return v.reason
        persistMcpServers()
        return ""
    }

    fun removeMcpServer(name: String) {
        if (mcpManager.removeServer(name)) persistMcpServers()
    }

    fun setMcpEnabled(name: String, enabled: Boolean) {
        if (mcpManager.setServerEnabled(name, enabled)) persistMcpServers()
    }

    suspend fun checkMcpServer(server: String): String {
        val r = mcpManager.check(server)
        return if (r.ok) "连接正常，枚举到 ${r.tools.size} 个工具" else "连接失败：${r.message}"
    }

    /** 握手 + 枚举，返回结构化服务器信息（能力/协议/工具/参数，供 UI 展示） */
    suspend fun describeMcpServer(server: String): McpServerInfo? = mcpManager.describe(server)

    /** 服务器最近一次请求/响应原文（需求 5：展示请求 JSON） */
    fun mcpLastRequest(server: String): String = mcpManager.lastRequestJson(server)
    fun mcpLastResponse(server: String): String = mcpManager.lastResponseJson(server)

    suspend fun bindMcpServerSkills(server: String): Int {
        val prefix = "mcp_${server.lowercase()}_"
        val added = mcpManager.bindToolsToSkills(server, prefix, skillRegistry)
        refreshSkills()
        return added
    }

    /** 内置 MCP 市场条目（分类/搜索） */
    val mcpMarketplace: List<McpMarketplaceEntry> get() = McpMarketplace.all()
    fun mcpMarketplaceByCategory(category: String): List<McpMarketplaceEntry> = McpMarketplace.byCategory(category)
    fun mcpMarketplaceSearch(keyword: String): List<McpMarketplaceEntry> = McpMarketplace.search(keyword)
}
