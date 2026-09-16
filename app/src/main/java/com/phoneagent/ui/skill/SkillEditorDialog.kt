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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillEditorDialog(
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