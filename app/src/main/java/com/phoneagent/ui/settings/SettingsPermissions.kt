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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.home.PermissionRadar
import com.phoneagent.ui.skill.WirelessAdbTab

/**
 * 权限与执行通道页：把散在主页/技能页的内容收拢到设置里，并接管原「Agent 运行」页里的
 * 执行通道优先级开关——它与本页的配对界面说的是同一件事，放两处只会互相打架。
 *
 * 只做聚合，不重写实现——权限雷达复用主页的 [PermissionRadar]，
 * 执行通道（无线 ADB 配对 / Shizuku / Termux 探测）复用技能页的 [WirelessAdbTab]，
 * 三处的状态与操作永远同源，不会各写一套后互相漂移。
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

            SettingsSection(
                title = "执行通道优先级",
                subtitle = "shell 执行通路的优先顺序（无线 ADB 为主，Shizuku / Termux 可选）",
                tone = SettingsTone.Warm,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val channels = listOf(
                        "AUTO" to "自动",
                        "ADB" to "无线ADB",
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
                            "ADB" -> "仅用无线 ADB；未连接时无真实 shell，走无障碍执行。"
                            "SHIZUKU" -> "仅用 Shizuku；不可用时用无线 ADB 拉起。"
                            "TERMUX" -> "仅用 Termux（普通应用权限）：可跑 curl / python / 文本处理，不能执行系统命令。"
                            else -> "优先无线 ADB，其次 Shizuku，最后 Termux；都不可用则走无障碍执行。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // 执行通道：通道状态 + 无线调试配对引导 + Shizuku / Termux 探测。
        // 该区块自带 20dp 横向留白，放在外层避免与外层内边距叠加成 40dp。
        WirelessAdbTab(vm)

        Spacer(Modifier.height(28.dp))
    }
}