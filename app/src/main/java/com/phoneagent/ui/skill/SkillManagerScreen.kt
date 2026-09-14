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
import com.phoneagent.mcp.McpMarketplace
import com.phoneagent.mcp.McpServerInfo
import com.phoneagent.mcp.McpTool
import com.phoneagent.prompt.PromptTemplate
import com.phoneagent.shizuku.adb.AdbError
import com.phoneagent.shizuku.adb.AdbPhase
import com.phoneagent.skill.Skill
import com.phoneagent.skill.SkillParam
import com.phoneagent.skill.SkillSource
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppCard
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch

/**
 * 技能与能力管理页（HPA 迭代 A7）。
 * Tab 栏布局：技能 / MCP / 无线 ADB / 提示词。
 * - 技能：Skill 卡片列表 + 批量增删 + 新建/编辑/详情
 * - MCP：配置服务器 + 有效性测试 + 绑定为 Skill
 * - 无线 ADB：Shizuku 状态 + 无线调试配对引导（配对码输入/连接状态/错误提示）
 * - 提示词：自定义可变提示词模板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(SkillTab.SKILLS) }
    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(
            title = "技能与能力",
            subtitle = "Skill · MCP · 无线 ADB · 提示词",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            SkillTab.entries.forEachIndexed { index, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SkillTab.entries.size),
                ) { Text(t.label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            SkillTab.SKILLS -> SkillsTab(vm)
            SkillTab.MCP -> McpTab(vm)
            SkillTab.WIRELESS_ADB -> WirelessAdbTab(vm)
            SkillTab.PROMPTS -> PromptsTab(vm)
        }
    }
}

private enum class SkillTab(val label: String) {
    SKILLS("技能"),
    MCP("MCP"),
    WIRELESS_ADB("无线ADB"),
    PROMPTS("提示词"),
}

// ============ 技能 Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsTab(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val skills by vm.skills.collectAsState()
    var batchMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(mutableSetOf<String>()) }
    var editing by remember { mutableStateOf<Skill?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    // 顶部操作条
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { showCreate = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("新增技能")
        }
        OutlinedButton(onClick = { batchMode = !batchMode; selected.clear() }) {
            Text(if (batchMode) "完成" else "批量管理")
        }
    }
    if (batchMode) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("已选 ${selected.size} 项", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selected.clear() }) { Text("全不选") }
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    vm.removeSkills(selected.toSet())
                    selected.clear()
                },
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除所选")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = {
                    // 导出到剪贴板/提示：展示 JSON 清单
                    val json = vm.exportSkills()
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("skills", json))
                    android.widget.Toast.makeText(context, "已导出 ${vm.skillAll().count { !it.isBuiltIn }} 个技能到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出")
                }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = cm.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (text.isNullOrBlank()) {
                        android.widget.Toast.makeText(context, "剪贴板为空或不是技能 JSON", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val n = vm.importSkills(text)
                        android.widget.Toast.makeText(context, "已导入 $n 个技能", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入")
                }
            }
        }
        items(skills, key = { it.id }) { skill ->
            SkillCard(
                skill = skill,
                batchMode = batchMode,
                checked = skill.id in selected,
                onCheckedChange = { on -> if (on) selected.add(skill.id) else selected.remove(skill.id) },
                onOpen = { editing = skill },
                onToggleEnabled = { vm.setSkillEnabled(skill.id, !skill.enabled) },
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    // 新建/编辑 弹窗
    if (showCreate) {
        SkillEditorDialog(
            initial = null,
            suggestedId = vm.nextSkillId(),
            onDismiss = { showCreate = false },
            onSave = { s -> vm.addSkill(s); showCreate = false },
        )
    }
    editing?.let { skill ->
        SkillEditorDialog(
            initial = skill,
            suggestedId = skill.id,
            onDismiss = { editing = null },
            onSave = { s -> vm.updateSkill(s); editing = null },
        )
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    batchMode: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    AppItemCard(onClick = onOpen) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (batchMode) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            } else {
                Icon(
                    if (skill.source == SkillSource.MCP) Icons.Rounded.Terminal else Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = if (skill.source == SkillSource.MCP) { MaterialTheme.colorScheme.secondary } else { MaterialTheme.colorScheme.primary },
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (skill.isBuiltIn) {
                        StatusPill("内置", MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (skill.source == SkillSource.MCP) {
                        StatusPill("MCP", MaterialTheme.colorScheme.secondary)
                    }
                }
                Text(
                    skill.description.ifBlank { skill.id },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(skill.legacyIntent ?: skill.category, MaterialTheme.colorScheme.tertiary)
                    if (skill.params.isNotEmpty()) {
                        StatusPill("参数×${skill.params.size}", MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (!batchMode) {
                Switch(checked = skill.enabled, onCheckedChange = { onToggleEnabled() })
            } else {
                IconButton(onClick = { onOpen() }) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
            }
        }
    }
}

/** 参数草稿（供编辑器结构化编辑） */
private data class ParamDraft(
    var name: String = "",
    var label: String = "",
    var type: String = "text",
    var required: Boolean = false,
    var description: String = "",
    var defaultValue: String = "",
    var optionsText: String = "",
)

