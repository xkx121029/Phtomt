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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.ui.ExtrasPage
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppSurfaceMuted
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalIsExpanded
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
    // ACA（编码 agent）可用技能：流式扫描，列表逐条增长
    val acaSkills by vm.acaSkills.collectAsState()
    val acaScanning by vm.acaScanning.collectAsState()
    val acaSkillErrors by vm.acaSkillErrors.collectAsState()

    // 视觉模型入口已收进 设置 → 端侧视觉，主页不再展示
    LaunchedEffect(Unit) {
        vm.refreshStatus(context)
        vm.refreshA11yState(context)
        vm.refreshPermissions(context)
        vm.refreshAcaSkills(context)
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
                    val configured = settings.apiKey.isNotBlank()
                    StatusCard(
                        icon = AppIcons.Key,
                        title = if (configured) "AI 已配置" else "未配置",
                        // 未配置时给一句"下一步做什么"：副标题留空的话，用户点进去只看到一整页设置，
                        // 不知道该填哪一项（这是首屏最需要说清的一件事）
                        subtitle = if (configured) settings.model else "接入模型后即可开始",
                        iconColor = if (configured) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        iconBackground = if (configured) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        } else {
                            AppSurfaceMuted
                        },
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
                // 调试 / 测试不再占首页位置：它们是排查工具，入口收进「设置 → 关于」，
                // 首屏这几个位置留给用户真正会天天用的入口
                QuickEntry(
                    modifier = Modifier.weight(1f).animateListItem(6),
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
        }

        val secondarySections: @Composable ColumnScope.() -> Unit = {
            // 编码技能（ACA）：与编码 Agent 共用 filesDir/skills，主页只扫盘不启动
            // node 子进程。流式输出 = 扫描逐条回调 + animateListItem 级联入场，
            // 观感是技能一条条"流"出来；描述非中文时经主引擎翻译成中文。
            SectionHeader("编码技能")
            if (acaSkills.isEmpty()) {
                Text(
                    text = if (acaScanning) "正在扫描编码技能…" else "暂无可用编码技能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            acaSkills.forEachIndexed { index, skill ->
                AcaSkillCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateListItem(index),
                    icon = AppIcons.Code,
                    name = skill.name,
                    description = skill.description,
                    meta = "v${skill.version} · ${skill.tools.size} 个工具 · ${skill.id}",
                    iconColor = MaterialTheme.colorScheme.secondary,
                    iconBackground = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    translate = { vm.translate(it) },
                )
            }
            if (acaSkillErrors.isNotEmpty()) {
                Text(
                    text = "无效技能包：${acaSkillErrors.joinToString("；")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
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
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
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