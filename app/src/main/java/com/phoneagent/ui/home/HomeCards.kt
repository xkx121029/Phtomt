package com.phoneagent.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppCardBorder
import com.phoneagent.ui.components.AppCardContainer
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.appBorderStroke

@Composable
internal fun QuickEntry(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onPress: () -> Unit = {},
) {
    PressableScale(modifier = modifier, onClick = onClick, onPress = onPress) {
        AppItemCard {
            AppIconTile(
                icon = icon,
                tint = tint,
                background = tint.copy(alpha = 0.14f),
                tileSize = 46.dp,
                iconSize = 24.dp,
                modifier = Modifier.padding(start = 14.dp, top = 13.dp, bottom = 13.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 13.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 状态格的三档（对应圆点颜色与文案）：可用 / 降级 / 不可用。
 *
 * 圆点和文案必须同义：红点只给「这一格现在确实不能用」，否则用户看到「无障碍」配红点
 * 就无法判断到底是通道名还是状态（UX-06 的原样）。[IDLE] 用于「按需授权 / 还没轮到」
 * 这类既不是好也不是坏的状态，避免把「任务时才要授权」画成永久红灯（UX-07）。
 */
internal enum class StatusTone { OK, DEGRADED, DOWN, IDLE }

@Composable
private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.OK -> AppTheme.colors.success
    StatusTone.DEGRADED -> AppTheme.colors.warning
    StatusTone.DOWN -> MaterialTheme.colorScheme.error
    StatusTone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
}

/**
 * 首屏主入口卡：整页只有一个"开始做事"的落点，点它直达 Agent 页。
 *
 * 原来首屏是「Agent / 设置 / 技能 / 浏览器」四张等权卡，新手要先读四个标题才知道从哪开始；
 * 主路径（下达任务）提成一张带主色底的大卡后，其余入口降为次要。
 *
 * 副标题跟模型的可信度四态走，不再由「Key 非空」决定：没验证过的配置不许写「已接入」，
 * 验证失败的写清失败原因并指向修复，否则用户第一次任务失败后无法把原因和 Key 联系起来。
 */
@Composable
internal fun PrimaryActionCard(
    trust: com.phoneagent.core.ai.ModelTrust.Snapshot,
    model: String,
    onClick: () -> Unit,
) {
    val buzz = com.phoneagent.ui.components.rememberHapticClick()
    val failed = trust.state == com.phoneagent.core.ai.ModelTrust.State.FAILED
    // 失败态用错误色，其余用主色：颜色也在说「这条路现在是通的 / 断了」
    val accent = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    PressableScale(onClick = { buzz(); onClick() }) {
        Surface(
            shape = RoundedCornerShape(AppTheme.metrics.cardRadius),
            color = accent.copy(alpha = 0.10f),
            border = appBorderStroke(1.dp, accent.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = AppTheme.metrics.cardPad,
                    vertical = AppTheme.metrics.cardPad,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (failed) "模型验证失败，先修复" else "让 AI 替我做事",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when (trust.state) {
                            com.phoneagent.core.ai.ModelTrust.State.VERIFIED ->
                                "已接入 $model · 说一句话就开始"
                            com.phoneagent.core.ai.ModelTrust.State.UNVERIFIED ->
                                "模型已保存，尚未验证 · 建议先测一次连接（状态条·模型）"
                            com.phoneagent.core.ai.ModelTrust.State.FAILED ->
                                "「${trust.error}」· 点这里去修复"
                            com.phoneagent.core.ai.ModelTrust.State.UNCONFIGURED ->
                                "下一步：先配置模型，再回来下达任务"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * 首屏运行状态条：一条卡里四格，分别回答模型 / 无障碍 / 截屏 / 执行通道能不能用。
 *
 * 三张独立状态卡太占首屏高度，也把"待办"与"常用入口"挤到第二屏；压成一条后
 * 一眼扫完，点哪格去哪格。第四格「执行通道」回答的是"AI 靠什么动手机"——
 * 无障碍、内置 Shizuku、Termux 三选一，这是失败排查时最先要看的一项。
 */
@Composable
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
internal fun HomeStatusStrip(
    modelState: String,
    modelTone: StatusTone,
    a11yReady: Boolean,
    screenshotReady: Boolean,
    screenshotNeeded: Boolean,
    channelName: String,
    channelValue: String,
    channelTone: StatusTone,
    onModel: () -> Unit,
    onA11y: () -> Unit,
    onScreenshot: () -> Unit,
    onChannel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppTheme.metrics.cardRadius),
        color = AppCardContainer,
        border = appBorderStroke(1.dp, AppCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            HomeStatusCell(
                label = "模型",
                // 四格并排时模型名常被挤成省略号，这格只回答「验过没有」；具体模型在「常用」卡里看
                value = modelState,
                tone = modelTone,
                onClick = onModel,
                modifier = Modifier.weight(1f),
            )
            HomeStatusDivider()
            HomeStatusCell(
                label = "无障碍",
                value = if (a11yReady) "已开启" else "未开启",
                tone = if (a11yReady) StatusTone.OK else StatusTone.DOWN,
                onClick = onA11y,
                modifier = Modifier.weight(1f),
            )
            HomeStatusDivider()
            HomeStatusCell(
                label = "截屏",
                // MediaProjection 是每次任务现授的临时授权，不是设置页里的常驻开关：
                // 写成「待授权」加红点，用户会去设置里找一个永远不存在的开关（UX-07）
                value = when {
                    !screenshotNeeded -> "不需要"
                    screenshotReady -> "已授权·本次"
                    else -> "任务时授权"
                },
                tone = when {
                    !screenshotNeeded -> StatusTone.IDLE
                    screenshotReady -> StatusTone.OK
                    else -> StatusTone.IDLE
                },
                onClick = onScreenshot,
                modifier = Modifier.weight(1f),
            )
            HomeStatusDivider()
            HomeStatusCell(
                label = "执行通道",
                // 值只报可用性，通道类型放副行：红点与文案同义，不再出现「无障碍」配红点
                value = channelValue,
                hint = channelName,
                tone = channelTone,
                onClick = onChannel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
// 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
@Suppress("MaxLineLength")
private fun HomeStatusCell(
    label: String,
    value: String,
    tone: StatusTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 值下面的补一行信息：执行通道格用它放通道类型（Shizuku / Termux / 无障碍） */
    hint: String? = null,
) {
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    val buzz = com.phoneagent.ui.components.rememberHapticClick()
    Column(
        modifier = modifier
            .clickable {
                buzz()
                onClick()
            }
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(toneColor(tone)),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        hint?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeStatusDivider() {
    Box(
        Modifier
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

