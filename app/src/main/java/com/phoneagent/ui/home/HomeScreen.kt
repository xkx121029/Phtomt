package com.phoneagent.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.ui.ExtrasPage
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppSurfaceMuted
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionHeader
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success

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

    // 视觉模型入口已收进 设置 → 端侧视觉，主页不再展示
    LaunchedEffect(Unit) {
        vm.refreshStatus(context)
        vm.refreshA11yState(context)
        vm.refreshPermissions(context)
    }

    // 页眉是浮在正文之上的玻璃板：贴顶时通栏直角，离顶才收成圆角浮板。
    // 品牌信息搬进页眉后，它随着页面滚动一直悬在内容上方，与 Agent / 调试 / 技能页
    // 共用同一套骨架（GlassHeaderScaffold），不再各页自己摆一个标题行。
    GlassHeaderScaffold(
        modifier = modifier,
        header = {
            AppTopBar(
                title = "手机智能体",
                subtitle = "AI 接管手机，替你把任务做完",
                leadingContent = { BrandTile() },
            )
        },
    ) { contentPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 页眉净空必须垫在滚动容器**内部**（verticalScroll 之后再 padding）：
                // 垫在容器外面只是把内容整体压低，页眉背后永远是一块纯底色，
                // 玻璃会退化成一条灰带，正文也不会从它下面穿过。
                // 再多让出 8dp 呼吸，与 Agent 页正文的上缘留白对齐
                .padding(top = contentPad.calculateTopPadding() + AppSpacing.Sm)
                .padding(horizontal = 20.dp)
                // 悬浮导航栏浮在内容之上：滚动内容要能滚到它上面去，只在最后让出净空
                .padding(LocalNavClearance.current),
        ) {
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
            }

            // 区块之间的纵向节奏只由 [SectionHeader] 自带的上边距决定，
            // 正文里再补 Spacer 就成了"两次留白叠一起"（原先这里是 20+20=40，
            // 上一区块却是 12+20=32，同一屏里两种节奏）
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
                        iconBackground = if (a11y) Success.copy(alpha = 0.15f) else AppSurfaceMuted,
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
                        iconBackground = if (screenshotActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else AppSurfaceMuted,
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
                        iconBackground = if (settings.apiKey.isNotBlank()) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) else AppSurfaceMuted,
                    )
                }
            }

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
}