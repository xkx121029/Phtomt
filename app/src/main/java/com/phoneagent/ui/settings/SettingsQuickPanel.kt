package com.phoneagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.phoneagent.overlay.OverlayDisplaySettings
import com.phoneagent.overlay.StepTextPlacement
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing
import kotlinx.coroutines.delay

/**
 * 设置首页的「常用」面板：最常改的三件事直接在第一屏可操作，不再往二级页里藏。
 *
 * 这一块的每一项都**只有这一个入口**：
 * - 执行方式（保守 / 均衡 / 自由）：原先在「任务行为」页，现移到这里，任务行为页只留步数与提示词；
 * - 主模型与线路：显示当前线路与模型，点一下直接进模型库挑选，省掉「云端模型 → 职责行 → 模型库」两跳；
 * - 浮层显示（桌面悬浮窗 / 顶部跑马灯 / 任务卡状态行 / 绿色边缘光效）：原先在「外观与导航 → 效果」里
 *   分三段摆放，现收在这一处；效果页只保留光标、标定与跑马灯样式这些低频项。
 *
 * 低频与危险项（清空数据、恢复默认、全自动审批、自由模式）仍留在各自页面并写明后果，
 * 首页只负责「今天就要用」的那几项。
 */
@Composable
internal fun SettingsQuickPanel(
    st: SettingsState,
    save: () -> Unit,
    onPickModel: () -> Unit,
) {
    val context = LocalContext.current
    // 浮层显示设置由浮层线的 OverlayDisplaySettings 统一读写（独立 prefs 文件）：
    // 这里只做写入口，正在运行的跑马灯/光效靠它自己的监听即时生效，无需重启。
    var stepPlacement by remember { mutableStateOf(OverlayDisplaySettings.stepTextPlacement(context)) }
    var edgeLightingOn by remember { mutableStateOf(OverlayDisplaySettings.edgeLightingEnabled(context)) }
    // 两个复选框是 stepPlacement 的展开形态：勾两处 = both，只勾顶部 = marquee，只勾卡片 = card
    var marqueeOn by remember { mutableStateOf(stepPlacement != StepTextPlacement.CARD) }
    var cardOn by remember { mutableStateOf(stepPlacement != StepTextPlacement.MARQUEE) }
    var stepTextHint by remember { mutableStateOf(false) }

    fun updateStepText(nextMarquee: Boolean, nextCard: Boolean) {
        if (!nextMarquee && !nextCard) {
            // 两处都取消会让描述无处可显：保持原勾选并就地提示，不静默变成空状态
            stepTextHint = true
            return
        }
        val next = when {
            nextMarquee && nextCard -> StepTextPlacement.BOTH
            nextMarquee -> StepTextPlacement.MARQUEE
            else -> StepTextPlacement.CARD
        }
        marqueeOn = nextMarquee
        cardOn = nextCard
        stepPlacement = next
        OverlayDisplaySettings.setStepTextPlacement(context, next)
    }
    LaunchedEffect(stepTextHint) {
        if (stepTextHint) {
            delay(2_500)
            stepTextHint = false
        }
    }

    SettingsSection(
        title = "常用设置",
        subtitle = "最常改的几项都在这里，改完立即生效",
        tone = SettingsTone.Brand,
    ) {
        // ---- 执行方式：给 AI 的授权范围 ----
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            Text(
                "任务执行方式",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(AppSpacing.Xxs))
            Text(
                "给 AI 的授权范围，与「执行通道」叠加生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Md))
            val mode = com.phoneagent.engine.execution.ActionMode.fromKey(st.actionMode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
            ) {
                com.phoneagent.engine.execution.ActionMode.entries.forEach { item ->
                    SettingsChoiceChip(
                        label = item.label,
                        selected = st.actionMode == item.key,
                        onClick = {
                            st.actionMode = item.key
                            save()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = mode.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (mode == com.phoneagent.engine.execution.ActionMode.FREE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // 自由模式是危险项：后果写在控件正下方，不靠用户去翻文档
            if (mode == com.phoneagent.engine.execution.ActionMode.FREE) {
                Spacer(Modifier.height(AppSpacing.Xs))
                Text(
                    "自由模式允许 AI 自写命令并直调无障碍端点，只在你信任当前任务时使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingsDivider(inset = false)

        // ---- 主模型与线路：一行看到当前用哪条线、哪个模型 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPickModel)
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            Icon(
                AppIcons.SmartToy,
                contentDescription = null,
                tint = SettingsTone.Brand.accent(),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "主模型",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(AppSpacing.Xxs))
                // 模型名与线路各占一行：模型名不再和右侧控件抢宽度，大字号下也不会被挤成省略号
                Text(
                    text = st.model.ifBlank { "未选模型" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (st.model.isBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 线路名与模型名分开写：只说「已配置」看不出走的是哪条线
                    text = "线路：" + st.baseUrl.let { endpointHost(it) }.ifBlank { "未配置" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // ---- 浮层显示：桌面悬浮窗 + 两处逐步文字 + 边缘光效 ----
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "浮层显示",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(AppSpacing.Xxs))
                    Text(
                        "任务进度浮在桌面；关闭时以下三项不生效",
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
            if (!st.floatingWindowEnabled) {
                Spacer(Modifier.height(AppSpacing.Xs))
                Text(
                    "悬浮窗已关闭：下面三项照常保存，重新打开悬浮窗后生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsDivider(inset = false)
        CheckRow(
            title = "顶部跑马灯",
            subtitle = "描述在顶部光带滚动；不勾就整条不显示",
            checked = marqueeOn,
        ) { next ->
            updateStepText(nextMarquee = next, nextCard = cardOn)
        }
        SettingsDivider(inset = false)
        CheckRow(
            title = "任务卡状态行",
            subtitle = "描述出现在任务卡里；不勾就只留「第 n/m 步」",
            checked = cardOn,
        ) { next ->
            updateStepText(nextMarquee = marqueeOn, nextCard = next)
        }
        if (stepTextHint) {
            // 只做一次性提示，不弹系统 Toast：提示就近放在控件下方，读到就知道怎么改
            Text(
                "至少保留一处显示",
                modifier = Modifier.padding(start = AppSpacing.Page, end = AppSpacing.Page, top = AppSpacing.Xxs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SettingsDivider(inset = false)
        CheckRow(
            title = "绿色边缘光效悬浮窗",
            subtitle = "任务执行时在屏幕边缘显示绿色光带；不勾就不建窗口、不占触摸，任务照常执行",
            checked = edgeLightingOn,
        ) { next ->
            edgeLightingOn = next
            OverlayDisplaySettings.setEdgeLightingEnabled(context, next)
        }
        Spacer(Modifier.height(AppSpacing.Sm))
    }
}

/**
 * 复选行：整行可点、可勾。
 *
 * 「逐步任务文字」和「绿色边缘光效」共用这一个：这组的语义是「要不要显示某一层」，
 * 不是单选互斥，也不是总开关，所以用 Checkbox 而不是 RadioButton / Switch。
 */
@Composable
private fun CheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(AppSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                ),
            )
            Spacer(Modifier.height(AppSpacing.Xxs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}