/** 把草稿转成 SkillParam */
private fun ParamDraft.toParam(): SkillParam? {
    if (name.isBlank()) return null
    return SkillParam(
        name = name.trim(),
        label = label.ifBlank { name.trim() },
        type = type,
        required = required,
        description = description.trim().ifBlank { null },
        defaultValue = defaultValue.trim().ifBlank { null },
        options = if (type == "select") optionsText.split(',').map { it.trim() }.filter { it.isNotBlank() } else emptyList(),
    )
}

private fun SkillParam.toDraft(): ParamDraft = ParamDraft(
    name = name,
    label = label,
    type = type,
    required = required,
    description = description ?: "",
    defaultValue = defaultValue ?: "",
    optionsText = options.joinToString(","),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditorDialog(
    initial: Skill?,
    suggestedId: String,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "自定义") }
    var legacyIntent by remember { mutableStateOf(initial?.legacyIntent ?: "") }
    var params by remember { mutableStateOf(initial?.params?.map { it.toDraft() } ?: emptyList<ParamDraft>()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize().padding(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (initial == null) "新增技能" else "编辑技能",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称（必填，AI 与用户可见）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // id：新建时展示建议值，编辑时只读
                OutlinedTextField(
                    value = suggestedId, onValueChange = {},
                    label = { Text("ID（${if (initial == null) "将自动生成" else "不可修改"}）") },
                    singleLine = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category, onValueChange = { category = it },
                    label = { Text("分类（如 自定义 / 系统 / 工具）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("说明（喂给 AI 以决定何时调用）") },
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                )
                OutlinedTextField(
                    value = legacyIntent, onValueChange = { legacyIntent = it },
                    label = { Text("兼容旧命令意图（如 open_app，可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("参数（${params.size}）", style = MaterialTheme.typography.titleSmall)
                if (params.isEmpty()) {
                    Text("暂无参数。可点击下方「添加参数」为技能补充输入项。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    params.forEachIndexed { i, p ->
                        SkillParamEditor(
                            draft = p,
                            onChange = { updated -> params = params.toMutableList().apply { this[i] = updated } },
                            onDelete = { params = params.toMutableList().apply { removeAt(i) } },
                        )
                    }
                }
                OutlinedButton(onClick = { params = params + ParamDraft() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加参数")
                }

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val skillParams = params.mapNotNull { it.toParam() }
                            val base = initial?.copy(
                                name = name.trim(),
                                description = desc.trim(),
                                category = category.trim().ifBlank { "自定义" },
                                legacyIntent = legacyIntent.trim().ifBlank { null },
                                params = skillParams,
                            ) ?: Skill(
                                id = suggestedId,
                                name = name.trim(),
                                description = desc.trim(),
                                source = SkillSource.INTENT,
                                category = category.trim().ifBlank { "自定义" },
                                legacyIntent = legacyIntent.trim().ifBlank { null },
                                params = skillParams,
                                createdBy = "user",
                            )
                            onSave(base)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                }
            }
        }
    }
}

/** 单个参数的完整编辑区 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillParamEditor(draft: ParamDraft, onChange: (ParamDraft) -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AppRadii.Chip))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("参数", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("删除") }
        }
        OutlinedTextField(
            value = draft.name, onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text("参数名（必填，如 city）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.label, onValueChange = { onChange(draft.copy(label = it)) },
            label = { Text("标签（默认取参数名）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("类型", style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("text", "number", "boolean", "select").forEachIndexed { index, t ->
                SegmentedButton(
                    selected = draft.type == t,
                    onClick = { onChange(draft.copy(type = t)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                ) { Text(t) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.required, onCheckedChange = { onChange(draft.copy(required = it)) })
            Text("必填", style = MaterialTheme.typography.labelMedium)
        }
        if (draft.type == "select") {
            OutlinedTextField(
                value = draft.optionsText, onValueChange = { onChange(draft.copy(optionsText = it)) },
                label = { Text("候选项（英文逗号分隔，如 a,b,c）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = draft.defaultValue, onValueChange = { onChange(draft.copy(defaultValue = it)) },
            label = { Text("默认值（可留空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.description, onValueChange = { onChange(draft.copy(description = it)) },
            label = { Text("说明（可留空）") },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        )
    }
}

// ============ MCP Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpTab(vm: MainViewModel) {
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

// ============ 无线 ADB Tab ============

@Composable
private fun WirelessAdbTab(vm: MainViewModel) {
    val shizukuState by vm.shizukuState.collectAsState()
    val adbStatus by vm.adbStatus.collectAsState()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 执行通道状态：无线 ADB 为主、Shizuku 可选增强
        val settings by vm.settingsFlow.collectAsState()
        val channelText = when (settings.executionChannel) {
            "ADB" -> "无线ADB优先"
            "SHIZUKU" -> "Shizuku优先"
            else -> "自动（无线ADB优先）"
        }
        val adbReady = adbStatus.phase == AdbPhase.READY
        val shizukuReady = shizukuState == com.phoneagent.shizuku.ShizukuManager.State.READY
        AppCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp).background(
                        if (adbReady) Success
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        RoundedCornerShape(AppRadii.Chip),
                    ))
                    Column(Modifier.weight(1f)) {
                        Text("执行通道（$channelText）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "无线 ADB：${adbPhaseText(adbStatus.phase)} · Shizuku：${if (shizukuReady) "就绪" else "可选未启用"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { msg = null; vm.ensureAdbReady { msg = it } }) { Text("检测通路") }
                }
                Text(
                    if (adbReady) "无线 ADB 已就绪，可直接执行 shell；Shizuku 为可选增强。"
                    else "无线 ADB 为主执行通道；配对连接后即可执行 shell，Shizuku 启动失败不影响执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 无线调试配对引导（分步）
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("无线调试配对（无需 Root）", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "按以下步骤配对后即可执行 shell；Shizuku 为可选增强，启动失败不影响执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 分步引导（序号圆点 + 人话 + 状态色）
                val stepStatuses = adbStepStatuses(adbStatus.phase, adbStatus.message)
                listOf(
                    "开启系统「无线调试」",
                    "输入 6 位配对码",
                    "配对并连接无线 ADB",
                    "可选拉起 Shizuku 增强",
                ).forEachIndexed { i, title ->
                    AdbStepRow(i + 1, title, stepStatuses[i])
                }

                // 失败/断线时提供重新配对入口
                if (adbStatus.phase == AdbPhase.FAILED || adbStatus.phase == AdbPhase.DISCONNECTED) {
                    OutlinedButton(
                        onClick = {
                            vm.cancelAdbPairing()
                            vm.startAdbDiscovery()
                            code = ""
                            msg = "已重新开始配对流程，请保持「使用配对码配对设备」界面…"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新配对")
                    }
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = { c -> code = c.filter { it.isDigit() }.take(6) },
                    label = { Text("6 位配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            vm.startAdbDiscovery()
                            msg = "已开始搜索无线调试服务，搜到后将在通知栏请求配对码…"
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("自动发现服务")
                    }
                    OutlinedButton(
                        onClick = { vm.pairAdb(code) { msg = it } },
                        enabled = code.length == 6,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("手动配对")
                    }
                }
                // 向导当前说话（Search 结果也回显到这里）
                val flowMsg by vm.adbPairingMessageFlow.collectAsState()
                if (flowMsg.isNotBlank()) {
                    StatusPill(flowMsg, MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
                }

                // 连接状态 + 错误提示
                if (adbStatus.isUserActionRequired || adbStatus.phase == AdbPhase.FAILED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            adbStatus.message.ifBlank { AdbError.UNKNOWN.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (adbStatus.phase != AdbPhase.UNPAIRED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Success)
                        Spacer(Modifier.width(6.dp))
                        Text(adbStatus.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (msg != null) {
            StatusPill(msg!!, if (msg!!.contains("就绪") || msg!!.contains("成功")) Success else MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun adbPhaseText(phase: AdbPhase): String = when (phase) {
    AdbPhase.UNPAIRED -> "未配对"
    AdbPhase.WAITING_CODE -> "等待输入配对码"
    AdbPhase.PAIRING -> "配对中"
    AdbPhase.PAIRED -> "已配对"
    AdbPhase.BOOTING -> "拉起 Shizuku 中"
    AdbPhase.READY -> "就绪"
    AdbPhase.DISCONNECTED -> "已断开"
    AdbPhase.FAILED -> "失败"
}

/** 无线 ADB 分步引导：某一步的状态 */
private enum class AdbStepStatus { DONE, ACTIVE, ERROR, PENDING }

/** 由当前 [AdbPhase] 与错误消息推导 4 步引导状态 */
private fun adbStepStatuses(phase: AdbPhase, message: String): List<AdbStepStatus> {
    return when (phase) {
        AdbPhase.READY -> listOf(AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.DONE)
        AdbPhase.WAITING_CODE -> listOf(AdbStepStatus.DONE, AdbStepStatus.ACTIVE, AdbStepStatus.PENDING, AdbStepStatus.PENDING)
        AdbPhase.PAIRING, AdbPhase.PAIRED -> listOf(AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.ACTIVE, AdbStepStatus.PENDING)
        AdbPhase.BOOTING -> listOf(AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.ACTIVE)
        AdbPhase.FAILED, AdbPhase.DISCONNECTED -> {
            val errStep = when {
                message.contains("配对") -> 3
                message.contains("无线调试") -> 1
                else -> 4
            }
            (1..4).map { i ->
                when {
                    i == errStep -> AdbStepStatus.ERROR
                    i < errStep -> AdbStepStatus.DONE
                    else -> AdbStepStatus.PENDING
                }
            }
        }
        else -> listOf(AdbStepStatus.PENDING, AdbStepStatus.PENDING, AdbStepStatus.PENDING, AdbStepStatus.PENDING)
    }
}

/** 单步引导行：序号圆点 + 标题 + 状态色 */
@Composable
private fun AdbStepRow(index: Int, title: String, status: AdbStepStatus) {
    val (bg, fg) = when (status) {
        AdbStepStatus.DONE -> Success to Color.White
        AdbStepStatus.ACTIVE -> MaterialTheme.colorScheme.primary to Color.White
        AdbStepStatus.ERROR -> MaterialTheme.colorScheme.error to Color.White
        AdbStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(24.dp).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (status) {
                    AdbStepStatus.DONE -> "✓"
                    AdbStepStatus.ERROR -> "!"
                    else -> "$index"
                },
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (status == AdbStepStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                color = if (status == AdbStepStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (status == AdbStepStatus.ACTIVE) {
                Text("进行中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ============ 提示词 Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptsTab(vm: MainViewModel) {
    val templates by vm.templates.collectAsState()
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "可变提示词模板。正文支持 {task}{skills}{mcpTools}{controls}{currentApp}{lastResult}{goal}{auditRejection}{situational} 等占位符，空模板将回退内置默认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(templates, key = { it.id }) { t ->
                AppItemCard(onClick = { editing = t }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                t.body.ifBlank { "（空模板 → 使用内置默认）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (t.isBuiltIn) StatusPill("内置", MaterialTheme.colorScheme.onSurfaceVariant) else IconButton(onClick = { editing = t }) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
                    }
                }
            }
        }
    }

    editing?.let { t ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑提示词：${t.name}") },
            text = {
                PromptEditor(template = t, onSave = { body ->
                    vm.saveTemplate(t.id, t.name, body)
                    editing = null
                })
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("关闭") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptEditor(template: PromptTemplate, onSave: (String) -> Unit) {
    var body by remember { mutableStateOf(template.body) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("模板正文") },
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onSave(body) }) { Text("恢复默认") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onSave(body) }) { Text("保存") }
        }
    }
}