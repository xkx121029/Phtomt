package com.phoneagent.ui.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.data.prefs.WorkbenchView
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.agent.AgentChrome
import com.phoneagent.ui.agent.AgentScreen
import com.phoneagent.ui.coding.CodingScreen
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.SettingsPage
import com.phoneagent.ui.theme.AppElevation
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSize
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppStyle
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.ScreenTransitions
import com.phoneagent.ui.theme.UiStyle
import com.phoneagent.ui.theme.appBorderStroke
import com.phoneagent.ui.theme.appElevation
import com.phoneagent.ui.theme.motionSettings

// 顶栏高度、配置面板上限、触摸目标全部取 AppSize / AppSpacing 令牌：
// 这三类尺寸在设置页与 Agent 页也有对应物，各自写死一份会各自漂移。

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
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList", "LongMethod")
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
    /** 任务抽屉开合：抽屉盖住整页，外层据此让悬浮导航栏让位 */
    onDrawerOpenChange: (Boolean) -> Unit = {},
) {
    val settings by vm.settingsFlow.collectAsState()

    val view = WorkbenchView.normalize(settings.workbenchView)

    var configOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 任务抽屉的开合状态放在壳层：打开它的汉堡按钮在顶栏上，而抽屉本身在 Agent 页里
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = configOpen) { configOpen = false }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    val motion = motionSettings()
    val switchMs = motion.scaledDuration(ScreenTransitions.Duration)

    fun selectView(next: String) {
        if (view != next) vm.saveSettings(settings.copy(workbenchView = next))
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏只有两枚图标：左边开任务抽屉，右边是溢出菜单。
            // 视图切换、配置面板、记忆都收进溢出菜单——Eta 的首页顶栏就是这样
            // 把"身份"和"功能入口"压到最小，正文才是唯一主角。
            WorkbenchTopBar(
                view = view,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onOpenDrawer = { drawerOpen = true },
                onSelectView = {
                    menuOpen = false
                    selectView(
                        if (view == WorkbenchView.CODING) WorkbenchView.AGENT else WorkbenchView.CODING,
                    )
                },
                onOpenConfig = {
                    menuOpen = false
                    configOpen = true
                },
                onOpenMemory = {
                    menuOpen = false
                    onOpenMemory()
                },
            )

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
                        // 顶栏已由壳层画，编码页不再重复一条自己的页眉
                        showHeader = false,
                    )
                } else {
                    // Agent 视图接上现有实现，不另造一份：任务流、审批卡、输入区都在原页里
                    AgentScreen(
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        onOpenMemory = onOpenMemory,
                        onOpenDocFullscreen = onOpenDocFullscreen,
                        onOpenSettings = { onOpenSettingsPage(SettingsPage.QUICK_START) },
                        onDrawerOpenChange = {
                            drawerOpen = it
                            onDrawerOpenChange(it)
                        },
                        chrome = AgentChrome.SHELL,
                        drawerOpenState = drawerOpen,
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

/**
 * 顶栏：左边开任务抽屉，右边溢出菜单。不画标题、不叠玻璃板、不加分割线。
 *
 * 视图切换、工作台配置、记忆都收进溢出菜单——顶栏只留"两个入口"，
 * 其余功能不跟正文抢位置。编码视图没有任务抽屉，汉堡位留空占位，
 * 两枚图标仍分别贴住左右边。
 */
@Composable
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
private fun WorkbenchTopBar(
    view: String,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onSelectView: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.Bar)
            .padding(horizontal = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (view == WorkbenchView.CODING) {
            Spacer(Modifier.width(AppSize.TouchTarget))
        } else {
            TopBarAction(
                icon = AppIcons.Menu,
                contentDescription = "任务列表",
                onClick = onOpenDrawer,
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            TopBarAction(
                icon = AppIcons.More,
                contentDescription = "更多",
                onClick = { onMenuOpenChange(true) },
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenuOpenChange(false) },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (view == WorkbenchView.CODING) "切换到 Agent" else "切换到编码工作台",
                        )
                    },
                    onClick = onSelectView,
                )
                DropdownMenuItem(text = { Text("工作台配置") }, onClick = onOpenConfig)
                DropdownMenuItem(text = { Text("记忆") }, onClick = onOpenMemory)
            }
        }
    }
}

@Composable
private fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .size(AppSize.TouchTarget)
            .clip(RoundedCornerShape(AppRadii.Chip))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.onSurfaceBase,
            modifier = Modifier.size(AppSize.Icon),
        )
    }
}

/**
 * 配置面板：把散落各处的入口按功能聚类，一处找齐。
 *
 * 只做两件事：能就地改的（风格 / 默认视图 / 编码审批）直接改并即时落盘；
 * 需要成组表单项的（模型、权限、导航、数据）给一条深链，跳到设置页对应分类，
 * 不在面板里重造一份表单——配置项仍然只有设置页那一份真源。
 */
@Composable
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod")
private fun WorkbenchConfigPanel(
    settings: AppSettings.Settings,
    currentStyle: UiStyle,
    onSave: (AppSettings.Settings) -> Unit,
    onClose: () -> Unit,
    onOpenSettingsPage: (SettingsPage) -> Unit,
) {
    val colors = AppTheme.colors
    val panelShape = RoundedCornerShape(topStart = AppRadii.Card, topEnd = AppRadii.Card)
    Surface(
        shape = panelShape,
        color = colors.surfaceBase,
        border = appBorderStroke(AppSize.Hairline, colors.outlineSoft),
        modifier = Modifier
            .fillMaxWidth()
            // 面板停在悬浮导航上方：底部内容不被导航压住，也不在导航底下留一条被截断的正文
            .padding(bottom = LocalNavClearance.current.calculateBottomPadding())
            // 它是真正盖住正文的一层，给一点抬手高度；工作台风格下 appElevation 自动为 no-op
            .appElevation(AppElevation.Sheet, panelShape),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = AppSize.PanelMax)
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
                    Spacer(Modifier.height(AppSpacing.Xxs))
                    Text(
                        "按功能归类；改完立即生效，无需重启",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceRaised,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(AppSize.TouchTarget)
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = "关闭",
                        tint = colors.onSurfaceRaised,
                        modifier = Modifier.size(AppSize.IconSm),
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
                LinkRow("主题 / 效果 / 选项卡", "外观与导航", onClick = { onOpenSettingsPage(SettingsPage.APPEARANCE) })
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
            modifier = Modifier.size(AppSize.IconSm),
        )
    }
}

