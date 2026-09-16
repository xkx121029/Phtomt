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

// ============ 技能 Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsTab(vm: MainViewModel) {
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
internal fun SkillCard(
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
internal data class ParamDraft(
    var name: String = "",
    var label: String = "",
    var type: String = "text",
    var required: Boolean = false,
    var description: String = "",
    var defaultValue: String = "",
    var optionsText: String = "",
)

/** 把草稿转成 SkillParam */
internal fun ParamDraft.toParam(): SkillParam? {
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

internal fun SkillParam.toDraft(): ParamDraft = ParamDraft(
    name = name,
    label = label,
    type = type,
    required = required,
    description = description ?: "",
    defaultValue = defaultValue ?: "",
    optionsText = options.joinToString(","),
)