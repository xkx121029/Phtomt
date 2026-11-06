package com.phoneagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.appBorder

/**
 * 起步建议：软件预置的固定示例，不是 AI 现编内容。
 * 只作为「可以这么说」的示范，点击即填入输入框，不自动执行。
 * 图标只用于网格形态（工作台）——一行一个的文字形态不需要图标来分区。
 */
private data class StartSuggestion(val text: String, val icon: ImageVector)

private val StartSuggestions = listOf(
    StartSuggestion("打开设置，把字体调大", AppIcons.Settings),
    StartSuggestion("打开时钟，设一个 10 分钟后的闹钟", AppIcons.ListTodo),
    StartSuggestion("把屏幕亮度调到最高", AppIcons.Tune),
    StartSuggestion("打开相册，看看最近拍的照片", AppIcons.Camera),
)

/**
 * 空态：没有任何任务痕迹时展示。
 * 唯一硬前置是无障碍服务（没它 AI 读不到控件）；悬浮窗是可选能力，这里不再提示。
 *
 * 刻意不写一段"描述你想让 AI 替你做的事…"的说明：输入框的占位文案与顶栏副标题
 * 已经把这句话说了两遍，空态只负责给出口——标题 + 可以照说的例子。
 */
@Composable
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod")
internal fun AgentEmptyState(
    a11yEnabled: Boolean,
    onPickSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 工作台壳层：改用 Eta 那样的「一句提问 + 2×2 建议卡网格」，不再竖排示例行 */
    grid: Boolean = false,
) {
    if (grid) {
        GridEmptyState(a11yEnabled = a11yEnabled, onPickSuggestion = onPickSuggestion, modifier = modifier)
        return
    }

    val colors = AppTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconTile(
            icon = AppIcons.SmartToy,
            tint = colors.emptyStateIcon,
            background = colors.surfaceSunken,
            tileSize = 64.dp,
            iconSize = 30.dp,
            cornerRadius = AppRadii.Card,
        )
        Spacer(Modifier.height(AppSpacing.Md))
        Text(
            text = "还没有任务",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (!a11yEnabled) {
            Spacer(Modifier.height(AppSpacing.Lg))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadii.Item))
                    .background(colors.warningContainer)
                    .appBorder(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
                    .padding(AppSpacing.Lg),
            ) {
                Text(
                    text = a11yMissingHint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onWarningContainer,
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))
        Text(
            text = "可以这么说",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurfaceRaised,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StartSuggestions.forEach { suggestion ->
                SuggestionRow(text = suggestion.text, onClick = { onPickSuggestion(suggestion.text) })
            }
        }
    }
}

/**
 * 无障碍缺失时的提示文案。
 *
 * 无障碍能力由独立安装的「服务组件」提供：组件没装时，系统无障碍列表里没有可开启的条目，
 * 只提示「去系统设置授权」会把用户带到死路。这里按实际状态给出可执行的下一步。
 */
@Composable
private fun a11yMissingHint(): String {
    val context = LocalContext.current
    val providerReady = remember {
        runCatching { com.phoneagent.device.provider.ProviderManager.isInstalled(context) }.getOrDefault(false)
    }
    return if (providerReady) {
        "无障碍服务未开启，AI 读不到页面控件，需要在系统设置里授权"
    } else {
        "无障碍依赖「服务组件」，它还没安装：先到 设置 → 权限与执行通道 安装服务组件，再开启无障碍"
    }
}

/** 一句起步示例：点一下带到输入区，样式贴齐输入框（不用全圆胶囊，避免标配观感） */
@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    PressableScale(
        onPress = buzz,
        onClick = { buzz(); onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Tile))
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = colors.onSurfaceRaised,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 工作台壳层的空态：一句提问居中 + 2×2 建议卡网格（结构对齐 Eta，配色与圆角走本项目令牌）。
 *
 * 与经典空态的区别只在组织方式：不给每句示例配箭头，而是四张等高的卡，
 * 图标在上、示例在下，一眼扫完「可以让我做哪几类事」。
 */
@Composable
private fun GridEmptyState(
    a11yEnabled: Boolean,
    onPickSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg)
            // 与 Eta 一样把提问摆在页面上部三分之一处：不贴顶，也不压到底部输入卡上
            .padding(top = AppSpacing.Lg * 4, bottom = AppSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "有什么可以帮你？",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(AppSpacing.Lg))

        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
            StartSuggestions.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                ) {
                    row.forEach { suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            onClick = { onPickSuggestion(suggestion.text) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 奇数条时补一个空位，末卡才不会被拉成整行宽
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (!a11yEnabled) {
            Spacer(Modifier.height(AppSpacing.Lg))
            Text(
                text = a11yMissingHint(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onWarningContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadii.Item))
                    .background(colors.warningContainer)
                    .appBorder(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
                    .padding(AppSpacing.Lg),
            )
        }
    }
}

/** 网格里的一张建议卡：图标在上、示例在下，两张同高，点击即把示例带进输入框 */
@Composable
private fun SuggestionCard(
    suggestion: StartSuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    PressableScale(
        onPress = buzz,
        onClick = { buzz(); onClick() },
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Tile))
            .padding(AppSpacing.Md),
    ) {
        Column {
            Icon(
                imageVector = suggestion.icon,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(AppSpacing.Md))
            Text(
                text = suggestion.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}