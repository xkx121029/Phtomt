package com.phoneagent.ui.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.coding.agent.CodingWorkspaces
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.data.prefs.WorkbenchView
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.agent.AgentScreen
import com.phoneagent.ui.coding.CodingScreen
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.SettingsPage
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppStyle
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.ScreenTransitions
import com.phoneagent.ui.theme.UiStyle
import com.phoneagent.ui.theme.appBorderStroke
import com.phoneagent.ui.theme.motionSettings

/** 顶栏高度：一行标题 + 视图切换，紧凑到不抢正文（不走新的间距令牌，只用固定行高） */
private val WorkbenchBarHeight = 52.dp

/** 状态条高度：一行事实，不再给每项套胶囊 */
private val WorkbenchStripHeight = 30.dp

/** 配置面板的最大高度：超过就内滚，不把整页拉成长表单 */
private val ConfigPanelMaxHeight = 460.dp

/**
 * 工作台（[UiStyle.WORKBENCH]）的首屏。
 *
 * 结构与 Eta 的壳层对齐，但视觉完全走本项目令牌：一条顶栏负责身份、视图切换与配置，
 * 一条状态条交代当前视图的事实，正文铺满到屏幕底、由各视图自己接管。
 * 与经典首页的区别不在配色，而在**层级组织**：
 * - 经典：页眉玻璃板浮在滚动内容上，卡片一层层往下排；
 * - 工作台：顶栏是实心横条 + 发丝线，不浮起、不投影，正文是唯一主角。
 *
 * Agent / Coding 两种视图共用这一个壳，切换状态落在 [AppSettings.Settings.workbenchView]，
 * 下次进入保持上次所在视图。两种视图不是换个标题：Agent 视图回答"任务在做什么"，
 * Coding 视图回答"代码改到哪一步"，正文分别复用既有的 AgentScreen 与 CodingScreen。
 */
@Composable
fun WorkbenchScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    /** Agent 页的记忆入口（原「记忆」Tab 已并入 Agent 页） */
    onOpenMemory: () -> Unit = {},
    /** Agent 生成内容的全屏阅读 */
    onOpenDocFullscreen: () -> Unit = {},
    /** 配置面板里的深链：翻到设置页对应分类 */
    onOpenSettingsPage: (SettingsPage) -> Unit = {},
    /** Coding 视图里 AI 回返正文的全屏阅读 */
    onOpenMarkdownFullscreen: (String) -> Unit = {},
) {
    val settings by vm.settingsFlow.collectAsState()
    val a11y by vm.a11yEnabled.collectAsState()
    val agent by vm.agentState.collectAsState()
    val context = LocalContext.current
    val colors = AppTheme.colors

    val view = WorkbenchView.normalize(settings.workbenchView)
    // 工作区名只用于状态条：读一次即可，不参与重组判定
    val workspaceName = remember(context) {
        runCatching { CodingWorkspaces.active(context).name }.getOrDefault("默认工作区")
    }

    var configOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = configOpen) { configOpen = false }

    val motion = motionSettings()
    val switchMs = motion.scaledDuration(ScreenTransitions.Duration)

    fun selectView(next: String) {
        if (view != next) vm.saveSettings(settings.copy(workbenchView = next))
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WorkbenchBar(
                view = view,
                onSelectView = ::selectView,
                onOpenConfig = { configOpen = true },
            )
            Hairline(colors.outlineSoft)

            // 状态条：同一行内用"标签 值"的紧凑格式，两种视图各回答两三个问题
            when (view) {
                WorkbenchView.CODING -> StatusStrip {
                    Fact("工作区", workspaceName)
                    FactDivider()
                    Fact("审批", approvalLabel(settings.codingApprovalMode))
                    FactDivider()
                    Fact("视图", "编码工作台")
                }

                else -> StatusStrip {
                    Fact("模型", settings.model.ifBlank { "未配置模型" })
                    FactDivider()
                    Fact("无障碍", if (a11y) "已连接" else "未连接")
                    FactDivider()
                    Fact("执行", if (agent.isRunning) "运行中" else "待命")
                }
            }
            Hairline(colors.outlineSoft)

            AnimatedContent(
                targetState = view,
                transitionSpec = {
                    val enter = fadeIn(tween(switchMs, easing = EaseOut)) +
                        slideInVertically(tween(switchMs, easing = EaseOut)) { full -> full / 12 }
                    val exit = fadeOut(tween(switchMs, easing = EaseOut))
                    if (motion.reduceMotion) {
                        fadeIn(tween(switchMs, easing = EaseOut)) togetherWith exit
                    } else {
                        enter togetherWith exit
                    }
                },
                label = "workbench-view",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { current ->
                if (current == WorkbenchView.CODING) {
                    CodingScreen(
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        onOpenMarkdownFullscreen = onOpenMarkdownFullscreen,
                    )
                } else {
                    // Agent 视图接上现有实现，不另造一份：任务流、审批卡、输入区都在原页里
                    AgentScreen(
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        onOpenMemory = onOpenMemory,
                        onOpenDocFullscreen = onOpenDocFullscreen,
                        onOpenSettings = { onOpenSettingsPage(SettingsPage.AI_MODELS) },
                    )
                }
            }
        }

        // 配置面板：嵌入式，自下而上进出；关闭方式为点面板外或返回手势
        if (configOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { configOpen = false },
            )
        }
        AnimatedVisibility(
            visible = configOpen,
            enter = slideInVertically(
                animationSpec = tween(motion.scaledDuration(DurationFast), easing = EaseOut),
            ) { it } + fadeIn(tween(motion.scaledDuration(DurationFast), easing = EaseOut)),
            exit = slideOutVertically(
                animationSpec = tween(motion.scaledDuration(DurationFast), easing = EaseOut),
            ) { it } + fadeOut(tween(motion.scaledDuration(DurationFast), easing = EaseOut)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            WorkbenchConfigPanel(
                settings = settings,
                currentStyle = AppStyle.current,
                onSave = { vm.saveSettings(it) },
                onClose = { configOpen = false },
                onOpenSettingsPage = {
                    configOpen = false
                    onOpenSettingsPage(it)
                },
            )
        }
    }
}

