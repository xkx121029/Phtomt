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
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSize
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.ThemePalette

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
    val tone: SettingsTone,
    val entries: List<SettingsEntry>,
    /**
     * 标题右侧的待处理计数，null = 这一组没有待办。
     *
     * 用它替代标题下那句描述性副标题：分组叫什么标题已经说了，剩下值得占位的信息
     * 只有「还差几项」——描述性文案在每次进设置时都要重读一遍，计数只需扫一眼数字。
     */
    val status: ((SettingsOverviewCtx) -> String?)? = null,
)

/**
 * 六块分区的定义（设置入口的唯一清单）。
 *
 * 分组只按**用户要解决什么**排：能不能执行（通道与权限）→ AI 从哪来（模型与端侧视觉）
 * → 怎么干活（任务行为与模式）→ 长什么样（界面与主题）→ 记住什么（数据与记忆）
 * → 怎么更新/求助（关于与更新）。技术模块名（如「编码运行时」「执行文档」）不再单独成组，
 * 它们各自只是上面某一组里的一个二级页。
 *
 * 同一件事只留一个入口：悬浮窗从「Agent 运行」并到「界面效果」，页面记忆从「Agent 运行」
 * 并到「数据与记忆」，动作模式从 Agent 页的转盘提到「任务行为」里成为正式设置项。
 */
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod")
internal fun settingsGroups(): List<SettingsGroup> = listOf(
    SettingsGroup(
        title = "执行通道与权限",
        tone = SettingsTone.Warm,
        // 只有存在待授权项时才占位；全部就绪的组不需要多一行「已就绪」
        status = { ctx -> if (ctx.pendingPermissions > 0) "权限 ${ctx.pendingPermissions} 项" else null },
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.PERMISSIONS,
                icon = AppIcons.Guard,
                title = "权限与执行通道",
                subtitle = "无障碍 / 截图 / Shizuku / Termux / 外部控制",
                tone = SettingsTone.Warm,
                summary = { ctx ->
                    when {
                        !ctx.permissionsKnown -> "检测中"
                        ctx.pendingPermissions == 0 -> "全部就绪"
                        else -> "待授权 ${ctx.pendingPermissions} 项"
                    }
                },
            ),
            SettingsEntry(
                page = SettingsPage.CODING,
                icon = AppIcons.Code,
                title = "编码 Agent",
                subtitle = "运行时 / 审批档位 / 工作区",
                tone = SettingsTone.Warm,
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
        title = "模型与端侧视觉",
        tone = SettingsTone.Brand,
        status = { ctx -> if (ctx.st.apiKey.isBlank()) "模型未配置" else null },
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.AI_MODELS,
                icon = AppIcons.SmartToy,
                title = "云端模型",
                subtitle = "端点 / 模型库 / 职责分配",
                tone = SettingsTone.Brand,
                // 与顶部状态条同一判定：只有模型名没有 Key 时仍算未配置，
                // 否则列表写着 glm-4.7-flash、任务却发不出去，比空着更误导
                summary = { ctx ->
                    when {
                        ctx.st.apiKey.isBlank() -> "未配置"
                        ctx.st.model.isNotBlank() -> ctx.st.model
                        else -> "未选模型"
                    }
                },
            ),
            SettingsEntry(
                page = SettingsPage.ONDEVICE,
                icon = AppIcons.Preview,
                title = "端侧视觉",
                subtitle = "本地 3B 模型下载与启用",
                tone = SettingsTone.Brand,
                summary = { ctx -> if (ctx.st.enableExternalVision) "已启用" else "已关闭" },
            ),
        ),
    ),
    SettingsGroup(
        title = "任务行为与模式",
        tone = SettingsTone.Cool,
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.AGENT,
                icon = AppIcons.Tune,
                title = "任务行为",
                subtitle = "步数 / 提示词 / 执行文档 / 副屏",
                tone = SettingsTone.Cool,
                // 执行方式已提到首页「常用」，这里只报本页真正管的步数，避免摘要指向别处
                summary = { ctx -> if (ctx.st.maxSteps > 0) "最多 ${ctx.st.maxSteps} 步" else "不限步数" },
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
        title = "外观与导航",
        tone = SettingsTone.Brand,
        entries = listOf(
            // 主题 / 界面效果 / 选项卡原先三个同级入口，改的都是同一件事的两面，
            // 合并成一页三个分区（SettingsAppearance），这里只留一个入口
            SettingsEntry(
                page = SettingsPage.APPEARANCE,
                icon = AppIcons.Palette,
                title = "外观与导航",
                subtitle = "配色 / 框线 / 光标与光效标定 / 选项卡",
                tone = SettingsTone.Brand,
                summary = { ctx ->
                    val tabs = com.phoneagent.ui.MainTabs.visibleTabs(ctx.st.tabOrder, ctx.st.tabVisible).size
                    "${ThemePalette.of(ctx.st.palette).label} · ${tabs} 个选项卡"
                },
            ),
        ),
    ),
    SettingsGroup(
        title = "数据与记忆",
        tone = SettingsTone.Neutral,
        entries = listOf(
            SettingsEntry(
                page = SettingsPage.DATA,
                icon = AppIcons.Folder,
                title = "数据与记忆",
                subtitle = "页面记忆 / 导出 / 清空记忆 / 恢复默认",
                tone = SettingsTone.Neutral,
                summary = { ctx -> if (ctx.st.pageMemoryEnabled) "页面记忆开" else "页面记忆关" },
            ),
        ),
    ),
    SettingsGroup(
        title = "关于与更新",
        tone = SettingsTone.Neutral,
        entries = listOf(
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
 * 顶栏下方第一张卡是「常用」：上层是就绪待办（还差哪一步），下层是最常改的三项，
 * 再往下一张张六块带色相的分区卡。首屏必须看得到分组入口——设置页首先是索引。
 *
 * 只有窄屏会走到这里：宽屏由 [SettingsScreen] 切换成「左分类 + 右详情」的双栏形态。
 */
@Composable
internal fun SettingsHome(
    st: SettingsState,
    vm: MainViewModel,
    onOpen: (SettingsPage) -> Unit,
    save: () -> Unit,
    onPickModel: () -> Unit,
) {
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
            // 一张「常用」卡里两层：就绪待办 + 高频项。合并后六组入口回到折叠线以上
            SettingsQuickPanel(
                st = st,
                save = save,
                onPickModel = onPickModel,
                ctx = ctx,
                onOpen = onOpen,
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
internal fun SettingsOverviewPane(
    st: SettingsState,
    vm: MainViewModel,
    onOpen: (SettingsPage) -> Unit,
    save: () -> Unit,
    onPickModel: () -> Unit,
) {
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
            // 宽屏默认落在「常用」：高频项与手机上同一块面板，不必先去左侧找分类
            SettingsQuickPanel(
                st = st,
                save = save,
                onPickModel = onPickModel,
                ctx = ctx,
                onOpen = onOpen,
            )
            Text(
                "更多设置从左侧选择",
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
            modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
        )
        // 宽屏也保留「常用」这一站：高频项不因为屏幕变宽就退回二级页里
        SettingsRailItem(
            entry = SettingsEntry(
                page = SettingsPage.HOME,
                icon = AppIcons.Tune,
                title = "常用设置",
                subtitle = "",
                tone = SettingsTone.Brand,
                summary = { "执行方式 / 主模型 / 浮层" },
            ),
            summary = "执行方式 / 主模型 / 浮层",
            selected = selected == SettingsPage.HOME,
            onClick = { onSelect(SettingsPage.HOME) },
        )
        groups.forEach { group ->
            Text(
                group.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = AppSpacing.Lg, top = AppSpacing.Md, bottom = AppSpacing.Xs),
            )
            group.entries.forEach { entry ->
                SettingsRailItem(
                    entry = entry,
                    summary = entry.summary(ctx),
                    // 执行通道深链与普通权限页是同一个入口的两种落点，选中态要认成同一项
                    selected = entry.page == selected ||
                        (entry.page == SettingsPage.PERMISSIONS &&
                            selected == SettingsPage.PERMISSIONS_CHANNEL),
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
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(AppRadii.Tile))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable {
                buzz()
                onClick()
            }
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
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
    val badge = group.status?.invoke(ctx)
    SettingsSection(
        title = group.title,
        tone = group.tone,
        // 标题右侧只放待处理计数：能一眼扫到的只有数字，描述性副标题每次进设置都要重读
        trailing = badge?.let { text ->
            {
                StatusPill(
                    text = text,
                    color = group.tone.accent(),
                )
            }
        },
    ) {
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
 * 就绪待办：左右两格分别代表「模型」与「权限」，一格一个圆点表示是否就绪。
 *
 * 不放进度条、不放百分比——设置页要回答的是"还差哪一步"，不是"完成了多少"。
 * 两格都可点，直接落到对应设置页。它和下面「常用」同属一张卡的两层：
 * 先看还差什么，再改最常改的项，首屏不必被两块独立卡片占满。
 */
@Composable
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod", "MaxLineLength")
internal fun ReadinessRow(
    ctx: SettingsOverviewCtx,
    onModel: () -> Unit,
    onPermission: () -> Unit,
    onQuickStart: () -> Unit,
) {
    // 有模型名不等于能跑：没填 Key 时引擎发不出请求，这里必须把 Key 也算进"就绪"
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    val modelReady = ctx.st.model.isNotBlank() && ctx.st.apiKey.isNotBlank()
    Column {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            ReadinessCell(
                title = "AI 模型",
                value = when {
                    ctx.st.apiKey.isBlank() -> "未配置"
                    ctx.st.model.isNotBlank() -> ctx.st.model
                    else -> "未选模型"
                },
                // 线路名从「主模型」行并过来：格子里放不下的只有这个词，语义没变
                hint = if (ctx.st.apiKey.isNotBlank()) "线路：" + endpointHost(ctx.st.baseUrl).ifBlank { "未配置" } else null,
                ready = modelReady,
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
        // 未就绪时只给一行结论，不再铺两步教程：步骤在「快速开始」整页里，
        // 这一行只负责把人送过去（首屏高度要留给下面的分组入口）
        if (!modelReady || !ctx.permissionsReady) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(AppSize.Hairline)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.Lg, end = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "还没配好，先走一遍快速开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onQuickStart) {
                    Text("开始配置")
                }
            }
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
    /** 值下面的补一行信息（当前只有模型格用：线路名） */
    hint: String? = null,
) {
    val buzz = rememberHapticClick()
    val dot = if (ready) AppTheme.colors.success else MaterialTheme.colorScheme.error
    Column(
        modifier = modifier
            .clickable {
                buzz()
                onClick()
            }
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
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
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}