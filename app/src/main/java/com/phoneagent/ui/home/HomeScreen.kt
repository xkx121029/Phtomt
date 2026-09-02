package com.phoneagent.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.a11y.AgentAccessibilityService
import com.phoneagent.model.PermissionItem
import com.phoneagent.model.PermissionKind
import com.phoneagent.ui.ExtrasPage
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionHeader
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.theme.Accent
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.BrandNavy
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onRequestScreenshot: () -> Unit,
    onNavigate: (Int) -> Unit = {},
    onOpenExtras: (ExtrasPage) -> Unit = {},
) {
    val context = LocalContext.current
    val settings by vm.settingsFlow.collectAsState()
    val a11y by vm.a11yEnabled.collectAsState()
    val agent by vm.agentState.collectAsState()
    val permissions by vm.permissions.collectAsState()
    val screenshotActive by vm.screenshotActive.collectAsState()
    val scope = rememberCoroutineScope()

    // 外挂视觉模型连接状态
    var visualConn by remember { mutableStateOf<Boolean?>(null) }
    var visualChecking by remember { mutableStateOf(false) }

    fun testVisual() {
        scope.launch {
            visualChecking = true
            visualConn = com.phoneagent.vision.ExternalVisionProvider.checkConnection(context)
            visualChecking = false
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshStatus(context)
        vm.refreshA11yState()
        vm.refreshPermissions(context)
        testVisual()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // 品牌 Hero 区
        Spacer(Modifier.height(12.dp))
        BrandHero()
        Spacer(Modifier.height(20.dp))

        // 运行状态卡
        AnimatedVisibility(
            visible = agent.isRunning,
            enter = fadeIn(animationSpec = tween(durationMillis = 160, easing = EaseOut)) +
                slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = tween(durationMillis = 160, easing = EaseOut),
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 160, easing = EaseOut)) +
                slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(durationMillis = 160, easing = EaseOut),
                ),
        ) {
            RunningBanner(agent.message.ifBlank { agent.task })
            Spacer(Modifier.height(20.dp))
        }

        // 状态概览
        SectionHeader("能力状态")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PressableScale(
                modifier = Modifier.weight(1f).animateListItem(0),
                onClick = { AgentAccessibilityService.openSettings(context) },
            ) {
                StatusCard(
                    icon = Icons.Rounded.TouchApp,
                    title = if (a11y) "无障碍服务" else "未开启",
                    subtitle = if (a11y) "已连接，可读取与操作" else "点击前往开启",
                    iconColor = if (a11y) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconBackground = if (a11y) Success.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PressableScale(
                modifier = Modifier.weight(1f).animateListItem(1),
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
                modifier = Modifier.weight(1f).animateListItem(2),
                onClick = { onNavigate(4) },
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

        // 外挂视觉模型调试
        Spacer(Modifier.height(20.dp))
        SectionHeader("视觉模型")
        VisionModelCard(
            connected = visualConn,
            checking = visualChecking,
            onTest = ::testVisual,
            onOpenExternal = {
                if (!com.phoneagent.vision.ExternalVisionProvider.launchApp(context)) {
                    android.widget.Toast.makeText(
                        context,
                        "未检测到外挂视觉 APK（com.phoneagent.ondevice），请先安装后重试",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else {
                    // 已拉起外挂，稍后重新检测连接状态
                    scope.launch { delay(600); testVisual() }
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        // 快捷模块入口
        SectionHeader("快捷入口")
        val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(3),
                icon = Icons.Filled.Bolt,
                tint = MaterialTheme.colorScheme.primary,
                title = "Agent",
                subtitle = "下达执行任务",
                onPress = { pressHaptic() },
                onClick = { onNavigate(1) },
            )
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(4),
                icon = Icons.Filled.Folder,
                tint = MaterialTheme.colorScheme.secondary,
                title = "工作区",
                subtitle = "AI 编写文档",
                onPress = { pressHaptic() },
                onClick = { onNavigate(2) },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(5),
                icon = Icons.Filled.Settings,
                tint = MaterialTheme.colorScheme.tertiary,
                title = "设置",
                subtitle = "模型与权限配置",
                onPress = { pressHaptic() },
                onClick = { onNavigate(4) },
            )
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(6),
                icon = Icons.Rounded.Code,
                tint = MaterialTheme.colorScheme.secondary,
                title = "调试",
                subtitle = "日志与指标",
                onPress = { pressHaptic() },
                onClick = { onOpenExtras(ExtrasPage.Debug) },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(7),
                icon = Icons.Rounded.Science,
                tint = MaterialTheme.colorScheme.primary,
                title = "测试",
                subtitle = "模型回归校验",
                onPress = { pressHaptic() },
                onClick = { onOpenExtras(ExtrasPage.Test) },
            )
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(8),
                icon = Icons.Rounded.Memory,
                tint = MaterialTheme.colorScheme.tertiary,
                title = "技能&能力",
                subtitle = "Skill/MCP/ADB",
                onPress = { pressHaptic() },
                onClick = { onOpenExtras(ExtrasPage.Skill) },
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

/** 品牌 Hero：渐变品牌瓦片 + 主标题/副标题 */
@Composable
private fun BrandHero(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier.fillMaxWidth()) {
        // 品牌瓦片：深海军蓝 → 电光蓝紫 渐变，呼应应用图标主色调
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(AppRadii.Item))
                .background(Brush.linearGradient(listOf(BrandNavy, Accent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Column {
            Text("手机智能体", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "AI 接管手机，替你把任务做完",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 运行状态横幅：呼吸指示灯 + 当前消息 */
@Composable
private fun RunningBanner(message: String) {
    val transition = rememberInfiniteTransition(label = "running-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "running-pulse-alpha",
    )
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 呼吸灯
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column {
                Text("智能体正在运行", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
            color = if (pending.isEmpty()) Success else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        permissions.forEachIndexed { i, p ->
            val raw = rawPermissionIcon(p.kind)
            val itemColor = if (p.granted) Success else MaterialTheme.colorScheme.primary
            PressableScale(
                modifier = Modifier.fillMaxWidth().animateListItem(8 + i),
                onPress = { if (!p.granted) pressHaptic() },
                onClick = {
                    if (!p.granted) {
                        vm.openPermissionSettings(context, p.kind)
                    }
                },
            ) {
                AppItemCard(
                    containerColor = if (p.granted) Success.copy(alpha = 0.08f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    AppIconTile(
                        icon = raw,
                        tint = itemColor,
                        background = itemColor.copy(alpha = 0.16f),
                        tileSize = 44.dp,
                        iconSize = 22.dp,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            p.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (p.granted) {
                        StatusPill(
                            text = "完成",
                            color = Success,
                            modifier = Modifier.padding(end = 14.dp),
                        )
                    } else {
                        StatusPill(
                            text = "去授权",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 14.dp),
                        )
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
    PermissionKind.SHIZUKU -> Icons.Filled.Terminal
}

@Composable
private fun QuickEntry(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onPress: () -> Unit = {},
) {
    PressableScale(modifier = modifier, onClick = onClick, onPress = onPress) {
        AppItemCard {
            AppIconTile(
                icon = icon,
                tint = tint,
                background = tint.copy(alpha = 0.14f),
                tileSize = 46.dp,
                iconSize = 24.dp,
                modifier = Modifier.padding(start = 14.dp, top = 13.dp, bottom = 13.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 13.dp)) {
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

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color,
    iconBackground: Color,
) {
    AppItemCard {
        AppIconTile(
            icon = icon,
            tint = iconColor,
            background = iconBackground,
            tileSize = 44.dp,
            iconSize = 22.dp,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 外挂视觉模型调试卡：显示端侧视觉 APK（3B 模型）是否已连接，
 * 并可直接点击重新检测连接。整个 Agent 流程优先使用它框选控件。
 */
@Composable
private fun VisionModelCard(
    connected: Boolean?,
    checking: Boolean,
    onTest: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val ready = connected == true
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = if (ready) Success.copy(alpha = 0.08f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 连接指示灯
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                ready -> Success
                                connected == false -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        ),
                )
                Column(Modifier.weight(1f)) {
                    Text("端侧视觉 Agent（3B）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            checking -> "检测连接中…"
                            ready -> "已连接，已接入 Agent 全流程"
                            connected == false -> "未连接：外挂 APK 未安装或不可用"
                            else -> "正在检测…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onTest) {
                    Text(if (checking) "检测中" else "重测")
                }
                if (!ready && !checking) {
                    androidx.compose.material3.TextButton(onClick = onOpenExternal) {
                        Text("打开外挂")
                    }
                }
            }
            Text(
                "截图 → 外挂视觉框选控件（类型+用途+坐标）→ 按坐标决策与点击；不可用时自动回落云端/本地 OCR。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
