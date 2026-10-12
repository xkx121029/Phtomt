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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.ThemePalette
import com.phoneagent.ui.theme.appBorderStroke

/**
 * 设置主页（一级导航）。
 *
 * 信息架构按「AI 接入 / 运行行为 / 设备接入 / 外观 / 系统」五组归位——分组依据是
 * **用户要去解决什么**（配模型、调行为、给权限、改外观、管数据），而不是按技术模块平铺。
 * 执行通道原先在「Agent 运行」里还有个优先级开关，与「权限与执行通道」页里的配对界面
 * 说的是同一件事，这次合并到一处，主页只留一个入口。
 *
 * 视觉走 Material 3 Expressive：顶栏下方先给一条「就绪度」状态条（先去哪、还差什么一目了然），
 * 再是五块各自带色相的分区容器，条目用圆形图标底盘。
 */
@Composable
internal fun SettingsHome(st: SettingsState, vm: MainViewModel, onOpen: (SettingsPage) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissions by vm.permissions.collectAsState()
    // 权限状态由 ViewModel 聚合，进设置页时刷一次，入口摘要才不会是空的
    LaunchedEffect(Unit) { vm.refreshPermissions(context) }
    val pendingPermissions = permissions.count { !it.granted }
    val modelReady = st.model.isNotBlank()

    // 滚动状态：传给骨架做显式浮起判定（官网风格，比内部 NestedScrollConnection 更稳）
    val scrollState = rememberScrollState()

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
                modelLabel = if (modelReady) st.model else "未配置",
                modelReady = modelReady,
                pendingPermissions = pendingPermissions,
                permissionReady = permissions.isNotEmpty() && pendingPermissions == 0,
                onModel = { onOpen(SettingsPage.AI_MODELS) },
                onPermission = { onOpen(SettingsPage.PERMISSIONS) },
            )

            // ---- AI 接入：接入哪家模型、端侧还是云端 ----
            SettingsSection(title = "AI 接入", subtitle = "云端模型与端侧视觉", tone = SettingsTone.Brand) {
                SettingsRow(
                    icon = AppIcons.SmartToy,
                    title = "云端模型",
                    subtitle = "端点 / 模型库 / 职责分配",
                    tone = SettingsTone.Brand,
                    summary = if (st.model.isNotBlank()) st.model else "未配置",
                    onClick = { onOpen(SettingsPage.AI_MODELS) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = AppIcons.Preview,
                    title = "端侧视觉",
                    subtitle = "本地 3B 模型下载与启用",
                    tone = SettingsTone.Brand,
                    summary = if (st.enableExternalVision) "已启用" else "已关闭",
                    onClick = { onOpen(SettingsPage.ONDEVICE) },
                )
            }

            // ---- 运行行为：AI 怎么干活 ----
            SettingsSection(title = "运行行为", subtitle = "执行参数、提示词与长线任务", tone = SettingsTone.Cool) {
                SettingsRow(
                    icon = AppIcons.Tune,
                    title = "运行与提示词",
                    subtitle = "步数上限 / 悬浮窗 / 页面记忆 / 提示词",
                    tone = SettingsTone.Cool,
                    summary = if (st.maxSteps > 0) "最多 ${st.maxSteps} 步" else "不设限",
                    onClick = { onOpen(SettingsPage.AGENT) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = AppIcons.ListTodo,
                    title = "长线任务",
                    subtitle = "执行策略 / 断点续传 / 任务模板",
                    tone = SettingsTone.Cool,
                    summary = "任务管理",
                    onClick = { onOpen(SettingsPage.LONG_RUN) },
                )
            }

            // ---- 设备接入：权限与 shell 通道 ----
            SettingsSection(title = "设备接入", subtitle = "系统权限与 shell 执行通道", tone = SettingsTone.Warm) {
                SettingsRow(
                    icon = AppIcons.Guard,
                    title = "权限与执行通道",
                    subtitle = "无障碍 / 悬浮窗 / Shizuku / Termux",
                    tone = SettingsTone.Warm,
                    summary = when {
                        permissions.isEmpty() -> "检测中"
                        pendingPermissions == 0 -> "全部就绪"
                        else -> "待授权 $pendingPermissions 项"
                    },
                    onClick = { onOpen(SettingsPage.PERMISSIONS) },
                )
            }

            // ---- 外观：主题与界面效果 ----
            SettingsSection(title = "外观", subtitle = "配色风格与运行时视觉效果", tone = SettingsTone.Brand) {
                SettingsRow(
                    icon = AppIcons.Palette,
                    title = "主题",
                    subtitle = "配色 / 框线 / 磨砂",
                    tone = SettingsTone.Brand,
                    summary = "${ThemePalette.of(st.palette).label} · ${if (st.borderless) "无框线" else "标准"}",
                    onClick = { onOpen(SettingsPage.THEME) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = AppIcons.Star,
                    title = "界面效果",
                    subtitle = "点击光标 / 边缘光效 / 悬浮窗跑马灯",
                    tone = SettingsTone.Brand,
                    summary = if (st.edgeLightingEnabled) "光效开" else "光效关",
                    onClick = { onOpen(SettingsPage.VISUAL) },
                )
            }

            // ---- 系统：本机数据与关于 ----
            SettingsSection(title = "系统", subtitle = "本机数据与帮助信息", tone = SettingsTone.Neutral) {
                SettingsRow(
                    icon = AppIcons.Folder,
                    title = "数据与存储",
                    subtitle = "导出 / 清空记忆 / 恢复默认",
                    tone = SettingsTone.Neutral,
                    summary = "本机数据",
                    onClick = { onOpen(SettingsPage.DATA) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = AppIcons.Refresh,
                    title = "软件更新",
                    subtitle = "检查新版本 / 接收渠道 / 自动检查",
                    tone = SettingsTone.Neutral,
                    summary = "v${com.phoneagent.BuildConfig.VERSION_NAME}",
                    onClick = { onOpen(SettingsPage.UPDATE) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = AppIcons.Info,
                    title = "关于与帮助",
                    subtitle = "版本 / 开源仓库 / 使用说明 / 隐私",
                    tone = SettingsTone.Neutral,
                    summary = "v${com.phoneagent.BuildConfig.VERSION_NAME}",
                    onClick = { onOpen(SettingsPage.ABOUT) },
                )
            }

            Spacer(Modifier.height(AppSpacing.Lg))
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
    modelLabel: String,
    modelReady: Boolean,
    pendingPermissions: Int,
    permissionReady: Boolean,
    onModel: () -> Unit,
    onPermission: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = AppCardContainer,
        border = appBorderStroke(1.dp, AppCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            ReadinessCell(
                title = "AI 模型",
                value = modelLabel,
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
                    pendingPermissions == 0 && permissionReady -> "已就绪"
                    pendingPermissions > 0 -> "待授权 $pendingPermissions 项"
                    else -> "检测中"
                },
                ready = permissionReady,
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
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}