/** 顶栏：身份 → 视图切换 → 配置，一条横线结束，不再叠玻璃板 */
@Composable
private fun WorkbenchBar(
    view: String,
    onSelectView: (String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkbenchBarHeight)
            .padding(start = AppSpacing.Lg, end = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(AppRadii.Chip))
                .background(colors.brand),
        )
        Spacer(Modifier.width(AppSpacing.Md))
        Text(
            "工作台",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurfaceBase,
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Text(
            if (view == WorkbenchView.CODING) "编码" else "操控",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceRaised,
        )
        Spacer(Modifier.weight(1f))
        ViewTab(
            label = "Agent",
            selected = view != WorkbenchView.CODING,
            onClick = { onSelectView(WorkbenchView.AGENT) },
        )
        Spacer(Modifier.width(AppSpacing.Md))
        ViewTab(
            label = "Coding",
            selected = view == WorkbenchView.CODING,
            onClick = { onSelectView(WorkbenchView.CODING) },
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(AppRadii.Chip))
                .clickable(onClick = onOpenConfig),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Settings,
                contentDescription = "工作台配置",
                tint = colors.onSurfaceRaised,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 视图切换的文字标签：选中项只靠"文字变深 + 下方 2dp 短线"表达，
 * 不滑动、不缩放；短线颜色做一次淡入淡出，符合"只做自下而上"的动效约束。
 */
@Composable
private fun ViewTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val tint by animateColorAsState(
        targetValue = if (selected) colors.onSurfaceBase else colors.onSurfaceRaised,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "view-tab-tint",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = AppSpacing.Xs),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
        Spacer(Modifier.height(AppSpacing.Xs))
        Box(
            Modifier
                .width(14.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(AppRadii.Chip))
                .background(if (selected) colors.brand else Color.Transparent),
        )
    }
}

/** 状态条容器：一行事实，左右与正文同一留白 */
@Composable
private fun StatusStrip(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkbenchStripHeight)
            .padding(horizontal = AppSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.Fact(label: String, value: String) {
    val colors = AppTheme.colors
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceRaised,
    )
    Spacer(Modifier.width(AppSpacing.Xs))
    Text(
        value,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = colors.onSurfaceBase,
    )
}

@Composable
private fun RowScope.FactDivider() {
    val colors = AppTheme.colors
    Spacer(Modifier.width(AppSpacing.Md))
    Box(
        Modifier
            .width(1.dp)
            .height(10.dp)
            .background(colors.outlineSoft),
    )
    Spacer(Modifier.width(AppSpacing.Md))
}

