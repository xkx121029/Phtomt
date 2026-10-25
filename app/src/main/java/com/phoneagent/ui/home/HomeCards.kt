package com.phoneagent.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.PressableScale

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

@Composable
internal fun StatusCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color,
    iconBackground: Color,
) {
    AppItemCard {
        AppIconTile(
            icon = icon,
            tint = iconColor,
            background = iconBackground,
            tileSize = 44.dp,
            iconSize = 22.dp,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * ACA（编码 agent）技能卡：主页「编码技能」区块用。
 *
 * 与 StatusCard 的差异只有两处：描述允许两行（skill 包的 description 常是
 * 一句话以上的英文），以及描述非中文时走 [translate] 自动翻成中文（失败
 * 回退原文，沿用主引擎的翻译缓存）。纯展示卡，不接点击。
 */
@Composable
internal fun AcaSkillCard(
    icon: ImageVector,
    name: String,
    description: String,
    meta: String,
    iconColor: Color,
    iconBackground: Color,
    translate: suspend (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    // 描述翻译：内容变化才重翻；空描述直接跳过
    var shown by androidx.compose.runtime.remember(description) {
        androidx.compose.runtime.mutableStateOf(description)
    }
    androidx.compose.runtime.LaunchedEffect(description) {
        if (description.isBlank()) return@LaunchedEffect
        shown = runCatching { translate(description) }.getOrNull() ?: description
    }
    androidx.compose.foundation.layout.Box(modifier) {
        AppItemCard {
            AppIconTile(
                icon = icon,
                tint = iconColor,
                background = iconBackground,
                tileSize = 44.dp,
                iconSize = 22.dp,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (shown.isNotBlank()) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }
    }
}

