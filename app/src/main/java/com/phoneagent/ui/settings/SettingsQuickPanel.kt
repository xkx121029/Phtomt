package com.phoneagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing

/**
 * 设置首页的「常用」面板：一张卡里两层入口。
 *
 * - 第一层「就绪待办」：模型（含线路）与权限各一格，还差什么一眼看到，点格子直达；
 * - 第二层「常用」：执行方式（只读，去 Agent 页改）与浮层显示总开关。
 *
 * 两层合在一处、不再各占一张卡，是为了让下面六组设置入口回到折叠线以上：
 * 设置首页首先是一张索引，不是三块面板的堆叠。
 * 「主模型」行也已并进模型格——同一件事（现在用哪个模型、走哪条线、点进去换一个）
 * 不需要占两行。
 *
 * 执行方式只展示不修改：值只有 Agent 页的转盘一个写入口，两处都能改同一个值必然漂移。
 */
@Composable
internal fun SettingsQuickPanel(
    st: SettingsState,
    save: () -> Unit,
    onPickModel: () -> Unit,
    ctx: SettingsOverviewCtx,
    onOpen: (SettingsPage) -> Unit,
) {
    SettingsSection(title = "常用", tone = SettingsTone.Brand) {
        // ---- 就绪待办：还差哪一步 ----
        ReadinessRow(
            ctx = ctx,
            onModel = {
                // 点模型格 = 挑主模型；还没配好时先去快速开始（填 Key + 测连接一步到位）
                if (ctx.st.apiKey.isNotBlank()) onPickModel()
                else onOpen(SettingsPage.QUICK_START)
            },
            onPermission = { onOpen(SettingsPage.PERMISSIONS) },
            onQuickStart = { onOpen(SettingsPage.QUICK_START) },
        )

        SettingsDivider(inset = false)

        // ---- 执行方式：只读，改值去 Agent 页转盘 ----
        val mode = com.phoneagent.engine.execution.ActionMode.fromKey(st.actionMode)
        val freeMode = mode == com.phoneagent.engine.execution.ActionMode.FREE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(SettingsPage.AGENT) }
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            Icon(
                AppIcons.Tune,
                contentDescription = null,
                tint = SettingsTone.Brand.accent(),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "任务执行方式",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(AppSpacing.Xxs))
                Text(
                    // 只读值写在副行：这里只回答「现在是哪一档」，改动在 Agent 页做
                    if (freeMode) "${mode.label} · 可自写命令，慎用" else "${mode.label} · 与执行通道叠加生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (freeMode) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }

        SettingsDivider(inset = false)

        // ---- 浮层显示：只留总开关 ----
        // 顶部跑马灯 / 任务卡状态行 / 边缘光效三个子项已移进「外观与导航 → 界面效果」，
        // 与它们各自控制的光带、光效放在同一分区：总开关在这里，样式在那边，一处只做一件事。
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "浮层显示",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(AppSpacing.Xxs))
                    Text(
                        "任务进度浮在桌面显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = st.floatingWindowEnabled,
                    onCheckedChange = {
                        st.floatingWindowEnabled = it
                        save()
                    },
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.Sm))
    }
}
