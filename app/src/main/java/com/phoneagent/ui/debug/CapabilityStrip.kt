package com.phoneagent.ui.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success

/**
 * 能力缺失提示条。
 *
 * 重构前这里常驻一条「无障碍 / 悬浮窗 / 截屏 / 自启动 / Shizuku」胶囊条，五项都正常时
 * 也占着首屏一整行——那是设置页的信息，不是调试页当下要看的东西。
 * 现在：五项都正常时完全不渲染；有缺失才出现一条可展开的提示，写明缺哪项、为什么需要，
 * 并给出直达设置的入口。完整五项状态收进页头「更多」菜单里的 [CapabilityStatusDialog]。
 */
@Composable
internal fun CapabilityNotice(
    permissions: List<PermissionItem>,
    onFix: (PermissionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val missing = permissions.filter { !it.granted }
    if (missing.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val tone = MaterialTheme.colorScheme.error
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        color = tone.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.28f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Report,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    "缺少 ${missing.size} 项能力：${missing.joinToString(" · ") { it.title }}",
                    style = MaterialTheme.typography.labelLarge,
                    color = tone,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = if (expanded) "收起缺失能力" else "展开缺失能力",
                    tint = tone,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                    expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(DurationFast, easing = EaseOut)),
            ) {
                Column(
                    modifier = Modifier.padding(top = AppSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                ) {
                    missing.forEach { p -> CapabilityRow(p, onFix) }
                }
            }
        }
    }
}

/** 完整五项能力状态（页头「更多」→「查看能力状态」），内嵌弹窗而非系统弹窗 */
@Composable
internal fun CapabilityStatusDialog(
    permissions: List<PermissionItem>,
    onFix: (PermissionKind) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("能力状态", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg)) {
                permissions.forEach { p -> CapabilityRow(p, onFix) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/** 单项能力：状态点 + 名称 + 说明 + 缺失时的「去设置」 */
@Composable
private fun CapabilityRow(item: PermissionItem, onFix: (PermissionKind) -> Unit) {
    val tone = if (item.granted) Success else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.Top) {
        // 已授权=实心点，未授权=空心点，比整块底色更克制
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (item.granted) tone else Color.Transparent)
                .border(1.dp, tone, CircleShape),
        )
        Spacer(Modifier.width(AppSpacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.granted) {
            Spacer(Modifier.width(AppSpacing.Md))
            Surface(
                onClick = { onFix(item.kind) },
                shape = RoundedCornerShape(AppRadii.Chip),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Text(
                    "去设置",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}