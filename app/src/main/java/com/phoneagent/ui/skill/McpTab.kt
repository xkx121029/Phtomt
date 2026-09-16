package com.phoneagent.ui.skill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phoneagent.feature.mcp.McpMarketplace
import com.phoneagent.feature.mcp.McpServerInfo
import com.phoneagent.feature.mcp.McpTool
import com.phoneagent.engine.prompt.PromptTemplate
import com.phoneagent.device.shell.AdbError
import com.phoneagent.device.shell.AdbPhase
import com.phoneagent.device.shell.AdbWirelessTransport
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillParam
import com.phoneagent.feature.skill.SkillSource
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppCard
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch

// ============ MCP Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpTab(vm: MainViewModel) {
    val servers by vm.mcpServers.collectAsState()
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var serverToken by remember { mutableStateOf("") }
    var showRequest by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var searchKw by remember { mutableStateOf("") }
    var marketCat by remember { mutableStateOf("全部") }
    var expanded by remember { mutableStateOf<String?>(null) }
    // name -> 描述结果；null 表示已尝试但失败
    var described by remember { mutableStateOf<Map<String, McpServerInfo?>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    val marketplaceEntries = remember(searchKw, marketCat) {
        val all = if (searchKw.isBlank()) McpMarketplace.all() else McpMarketplace.search(searchKw)
        if (marketCat == "全部") all else all.filter { it.category == marketCat }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 内置 MCP 市场（需求 6） ----
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("MCP 市场", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = searchKw, onValueChange = { searchKw = it },
                    label = { Text("搜索市场（名称/描述）") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", McpMarketplace.CATEGORY_TOOLS, McpMarketplace.CATEGORY_PUBLIC).forEach { c ->
                        FilterChip(
                            selected = marketCat == c,
                            onClick = { marketCat = c },
                            label = { Text(c) },
                        )
                    }
                }
                if (marketplaceEntries.isEmpty()) {
                    Text("没有匹配的市场条目。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    marketplaceEntries.forEach { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    if (entry.isPublic) StatusPill("公共", MaterialTheme.colorScheme.primary)
                                    else StatusPill("需配置", MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(entry.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = {
                                serverName = entry.name
                                serverUrl = entry.url
                                serverToken = entry.defaultToken
                                showRequest = true
                                msg = "已选用「${entry.name}」，可按需修改后添加"
                            }) { Text("选用") }
                        }
                    }
                }
            }
        }

        // ---- 新增服务器（需求 5：展示请求信息 JSON） ----
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("新增 MCP 服务器", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverName, onValueChange = { serverName = it },
                    label = { Text("服务器名（如 filesystem）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = serverUrl, onValueChange = { serverUrl = it },
                    label = { Text("地址（http://…/mcp）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = serverToken, onValueChange = { serverToken = it },
                    label = { Text("Token（Bearer 鉴权，可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showRequest = !showRequest }) {
                    Text(if (showRequest) "收起请求信息" else "查看发送给服务器的请求信息（JSON）")
                }
                if (showRequest) {
                    JsonPreviewBox(mcpInitializeRequestJson(serverName.trim(), serverUrl.trim(), serverToken.trim()))
                }
                Button(
                    enabled = serverName.isNotBlank() && serverUrl.isNotBlank(),
                    onClick = {
                        val err = vm.addMcpServer(serverName.trim(), serverUrl.trim(), serverToken.trim())
                        msg = if (err.isEmpty()) "已添加服务器「${serverName.trim()}」" else err
                        if (err.isEmpty()) { serverName = ""; serverUrl = ""; serverToken = ""; showRequest = false }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("添加并保存") }
            }
        }

        if (msg != null) {
            StatusPill(msg!!, MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
        }

        Text("已配置服务器（${servers.size}）", style = MaterialTheme.typography.titleSmall)
        if (servers.isEmpty()) {
            Text(
                "尚未配置 MCP 服务器。MCP 工具接入后会自动生成 source=MCP 的 Skill，与内置技能共存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            servers.forEach { cfg ->
                val isExpanded = expanded == cfg.name
                AppItemCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(cfg.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (cfg.enabled) StatusPill("启用", MaterialTheme.colorScheme.primary)
                                else StatusPill("停用", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(cfg.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { expanded = if (isExpanded) null else cfg.name }) {
                            Icon(if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, contentDescription = "详情")
                        }
                    }
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("启用", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = { vm.setMcpEnabled(cfg.name, it) },
                        )
                        TextButton(onClick = { scope.launch { msg = vm.checkMcpServer(cfg.name) } }) { Text("测试有效性") }
                        TextButton(onClick = { scope.launch { val n = vm.bindMcpServerSkills(cfg.name); msg = "已绑定 $n 个工具为 Skill" } }) { Text("绑定为技能") }
                        TextButton(onClick = { vm.removeMcpServer(cfg.name) }) { Text("删除") }
                    }
                    if (isExpanded) {
                        HorizontalDivider()
                        // 描述信息加载
                        val info = described[cfg.name]
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                described = described + (cfg.name to null)
                                scope.launch { described = described + (cfg.name to vm.describeMcpServer(cfg.name)) }
                            }) { Text("拉取服务器信息") }
                            TextButton(onClick = {
                                scope.launch { msg = vm.checkMcpServer(cfg.name); }
                            }) { Text("刷新请求/响应") }
                        }
                        when {
                            info == null && described.containsKey(cfg.name) -> {
                                Text("服务器信息拉取失败，请检查地址与连接。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp))
                            }
                            info != null -> {
                                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("协议版本：${info.protocolVersion}", style = MaterialTheme.typography.bodySmall)
                                    if (info.serverInfo.isNotEmpty()) {
                                        Text("服务器元信息：${info.serverInfo.entries.joinToString("、") { "${it.key}=${it.value}" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (info.capabilities.isNotEmpty()) {
                                        Text("能力：${info.capabilities.entries.joinToString("、") { it.key }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("工具（${info.tools.size}）：", style = MaterialTheme.typography.labelMedium)
                                    if (info.tools.isEmpty()) {
                                        Text("未枚举到工具。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        info.tools.forEach { McpToolDetail(it) }
                                    }
                                }
                            }
                        }
                        // 最近请求/响应 JSON（需求 5）
                        val req = vm.mcpLastRequest(cfg.name)
                        val resp = vm.mcpLastResponse(cfg.name)
                        if (req.isNotBlank()) {
                            Text("最近请求（JSON-RPC）：", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                            JsonPreviewBox(req)
                        }
                        if (resp.isNotBlank()) {
                            Text("最近响应（JSON-RPC）：", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                            JsonPreviewBox(resp)
                        }
                        if (req.isBlank() && resp.isBlank()) {
                            Text("尚无请求/响应记录。点击「测试有效性」或「刷新请求/响应」后在此查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 单个 MCP 工具详情（名称/只读/说明/参数） */
@Composable
private fun McpToolDetail(tool: McpTool) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (tool.isReadOnly) StatusPill("只读", MaterialTheme.colorScheme.secondary)
        }
        if (tool.description.isNotBlank()) {
            Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tool.params.isNotEmpty()) {
            tool.params.forEach { p ->
                val reqMark = if (p.required) "*" else ""
                val opts = if (p.options.isNotEmpty()) "[${p.options.joinToString("/")}]" else ""
                Text(
                    "  · ${p.name}${reqMark}(${p.type}$opts)${p.description.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 等宽字体 JSON 预览框 */
@Composable
private fun JsonPreviewBox(json: String) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AppRadii.Chip))
            .padding(10.dp),
    ) {
        Text(
            json,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 生成"添加 MCP 服务器"时的初始化 JSON-RPC 请求预览（需求 5） */
private fun mcpInitializeRequestJson(name: String, url: String, token: String): String {
    val auth = if (token.isNotBlank()) "Authorization: Bearer ${token}" else "（无鉴权）"
    return buildString {
        appendLine("// POST $url")
        appendLine("// 请求头: $auth")
        appendLine("{")
        appendLine("  \"jsonrpc\": \"2.0\",")
        appendLine("  \"id\": 1,")
        appendLine("  \"method\": \"initialize\",")
        appendLine("  \"params\": {")
        appendLine("    \"protocolVersion\": \"2025-03-26\",")
        appendLine("    \"capabilities\": {},")
        appendLine("    \"clientInfo\": { \"name\": \"happy-phone-agent\", \"version\": \"0.1\" }")
        appendLine("  }")
        appendLine("}")
    }
}