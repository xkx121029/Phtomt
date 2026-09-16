package com.phoneagent.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoneagent.feature.mcp.McpServerConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.mcpStore by preferencesDataStore(name = "mcp_servers")

/**
 * MCP 服务器配置持久化：用 DataStore 保存 [List<McpServerConfig>] 的 JSON。
 * 与 [com.phoneagent.data.prefs.AppSettings] 同一套 DataStore 风格。
 */
class McpStore(private val context: Context) {

    companion object {
        private val KEY_SERVERS = stringPreferencesKey("mcp_servers_json")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val serializer = ListSerializer(McpServerConfig.serializer())
    }

    /** 读取已持久化的服务器列表（无则空列表） */
    suspend fun load(): List<McpServerConfig> {
        val raw = context.mcpStore.data.first()[KEY_SERVERS] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    /** 保存服务器列表（覆盖写入） */
    suspend fun save(servers: List<McpServerConfig>) {
        val raw = json.encodeToString(serializer, servers)
        context.mcpStore.edit { prefs -> prefs[KEY_SERVERS] = raw }
    }
}
