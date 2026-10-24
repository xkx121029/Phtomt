package com.phoneagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.coding.CodingRuntimeState
import com.phoneagent.coding.agent.ConnState
import com.phoneagent.coding.agent.Workspace
import com.phoneagent.engine.coding.CodingAgentBridge
import com.phoneagent.ui.coding.openWorkspaceDirectory
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import org.koin.compose.koinInject

/**
 * 编码分页（Superagent 融合 Phase 6）：运行时状态、审批档位、
 * 工作区路径、编码 agent 连接状态。状态一律读 [CodingRuntimeManager] 与
 * [CodingAgentBridge]（Koin 单例，与编码页/引擎路由共享同一实例）。
 */
@Composable
internal fun SettingsCoding(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    val runtime = koinInject<com.phoneagent.coding.CodingRuntimeManager>()
    val bridge = koinInject<CodingAgentBridge>()
    val runtimeState by runtime.state.collectAsState()
    val clientState by bridge.client.state.collectAsState()
    val context = LocalContext.current
    val buzz = rememberHapticClick()
    val snackbar = LocalSnackbar.current

    LaunchedEffect(Unit) { runtime.refresh() }
    val workspacePath = remember { Workspace.resolve(context).absolutePath }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("编码", onBack, subtitle = "Node 编码运行时 · 审批档位 · 工作区")
        Spacer(Modifier.height(20.dp))

        // ========== A 运行时状态 ==========
        SettingsSection(
            title = "运行时状态",
            subtitle = "Node 宿主随主包内置，编码 Agent 跑在里面",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                when (val stt = runtimeState) {
                    is CodingRuntimeState.Installed -> {
                        StatusRow("状态", "内置就绪")
                        Spacer(Modifier.height(8.dp))
                        StatusRow("版本", "v${stt.versionName} (${stt.versionCode})")
                    }

                    is CodingRuntimeState.NotReady -> {
                        StatusRow("状态", "未就绪")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stt.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== B 审批档位 ==========
        SettingsSection(
            title = "审批档位",
            subtitle = "编码 agent 执行敏感工具时的放行策略（改动即时生效）",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                ApprovalModeSegment(
                    current = st.codingApprovalMode,
                    onSelect = {
                        buzz()
                        st.codingApprovalMode = it
                        save()
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (st.codingApprovalMode) {
                        "ask" -> "每个敏感工具都先在编码页的审批卡里等你确认"
                        "bypass" -> "所有工具直接执行，不再询问（谨慎使用）"
                        else -> "多数工具自动执行，高危动作仍会请求确认"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== C 工作区与连接 ==========
        SettingsSection(title = "工作区与连接", subtitle = "编码文件的落盘位置与 agent 通道状态", tone = SettingsTone.Brand) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("工作区", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            workspacePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        buzz()
                        if (!openWorkspaceDirectory(context)) snackbar?.show("无法打开文件管理器查看该目录")
                    }) {
                        Text("打开")
                    }
                }
                SettingsDivider(inset = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dotColor, label) = when (clientState.conn) {
                        ConnState.CONNECTED -> AppTheme.colors.success to "已连接"
                        ConnState.CONNECTING -> MaterialTheme.colorScheme.primary to "连接中"
                        ConnState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to "未连接"
                    }
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("编码 Agent 连接", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (clientState.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        clientState.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 审批档位三选一分段控件：ask / risky / bypass */
@Composable
private fun ApprovalModeSegment(current: String, onSelect: (String) -> Unit) {
    val modes = listOf(
        "ask" to "询问",
        "risky" to "默认放行",
        "bypass" to "全自动",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Chip))
            .background(AppTheme.colors.surfaceRaised)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        modes.forEach { (id, label) ->
            val selected = current == id
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(AppRadii.Chip))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .clickable { onSelect(id) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 状态行：左侧名称，右侧取值 */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
