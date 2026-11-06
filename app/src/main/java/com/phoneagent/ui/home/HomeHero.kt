package com.phoneagent.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.BrandMark
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.BrandAnchor
import com.phoneagent.ui.theme.BrandMarkTint
import com.phoneagent.ui.theme.DurationPulse
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.appBorderStroke
import com.phoneagent.ui.theme.motionSettings

/**
 * 品牌瓦片：玄青深锚底 + 「亮格」标记（VI v1.0）。
 * 旧版是"深海军蓝 → 电光蓝紫"渐变加星芒图标——既撞 AI 通用皮肤，也与"不要蓝紫渐变"的
 * 品牌口径冲突，已退役；标记与桌面图标、官网 favicon 共用同一套几何。
 */
@Composable
internal fun BrandTile(size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(BrandAnchor),
        contentAlignment = Alignment.Center,
    ) {
        BrandMark(size = size * 0.6f, tint = BrandMarkTint)
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
        border = appBorderStroke(
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