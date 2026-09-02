package com.phoneagent.ui.skill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    var legacyIntent by remember { mutableStateOf(initial?.legacyIntent ?: "") }
    var paramsText by remember { mutableStateOf(initial?.params?.joinToString("\n") { "${it.name}||${it.label}" } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增技能" else "编辑技能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("说明") },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
                OutlinedTextField(
                    value = legacyIntent, onValueChange = { legacyIntent = it },
                    label = { Text("兼容旧命令意图（如 open_app，可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = paramsText, onValueChange = { paramsText = it },
                    label = { Text("参数（每行：参数名||标签，可留空）") },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val params = paramsText.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            val idx = line.indexOf("||")
                            val p = if (idx >= 0) line.substring(0, idx).trim() to line.substring(idx + 2).trim() else line to line
                            if (p.first.isEmpty()) null
                            else SkillParam(name = p.first, label = p.second.ifBlank { p.first })
                        }
                        .toList()
                    val base = initial?.copy(
                        name = name.trim(),
                        description = desc.trim(),
                        legacyIntent = legacyIntent.trim().ifBlank { null },
                        params = params,
                    ) ?: Skill(
                        id = suggestedId,
                        name = name.trim(),
                        description = desc.trim(),
                        source = SkillSource.INTENT,
                        category = "自定义",
                        legacyIntent = legacyIntent.trim().ifBlank { null },
                        params = params,
                        createdBy = "user",
                    )
                    onSave(base)
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ============ MCP Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpTab(vm: MainViewModel) {
    val servers by vm.mcpServers.collectAsState()
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                Button(
                    enabled = serverName.isNotBlank() && serverUrl.isNotBlank(),
                    onClick = {
                        vm.addMcpServer(serverName.trim(), serverUrl.trim())
                        msg = "已添加服务器「${serverName.trim()}」"
                        serverName = ""; serverUrl = ""
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
                AppItemCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f)) {
                            Text(cfg.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(cfg.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            scope.launch { msg = vm.checkMcpServer(cfg.name) }
                        }) { Text("测试有效性") }
                        TextButton(onClick = {
                            scope.launch { val n = vm.bindMcpServerSkills(cfg.name); msg = "已绑定 $n 个工具为 Skill" }
                        }) { Text("绑定为技能") }
                    }
                }
            }
        }
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
        // Shizuku 权限状态
        val stateText = when (shizukuState) {
            com.phoneagent.shizuku.ShizukuManager.State.READY -> "Shizuku 已就绪"
            com.phoneagent.shizuku.ShizukuManager.State.PERMISSION_DENIED -> "Shizuku 已运行，但未授权"
            com.phoneagent.shizuku.ShizukuManager.State.UNAVAILABLE -> "Shizuku 未运行"
        }
        AppCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).background(
                    if (shizukuState == com.phoneagent.shizuku.ShizukuManager.State.READY) Success
                    else if (shizukuState == com.phoneagent.shizuku.ShizukuManager.State.PERMISSION_DENIED) Warning
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    RoundedCornerShape(AppRadii.Chip),
                ))
                Column(Modifier.weight(1f)) {
                    Text(stateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "ADB 无线调试状态：${adbPhaseText(adbStatus.phase)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { msg = null; vm.ensureAdbReady { msg = it } }) { Text("检测通路") }
            }
        }

        // 无线调试配对引导
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("无线调试配对（无需 Root）", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "若 Shizuku 未运行，可通过系统自带「无线调试」完成配对，本应用自动在后台拉起 Shizuku 服务。步骤：\n" +
                        "1. 手机设置 → 开发者选项 → 开启「无线调试」\n" +
                        "2. 点击「使用配对码配对设备」，记下 6 位配对码与主机/端口\n" +
                        "3. 在下框输入配对码，点「配对并启动」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { c -> code = c.filter { it.isDigit() }.take(6) },
                    label = { Text("6 位配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = code.length == 6,
                    onClick = {
                        vm.pairAdb(code) { msg = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("配对并启动")
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