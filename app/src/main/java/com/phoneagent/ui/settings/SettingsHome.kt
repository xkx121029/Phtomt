package com.phoneagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppCardBorder
import com.phoneagent.ui.components.AppCardContainer
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.ThemePalette
import com.phoneagent.ui.theme.appBorderStroke

// ========== 设置页导航条目（唯一定义点） ==========
//
// 手机上这些条目渲染成主页里的五块分区卡；平板宽屏下则由设置页左边的常驻分类栏
// 直接消费同一份数据。两处各写一遍必然漂移（图标、文案、摘要口径改了一处忘了另一处），
// 所以条目只在这里定义一次，渲染形态由宿主决定。

/** 摘要文案的取数上下文：摘要要读当前设置与权限状态，两个值都由宿主聚合后传进来 */
internal data class SettingsOverviewCtx(
    val st: SettingsState,
    /** 尚未授予的权限项数（权限还没查出来时为 0） */
    val pendingPermissions: Int,
    /** 权限列表是否已拿到（false 时摘要显示「检测中」而不是「全部就绪」） */
    val permissionsKnown: Boolean,
    val permissionsReady: Boolean,
)

/** 一个设置入口：图标 / 标题 / 副标题 / 色相 / 右侧摘要 */
internal data class SettingsEntry(
    val page: SettingsPage,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val tone: SettingsTone,
    val summary: (SettingsOverviewCtx) -> String,
)

/** 一组设置入口：分区标题 + 同色相的一组条目 */
internal data class SettingsGroup(
    val title: String,
    val subtitle: String,
    val tone: SettingsTone,
    val entries: List<SettingsEntry>,
)

/**
 * 五块分区的定义。
 *
 * 分组依据是**用户要去解决什么**（配模型、调行为、给权限、改外观、管数据），
 * 而不是按技术模块平铺。执行通道原先在「Agent 运行」里还有个优先级开关，
 * 与「权限与执行通道」页里的配对界面说的是同一件事，现已合并到一处。
 */
internal fun settingsGroups(): List<SettingsGroup> = listOf(
    SettingsGroup(
        title = "AI 接入",
        subtitle = "云端模型与端侧视觉",
        tone = SettingsTone.Brand,
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.AI_MODELS,
                icon = AppIcons.SmartToy,
                title = "云端模型",
                subtitle = "端点 / 模型库 / 职责分配",
                tone = SettingsTone.Brand,
                summary = { ctx -> ctx.st.model.takeIf { it.isNotBlank() } ?: "未配置" },
            ),
            SettingsEntry(
                page = SettingsPage.ONDEVICE,
                icon = AppIcons.Preview,
                title = "端侧视觉",
                subtitle = "本地 3B 模型下载与启用",
                tone = SettingsTone.Brand,
                summary = { ctx -> if (ctx.st.enableExternalVision) "已启用" else "已关闭" },
            ),
            SettingsEntry(
                page = SettingsPage.CODING,
                icon = AppIcons.Code,
                title = "编码 Agent",
                subtitle = "运行时 / 审批档位 / 工作区",
                tone = SettingsTone.Brand,
                summary = { ctx ->
                    when (ctx.st.codingApprovalMode) {
                        "ask" -> "审批：询问"
                        "bypass" -> "审批：全自动"
                        else -> "审批：默认放行"
                    }
                },
            ),
        ),
    ),
    SettingsGroup(
        title = "运行行为",
        subtitle = "执行参数、提示词与长线任务",
        tone = SettingsTone.Cool,
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.AGENT,
                icon = AppIcons.Tune,
                title = "运行与提示词",
                subtitle = "步数上限 / 悬浮窗 / 页面记忆 / 提示词",
                tone = SettingsTone.Cool,
                summary = { ctx -> if (ctx.st.maxSteps > 0) "最多 ${ctx.st.maxSteps} 步" else "不设限" },
            ),
            SettingsEntry(
                page = SettingsPage.LONG_RUN,
                icon = AppIcons.ListTodo,
                title = "长线任务",
                subtitle = "执行策略 / 断点续传 / 任务模板",
                tone = SettingsTone.Cool,
                summary = { "任务管理" },
            ),
        ),
    ),
    SettingsGroup(
        title = "设备接入",
        subtitle = "系统权限与 shell 执行通道",
        tone = SettingsTone.Warm,
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.PERMISSIONS,
                icon = AppIcons.Guard,
                title = "权限与执行通道",
                subtitle = "无障碍 / 悬浮窗 / Shizuku / Termux",
                tone = SettingsTone.Warm,
                summary = { ctx ->
                    when {
                        !ctx.permissionsKnown -> "检测中"
                        ctx.pendingPermissions == 0 -> "全部就绪"
                        else -> "待授权 ${ctx.pendingPermissions} 项"
                    }
                },
            ),
        ),
    ),
    SettingsGroup(
        title = "外观",
        subtitle = "配色风格与运行时视觉效果",
        tone = SettingsTone.Brand,
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.THEME,
                icon = AppIcons.Palette,
                title = "主题",
                subtitle = "配色 / 框线 / 磨砂",
                tone = SettingsTone.Brand,
                summary = { ctx ->
                    "${ThemePalette.of(ctx.st.palette).label} · ${if (ctx.st.borderless) "无框线" else "标准"}"
                },
            ),
            SettingsEntry(
                page = SettingsPage.VISUAL,
                icon = AppIcons.Star,
                title = "界面效果",
                subtitle = "点击光标 / 边缘光效 / 悬浮窗跑马灯",
                tone = SettingsTone.Brand,
                summary = { ctx -> if (ctx.st.edgeLightingEnabled) "光效开" else "光效关" },
            ),
            SettingsEntry(
                page = SettingsPage.TABS,
                icon = AppIcons.Reorder,
                title = "底部选项卡",
                subtitle = "可见性 / 排序",
                tone = SettingsTone.Brand,
                summary = { ctx ->
                    "${com.phoneagent.ui.MainTabs.visibleTabs(ctx.st.tabOrder, ctx.st.tabVisible).size} 个可见"
                },
            ),
        ),
    ),
    SettingsGroup(
        title = "系统",
        subtitle = "本机数据与帮助信息",
        tone = SettingsTone.Neutral,
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.DATA,
                icon = AppIcons.Folder,
                title = "数据与存储",
                subtitle = "导出 / 清空记忆 / 恢复默认",
                tone = SettingsTone.Neutral,
                summary = { "本机数据" },
            ),
            SettingsEntry(
                page = SettingsPage.UPDATE,
                icon = AppIcons.Refresh,
                title = "软件更新",
                subtitle = "检查新版本 / 接收渠道 / 自动检查",
                tone = SettingsTone.Neutral,
                summary = { "v${com.phoneagent.BuildConfig.VERSION_NAME}" },
            ),
            SettingsEntry(
                page = SettingsPage.ABOUT,
                icon = AppIcons.Info,
                title = "关于与帮助",
                subtitle = "版本 / 开源仓库 / 使用说明 / 隐私",
                tone = SettingsTone.Neutral,
                summary = { "v${com.phoneagent.BuildConfig.VERSION_NAME}" },
            ),
        ),
    ),
)

