package com.phoneagent.ui.skill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
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
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.feature.mcp.McpMarketplace
import com.phoneagent.feature.mcp.McpServerInfo
import com.phoneagent.feature.mcp.McpTool
import com.phoneagent.device.shell.AdbError
import com.phoneagent.device.shell.AdbPhase
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
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

// ============ 技能 Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsTab(vm: MainViewModel, onOpenEditor: (Skill?) -> Unit, onOpenMacroEditor: (Skill?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 宿主注入的全局浮条：轻量反馈统一走它，不用系统 Toast
    val snackbar = LocalSnackbar.current
    val skills by vm.skills.collectAsState()
    var batchMode by remember { mutableStateOf(false) }
    // 必须是不可变 Set + 赋值更新：往普通 MutableSet 里 add/remove 不会触发 Compose 重组，
    // 勾选后「已选 N 项」、勾选框与删除键可用态都会停在旧值
    var selected by remember { mutableStateOf(emptySet<String>()) }

    // 顶部操作条
    //
    // 五个按钮挤一行时「新增技能」在 1080 宽下会折成「新增技 / 能」：
    // 图标 + 间距已经吃掉四成宽度。这里只保留两个主操作并去掉图标，
    // 导出 / 导入收进下方清单的首行——它们不是每次进来都要点的动作。
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppTheme.metrics.pagePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onOpenEditor(null) },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = AppSpacing.Md),
        ) {
            Text("新增技能", maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = { onOpenMacroEditor(null) },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = AppSpacing.Md),
        ) {
            Text("新增宏", maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = { batchMode = !batchMode; selected = emptySet() },
            contentPadding = PaddingValues(horizontal = AppSpacing.Md),
        ) {
            Text(if (batchMode) "完成" else "批量管理", maxLines = 1, softWrap = false)
        }
    }
    if (batchMode) {
        // 批量删除走两步确认：第一次点只变文案，3 秒不点自动复位（对齐 DataActionRow 的 armed 模式）
        var deleteArmed by remember { mutableStateOf(false) }
        LaunchedEffect(deleteArmed) {
            if (deleteArmed) {
                kotlinx.coroutines.delay(3000)
                deleteArmed = false
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppTheme.metrics.pagePad, vertical = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("已选 ${selected.size} 项", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selected = emptySet(); deleteArmed = false }) { Text("全不选") }
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    if (!deleteArmed) {
                        deleteArmed = true
                    } else {
                        deleteArmed = false
                        val ids = selected
                        selected = emptySet()
                        vm.removeSkills(ids) { n ->
                            snackbar?.show("已删除 $n 个技能", SnackbarType.SUCCESS)
                        }
                    }
                },
            ) {
                Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (deleteArmed) "再点确认删除" else "删除所选",
                    color = if (deleteArmed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.metrics.pagePad),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        item {
            // 首次出现的术语都在这里用一句人话解释一次，卡片上就可以只写名字与用途。
            // 这是本页唯一的术语表：页内其他位置不再重复解释。
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Xxs)) {
                Text(
                    "什么是技能",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "技能 = 一句话就能交给我的事，比如「设个明早 7 点的闹钟」。\n" +
                        "宏 = 把几个技能串成一条流水线，一句话跑完一整套。\n" +
                        "MCP = 接入外部工具的通用接口；带 MCP 标记的是从外部接进来的工具，不是内置的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                        // 导入的 JSON 解析已在 ViewModel 里挪到后台线程，结果回主线程再弹浮条
                        vm.importSkills(text) { n ->
                            snackbar?.show("已导入 $n 个技能", SnackbarType.SUCCESS)
                        }
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
                onCheckedChange = { on -> selected = if (on) selected + skill.id else selected - skill.id },
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
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
                    } else if (skill.source == SkillSource.MACRO) {
                        StatusPill("宏", MaterialTheme.colorScheme.tertiary)
                    }
                }
                Text(
                    skill.description.ifBlank { "还没有说明" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 这一格原先直接印 legacyIntent（set_alarm、open_app 这类下划线标识符），
                    // 对普通用户等于没写。认识的走人话翻译器，不认识的退回分类名。
                    StatusPill(
                        skill.legacyIntent
                            ?.takeIf { HumanTranslator.knowsAction(it) }
                            ?.let { HumanTranslator.actionVerb(it) }
                            ?: skill.category,
                        MaterialTheme.colorScheme.tertiary,
                    )
                    if (skill.source == SkillSource.MACRO && skill.steps.isNotEmpty()) {
                        StatusPill("步骤×${skill.steps.size}", MaterialTheme.colorScheme.secondary)
                    }
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

/** 宏步骤草稿：args 用「k=v，逗号分隔」的文本承载（占位符 {{参数名}}/{{stepN}} 原样保留） */
internal data class MacroStepDraft(
    var skillId: String = "",
    var argsText: String = "",
    var description: String = "",
)

/** 把步骤参数文本解析为 Map：支持中英文逗号分隔；只取每段第一个 = 前后的内容，坏段丢弃 */
internal fun MacroStepDraft.toArgs(): Map<String, String> =
    argsText.split(',', '，')
        .mapNotNull { seg ->
            val idx = seg.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val k = seg.substring(0, idx).trim()
            val v = seg.substring(idx + 1).trim()
            if (k.isEmpty()) null else k to v
        }
        .toMap()

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