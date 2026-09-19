package com.phoneagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/** 分类中文化（供卡片展示） */
internal fun memoryCategoryLabel(category: String): String = when (category) {
    "preference" -> "偏好"
    "fact" -> "事实"
    "habit" -> "习惯"
    "tip" -> "技巧"
    else -> "记忆"
}

/**
 * AI 写入记忆的卡片：一条刚发生的记忆写入，紧跟其所在步骤。
 * 展示写出内容 + 「已记住 / 已更新」徽标 + 撤销按钮（撤销只对这条生效）。
 */
@Composable
internal fun MemoryCardItem(item: AgentTimelineItem.MemoryAdded, onUndo: (Long) -> Unit) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.Lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadii.Item))
                .background(colors.surfaceRaised)
                .border(1.dp, colors.outlineSoft.copy(alpha = 0.6f), RoundedCornerShape(AppRadii.Item))
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Memory,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.updated) "记忆已更新" else "记住了新信息",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.brand,
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Text(
                        "· ${memoryCategoryLabel(item.category)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceRaised,
                )
            }
            Text(
                "撤销",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadii.Inline))
                    .clickable {
                        buzz()
                        onUndo(item.id)
                    }
                    .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
            )
        }
    }
}