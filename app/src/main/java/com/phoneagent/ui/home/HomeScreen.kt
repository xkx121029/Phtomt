package com.phoneagent.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoneagent.ui.ExtrasPage
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalIsExpanded
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.SectionHeader
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut

@Composable
fun HomeScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onRequestScreenshot: () -> Unit,
    onNavigate: (Int) -> Unit = {},
    onOpenExtras: (ExtrasPage) -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    /** 执行通道格：直落权限页的「执行通道」段，而不是授权段 */
    onOpenChannel: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by vm.settingsFlow.collectAsState()
    val a11y by vm.a11yEnabled.collectAsState()
    val agent by vm.agentState.collectAsState()
    val permissions by vm.permissions.collectAsState()
    val screenshotActive by vm.screenshotActive.collectAsState()
    // 执行通道：Shizuku / Termux 的真实可用性，决定状态条第四格怎么报
    val shizukuState by vm.shizukuState.collectAsState()
    val termuxStatus by vm.termuxStatus.collectAsState()
    val shizukuReady = shizukuState == com.phoneagent.device.shell.ShizukuManager.State.READY
    val termuxReady = termuxStatus.ready
    val channel = when (settings.executionChannel) {
        "SHIZUKU" -> if (shizukuReady) "Shizuku" else "未就绪"
        "TERMUX" -> if (termuxReady) "Termux" else "未就绪"
        else -> when {
            shizukuReady -> "Shizuku"
            termuxReady -> "Termux"
            else -> "无障碍"
        }
    }
    val channelReady = shizukuReady || termuxReady || a11y

    // 视觉模型入口已收进 设置 → 端侧视觉，主页不再展示
    LaunchedEffect(Unit) {
        vm.refreshStatus(context)
        vm.refreshA11yState(context)
        vm.refreshPermissions(context)
        vm.refreshTermuxStatus()
    }

    // 从系统授权页（无障碍/悬浮窗/电池/通知）返回后立刻重读，否则首页仍显示「去授权」，
    // 得切一次页才更新。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshStatus(context)
                vm.refreshA11yState(context)
                vm.refreshPermissions(context)
                vm.refreshTermuxStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 顶部滚动容器的状态：传给 GlassHeaderScaffold 做显式浮起判定（官网风格，比内部
    // NestedScrollConnection 更稳——不依赖手势方向约定）。8px 阈值同官网，防手抖。
    val scrollState = rememberScrollState()

    // 页眉是浮在正文之上的玻璃板：贴顶时通栏直角，离顶才收成圆角浮板。
    // 品牌信息搬进页眉后，它随着页面滚动一直悬在内容上方，与 Agent / 调试 / 技能页
    // 共用同一套骨架（GlassHeaderScaffold），不再各页自己摆一个标题行。
    GlassHeaderScaffold(
        modifier = modifier,
        floating = { scrollState.value > 8 },
        header = {
            AppTopBar(
                title = "手机智能体",
                subtitle = "AI 接管手机，替你把任务做完",
                leadingContent = { BrandTile() },
            )
        },
    ) { contentPad ->
        // 两组区块：横屏时左右并排，竖屏时仍按原顺序单列往下排。
        // 组内顺序不变，所以竖屏的阅读节奏与改动前完全一致。
        val primarySections: @Composable ColumnScope.() -> Unit = {
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

            // 首屏第一件事只回答一个问题：我要做什么。一个主入口卡，点它就去 Agent 页下达任务；
            // 未配置模型时同一张卡直接说"下一步先配置"，不另开一张状态卡
            val configured = settings.apiKey.isNotBlank()
            PrimaryActionCard(
                modelReady = configured,
                model = settings.model,
                onClick = { onNavigate(if (configured) 0 else 2) },
            )

            // 状态压成一条：三格分别回答"模型 / 无障碍 / 截屏"能不能用，点哪格去哪格
            SectionHeader("运行状态")
            HomeStatusStrip(
                modelReady = configured,
                // 四格并排时模型名常被挤成省略号，这格只回答「配好了没有」；具体模型在「常用」卡里看
                model = if (configured) "已配置" else "未配置",
                a11yReady = a11y,
                screenshotReady = screenshotActive,
                channel = channel,
                channelReady = channelReady,
                onModel = { onNavigate(2) },
                // 无障碍由「服务组件」提供：没装组件就先去装，别把用户丢进空的无障碍列表
                onA11y = { vm.openPermissionSettings(context, PermissionKind.ACCESSIBILITY) },
                onScreenshot = onRequestScreenshot,
                onChannel = onOpenChannel,
            )

            // 常用入口只留两个：其余能力都收在底部导航（Coding / 记忆 / 设置）与「设置」里
            SectionHeader("常用")
            val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QuickEntry(
                    modifier = Modifier.weight(1f).animateListItem(3),
                    icon = AppIcons.Memory,
                    tint = MaterialTheme.colorScheme.tertiary,
                    title = "技能&能力",
                    subtitle = "Skill/MCP/ADB",
                    onPress = { pressHaptic() },
                    onClick = { onOpenExtras(ExtrasPage.Skill) },
                )
                QuickEntry(
                    modifier = Modifier.weight(1f).animateListItem(4),
                    icon = AppIcons.Globe,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = "浏览器",
                    subtitle = "AI 上网、操作网页",
                    onPress = { pressHaptic() },
                    onClick = { onOpenExtras(ExtrasPage.Browser) },
                )
            }
        }

        val secondarySections: @Composable ColumnScope.() -> Unit = {
            // 编码技能已从首页移除：技能清单在 Coding Tab 与「设置 → 编码 Agent」各有入口，
            // 首页再平铺一遍只是把第一屏让给重复信息。
            // 权限改为一行待办入口，不再平铺 6 张卡（见 PermissionTodoLine）；N=0 时整行不显示。
            PermissionTodoLine(permissions, onOpen = onOpenPermissions)

            // 版本号
            Spacer(Modifier.height(20.dp))
            Text(
                text = "v${com.phoneagent.BuildConfig.VERSION_NAME} (${com.phoneagent.BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                // 滚动列只铺宽，不铺满高：内容少时让列跟着内容收，
                // 不再被 fillMaxSize 撑到视口高而在页尾留一块结构性的空区
                .fillMaxWidth()
                .verticalScroll(scrollState)
                // 页眉净空必须垫在滚动容器**内部**（verticalScroll 之后再 padding）：
                // 垫在容器外面只是把内容整体压低，页眉背后永远是一块纯底色，
                // 玻璃会退化成一条灰带，正文也不会从它下面穿过。
                // 再多让出 8dp 呼吸，与 Agent 页正文的上缘留白对齐
                .padding(top = contentPad.calculateTopPadding() + AppSpacing.Sm)
                .padding(horizontal = 20.dp)
                // 悬浮导航栏浮在内容之上：滚动内容要能滚到它上面去，只在最后让出净空
                .padding(LocalNavClearance.current),
        ) {
            // 宽屏左右并排、窄屏单列往下排。判定按宽度而不是方向：
            // 平板竖屏同样够宽、单列会把一块留白拉得很难看
            if (LocalIsExpanded.current) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f), content = primarySections)
                    Column(modifier = Modifier.weight(1f), content = secondarySections)
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth(), content = primarySections)
                Column(modifier = Modifier.fillMaxWidth(), content = secondarySections)
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}