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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.TouchApp
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

/** 品牌 Hero：渐变品牌瓦片 + 主标题/副标题 */
@Composable
internal fun BrandHero(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier.fillMaxWidth()) {
        // 品牌瓦片：深海军蓝 → 电光蓝紫 渐变，呼应应用图标主色调
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(AppRadii.Item))
                .background(Brush.linearGradient(listOf(BrandNavy, Accent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Column {
            Text("手机智能体", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "AI 接管手机，替你把任务做完",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 运行状态横幅：呼吸指示灯 + 当前消息 */
@Composable
internal fun RunningBanner(message: String) {
    val transition = rememberInfiniteTransition(label = "running-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "running-pulse-alpha",
    )
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 呼吸灯
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column {
                Text("智能体正在运行", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}