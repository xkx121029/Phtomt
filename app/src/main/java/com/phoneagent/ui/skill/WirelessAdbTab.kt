package com.phoneagent.ui.skill.WirelessAdbTab

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Terminal
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
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import kotlinx.coroutines.launch

// ============ 无线 ADB Tab ============

@Composable
internal fun WirelessAdbTab(vm: MainViewModel) {
    val shizukuState by vm.shizukuState.collectAsState()
    val adbStatus by vm.adbStatus.collectAsState()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 执行通道状态：无线 ADB 为主、Shizuku 可选增强
        val settings by vm.settingsFlow.collectAsState()
        val channelText = when (settings.executionChannel) {
            "ADB" -> "无线ADB优先"
            "SHIZUKU" -> "Shizuku优先"
            else -> "自动（无线ADB优先）"
        }
        val adbReady = adbStatus.phase == AdbPhase.READY
        val shizukuReady = shizukuState == com.phoneagent.device.shell.ShizukuManager.State.READY
        val localIp = remember { AdbWirelessTransport.localIpv4Address() }
        AppCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp).background(
                        if (adbReady) Success
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        RoundedCornerShape(AppRadii.Chip),
                    ))
                    Column(Modifier.weight(1f)) {
                        Text("执行通道（$channelText）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "无线 ADB：${adbPhaseText(adbStatus.phase)} · Shizuku：${if (shizukuReady) "就绪" else "可选未启用"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { msg = null; vm.ensureAdbReady { msg = it } }) { Text("检测通路") }
                }
                Text(
                    if (localIp != null) "本机 IP：$localIp" else "未能获取本机 IP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (adbReady) "无线 ADB 已就绪，可直接执行 shell；Shizuku 为可选增强。"
                    else "无线 ADB 为主执行通道；配对连接后即可执行 shell，Shizuku 启动失败不影响执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 无线调试配对引导（分步）
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("无线调试配对（无需 Root）", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "按以下步骤配对后即可执行 shell；Shizuku 为可选增强，启动失败不影响执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 分步引导（序号圆点 + 人话 + 状态色）
                val stepStatuses = adbStepStatuses(adbStatus.phase, adbStatus.message)
                listOf(
                    "开启系统「无线调试」",
                    "输入 6 位配对码",
                    "配对并连接无线 ADB",
                    "可选拉起 Shizuku 增强",
                ).forEachIndexed { i, title ->
                    AdbStepRow(i + 1, title, stepStatuses[i])
                }

                // 失败/断线时提供重新配对入口
                if (adbStatus.phase == AdbPhase.FAILED || adbStatus.phase == AdbPhase.DISCONNECTED) {
                    OutlinedButton(
                        onClick = {
                            vm.cancelAdbPairing()
                            vm.startAdbDiscovery()
                            code = ""
                            msg = "已重新开始配对流程，请保持「使用配对码配对设备」界面…"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新配对")
                    }
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = { c -> code = c.filter { it.isDigit() }.take(6) },
                    label = { Text("6 位配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            vm.startAdbDiscovery()
                            msg = "已开始搜索无线调试服务，搜到后将在通知栏请求配对码…"
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("自动发现服务")
                    }
                    OutlinedButton(
                        onClick = { vm.pairAdb(code) { msg = it } },
                        enabled = code.length == 6,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("手动配对")
                    }
                }
                // 向导当前说话（Search 结果也回显到这里）
                val flowMsg by vm.adbPairingMessageFlow.collectAsState()
                if (flowMsg.isNotBlank()) {
                    StatusPill(flowMsg, MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
                }

                // 连接状态 + 错误提示
                if (adbStatus.isUserActionRequired || adbStatus.phase == AdbPhase.FAILED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            adbStatus.message.ifBlank { AdbError.UNKNOWN.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (adbStatus.phase != AdbPhase.UNPAIRED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Success)
                        Spacer(Modifier.width(6.dp))
                        Text(adbStatus.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (msg != null) {
            StatusPill(msg!!, if (msg!!.contains("就绪") || msg!!.contains("成功")) Success else MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun adbPhaseText(phase: AdbPhase): String = when (phase) {
    AdbPhase.UNPAIRED -> "未配对"
    AdbPhase.WAITING_CODE -> "等待输入配对码"
    AdbPhase.PAIRING -> "配对中"
    AdbPhase.PAIRED -> "已配对"
    AdbPhase.BOOTING -> "拉起 Shizuku 中"
    AdbPhase.READY -> "就绪"
    AdbPhase.DISCONNECTED -> "已断开"
    AdbPhase.FAILED -> "失败"
}

/** 无线 ADB 分步引导：某一步的状态 */
private enum class AdbStepStatus { DONE, ACTIVE, ERROR, PENDING }

/** 由当前 [AdbPhase] 与错误消息推导 4 步引导状态 */
private fun adbStepStatuses(phase: AdbPhase, message: String): List<AdbStepStatus> {
    return when (phase) {
        AdbPhase.READY -> listOf(AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.DONE)
        AdbPhase.WAITING_CODE -> listOf(AdbStepStatus.DONE, AdbStepStatus.ACTIVE, AdbStepStatus.PENDING, AdbStepStatus.PENDING)
        AdbPhase.PAIRING, AdbPhase.PAIRED -> listOf(AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.ACTIVE, AdbStepStatus.PENDING)
        AdbPhase.BOOTING -> listOf(AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.DONE, AdbStepStatus.ACTIVE)
        AdbPhase.FAILED, AdbPhase.DISCONNECTED -> {
            val errStep = when {
                message.contains("配对") -> 3
                message.contains("无线调试") -> 1
                else -> 4
            }
            (1..4).map { i ->
                when {
                    i == errStep -> AdbStepStatus.ERROR
                    i < errStep -> AdbStepStatus.DONE
                    else -> AdbStepStatus.PENDING
                }
            }
        }
        else -> listOf(AdbStepStatus.PENDING, AdbStepStatus.PENDING, AdbStepStatus.PENDING, AdbStepStatus.PENDING)
    }
}

/** 单步引导行：序号圆点 + 标题 + 状态色 */
@Composable
private fun AdbStepRow(index: Int, title: String, status: AdbStepStatus) {
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