/**
 * 聚合设置入口摘要要用的状态：权限列表 + 待授权项数。
 *
 * 主页、宽屏总览、左侧分类栏三处都要这份数据，各自 collect 一遍会各自触发刷新；
 * 统一在这里取一次，调用方只拿结果。
 */
@Composable
internal fun rememberSettingsOverviewCtx(st: SettingsState, vm: MainViewModel): SettingsOverviewCtx {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissions by vm.permissions.collectAsState()
    // 权限状态由 ViewModel 聚合，进设置页时刷一次，入口摘要才不会是空的
    LaunchedEffect(Unit) { vm.refreshPermissions(context) }
    val pending = permissions.count { !it.granted }
    val known = permissions.isNotEmpty()
    return SettingsOverviewCtx(
        st = st,
        pendingPermissions = pending,
        permissionsKnown = known,
        permissionsReady = known && pending == 0,
    )
}

/**
 * 设置主页（一级导航）。
 *
 * 视觉走 Material 3 Expressive：顶栏下方先给一条「就绪度」状态条（先去哪、还差什么一目了然），
 * 再是五块各自带色相的分区容器，条目用圆形图标底盘。
 *
 * 只有窄屏会走到这里：宽屏由 [SettingsScreen] 切换成「左分类 + 右详情」的双栏形态。
 */
@Composable
internal fun SettingsHome(st: SettingsState, vm: MainViewModel, onOpen: (SettingsPage) -> Unit) {
    val ctx = rememberSettingsOverviewCtx(st, vm)

    // 滚动状态：传给骨架做显式浮起判定（官网风格，比内部 NestedScrollConnection 更稳）
    val scrollState = rememberScrollState()
    val groups = settingsGroups()

    GlassHeaderScaffold(
        modifier = Modifier.fillMaxSize(),
        floating = { scrollState.value > 8 },
        header = {
            AppTopBar(
                title = "设置",
                subtitle = "按用途分层管理 AI 服务与运行行为",
            )
        },
    ) { contentPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = contentPad.calculateTopPadding() + AppSpacing.Sm)
                .padding(horizontal = SettingsScreenPad)
                .padding(LocalNavClearance.current),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
        ) {
            // 就绪度状态条：把"能不能开工"最关键的两件事提到最前，点一下直达对应设置
            ReadinessStrip(
                ctx = ctx,
                onModel = { onOpen(SettingsPage.AI_MODELS) },
                onPermission = { onOpen(SettingsPage.PERMISSIONS) },
            )

            groups.forEach { group -> SettingsGroupSection(group, ctx, onOpen) }

            Spacer(Modifier.height(AppSpacing.Lg))
        }
    }
}

