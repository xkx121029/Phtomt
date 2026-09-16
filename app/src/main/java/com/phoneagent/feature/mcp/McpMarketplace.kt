package com.phoneagent.feature.mcp.McpMarketplace

/**
 * 内置 MCP 市场：预设一批常用 MCP 服务器的快捷配置。
 *
 * 分类：
 * - 经典工具类（[CATEGORY_TOOLS]）：filesystem / sqlite / github / notion 等本地或自托管 MCP，
 *   默认需要用户填写自己的 URL / Token（[McpMarketplaceEntry.isPublic] = false）。
 * - 公共开放API（[CATEGORY_PUBLIC]）：天气、新闻、汇率、股票等公开 MCP/HTTP 端点，
 *   无需鉴权（[McpMarketplaceEntry.isPublic] = true），默认 URL 可直接使用。
 *
 * 市场条目仅作为"快捷填充"入口：用户点击选用后回填新增表单，仍可修改 URL/Token 后添加。
 * 纯 Kotlin、无 Android 依赖，便于单元测试。
 */
object McpMarketplace {

    const val CATEGORY_TOOLS = "经典工具类"
    const val CATEGORY_PUBLIC = "公共开放API"

    private val entries = listOf(
        // ---- 经典工具类（需用户自配 URL / Token） ----
        McpMarketplaceEntry(
            id = "tools_filesystem", name = "文件系统", category = CATEGORY_TOOLS,
            url = "http://127.0.0.1:3000/mcp", description = "本地文件读写/目录浏览（需自行部署 mcp-filesystem）",
        ),
        McpMarketplaceEntry(
            id = "tools_sqlite", name = "SQLite", category = CATEGORY_TOOLS,
            url = "http://127.0.0.1:3001/mcp", description = "SQLite 数据库查询/建表（需自行部署 mcp-sqlite）",
        ),
        McpMarketplaceEntry(
            id = "tools_github", name = "GitHub", category = CATEGORY_TOOLS,
            url = "http://127.0.0.1:3002/mcp", defaultToken = "github_pat_xxx",
            description = "仓库/Issue/PR 管理（需配置 GitHub Token，Bearer 鉴权）",
        ),
        McpMarketplaceEntry(
            id = "tools_notion", name = "Notion", category = CATEGORY_TOOLS,
            url = "http://127.0.0.1:3003/mcp", defaultToken = "ntn_xxx",
            description = "Notion 页面/数据库读写（需配置 Notion API Key）",
        ),
        // ---- 公共开放 API（无需鉴权，默认 URL 可直接用） ----
        McpMarketplaceEntry(
            id = "pub_weather", name = "天气查询", category = CATEGORY_PUBLIC,
            url = "https://wttr.in", protocolVersion = "2025-03-26", isPublic = true,
            description = "按城市查询天气（开放接口，返回文本）",
        ),
        McpMarketplaceEntry(
            id = "pub_fx", name = "汇率查询", category = CATEGORY_PUBLIC,
            url = "https://api.frankfurter.app", protocolVersion = "2025-03-26", isPublic = true,
            description = "实时汇率查询（无需 Key）",
        ),
        McpMarketplaceEntry(
            id = "pub_news", name = "新闻头条", category = CATEGORY_PUBLIC,
            url = "https://saurav.tech/NewsAPI", protocolVersion = "2025-03-26", isPublic = true,
            description = "聚合新闻头条（公开端点）",
        ),
        McpMarketplaceEntry(
            id = "pub_coingecko", name = "加密货币行情", category = CATEGORY_PUBLIC,
            url = "https://api.coingecko.com/api/v3", protocolVersion = "2025-03-26", isPublic = true,
            description = "加密货币实时行情（公开端点）",
        ),
    )

    fun all(): List<McpMarketplaceEntry> = entries

    fun byId(id: String): McpMarketplaceEntry? = entries.find { it.id == id }

    fun categories(): List<String> = listOf(CATEGORY_TOOLS, CATEGORY_PUBLIC)

    fun byCategory(category: String): List<McpMarketplaceEntry> = entries.filter { it.category == category }

    /** 按名称/描述关键词搜索 */
    fun search(keyword: String): List<McpMarketplaceEntry> {
        val k = keyword.trim()
        if (k.isEmpty()) return entries
        return entries.filter {
            it.name.contains(k, ignoreCase = true) || it.description.contains(k, ignoreCase = true)
        }
    }

    /** 生成一条待添加的服务器配置（用户可再改） */
    fun toConfig(entry: McpMarketplaceEntry): McpServerConfig = McpServerConfig(
        name = entry.name,
        url = entry.url,
        token = entry.defaultToken,
        protocolVersion = entry.protocolVersion,
        enabled = true,
    )
}

/** 一条市场预设 */
data class McpMarketplaceEntry(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val description: String = "",
    val protocolVersion: String = "2025-03-26",
    val defaultToken: String = "",
    /** true = 公共开放（无需鉴权，默认 URL 可直接用）；false = 需用户自配 URL/Token */
    val isPublic: Boolean = false,
)
