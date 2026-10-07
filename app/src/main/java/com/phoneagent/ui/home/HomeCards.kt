package com.phoneagent.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

