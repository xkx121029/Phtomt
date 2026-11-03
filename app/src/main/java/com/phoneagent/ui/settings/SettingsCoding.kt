package com.phoneagent.ui.settings

import androidx.compose.foundation.background
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
import com.phoneagent.ui.theme.AppTheme
import org.koin.compose.koinInject

/**
 * 编码分页（Superagent 融合 Phase 6）：运行时状态、审批档位、
 * 工作区路径、编码 agent 连接状态。状态一律读 [CodingRuntimeManager] 与
 * [CodingAgentBridge]（Koin 单例，与编码页/引擎路由共享同一实例）。
 */
@Composable
internal fun SettingsCoding(
    st: SettingsState,
    save: () -> Unit,
    onBack: () -> Unit,
    onOpenEnv: () -> Unit,
) {
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
        SettingsPageHeader("编码", onBack, subtitle = "Node 运行时 · 构建环境 · 审批档位 · 工作区")
        Spacer(Modifier.height(20.dp))

        CodingEnvEntrySection(onOpen = { buzz(); onOpenEnv() })
        Spacer(Modifier.height(16.dp))

        CodingRuntimeSection(runtimeState)
        Spacer(Modifier.height(16.dp))

        CodingApprovalSection(
            mode = st.codingApprovalMode,
            onSelect = {
                buzz()
                st.codingApprovalMode = it
                save()
            },
        )
        Spacer(Modifier.height(16.dp))

        CodingWorkspaceSection(
            workspacePath = workspacePath,
            conn = clientState.conn,
            error = clientState.error,
            onOpenWorkspace = {
                buzz()
                if (!openWorkspaceDirectory(context)) snackbar?.show("无法打开文件管理器查看该目录")
            },
        )
        Spacer(Modifier.height(28.dp))
    }
}

/** 构建环境（二级页入口）：放在最上方，它是「能不能在设备里自己打出 APK」这个前提 */
@Composable
private fun CodingEnvEntrySection(onOpen: () -> Unit) {
    SettingsSection(
        title = "构建环境",
        subtitle = "设备内构建 APK 的 Linux 工具链（独立组件承载）",
        tone = SettingsTone.Cool,
    ) {
        SettingsRow(
            icon = AppIcons.Terminal,
            title = "环境",
            subtitle = "自动识别工程版本，一键安装 Gradle + AGP 配套组件",
            tone = SettingsTone.Cool,
            onClick = onOpen,
        )
    }
}

/** 运行时状态：Node 宿主随主包内置 */
@Composable
private fun CodingRuntimeSection(runtimeState: CodingRuntimeState) {
    SettingsSection(
        title = "运行时状态",
        subtitle = "Node 宿主随主包内置，编码 Agent 跑在里面",
        tone = SettingsTone.Brand,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            when (runtimeState) {
                is CodingRuntimeState.Installed -> {
                    SettingsStatusRow("状态", "内置就绪")
                    Spacer(Modifier.height(8.dp))
                    SettingsStatusRow(
                        "版本",
                        "v${runtimeState.versionName} (${runtimeState.versionCode})",
                    )
                }

                is CodingRuntimeState.NotReady -> {
                    SettingsStatusRow("状态", "未就绪")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        runtimeState.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 审批档位：编码 agent 执行敏感工具时的放行策略（改动即时生效） */
@Composable
private fun CodingApprovalSection(mode: String, onSelect: (String) -> Unit) {
    SettingsSection(
        title = "审批档位",
        subtitle = "编码 agent 执行敏感工具时的放行策略（改动即时生效）",
        tone = SettingsTone.Brand,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            ApprovalModeSegment(current = mode, onSelect = onSelect)
            Spacer(Modifier.height(8.dp))
            Text(
                approvalModeHint(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 工作区与连接：编码文件的落盘位置与 agent 通道状态 */
@Composable
private fun CodingWorkspaceSection(
    workspacePath: String,
    conn: ConnState,
    error: String?,
    onOpenWorkspace: () -> Unit,
) {
    SettingsSection(
        title = "工作区与连接",
        subtitle = "编码文件的落盘位置与 agent 通道状态",
        tone = SettingsTone.Brand,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
                OutlinedButton(onClick = onOpenWorkspace) {
                    Text("打开")
                }
            }
            SettingsDivider(inset = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dotColor, label) = when (conn) {
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
                Text(
                    "编码 Agent 连接",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
