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
import androidx.compose.ui.unit.Dp
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
import com.phoneagent.ui.theme.DurationPulse
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.motionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

/** 品牌瓦片：深海军蓝 → 电光蓝紫 渐变，呼应应用图标主色调。放在页眉最左侧 */
@Composable
internal fun BrandTile(size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(Brush.linearGradient(listOf(BrandNavy, Accent))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** 运行状态横幅：呼吸指示灯 + 当前消息 */
@Composable
internal fun RunningBanner(message: String) {
    // 与 Agent 页的呼吸点同一条规矩：减少动画时静态常亮，不闪烁
    val reduceMotion = motionSettings().reduceMotion
    val pulse = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "running-pulse")
        val animated by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = DurationPulse, easing = EaseOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "running-pulse-alpha",
        )
        animated
    }
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