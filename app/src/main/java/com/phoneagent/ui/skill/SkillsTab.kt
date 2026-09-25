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
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.SnackbarType
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

// ============ 技能 Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsTab(vm: MainViewModel, onOpenEditor: (Skill?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 宿主注入的全局浮条：轻量反馈统一走它，不用系统 Toast
    val snackbar = LocalSnackbar.current
    val skills by vm.skills.collectAsState()
    var batchMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(mutableSetOf<String>()) }

    // 顶部操作条
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { onOpenEditor(null) }, modifier = Modifier.weight(1f)) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
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
                Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
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
                    snackbar?.show("已导出 ${vm.skillAll().count { !it.isBuiltIn }} 个技能到剪贴板", SnackbarType.SUCCESS)
                }) {
                    Icon(AppIcons.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出")
                }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = cm.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (text.isNullOrBlank()) {
                        snackbar?.show("剪贴板为空或不是技能 JSON", SnackbarType.WARNING)
                    } else {
                        val n = vm.importSkills(text)
                        snackbar?.show("已导入 $n 个技能", SnackbarType.SUCCESS)
                    }
                }) {
                    Icon(AppIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
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
                onOpen = { onOpenEditor(skill) },
                onToggleEnabled = { vm.setSkillEnabled(skill.id, !skill.enabled) },
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
    // 新建/编辑浮层由 SkillManagerScreen 在骨架 overlay 槽里渲染：
    // 本 Tab 的根是 LazyColumn（可滚动、高度不受约束），满屏浮层挂在这里会被压成 0 高
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
                    if (skill.source == SkillSource.MCP) AppIcons.Terminal else AppIcons.Bolt,
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
                    Icon(AppIcons.Edit, contentDescription = "编辑")
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