@Composable
private fun Hairline(color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** 编码审批档位的中文说法；与设置页共用同一套口径 */
private fun approvalLabel(raw: String): String = when (raw) {
    "ask" -> "每次询问"
    "bypass" -> "全部放行"
    else -> "仅风险询问"
}

/**
 * 配置面板：把散落各处的入口按功能聚类，一处找齐。
 *
 * 只做两件事：能就地改的（风格 / 默认视图 / 编码审批）直接改并即时落盘；
 * 需要成组表单项的（模型、权限、导航、数据）给一条深链，跳到设置页对应分类，
 * 不在面板里重造一份表单——配置项仍然只有设置页那一份真源。
 */
@Composable
private fun WorkbenchConfigPanel(
    settings: AppSettings.Settings,
    currentStyle: UiStyle,
    onSave: (AppSettings.Settings) -> Unit,
    onClose: () -> Unit,
    onOpenSettingsPage: (SettingsPage) -> Unit,
) {
    val colors = AppTheme.colors
    Surface(
        shape = RoundedCornerShape(topStart = AppRadii.Card, topEnd = AppRadii.Card),
        color = colors.surfaceBase,
        border = appBorderStroke(1.dp, colors.outlineSoft),
        modifier = Modifier
            .fillMaxWidth()
            // 面板停在悬浮导航上方：底部内容不被导航压住，也不在导航底下留一条被截断的正文
            .padding(bottom = LocalNavClearance.current.calculateBottomPadding()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = ConfigPanelMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppSpacing.Lg,
                    end = AppSpacing.Lg,
                    bottom = AppSpacing.Lg,
                    top = AppSpacing.Lg,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "工作台配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceBase,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "按功能归类；改完立即生效，无需重启",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceRaised,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = "关闭",
                        tint = colors.onSurfaceRaised,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.Lg))

            ConfigGroup("外观") {
                ChoiceRow(
                    label = "界面风格",
                    options = UiStyle.values().map { it.id to it.label },
                    selected = currentStyle.id,
                    onSelect = { id -> onSave(settings.copy(uiStyle = id)) },
                )
                LinkRow("配色、框线与磨砂", "主题", onClick = { onOpenSettingsPage(SettingsPage.THEME) })
            }

            ConfigGroup("工作台") {
                ChoiceRow(
                    label = "默认视图",
                    options = listOf(
                        WorkbenchView.AGENT to "Agent",
                        WorkbenchView.CODING to "Coding",
                    ),
                    selected = WorkbenchView.normalize(settings.workbenchView),
                    onSelect = { id -> onSave(settings.copy(workbenchView = id)) },
                )
            }

            ConfigGroup("模型与执行") {
                LinkRow(
                    label = "模型与接入",
                    value = settings.model.ifBlank { "未配置" },
                    onClick = { onOpenSettingsPage(SettingsPage.AI_MODELS) },
                )
                LinkRow(
                    label = "权限与能力",
                    value = "无障碍 / 悬浮窗 / 截屏",
                    onClick = { onOpenSettingsPage(SettingsPage.PERMISSIONS) },
                )
            }

            ConfigGroup("编码") {
                LinkRow(
                    label = "编码环境",
                    value = "运行时 / 构建环境",
                    onClick = { onOpenSettingsPage(SettingsPage.CODING_ENV) },
                )
                ChoiceRow(
                    label = "审批档位",
                    options = listOf(
                        "ask" to "每次询问",
                        "risky" to "仅风险",
                        "bypass" to "全部放行",
                    ),
                    selected = settings.codingApprovalMode,
                    onSelect = { mode -> onSave(settings.copy(codingApprovalMode = mode)) },
                )
            }

            ConfigGroup("导航与数据") {
                LinkRow("选项卡", "顺序与可见性", onClick = { onOpenSettingsPage(SettingsPage.TABS) })
                LinkRow("数据与记忆", "导出 / 清空", onClick = { onOpenSettingsPage(SettingsPage.DATA) })
                LinkRow("关于与更新", "版本 / 频道", onClick = { onOpenSettingsPage(SettingsPage.ABOUT) })
            }

            Spacer(Modifier.height(AppSpacing.Sm))
        }
    }
}

@Composable
private fun ConfigGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = AppTheme.colors
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceRaised,
        modifier = Modifier.padding(top = AppSpacing.Md, bottom = AppSpacing.Xs),
    )
    Column(Modifier.fillMaxWidth(), content = content)
}

/** 就地可改的一行：左侧名称，右侧是若干文字选项，选中项用底色 + 主色文字标出 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceBase,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)) {
            options.forEach { (id, text) ->
                val active = id == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(if (active) colors.brandContainer else colors.surfaceSunken)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (!active) onSelect(id) }
                        .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) colors.onBrandContainer else colors.onSurfaceRaised,
                    )
                }
            }
        }
    }
}

/** 需要成组表单的行：右侧一句摘要 + 箭头，整行可点，跳到设置页对应分类 */
@Composable
private fun LinkRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceBase,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceRaised,
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceRaised,
            modifier = Modifier.size(14.dp),
        )
    }
}
