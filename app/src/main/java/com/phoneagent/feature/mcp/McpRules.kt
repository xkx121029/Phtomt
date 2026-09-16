package com.phoneagent.feature.mcp

/**
 * MCP 验证与使用规则引擎（纯 Kotlin，便于单元测试）。
 *
 * 分两类：
 * - **验证规则**：服务器配置是否合法（URL 格式、名称唯一非空、协议版本、token 可选），
 *   以及"可用"判定（握手 initialize + 枚举 tools/list 必须都成功）。
 * - **使用规则**：调用 MCP 工具前/后的约束（敏感只读、结果脱敏、错误归一化中文、参数校验）。
 */
object McpRules {

    /** 支持的协议版本集合（MCP 2025-03-26 为当前规范版本） */
    val supportedProtocolVersions = setOf("2025-03-26")

    // ==================== 验证规则 ====================

    /** 校验服务器名：非空且不含非法字符 */
    fun validateName(name: String): McpValidation {
        val n = name.trim()
        if (n.isEmpty()) return McpValidation(false, "服务器名不能为空")
        if (n.any { it.isWhitespace() }) return McpValidation(false, "服务器名不能包含空格")
        return McpValidation(true)
    }

    /** 校验地址：非空且为 http/https URL */
    fun validateUrl(url: String): McpValidation {
        val u = url.trim()
        if (u.isEmpty()) return McpValidation(false, "服务器地址不能为空")
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return McpValidation(false, "服务器地址须以 http:// 或 https:// 开头")
        }
        val host = u.substringAfter("://").substringBefore("/").substringBefore(":")
        if (host.isBlank()) return McpValidation(false, "服务器地址缺少有效主机名")
        return McpValidation(true)
    }

    /** 校验协议版本：为空则回退默认，非空则须在支持集合内 */
    fun validateProtocol(protocolVersion: String): McpValidation {
        val v = protocolVersion.trim()
        if (v.isEmpty()) return McpValidation(true, "协议版本为空，将使用默认 2025-03-26")
        if (v !in supportedProtocolVersions) return McpValidation(false, "不支持的协议版本「$v」（支持：${supportedProtocolVersions.joinToString()}）")
        return McpValidation(true)
    }

    /** 校验名称在现有列表中是否唯一（排除自身） */
    fun validateUniqueName(name: String, existing: List<McpServerConfig>): McpValidation {
        if (existing.any { it.name.equals(name.trim(), ignoreCase = true) && !it.name.equals(name.trim()) }) {
            return McpValidation(false, "服务器名「${name.trim()}」已存在")
        }
        return McpValidation(true)
    }

    /** 对一条完整配置做全量校验 */
    fun validateConfig(cfg: McpServerConfig): McpValidation {
        validateName(cfg.name).takeIf { !it.ok }?.let { return it }
        validateUrl(cfg.url).takeIf { !it.ok }?.let { return it }
        validateProtocol(cfg.protocolVersion).takeIf { !it.ok }?.let { return it }
        return McpValidation(true)
    }

    /** 校验 + 唯一性 */
    fun validateAdd(cfg: McpServerConfig, existing: List<McpServerConfig>): McpValidation {
        validateConfig(cfg).takeIf { !it.ok }?.let { return it }
        validateUniqueName(cfg.name, existing).takeIf { !it.ok }?.let { return it }
        return McpValidation(true)
    }

    // ==================== 使用规则 ====================

    /** 是否只读类工具：根据输入 schema 判断——无必填参数或参数全为可选，且无写入语义暗示 */
    fun isReadOnlyTool(tool: McpTool): Boolean {
        if (tool.params.any { it.required }) return false
        val t = tool.name.lowercase()
        if (t.contains("write") || t.contains("create") || t.contains("update") ||
            t.contains("delete") || t.contains("send") || t.contains("insert") ||
            t.contains("set") || t.contains("edit") || t.contains("upload")
        ) return false
        return true
    }

    /** 工具名合法性：非空且仅含常见标识符字符 */
    fun validateToolName(name: String): McpValidation =
        if (name.isBlank() || !name.matches(Regex("[\\w.-]+"))) {
            McpValidation(false, "非法工具名「$name」")
        } else McpValidation(true)

    /**
     * 调用前校验参数：缺少必填参数 → 拒绝调用。
     * @return 缺失的必填参数名列表（空 = 通过）
     */
    fun missingRequired(tool: McpTool, args: Map<String, String>): List<String> =
        tool.params.filter { it.required && args[it.name].isNullOrBlank() }.map { it.name }

    /** 使用规则的标准化错误文案（中文，供注入 AI 决策上下文） */
    fun usageError(toolName: String, reason: String): String =
        "MCP 工具「$toolName」调用失败：$reason"
}
