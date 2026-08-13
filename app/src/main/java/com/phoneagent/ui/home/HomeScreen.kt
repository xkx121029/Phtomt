package com.phoneagent.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.DurationFast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.a11y.AgentAccessibilityService
import com.phoneagent.model.PermissionItem
import com.phoneagent.model.PermissionKind
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionHeader
import com.phoneagent.ui.components.StatusCard

// 语义化颜色常量
// 成功状态颜色（Material3 无内置 success token）
private val SuccessColor = Color(0xFF2E9E6B)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onRequestScreenshot: () -> Unit,
    onNavigate: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val settings by vm.settingsFlow.collectAsState()
    val a11y by vm.a11yEnabled.collectAsState()
    val agent by vm.agentState.collectAsState()
    val permissions by vm.permissions.collectAsState()
    val screenshotActive by vm.screenshotActive.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshStatus(context)
        vm.refreshA11yState()
        vm.refreshPermissions(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        // 品牌区
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp))
            }
            Column {
                Text("手机智能体", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "AI 接管手机，替你把任务做完",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 运行状态卡图
        AnimatedVisibility(
            visible = agent.isRunning,
            enter = fadeIn(animationSpec = tween(durationMillis = DurationFast, easing = EaseOut)) +
                slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = tween(durationMillis = DurationFast, easing = EaseOut),
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = DurationFast, easing = EaseOut)) +
                slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(durationMillis = DurationFast, easing = EaseOut),
                ),
        ) {
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("智能体正在运行", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            agent.message.ifBlank { agent.task },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 状态概览
        SectionHeader("能力状态")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PressableScale(
                modifier = Modifier.weight(1f),
                onClick = { AgentAccessibilityService.openSettings(context) },
            ) {
                StatusCard(
                    icon = Icons.Rounded.TouchApp,
                    title = if (a11y) "无障碍服务" else "未开启",
                    subtitle = if (a11y) "已连接，可读取与操作" else "点击前往开启",
                    iconColor = if (a11y) SuccessColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconBackground = if (a11y) SuccessColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PressableScale(
                modifier = Modifier.weight(1f),
                onClick = onRequestScreenshot,
            ) {
                StatusCard(
                    icon = Icons.Rounded.CameraAlt,
                    title = "屏幕捕获",
                    subtitle = if (screenshotActive) "运行中，支持视觉理解" else "点击授权截屏",
                    iconColor = if (screenshotActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconBackground = if (screenshotActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
            }
            PressableScale(
                modifier = Modifier.weight(1f),
                onClick = {},
            ) {
                StatusCard(
                    icon = Icons.Rounded.Key,
                    title = if (settings.apiKey.isNotBlank()) "AI 已配置" else "未配置",
                    subtitle = settings.model,
                    iconColor = if (settings.apiKey.isNotBlank()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconBackground = if (settings.apiKey.isNotBlank()) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        // 快捷模块入口
        SectionHeader("快捷入口")
        val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickEntry(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Bolt,
                title = "Agent",
                subtitle = "下达执行任务",
                onPress = { pressHaptic() },
                onClick = { onNavigate(1) },
            )
            QuickEntry(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Science,
                title = "测试",
                subtitle = "模型回归校验",
                onPress = { pressHaptic() },
                onClick = { onNavigate(2) },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickEntry(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Settings,
                title = "设置",
                subtitle = "模型与权限配置",
                onPress = { pressHaptic() },
                onClick = { onNavigate(3) },
            )
            QuickEntry(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Terminal,
                title = "调试",
                subtitle = "日志与指标",
                onPress = { pressHaptic() },
                onClick = { onNavigate(4) },
            )
        }

        // 权限雷达
        SectionHeader("权限雷达")
        PermissionRadar(permissions, context, vm)

        // 版本号
        Spacer(Modifier.height(20.dp))
        Text(
            text = "v${com.phoneagent.BuildConfig.VERSION_NAME} (${com.phoneagent.BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PermissionRadar(
    permissions: List<PermissionItem>,
    context: android.content.Context,
    vm: MainViewModel,
) {
    val pending = permissions.filter { !it.granted }
    val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (pending.isEmpty()) "全部已就绪" else "尚需授权 ${pending.size} 项",
            style = MaterialTheme.typography.bodyMedium,
            color = if (pending.isEmpty()) SuccessColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        permissions.forEach { p ->
            val raw = rawPermissionIcon(p.kind)
            val itemColor = if (p.granted) SuccessColor else MaterialTheme.colorScheme.primary
            PressableScale(
                modifier = Modifier.fillMaxWidth(),
                onPress = { if (!p.granted) pressHaptic() },
                onClick = {
                    if (!p.granted) {
                        vm.openPermissionSettings(context, p.kind)
                    }
                },
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (p.granted) SuccessColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = itemColor.copy(alpha = 0.16f),
                        ) {
                            Icon(raw, contentDescription = null, tint = itemColor, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                p.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (p.granted) {
                            Text(
                                "完成",
                                style = MaterialTheme.typography.labelMedium,
                                color = SuccessColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            Text(
                                "去授权",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun rawPermissionIcon(kind: PermissionKind): ImageVector = when (kind) {
    PermissionKind.ACCESSIBILITY -> Icons.Rounded.TouchApp
    PermissionKind.OVERLAY -> Icons.Rounded.CameraAlt
    PermissionKind.AUTOSTART -> Icons.Filled.Bolt
    PermissionKind.QUERY_ALL_PACKAGES -> Icons.Rounded.Memory
}

@Composable
private fun QuickEntry(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onPress: () -> Unit = {},
) {
    PressableScale(modifier = modifier, onClick = onClick, onPress = onPress) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

