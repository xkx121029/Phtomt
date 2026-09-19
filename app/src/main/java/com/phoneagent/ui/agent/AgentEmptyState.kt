package com.phoneagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/**
 * 起步建议：软件预置的固定示例，不是 AI 现编内容。
 * 只作为「可以这么说」的示范，点击即填入输入框，不自动执行。
 */
private val StartSuggestions = listOf(
    "打开设置，把字体调大",
    "打开时钟，设一个 10 分钟后的闹钟",
    "把屏幕亮度调到最高",
    "打开相册，看看最近拍的照片",
)

/**
 * 空态：没有任何任务痕迹时展示。
 * 唯一硬前置是无障碍服务（没它 AI 读不到控件）；悬浮窗是可选能力，这里不再提示。
 * 提示内嵌在空态里（不使用系统弹窗），点建议句直接带入输入区。
 */
@Composable
internal fun AgentEmptyState(
    a11yEnabled: Boolean,
    onPickSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = "描述你想让 AI 替你做的事，它会一步步操作手机，并把每一步的依据留在这里",
            style = MaterialTheme.typography.bodySmall,
            color = colors.emptyStateText,
        )

        if (!a11yEnabled) {
            Spacer(Modifier.height(AppSpacing.Lg))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadii.Item))
                    .background(colors.warningContainer)
                    .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
                    .padding(AppSpacing.Lg),
            ) {
                Text(
                    text = "无障碍服务未开启，AI 读不到页面控件，需要在系统设置里授权",
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
                SuggestionRow(text = suggestion, onClick = { onPickSuggestion(suggestion) })
            }
        }
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
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Tile))
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