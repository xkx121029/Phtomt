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
import com.phoneagent.ui.theme.AppTheme
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
    /** 模型「云端模型」页：看上次验证结论、重新测试连接 */
    onOpenModelSettings: () -> Unit = {},
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
    // 通道类型（AI 靠什么动手机）与「这一路到底通不通」分成两件事算：
    // 原来把两者混成一个字符串，AUTO 下两个 shell 都不可用时回退成「无障碍」却不看无障碍开没开，
    // 于是出现「无障碍」配红点（UX-06）
    val channelName = when {
        settings.executionChannel == "SHIZUKU" -> "Shizuku"
        settings.executionChannel == "TERMUX" -> "Termux"
        shizukuReady -> "Shizuku"
        termuxReady -> "Termux"
        else -> "无障碍"
    }
    val channelTone = when (channelName) {
        "Shizuku" -> if (shizukuReady) StatusTone.OK else StatusTone.DOWN
        "Termux" -> if (termuxReady) StatusTone.OK else StatusTone.DOWN
        // 只剩无障碍：开着算降级（能用但没有 shell 通道的完整能力），关掉才算不可用
        else -> if (a11y) StatusTone.DEGRADED else StatusTone.DOWN
    }
    val channelValue = when (channelTone) {
        StatusTone.OK -> "可用"
        StatusTone.DEGRADED -> "降级"
        else -> "不可用"
    }
    // 主模型的可信度（未配置 / 未验证 / 已验证 / 验证失败），首页所有「配好了没有」的表述都取它
    val trust = settings.modelTrust

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
            // 没验过的模型不带你去下达任务——未验证的去模型页测一次，失败的先去修复（UX-01）
            PrimaryActionCard(
                trust = trust,
                model = settings.model,
                onClick = {
                    when (trust.state) {
                        com.phoneagent.core.ai.ModelTrust.State.VERIFIED,
                        // 未验证不拦：允许用户离线配好后直接开跑，风险在副标题里说清；
                        // 想先验一次就走状态条的模型格（那里直达云端模型页）
                        com.phoneagent.core.ai.ModelTrust.State.UNVERIFIED,
                        -> onNavigate(0)
                        com.phoneagent.core.ai.ModelTrust.State.UNCONFIGURED -> onNavigate(2)
                        // 验证失败不放行：点了去修，不让人拿着已知失效的 Key 起任务
                        else -> onOpenModelSettings()
                    }
                },
            )

            // 状态压成一条：四格分别回答"模型 / 无障碍 / 截屏 / 执行通道"能不能用，点哪格去哪格
            SectionHeader("运行状态")
            HomeStatusStrip(
                modelState = when (trust.state) {
                    com.phoneagent.core.ai.ModelTrust.State.VERIFIED -> "已验证"
                    com.phoneagent.core.ai.ModelTrust.State.UNVERIFIED -> "未验证"
                    com.phoneagent.core.ai.ModelTrust.State.FAILED -> "验证失败"
                    com.phoneagent.core.ai.ModelTrust.State.UNCONFIGURED -> "未配置"
                },
                modelTone = when (trust.state) {
                    com.phoneagent.core.ai.ModelTrust.State.VERIFIED -> StatusTone.OK
                    com.phoneagent.core.ai.ModelTrust.State.UNVERIFIED -> StatusTone.DEGRADED
                    else -> StatusTone.DOWN
                },
                a11yReady = a11y,
                screenshotReady = screenshotActive,
                // 关掉「附送屏幕截图」后这一步根本不需要授权，别再报一项待办
                screenshotNeeded = settings.attachScreenshot,
                channelName = channelName,
                channelValue = channelValue,
                channelTone = channelTone,
                // 已填配置的落到模型页（能看到上次验证的结论），从零开始才去快速开始
                onModel = { if (trust.configured) onOpenModelSettings() else onNavigate(2) },
                // 无障碍由「服务组件」提供：没装组件就先去装，别把用户丢进空的无障碍列表
                onA11y = { vm.openPermissionSettings(context, PermissionKind.ACCESSIBILITY) },
                onScreenshot = onRequestScreenshot,
                onChannel = onOpenChannel,
            )

            // 常用入口只留两个：其余能力都收在底部导航（Coding / 记忆 / 设置）与「设置」里
            SectionHeader("常用")
            val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md), modifier = Modifier.fillMaxWidth()) {
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
            Spacer(Modifier.height(AppTheme.metrics.pagePad))
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
                .padding(horizontal = AppTheme.metrics.pagePad)
                // 悬浮导航栏浮在内容之上：滚动内容要能滚到它上面去，只在最后让出净空
                .padding(LocalNavClearance.current),
        ) {
            // 宽屏左右并排、窄屏单列往下排。判定按宽度而不是方向：
            // 平板竖屏同样够宽、单列会把一块留白拉得很难看
            if (LocalIsExpanded.current) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.metrics.pagePad),
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