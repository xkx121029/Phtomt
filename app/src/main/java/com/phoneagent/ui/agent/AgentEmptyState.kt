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
    /** 用户本轮已经明确选过「仍然尝试（截图模式）」 */
    screenshotModeChosen: Boolean = false,
    /** 去系统无障碍设置 */
    onOpenPermissions: () -> Unit = {},
    /** 去应用内的「权限与执行通道」（服务组件没装时，系统无障碍列表里没有可开的条目） */
    onOpenSettings: () -> Unit = {},
    /** 明确选择用截图模式继续 */
    onChooseScreenshotMode: () -> Unit = {},
) {
    if (grid) {
        GridEmptyState(
            a11yEnabled = a11yEnabled,
            onPickSuggestion = onPickSuggestion,
            modifier = modifier,
            screenshotModeChosen = screenshotModeChosen,
            onOpenPermissions = onOpenPermissions,
            onOpenSettings = onOpenSettings,
            onChooseScreenshotMode = onChooseScreenshotMode,
        )
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
            cornerRadius = AppTheme.metrics.cardRadius,
        )
        Spacer(Modifier.height(AppSpacing.Md))
        Text(
            text = "还没有任务",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (!a11yEnabled) {
            Spacer(Modifier.height(AppSpacing.Lg))
            A11yCapabilityCard(
                screenshotModeChosen = screenshotModeChosen,
                onOpenPermissions = onOpenPermissions,
                onOpenSettings = onOpenSettings,
                onChooseScreenshotMode = onChooseScreenshotMode,
            )
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
 * 无障碍缺失时的能力卡：既说清缺什么，也把两条路都摆在卡上。
 *
 * 原来这里是一段纯文本提示——文字没说错，但用户读完只能自己去找系统设置，
 * 「我就想先试试」也没有出口，结果往往是发一条注定失败的请求，等 40–60 秒才收到失败。
 * 现在两个出口都在卡里：主按钮去开授权（服务组件没装时改为去装组件），
 * 次按钮明确改用截图模式，并当场说清这一模式的能力边界。
 */
@Composable
private fun A11yCapabilityCard(
    screenshotModeChosen: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseScreenshotMode: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val providerReady = remember {
        runCatching { com.phoneagent.device.provider.ProviderManager.isInstalled(context) }.getOrDefault(false)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.metrics.itemRadius))
            .background(colors.warningContainer)
            .appBorder(1.dp, colors.outlineSoft, RoundedCornerShape(AppTheme.metrics.itemRadius))
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.TouchApp,
                contentDescription = null,
                tint = colors.onWarningContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Text(
                text = if (screenshotModeChosen) "已选用截图模式" else "无障碍没开，AI 看不见屏幕",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onWarningContainer,
            )
        }
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = a11yMissingHint(providerReady),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onWarningContainer,
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        Text(
            text = SCREEN_SHOT_MODE_BOUNDARY,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onWarningContainer.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(AppSpacing.Md))
        if (screenshotModeChosen) {
            PressableScale(onClick = onOpenPermissions) {
                Text(
                    text = "改为开启无障碍",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onWarningContainer,
                    modifier = Modifier.padding(vertical = AppSpacing.Xs),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AgentActionButton(
                    text = if (providerReady) "去开启无障碍" else "去装服务组件",
                    icon = AppIcons.TouchApp,
                    tone = AgentButtonTone.PRIMARY,
                    onClick = if (providerReady) onOpenPermissions else onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
                AgentActionButton(
                    text = "仍用截图模式",
                    tone = AgentButtonTone.NEUTRAL,
                    onClick = onChooseScreenshotMode,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 截图模式的能力边界。写在用户做选择之前，而不是失败之后：
 * 他们要的是一个有依据的选择，不是一句「你自己非要试的」。
 */
private const val SCREEN_SHOT_MODE_BOUNDARY =
    "截图模式：读不到控件树，只能按画面猜位置——步骤更慢、更容易失败；" +
        "支付、密码这类页面读不到内容，AI 会停下来等你。"

/**
 * 无障碍缺失时的提示文案。
 *
 * 无障碍能力由独立安装的「服务组件」提供：组件没装时，系统无障碍列表里没有可开启的条目，
 * 只提示「去系统设置授权」会把用户带到死路。这里按实际状态给出可执行的下一步。
 */
private fun a11yMissingHint(providerReady: Boolean): String = if (providerReady) {
    "无障碍服务未开启，AI 读不到页面控件，需要在系统设置里授权。"
} else {
    "无障碍依赖「服务组件」，它还没安装：先到 设置 → 权限与执行通道 安装服务组件，再开启无障碍。"
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
            .clip(RoundedCornerShape(AppTheme.metrics.tileRadius))
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, RoundedCornerShape(AppTheme.metrics.tileRadius))
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
    screenshotModeChosen: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseScreenshotMode: () -> Unit,
) {
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
            A11yCapabilityCard(
                screenshotModeChosen = screenshotModeChosen,
                onOpenPermissions = onOpenPermissions,
                onOpenSettings = onOpenSettings,
                onChooseScreenshotMode = onChooseScreenshotMode,
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
            .clip(RoundedCornerShape(AppTheme.metrics.tileRadius))
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, RoundedCornerShape(AppTheme.metrics.tileRadius))
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