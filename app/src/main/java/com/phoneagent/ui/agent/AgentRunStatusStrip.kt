package com.phoneagent.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.AgentState
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.EaseInOut
import com.phoneagent.ui.theme.motionSettings
import kotlinx.coroutines.delay

/** 相位中文名（与列表项 LiveStatus 保持一致，不另造词） */
private fun stripPhaseWord(phase: AgentState.Phase): String = when (phase) {
    AgentState.Phase.IDLE -> "空闲"
    AgentState.Phase.OBSERVING -> "观察页面"
    AgentState.Phase.THINKING -> "思考下一步"
    AgentState.Phase.ACTING -> "执行动作"
    AgentState.Phase.DONE -> "已完成"
    AgentState.Phase.ERROR -> "出错"
}

/** 用时人话化：小于 1 分钟只报秒，超过则报分秒 */
private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    return if (totalSec < 60) {
        "${totalSec} 秒"
    } else {
        "${totalSec / 60}:${"%02d".format(totalSec % 60)}"
    }
}

/**
 * 运行状态条：相位灯 + 步数 / 阶段 + 置信度 + 用时 + 画面开关 + 停止。
 *
 * 需要协助时整条切成 errorContainer 配色，并提供「去回复」一键聚焦输入区。
 */
@Composable
internal fun AgentRunStatusStrip(
    state: AgentState,
    plannedSteps: Int,
    confidence: Double?,
    needsUser: Boolean,
    previewVisible: Boolean,
    onTogglePreview: () -> Unit,
    onFocusComposer: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val reduceMotion = motionSettings().reduceMotion

    var elapsedMs by remember(state.isRunning) { mutableLongStateOf(0L) }
    LaunchedEffect(state.isRunning) {
        if (state.isRunning) {
            elapsedMs = 0L
            while (true) {
                delay(1000)
                elapsedMs += 1000
            }
        }
    }

    val container = if (needsUser) colors.errorContainer else colors.surfaceRaised
    val onContainer = if (needsUser) colors.onErrorContainer else colors.onSurfaceRaised

    val dotColor = when {
        needsUser -> colors.error
        state.phase == AgentState.Phase.OBSERVING -> colors.accentCool
        state.phase == AgentState.Phase.THINKING || state.phase == AgentState.Phase.ACTING -> colors.brand
        state.phase == AgentState.Phase.ERROR -> colors.error
        else -> colors.railIdle
    }
    val dotAlpha = if (reduceMotion || !state.isRunning) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "strip-pulse")
        val animated by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "strip-pulse-alpha",
        )
        animated
    }

    val total = maxOf(plannedSteps, state.stepCount)
    val stepText = if (total > 0) {
        "第 ${state.stepCount.coerceAtMost(total)} 步 / 共 $total 步 · ${stripPhaseWord(state.phase)}"
    } else {
        stripPhaseWord(state.phase)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 外层留白只负责"离玻璃板边缘多远"：玻璃板自己已经退到屏幕里一段，
            // 这里补上那段距离，状态条在屏幕上仍在原来的位置
            .padding(horizontal = AgentGlassInnerPad, vertical = AppSpacing.Sm)
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(container)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = dotAlpha)),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Text(
                text = stepText,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (needsUser) colors.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (state.isRunning) {
                Text(
                    text = "用时 ${formatElapsed(elapsedMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.Sm))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (confidence != null) {
                StatusPill(
                    text = "把握 ${(confidence * 100).toInt()}%",
                    color = confidenceColor(confidence),
                )
            }
            AgentActionButton(
                text = if (previewVisible) "收起画面" else "看画面",
                icon = AppIcons.Preview,
                tone = if (previewVisible) AgentButtonTone.PRIMARY else AgentButtonTone.NEUTRAL,
                onClick = onTogglePreview,
            )
            if (needsUser) {
                AgentActionButton(
                    text = "去回复",
                    icon = AppIcons.Edit,
                    onClick = onFocusComposer,
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.isRunning) {
                AgentActionButton(
                    text = "停止",
                    icon = AppIcons.Stop,
                    tone = AgentButtonTone.DANGER,
                    onClick = onStop,
                )
            }
        }
    }
}