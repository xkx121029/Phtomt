package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.home.PermissionRadar

/**
 * 权限与执行通道页：把散在主页/技能页的内容收拢到设置里，并接管原「Agent 运行」页里的
 * 执行通道优先级开关——它与本页的配对界面说的是同一件事，放两处只会互相打架。
 *
 * 只做聚合，不重写实现——权限雷达复用主页的 [PermissionRadar]；
 * 执行通道展示内置 Shizuku（一键启动 + 首次引导）与 Termux（[ShizukuChannelCard] / [TermuxChannelCard]）。
 * 「无线ADB」独立通道已砍：无线 ADB 只是内置 Shizuku server 的启动引导机制，不再单独暴露。
 */
@Composable
internal fun SettingsPermissions(
    vm: MainViewModel,
    st: SettingsState,
    save: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val permissions by vm.permissions.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshPermissions(context)
        vm.refreshStatus(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LocalNavClearance.current),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SettingsScreenPad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsPageHeader(
                title = "权限与执行通道",
                onBack = onBack,
                subtitle = "授予系统权限，配置 shell 执行通路",
            )

            SettingsSection(title = "系统权限", subtitle = "点击未授权项跳转对应授权页", tone = SettingsTone.Warm) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "授权完成后返回本页点「重新检测」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // 去系统设置授完权再回来，本页不会自行重组，留一个手动刷新的入口
                        TextButton(onClick = { vm.refreshPermissions(context) }) { Text("重新检测") }
                    }
                    Spacer(Modifier.height(6.dp))
                    PermissionRadar(permissions, context, vm)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // 系统级入口：数字助理角色（长按电源键唤起）与通知感知（AI 的通知维度上下文）。
            // 两者都是系统授权，不进权限雷达（雷达枚举的是运行时权限），单独给一区引导。
            SettingsSection(title = "系统级入口", subtitle = "数字助理唤起与通知感知", tone = SettingsTone.Warm) {
                val activity = androidx.activity.compose.LocalActivity.current
                val roleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                ) { }
                var assistantHeld by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(
                        com.phoneagent.feature.assistant.AssistantRole.isDefaultAssistant(context),
                    )
                }
                val notifEnabled = com.phoneagent.feature.notify.notificationListenerEnabled(context)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "数字助理：设为默认助理后，长按电源键或边缘手势直接唤起 Happy Agent，" +
                            "唤起时的屏幕内容会带给任务（原来占这个位置的语音助手仍有各自的专属入口）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.Button(
                        enabled = !assistantHeld,
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                com.phoneagent.feature.assistant.AssistantRole.requestRole(activity)?.let {
                                    roleLauncher.launch(it)
                                    assistantHeld = com.phoneagent.feature.assistant.AssistantRole.isDefaultAssistant(context)
                                }
                            }
                        },
                    ) {
                        Text(if (assistantHeld) "已是默认数字助理" else "设为系统数字助理")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "通知感知：开启后 AI 能读到通知（验证码/提醒），并支持「读取通知/清理通知」技能。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        enabled = !notifEnabled,
                        onClick = { context.startActivity(android.content.Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS")) },
                    ) { Text(if (notifEnabled) "通知使用权已开启" else "开启通知使用权") }
                    Spacer(Modifier.height(12.dp))
                    // 对外控制接口：广播契约在 TaskControlReceiver（下发任务/停止/查询状态）
                    Text(
                        "对外控制接口：允许 Tasker、MacroDroid 等自动化工具以广播驱动本应用（下发任务 / 停止 / 查询状态）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.Switch(
                        checked = st.externalControlEnabled,
                        onCheckedChange = { st.externalControlEnabled = it; save() },
                    )
                    if (st.externalControlEnabled) {
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = st.externalControlToken,
                            onValueChange = { st.externalControlToken = it; save() },
                            label = { Text("广播口令（token）") },
                            placeholder = { Text("留空=仅靠开关（不推荐）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "广播示例：action=com.phoneagent.action.EXTERNAL_RUN_TASK，extras：text=任务内容、token=口令",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsSection(
                title = "执行通道优先级",
                subtitle = "shell 执行通路的优先顺序（内置 Shizuku 为主，Termux 可选）",
                tone = SettingsTone.Warm,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val channels = listOf(
                        "AUTO" to "自动",
                        "SHIZUKU" to "Shizuku",
                        "TERMUX" to "Termux",
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        channels.forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = st.executionChannel == key,
                                onClick = { st.executionChannel = key; save() },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = channels.size),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (st.executionChannel) {
                            "SHIZUKU" -> "仅认 Shizuku 服务就绪；未启动时可在下方一键启动。"
                            "TERMUX" -> "仅用 Termux（普通应用权限）：可跑 curl / python / 文本处理，不能执行系统命令。"
                            else -> "优先 Shizuku 服务，其次引导会话（server 拉起前后均可执行 shell），最后 Termux；都不可用则走无障碍执行。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // 执行通道：内置 Shizuku（一键启动 / 首次引导）+ Termux 探测。
        // 该区块自带 20dp 横向留白，放在外层避免与外层内边距叠加成 40dp。
        ShizukuChannelCard(vm)
        Spacer(Modifier.height(12.dp))
        TermuxChannelCard(vm)

        Spacer(Modifier.height(28.dp))
    }
}
