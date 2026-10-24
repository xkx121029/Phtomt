package com.phoneagent.ui.settings

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
            .padding(horizontal = SettingsScreenPad)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        // 骨架节奏与全设置流程一致：页头后 20 / 分区之间 16 / 结尾 28。
        // 原先这里套了两层 Column，Shizuku/Termux 卡放在无横向留白的外层，
        // 铺满到屏幕边缘，与其他页的 20dp 栅格对不上——已拍平收进同一层。
        SettingsPageHeader(
            title = "权限与执行通道",
            onBack = onBack,
            subtitle = "授予系统权限，配置 shell 执行通路",
        )
        Spacer(Modifier.height(20.dp))

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

            Spacer(Modifier.height(16.dp))

            // 系统级入口：数字助理角色（长按电源键唤起）与通知感知（AI 的通知维度上下文）。
            // 两者都是系统授权，不进权限雷达（雷达枚举的是运行时权限），单独给一区引导。
            SettingsSection(title = "系统级入口", subtitle = "数字助理唤起与通知感知", tone = SettingsTone.Warm) {
                val activity = androidx.activity.compose.LocalActivity.current
                var assistantHeld by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(
                        com.phoneagent.feature.assistant.AssistantRole.isDefaultAssistant(context),
                    )
                }
                val roleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                ) {
                    // 授权框关闭后回读状态（授权/拒绝都要刷新，否则按钮文案停在旧状态）
                    assistantHeld = com.phoneagent.feature.assistant.AssistantRole.isDefaultAssistant(context)
                }
                val notifEnabled = com.phoneagent.feature.notify.notificationListenerEnabled(context)
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Text(
                        "数字助理：设为默认助理后，长按电源键或边缘手势直接唤起 Happy Agent，" +
                            "唤起时的屏幕内容会带给任务（原来占这个位置的语音助手仍有各自的专属入口）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 区块内的主操作统一满宽按钮，与端侧视觉/数据页的行动按钮同一语言
                    androidx.compose.material3.Button(
                        enabled = !assistantHeld,
                        onClick = {
                            val host = activity ?: context as? android.app.Activity
                            // 拿得到系统授权框就弹框授权；拿不到（API<29 / 机型不支持该角色）时
                            // 兜底跳系统「数字助理应用」设置页手动指定——不再静默无反应
                            val request = if (android.os.Build.VERSION.SDK_INT >= 29) {
                                com.phoneagent.feature.assistant.AssistantRole.requestRole(host)
                            } else {
                                null
                            }
                            if (request != null) {
                                roleLauncher.launch(request)
                            } else {
                                com.phoneagent.feature.assistant.AssistantRole.openAssistantSettings(host ?: context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
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
                    androidx.compose.material3.OutlinedButton(
                        enabled = !notifEnabled,
                        onClick = {
                            // 裸 startActivity 在没有该设置页的 ROM 上会 ActivityNotFoundException 崩溃，
                            // 收口到带回退链与 NEW_TASK 标记的封装
                            com.phoneagent.feature.notify.openNotificationAccessSettings(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (notifEnabled) "通知使用权已开启" else "开启通知使用权") }
                }
                // 对外控制接口：广播契约在 TaskControlReceiver（下发任务/停止/查询状态）。
                // 开关统一走 SettingsToggleRow（原先是一颗裸 Switch，与全页开关语言不一致）
                SettingsToggleRow(
                    title = "对外控制接口",
                    subtitle = "允许 Tasker、MacroDroid 等自动化工具以广播驱动本应用（下发任务 / 停止 / 查询状态）",
                    value = st.externalControlEnabled,
                ) {
                    st.externalControlEnabled = it
                    save()
                }
                if (st.externalControlEnabled) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
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
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

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

            Spacer(Modifier.height(16.dp))

        // 执行通道：内置 Shizuku（一键启动 / 首次引导）+ Termux 探测。
        // 收进与分区同层的 20dp 栅格，卡片不再铺到屏幕边缘
        ShizukuChannelCard(vm)
        Spacer(Modifier.height(12.dp))
        TermuxChannelCard(vm)

        Spacer(Modifier.height(28.dp))
    }
}
