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
import com.phoneagent.ui.components.GlassHeaderInnerPad
import com.phoneagent.ui.components.GlassHeaderScaffold
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
    GlassHeaderScaffold(
        modifier = modifier,
        header = {
            AppTopBar(
                title = "技能与能力",
                subtitle = "Skill · MCP · 无线 ADB · 提示词",
                contentPadding = PaddingValues(horizontal = GlassHeaderInnerPad, vertical = 8.dp),
            )
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
                SkillTab.SKILLS -> SkillsTab(vm)
                SkillTab.MCP -> McpTab(vm)
                SkillTab.WIRELESS_ADB -> WirelessAdbTab(vm)
                SkillTab.PROMPTS -> PromptsTab(vm)
            }
        }
    }
}

private enum class SkillTab(val label: String) {
    SKILLS("技能"),
    MCP("MCP"),
    WIRELESS_ADB("无线ADB"),
    PROMPTS("提示词"),
}