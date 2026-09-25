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
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.ExtrasPage
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.LocalBottomNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionHeader
import com.phoneagent.ui.components.SnackbarType
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.theme.Accent
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.BrandNavy
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

@Composable
fun HomeScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onRequestScreenshot: () -> Unit,
    onNavigate: (Int) -> Unit = {},
    onOpenExtras: (ExtrasPage) -> Unit = {},
) {
    val context = LocalContext.current
    // 宿主注入的全局浮条：在组合期取值，闭包里直接用，避免在协程里读 CompositionLocal
    val snackbar = LocalSnackbar.current
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
            visualConn = com.phoneagent.device.vision.ExternalVisionProvider.checkConnection(context)
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
            .padding(horizontal = 20.dp)
            // 悬浮导航栏浮在内容之上：滚动内容要能滚到它上面去，只在最后让出净空
            .padding(bottom = LocalBottomNavClearance.current),
    ) {
        // 品牌 Hero 区
        Spacer(Modifier.height(12.dp))
        BrandHero()
        Spacer(Modifier.height(20.dp))

        // 运行状态卡
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
                    icon = AppIcons.TouchApp,
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
                    icon = AppIcons.Camera,
                    title = "屏幕捕获",
                    subtitle = if (screenshotActive) "运行中，支持视觉理解" else "点击授权截屏",
                    iconColor = if (screenshotActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconBackground = if (screenshotActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
            }
            PressableScale(
                modifier = Modifier.weight(1f).animateListItem(2),
                // 设置 Tab 索引（去掉记忆 Tab 后为 2）
                onClick = { onNavigate(2) },
            ) {
                StatusCard(
                    icon = AppIcons.Key,
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
                if (!com.phoneagent.device.vision.ExternalVisionProvider.launchApp(context)) {
                    // 轻量反馈走页面内浮条，不用系统 Toast（字体/圆角/位置都不是本项目语言）
                    snackbar?.show(
                        "未检测到外挂视觉 APK（com.phoneagent.ondevice），请先安装后重试",
                        SnackbarType.WARNING,
                    )
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
                icon = AppIcons.Bolt,
                tint = MaterialTheme.colorScheme.primary,
                title = "Agent",
                subtitle = "下达执行任务",
                onPress = { pressHaptic() },
                onClick = { onNavigate(0) },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(5),
                icon = AppIcons.Settings,
                tint = MaterialTheme.colorScheme.tertiary,
                title = "设置",
                subtitle = "模型与权限配置",
                onPress = { pressHaptic() },
                // 设置 Tab 索引（去掉记忆 Tab 后为 2）
                onClick = { onNavigate(2) },
            )
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(6),
                icon = AppIcons.Code,
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
                icon = AppIcons.Science,
                tint = MaterialTheme.colorScheme.primary,
                title = "测试",
                subtitle = "模型回归校验",
                onPress = { pressHaptic() },
                onClick = { onOpenExtras(ExtrasPage.Test) },
            )
            QuickEntry(
                modifier = Modifier.weight(1f).animateListItem(8),
                icon = AppIcons.Memory,
                tint = MaterialTheme.colorScheme.tertiary,
                title = "技能&能力",
                subtitle = "Skill/MCP/ADB",
                onPress = { pressHaptic() },
                onClick = { onOpenExtras(ExtrasPage.Skill) },
            )
        }
        Spacer(Modifier.height(12.dp))
        QuickEntry(
            modifier = Modifier.fillMaxWidth().animateListItem(9),
            icon = AppIcons.Globe,
            tint = MaterialTheme.colorScheme.secondary,
            title = "浏览器",
            subtitle = "AI 上网、操作网页的落点",
            onPress = { pressHaptic() },
            onClick = { onOpenExtras(ExtrasPage.Browser) },
        )

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
