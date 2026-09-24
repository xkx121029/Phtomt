package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import com.phoneagent.ui.components.LocalBottomNavClearance
import com.phoneagent.ui.home.PermissionRadar
import com.phoneagent.ui.skill.WirelessAdbTab

/**
 * 权限与执行通道页：把散在主页/技能页的两块内容收拢到设置里。
 *
 * 只做聚合，不重写实现——权限雷达直接复用主页的 [PermissionRadar]，
 * 执行通道（无线 ADB 配对 / Shizuku / Termux 探测）直接复用技能页的 [WirelessAdbTab]，
 * 三处的状态与操作永远同源，不会各写一套后互相漂移。
 */
@Composable
internal fun SettingsPermissions(vm: MainViewModel, onBack: () -> Unit) {
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
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(bottom = LocalBottomNavClearance.current),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            SettingsTopBar("权限与执行通道", onBack)
            Spacer(Modifier.height(16.dp))

            GroupCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    // 去系统设置授完权再回来，本页不会自行重组，留一个手动刷新的入口
                    TextButton(onClick = { vm.refreshPermissions(context) }) { Text("重新检测") }
                }
                Text(
                    "点击未授权项跳转对应的系统授权页；授权完成后返回本页点「重新检测」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    PermissionRadar(permissions, context, vm)
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // 执行通道：通道状态 + 无线调试配对引导 + Shizuku / Termux 探测。
        // 该区块自带 20dp 横向留白，放在外层避免与外层内边距叠加成 40dp。
        WirelessAdbTab(vm)

        Spacer(Modifier.height(28.dp))
    }
}
