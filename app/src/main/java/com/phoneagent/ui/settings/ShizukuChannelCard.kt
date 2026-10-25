package com.phoneagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.device.shell.AdbPhase
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppCard
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success

/**
 * 内置 Shizuku 执行通道卡片。
 *
 * 语义收口：本应用内嵌 Shizuku server（随 APK 分发，经无线 ADB 以 shell uid 拉起），
 * 用户看到的是同一个「Shizuku」概念——无线 ADB 只是**启动引导机制**，不再是独立通道。
 * - 就绪判定：Shizuku binder 可用，或引导会话（ADB）已连接（server 拉起前后都能执行 shell）。
 * - 首次启动需要用户在系统「无线调试」页查看 6 位配对码，之后密钥已存，一键启动无需再配。
 */
@Composable
internal fun ShizukuChannelCard(vm: MainViewModel) {
    val shizukuState by vm.shizukuState.collectAsState()
    val adbStatus by vm.adbStatus.collectAsState()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    val shizukuReady = shizukuState == com.phoneagent.device.shell.ShizukuManager.State.READY
    val adbReady = adbStatus.phase == AdbPhase.READY
    val channelReady = shizukuReady || adbReady
    val needsPairing = adbStatus.isUserActionRequired || adbStatus.phase == AdbPhase.FAILED

    AppCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).background(
                    if (channelReady) Success
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    RoundedCornerShape(AppRadii.Chip),
                ))
                Column(Modifier.weight(1f)) {
                    Text("内置 Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (channelReady) "已就绪：可执行系统级 shell 命令"
                        else "未启动：shell 命令暂不可用，任务走无障碍执行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { msg = null; vm.ensureShizukuReady { msg = it } }) { Text("一键启动") }
            }
            Text(
                "Shizuku 服务随「服务组件」一起安装，无需另外安装软件；首次启动需完成一次配对引导，之后开机自动可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 状态明细：binder 与引导会话两条线索分开呈现，出问题时能看出卡在哪一环
            val stepStatuses = shizukuStepStatuses(shizukuReady, adbStatus.phase, adbStatus.message)
            listOf(
                "Shizuku 服务已启动",
                "已授权本应用执行",
            ).forEachIndexed { i, title ->
                AdbStepRow(i + 1, title, stepStatuses[i])
            }

            // 未授权时提供授权入口（server 活着但没批权限）
            if (shizukuState == com.phoneagent.device.shell.ShizukuManager.State.PERMISSION_DENIED) {
                Button(
                    onClick = { msg = null; vm.requestShizukuPermission { granted -> msg = if (granted) "已授权" else "授权被拒绝" } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("授予 Shizuku 执行权限")
                }
            }

            // 首次启动引导：分步说明 + 配对码输入（仅在需要时展开，避免干扰已就绪用户）
            if (!channelReady || needsPairing) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { c -> code = c.filter { it.isDigit() }.take(6) },
                    label = { Text("6 位配对码（首次启动需要）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            vm.startAdbDiscovery()
                            msg = "正在搜索系统无线调试服务，请保持「使用配对码配对设备」界面…"
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("开始引导")
                    }
                    OutlinedButton(
                        onClick = { vm.pairShizuku(code) { msg = it } },
                        enabled = code.length == 6,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("输入配对码")
                    }
                }
                if (needsPairing) {
                    OutlinedButton(
                        onClick = {
                            vm.cancelAdbPairing()
                            vm.startAdbDiscovery()
                            code = ""
                            msg = "已重新开始引导，请保持「使用配对码配对设备」界面…"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重新引导")
                    }
                }
            }

            // 向导当前说话（搜索结果也回显到这里）
            val flowMsg by vm.adbPairingMessageFlow.collectAsState()
            if (flowMsg.isNotBlank()) {
                StatusPill(flowMsg, MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
            }

            if (msg != null) {
                StatusPill(
                    msg!!,
                    if (msg!!.contains("就绪") || msg!!.contains("成功") || msg!!.contains("已授权")) Success
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Termux 执行通道卡片：三项前置条件（已安装 / 已授权 / 已开启 allow-external-apps）+ 分步引导。
 *
 * 与内置 Shizuku 的关键差异：Termux 是**普通应用权限**的 Linux 环境，
 * 只能跑 curl / python / 文本处理等工具链命令，不能执行 am / pm / settings 等系统命令。
 * 该边界同时声明给 AI（见提示词的执行通道能力段），避免它拿 Termux 去执行系统操作。
 */
@Composable
internal fun TermuxChannelCard(vm: MainViewModel) {
    val context = LocalContext.current
    val status by vm.termuxStatus.collectAsState()

    LaunchedEffect(Unit) { vm.refreshTermuxStatus() }

    AppCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(12.dp).background(
                        if (status.ready) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                        RoundedCornerShape(AppRadii.Chip),
                    ),
                )
                Column(Modifier.weight(1f)) {
                    Text("Termux 执行通道", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "普通应用权限的 Linux 环境：可跑 curl / python / 文本处理，不能执行系统命令",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.refreshTermuxStatus() }) { Text("重新检测") }
            }

            AdbStepRow(1, "已安装 Termux", if (status.installed) AdbStepStatus.DONE else AdbStepStatus.PENDING)
            AdbStepRow(2, "已授予执行权限", if (status.permissionGranted) AdbStepStatus.DONE else AdbStepStatus.PENDING)
            AdbStepRow(3, "已开启 allow-external-apps", if (status.ready) AdbStepStatus.DONE else AdbStepStatus.PENDING)

            when {
                !status.installed -> OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://f-droid.org/packages/com.termux/"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("前往 F-Droid 安装 Termux")
                }

                // 权限属于服务组件（真正调 Termux 的是它）。主应用已不声明 RUN_COMMAND，
                // 在主应用里弹运行时授权框只会被判为拒绝——这正是「点授予执行权限没反应、
                // 永远停在未授权」的原因。改为借内置 Shizuku 的 shell uid 直接 pm grant
                !status.permissionGranted -> Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Button(
                        onClick = { vm.grantTermuxPermission() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(AppIcons.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("授予执行权限")
                    }
                    TextButton(
                        onClick = { openProviderPermissions(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("改为在系统设置中授权") }
                }

                else -> Button(
                    onClick = { vm.probeTermux() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("实跑探测")
                }
            }

            if (status.installed && status.permissionGranted && !status.ready) {
                Text(
                    "若探测失败，请在 Termux 里执行一次：echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status.message.isNotBlank()) {
                StatusPill(
                    status.message,
                    if (status.ready) Success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 打开服务组件的应用详情页。
 *
 * RUN_COMMAND 是 Termux 声明的运行时权限，授权对象必须是**服务组件**（真正调 Termux 的进程），
 * 主应用自己的权限页对它无效；部分 ROM 不在设置里单列该权限，这条路是 Shizuku 不可用时的兜底。
 */
private fun openProviderPermissions(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:${com.phoneagent.device.provider.ProviderManager.PACKAGE}"),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

// ============ 分步引导的共用小组件（原技能页 WirelessAdbTab 迁入） ============

/** 引导步骤的状态 */
internal enum class AdbStepStatus { DONE, ACTIVE, ERROR, PENDING }

/** 由内置 Shizuku 的就绪态与引导会话阶段推导 2 步状态（服务启动 / 授权） */
private fun shizukuStepStatuses(
    shizukuReady: Boolean,
    phase: AdbPhase,
    message: String,
): List<AdbStepStatus> {
    if (shizukuReady) return listOf(AdbStepStatus.DONE, AdbStepStatus.DONE)
    return when (phase) {
        AdbPhase.READY ->
            // 引导会话就绪（server 拉起中或以会话代执行）：第一步视为完成，等授权/等待就绪
            listOf(AdbStepStatus.DONE, AdbStepStatus.ACTIVE)
        AdbPhase.BOOTING -> listOf(AdbStepStatus.ACTIVE, AdbStepStatus.PENDING)
        AdbPhase.FAILED, AdbPhase.DISCONNECTED ->
            if (message.contains("权限") || message.contains("授权"))
                listOf(AdbStepStatus.DONE, AdbStepStatus.ERROR)
            else listOf(AdbStepStatus.ERROR, AdbStepStatus.PENDING)
        else -> listOf(AdbStepStatus.PENDING, AdbStepStatus.PENDING)
    }
}

/** 单步引导行：序号圆点 + 标题 + 状态色 */
@Composable
internal fun AdbStepRow(index: Int, title: String, status: AdbStepStatus) {
    val (bg, fg) = when (status) {
        AdbStepStatus.DONE -> Success to Color.White
        AdbStepStatus.ACTIVE -> MaterialTheme.colorScheme.primary to Color.White
        AdbStepStatus.ERROR -> MaterialTheme.colorScheme.error to Color.White
        AdbStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(24.dp).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (status) {
                    AdbStepStatus.DONE -> "✓"
                    AdbStepStatus.ERROR -> "!"
                    else -> "$index"
                },
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (status == AdbStepStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                color = if (status == AdbStepStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (status == AdbStepStatus.ACTIVE) {
                Text("进行中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
