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
import com.phoneagent.device.shell.AdbError
import com.phoneagent.device.shell.AdbPhase
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillParam
import com.phoneagent.feature.skill.SkillSource
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppCard
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch

/**
 * 技能与能力管理页（HPA 迭代 A7）。
 * Tab 栏布局：技能 / MCP（原「无线 ADB」Tab 已并入设置页的内置 Shizuku 通道卡）。
 * - 技能：Skill 卡片列表 + 批量增删 + 新建/编辑/详情
 * - MCP：配置服务器 + 有效性测试 + 绑定为 Skill
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(SkillTab.SKILLS) }
    // 技能编辑器由本页渲染（而不是技能 Tab 内）：各 Tab 都是可滚动容器，
    // 满屏浮层挂在里面拿不到高度约束；放骨架的 overlay 槽才能压住页眉与正文
    var editorOpen by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<Skill?>(null) }
    // 宏编辑复用同一张 SkillEditorDialog（isMacro=true 切换表单形态）：
    // availableSkills 供步骤选择器列出可引用的技能（编辑器自身不持有 ViewModel）
    var editorIsMacro by remember { mutableStateOf(false) }
    val availableSkills = vm.skillAll()
    GlassHeaderScaffold(
        modifier = modifier,
        header = {
            AppTopBar(
                title = "技能与能力",
                subtitle = "Skill · MCP",
            )
        },
        overlay = {
            if (editorOpen) {
                val editing = editingSkill
                SkillEditorDialog(
                    initial = editing,
                    suggestedId = editing?.id ?: vm.nextSkillId(),
                    isMacro = editorIsMacro,
                    availableSkills = availableSkills,
                    onDismiss = { editorOpen = false },
                    onSave = { s ->
                        if (editing == null) vm.addSkill(s) else vm.updateSkill(s)
                        editorOpen = false
                    },
                )
            }
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 净空按玻璃页眉实测高度垫出：页签栏落在页眉下沿之外，不会被压住
                .padding(top = pad.calculateTopPadding()),
        ) {
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
                SkillTab.SKILLS -> SkillsTab(
                    vm = vm,
                    // null = 新建（普通技能），非 null = 编辑该技能（isMacro 按其真实来源判定，
                    // 否则点开一张宏卡再保存会被降级成普通技能）
                    onOpenEditor = { s ->
                        editorIsMacro = s?.source == SkillSource.MACRO
                        editingSkill = s
                        editorOpen = true
                    },
                    onOpenMacroEditor = { s ->
                        editorIsMacro = true
                        editingSkill = s
                        editorOpen = true
                    },
                )
                SkillTab.MCP -> McpTab(vm)
            }
        }
    }
}

private enum class SkillTab(val label: String) {
    SKILLS("技能"),
    MCP("MCP"),
}