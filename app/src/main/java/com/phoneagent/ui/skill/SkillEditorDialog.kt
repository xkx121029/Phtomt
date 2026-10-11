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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
import com.phoneagent.device.shell.AdbError
import com.phoneagent.device.shell.AdbPhase
import com.phoneagent.device.shell.AdbWirelessTransport
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillParam
import com.phoneagent.feature.skill.SkillSource
import com.phoneagent.domain.model.IntentType
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppCard
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.InlineOverlay
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillEditorDialog(
    initial: Skill?,
    suggestedId: String,
    isMacro: Boolean = false,
    availableSkills: List<Skill> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "自定义") }
    var legacyIntent by remember { mutableStateOf(initial?.legacyIntent ?: "") }
    var params by remember { mutableStateOf(initial?.params?.map { it.toDraft() } ?: emptyList<ParamDraft>()) }
    // 宏步骤草稿：编辑既有宏时从 steps 还原；args 文本按 k=v 逗号分隔展示（占位符原样保留）
    var steps by remember {
        mutableStateOf(
            initial?.steps?.map {
                MacroStepDraft(
                    skillId = it.skillId,
                    argsText = it.args.entries.joinToString(",") { (k, v) -> "$k=$v" },
                    description = it.description,
                )
            } ?: emptyList<MacroStepDraft>(),
        )
    }

    // 保存前预检：INTENT 技能要靠「legacyIntent ?: id 去前缀」映射到可执行意图才能被调用
    // （与 SkillCompat.normalize 的 INTENT 分支同一判定）。这里不走真实 registry 模拟调用——
    // 新技能尚未入库、被编辑技能若处于停用态都会让 normalize 误报；判定失败时阻止保存并展示原因，
    // 否则存下来的就是一个永远调不动的死技能
    val effectiveSource = initial?.source ?: if (isMacro) SkillSource.MACRO else SkillSource.INTENT
    val effectiveIntent = legacyIntent.trim().ifBlank { suggestedId.removePrefix("skill_") }
    val invocableError = if (!isMacro && effectiveSource == SkillSource.INTENT && effectiveIntent !in IntentType.ALL) {
        if (legacyIntent.isBlank()) {
            "旧命令意图留空，技能 ID 也映射不到可执行意图，保存后无法被调用；请填写意图名（如 open_app）"
        } else {
            "「${legacyIntent.trim()}」不是有效的意图名，保存后无法被调用"
        }
    } else {
        null
    }
    // 宏必须至少配置一步：空步骤的宏在提示词与执行层都不产生任何效果，等于死宏
    val macroError = if (isMacro && steps.none { it.skillId.isNotBlank() }) {
        "宏至少需要一个步骤：请点击「添加步骤」并选择目标技能"
    } else {
        null
    }

    // 技能编辑器是一张长表单，用可滚动的页内浮层承载——不用系统 Dialog：
    // 系统弹窗自带另一套字体、圆角与入场方式，和全站浮层不是一套语言
    InlineOverlay(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            Text(
                if (initial == null) (if (isMacro) "新增宏技能" else "新增技能") else (if (isMacro) "编辑宏技能" else "编辑技能"),
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
            if (!isMacro) {
                OutlinedTextField(
                    value = legacyIntent, onValueChange = { legacyIntent = it },
                    label = { Text("兼容旧命令意图（如 open_app，留空将无法调用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            invocableError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (isMacro) {
                // 宏步骤：按顺序执行的目标技能序列。只允许引用已启用的非宏技能（宏不可嵌套，
                // 与引擎 macroPrecheckReason 的判定一致），选择器里就只列这些
                Text("步骤（按顺序执行，失败即停）", style = MaterialTheme.typography.titleSmall)
                val stepCandidates = availableSkills.filter { it.enabled && it.source != SkillSource.MACRO }
                if (steps.isEmpty()) {
                    Text("暂无步骤。点击「添加步骤」从已启用的技能中选择。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                steps.forEachIndexed { i, s ->
                    MacroStepEditor(
                        draft = s,
                        candidates = stepCandidates,
                        canMoveUp = i > 0,
                        onChange = { updated -> steps = steps.toMutableList().apply { this[i] = updated } },
                        onMoveUp = { steps = steps.toMutableList().apply { add(i - 1, removeAt(i)) } },
                        onDelete = { steps = steps.toMutableList().apply { removeAt(i) } },
                    )
                }
                OutlinedButton(
                    enabled = stepCandidates.isNotEmpty(),
                    onClick = { steps = steps + MacroStepDraft(skillId = stepCandidates.firstOrNull()?.id ?: "") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(AppSpacing.Xs))
                    Text(if (stepCandidates.isEmpty()) "没有可引用的技能" else "添加步骤")
                }
                macroError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

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
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(AppSpacing.Xs))
                Text("添加参数")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    // 预检失败同样禁止保存：死技能/死宏不如不存
                    enabled = name.isNotBlank() && invocableError == null && macroError == null,
                    onClick = {
                        val skillParams = params.mapNotNull { it.toParam() }
                        val macroSteps = steps.filter { it.skillId.isNotBlank() }.mapIndexed { i, it ->
                            com.phoneagent.feature.skill.MacroStep(
                                skillId = it.skillId.trim(),
                                args = it.toArgs(),
                                description = it.description.trim(),
                            )
                        }
                        val base = if (isMacro) {
                            initial?.copy(
                                name = name.trim(),
                                description = desc.trim(),
                                category = category.trim().ifBlank { "自定义" },
                                legacyIntent = null,
                                params = skillParams,
                                steps = macroSteps,
                            ) ?: Skill(
                                id = suggestedId,
                                name = name.trim(),
                                description = desc.trim(),
                                source = SkillSource.MACRO,
                                category = category.trim().ifBlank { "自定义" },
                                params = skillParams,
                                steps = macroSteps,
                                createdBy = "user",
                            )
                        } else {
                            initial?.copy(
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
                        }
                        onSave(base)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }
}

/** 单个宏步骤的编辑区：目标技能下拉 + 固定参数 + 说明 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacroStepEditor(
    draft: MacroStepDraft,
    candidates: List<Skill>,
    canMoveUp: Boolean,
    onChange: (MacroStepDraft) -> Unit,
    onMoveUp: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(AppRadii.Chip))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("步骤", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (canMoveUp) TextButton(onClick = onMoveUp) { Text("上移") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
        // 目标技能：从已启用的非宏技能中选择（宏不可嵌套；被停用的技能预检也过不了）
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = candidates.firstOrNull { it.id == draft.skillId }?.name ?: draft.skillId,
                onValueChange = {},
                label = { Text("目标技能") },
                singleLine = true,
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                candidates.forEach { c ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(c.name)
                                Text(c.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            onChange(draft.copy(skillId = c.id))
                            expanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft.argsText, onValueChange = { onChange(draft.copy(argsText = it)) },
            label = { Text("参数（k=v，逗号分隔；可用 {{参数名}} 或 {{stepN}} 占位符）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.description, onValueChange = { onChange(draft.copy(description = it)) },
            label = { Text("步骤说明（可留空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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