package com.phoneagent.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.ExtrasPage
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionHeader
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.theme.Accent
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.BrandNavy
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

@Composable
internal fun PermissionRadar(
    permissions: List<PermissionItem>,
    context: android.content.Context,
    vm: MainViewModel,
) {
    val pending = permissions.filter { !it.granted }
    val pressHaptic = com.phoneagent.ui.components.rememberHapticPress()

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (pending.isEmpty()) "全部已就绪" else "尚需授权 ${pending.size} 项",
            style = MaterialTheme.typography.bodyMedium,
            color = if (pending.isEmpty()) Success else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        permissions.forEachIndexed { i, p ->
            val raw = rawPermissionIcon(p.kind)
            val itemColor = if (p.granted) Success else MaterialTheme.colorScheme.primary
            PressableScale(
                modifier = Modifier.fillMaxWidth().animateListItem(8 + i),
                onPress = { if (!p.granted) pressHaptic() },
                onClick = {
                    if (!p.granted) {
                        vm.openPermissionSettings(context, p.kind)
                    }
                },
            ) {
                AppItemCard(
                    containerColor = if (p.granted) Success.copy(alpha = 0.08f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
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
                    if (p.granted) {
                        StatusPill(
                            text = "完成",
                            color = Success,
                            modifier = Modifier.padding(end = 14.dp),
                        )
                    } else {
                        StatusPill(
                            text = "去授权",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun rawPermissionIcon(kind: PermissionKind): ImageVector = when (kind) {
    PermissionKind.ACCESSIBILITY -> AppIcons.TouchApp
    PermissionKind.OVERLAY -> AppIcons.Camera
    PermissionKind.AUTOSTART -> AppIcons.Bolt
    PermissionKind.QUERY_ALL_PACKAGES -> AppIcons.Memory
    PermissionKind.SHIZUKU -> AppIcons.Terminal
}