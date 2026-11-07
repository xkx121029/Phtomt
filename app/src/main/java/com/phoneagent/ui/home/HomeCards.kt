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
import com.phoneagent.ui.theme.AppRadii
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
 * 首屏主入口卡：整页只有一个"开始做事"的落点，点它直达 Agent 页。
 *
 * 原来首屏是「Agent / 设置 / 技能 / 浏览器」四张等权卡，新手要先读四个标题才知道从哪开始；
 * 主路径（下达任务）提成一张带主色底的大卡后，其余入口降为次要。
 */
@Composable
internal fun PrimaryActionCard(
    modelReady: Boolean,
    model: String,
    onClick: () -> Unit,
) {
    val buzz = com.phoneagent.ui.components.rememberHapticClick()
    PressableScale(onClick = { buzz(); onClick() }) {
        Surface(
            shape = RoundedCornerShape(AppRadii.Card),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            border = appBorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
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
                        "让 AI 替我做事",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (modelReady) "已接入 $model · 说一句话就开始" else "下一步：先配置模型，再回来下达任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
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
    modelReady: Boolean,
    model: String,
    a11yReady: Boolean,
    screenshotReady: Boolean,
    channel: String,
    channelReady: Boolean,
    onModel: () -> Unit,
    onA11y: () -> Unit,
    onScreenshot: () -> Unit,
    onChannel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = AppCardContainer,
        border = appBorderStroke(1.dp, AppCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            HomeStatusCell(
                label = "模型",
                value = if (modelReady) model else "未配置",
                ready = modelReady,
                onClick = onModel,
                modifier = Modifier.weight(1f),
            )
            HomeStatusDivider()
            HomeStatusCell(
                label = "无障碍",
                value = if (a11yReady) "已开启" else "未开启",
                ready = a11yReady,
                onClick = onA11y,
                modifier = Modifier.weight(1f),
            )
            HomeStatusDivider()
            HomeStatusCell(
                label = "截屏",
                value = if (screenshotReady) "已授权" else "待授权",
                ready = screenshotReady,
                onClick = onScreenshot,
                modifier = Modifier.weight(1f),
            )
            HomeStatusDivider()
            HomeStatusCell(
                label = "执行通道",
                value = channel,
                ready = channelReady,
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
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            .padding(horizontal = 12.dp, vertical = 12.dp),
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
                    .background(if (ready) com.phoneagent.ui.theme.AppTheme.colors.success else MaterialTheme.colorScheme.error),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
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

