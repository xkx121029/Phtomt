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
import com.phoneagent.ui.components.InlineOverlay
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

// ============ 提示词 Tab ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptsTab(vm: MainViewModel) {
    val templates by vm.templates.collectAsState()
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            Text(
                "可变提示词模板。正文支持 {task}{skills}{mcpTools}{controls}{currentApp}{lastResult}{goal}{auditRejection}{situational} 等占位符，空模板将回退内置默认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md), modifier = Modifier.fillMaxSize()) {
                items(templates, key = { it.id }) { t ->
                    AppItemCard(onClick = { editing = t }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
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
                            if (t.isBuiltIn) StatusPill("内置", MaterialTheme.colorScheme.onSurfaceVariant) else IconButton(onClick = { editing = t }) { Icon(AppIcons.Edit, contentDescription = "编辑") }
                        }
                    }
                }
            }
        }

        // 编辑模板走页内浮层：系统弹窗的圆角、按钮排布、入场方式都不是本项目的语言
        editing?.let { t ->
            InlineOverlay(onDismiss = { editing = null }) {
                Text(
                    "编辑提示词：${t.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                PromptEditor(template = t, onSave = { body ->
                    vm.saveTemplate(t.id, t.name, body)
                    editing = null
                })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editing = null }) { Text("关闭") }
                }
            }
        }
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