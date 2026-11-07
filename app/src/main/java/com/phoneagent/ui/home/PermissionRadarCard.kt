package com.phoneagent.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.components.rememberHapticPress
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success

/**
 * 权限待办入口：**一行**，不再平铺 6 张权限卡。
 *
 * 授权是一次性动作，做完就不需要再看；平铺的卡片会永久占掉首屏一块高度，
 * 与「设置 → 权限与执行通道」重复。这里只报还差几项，点进对应设置页去处理。
 * 全部就绪（N=0）时整行不渲染——没有待办就不要留一个"已完成"的空壳。
 */
@Composable
internal fun PermissionTodoLine(
    permissions: List<PermissionItem>,
    onOpen: () -> Unit,
) {
    val pending = permissions.count { !it.granted }
    if (pending <= 0) return
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                buzz()
                onOpen()
            }
            .padding(horizontal = AppSpacing.Xs, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xxs),
    ) {
        Text(
            text = "还缺 $pending 项授权",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 权限清单：设置页「授权」分段里用，每一项点未授权项直达对应系统页。
 *
 * 首页已不再平铺这组卡片（只留上面一行待办入口），这里是唯一的完整清单。
 */
@Composable
internal fun PermissionRadar(
    permissions: List<PermissionItem>,
    context: android.content.Context,
    vm: MainViewModel,
) {
    val pressHaptic = rememberHapticPress()

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm), modifier = Modifier.fillMaxWidth()) {
        permissions.forEachIndexed { i, p ->
            val raw = rawPermissionIcon(p.kind)
            val itemColor = if (p.granted) Success else MaterialTheme.colorScheme.primary
            PressableScale(
                modifier = Modifier.fillMaxWidth().animateListItem(i),
                onPress = { if (!p.granted) pressHaptic() },
                onClick = {
                    if (!p.granted) {
                        vm.openPermissionSettings(context, p.kind)
                    }
                },
            ) {
                AppItemCard(
                    containerColor = if (p.granted) {
                        Success.copy(alpha = 0.08f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    },
                ) {
                    AppIconTile(
                        icon = raw,
                        tint = itemColor,
                        background = itemColor.copy(alpha = 0.16f),
                        tileSize = 44.dp,
                        iconSize = 22.dp,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            p.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        text = if (p.granted) "完成" else "去授权",
                        color = itemColor,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                }
            }
        }
    }
}

private fun rawPermissionIcon(kind: PermissionKind): ImageVector = when (kind) {
    PermissionKind.ACCESSIBILITY -> AppIcons.TouchApp
    PermissionKind.OVERLAY -> AppIcons.Camera
    PermissionKind.WRITE_SETTINGS -> AppIcons.Tune
    PermissionKind.AUTOSTART -> AppIcons.Bolt
    PermissionKind.QUERY_ALL_PACKAGES -> AppIcons.Memory
    PermissionKind.NOTIFICATIONS -> AppIcons.Notifications
}