/**
 * 宽屏双栏下的右侧「总览」：左侧分类栏已经列出全部入口，这里不再重复一遍，
 * 只留就绪度状态条与一句指引——重复列一遍等于把同一份导航摆两处。
 */
@Composable
internal fun SettingsOverviewPane(st: SettingsState, vm: MainViewModel, onOpen: (SettingsPage) -> Unit) {
    val ctx = rememberSettingsOverviewCtx(st, vm)
    val scrollState = rememberScrollState()
    GlassHeaderScaffold(
        modifier = Modifier.fillMaxSize(),
        floating = { scrollState.value > 8 },
        header = {
            AppTopBar(title = "设置", subtitle = "按用途分层管理 AI 服务与运行行为")
        },
    ) { contentPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = contentPad.calculateTopPadding() + AppSpacing.Sm)
                .padding(horizontal = SettingsScreenPad),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
        ) {
            ReadinessStrip(
                ctx = ctx,
                onModel = { onOpen(SettingsPage.AI_MODELS) },
                onPermission = { onOpen(SettingsPage.PERMISSIONS) },
            )
            Text(
                "从左侧选择一类设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Lg))
        }
    }
}

/**
 * 宽屏双栏下的左侧分类栏：分组标题 + 条目，样式与主页分区卡同源（同样的色相与圆形图标底盘），
 * 只是排成常驻的竖列。
 */
@Composable
internal fun SettingsCatalogRail(
    st: SettingsState,
    vm: MainViewModel,
    selected: SettingsPage,
    onSelect: (SettingsPage) -> Unit,
) {
    val ctx = rememberSettingsOverviewCtx(st, vm)
    val scrollState = rememberScrollState()
    val groups = settingsGroups()
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(scrollState)
            .padding(vertical = AppSpacing.Md),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = AppSpacing.Sm),
        )
        groups.forEach { group ->
            Text(
                group.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = AppSpacing.Md, bottom = AppSpacing.Xs),
            )
            group.entries.forEach { entry ->
                SettingsRailItem(
                    entry = entry,
                    summary = entry.summary(ctx),
                    selected = entry.page == selected,
                    onClick = { onSelect(entry.page) },
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.Md))
    }
}

/** 分类栏条目：选中态用主色淡底胶囊，不选中时只有文字与图标 */
@Composable
private fun SettingsRailItem(
    entry: SettingsEntry,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val buzz = rememberHapticClick()
    val accent = entry.tone.accent()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Sm, vertical = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(AppRadii.Tile))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable {
                buzz()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            entry.icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else accent,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 一块分区容器：标题眉条 + 同色相的一组入口 */
@Composable
internal fun SettingsGroupSection(
    group: SettingsGroup,
    ctx: SettingsOverviewCtx,
    onOpen: (SettingsPage) -> Unit,
) {
    SettingsSection(title = group.title, subtitle = group.subtitle, tone = group.tone) {
        group.entries.forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                icon = entry.icon,
                title = entry.title,
                subtitle = entry.subtitle,
                tone = entry.tone,
                summary = entry.summary(ctx),
                onClick = { onOpen(entry.page) },
            )
        }
    }
}

/**
 * 就绪度状态条：左右两格分别代表「模型」与「权限」，一格一个圆点表示是否就绪。
 *
 * 不放进度条、不放百分比——设置页要回答的是"还差哪一步"，不是"完成了多少"。
 * 两格都可点，直接落到对应设置页，省去在五组里找入口。
 */
@Composable
private fun ReadinessStrip(
    ctx: SettingsOverviewCtx,
    onModel: () -> Unit,
    onPermission: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadii.Card),
        color = AppCardContainer,
        border = appBorderStroke(1.dp, AppCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            ReadinessCell(
                title = "AI 模型",
                value = ctx.st.model.takeIf { it.isNotBlank() } ?: "未配置",
                ready = ctx.st.model.isNotBlank(),
                onClick = onModel,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .width(0.5.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
            ReadinessCell(
                title = "运行权限",
                value = when {
                    ctx.permissionsReady -> "已就绪"
                    ctx.pendingPermissions > 0 -> "待授权 ${ctx.pendingPermissions} 项"
                    else -> "检测中"
                },
                ready = ctx.permissionsReady,
                onClick = onPermission,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReadinessCell(
    title: String,
    value: String,
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buzz = rememberHapticClick()
    val dot = if (ready) AppTheme.colors.success else MaterialTheme.colorScheme.error
    Column(
        modifier = modifier
            .clickable {
                buzz()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
