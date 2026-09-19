package com.phoneagent.ui.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.AgentState
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.motionSettings
import kotlinx.coroutines.delay

/** 相位中文名 */
private fun phaseWord(phase: AgentState.Phase): String = when (phase) {
    AgentState.Phase.IDLE -> "空闲"
    AgentState.Phase.OBSERVING -> "观察页面"
    AgentState.Phase.THINKING -> "思考下一步"
    AgentState.Phase.ACTING -> "执行动作"
    AgentState.Phase.DONE -> "已完成"
    AgentState.Phase.ERROR -> "出错"
}

/**
 * 实时中间状态：无论中间刷过多少条，列表里恒定只有这一条，
 * 被折叠掉的条数用「另有 N 条动态」告知，避免刷屏。
 *
 * 观感上刻意与"已完成"的步骤卡拉开层次（参考 Aether 的做法）：
 * 进行中只是过程，不铺卡片底色、不加边框，只用一行弱化文字 + 一条 1dp 细分线；
 * 思考阶段让文字微光扫过，暗示"正在生成"，并附上"已工作 N 秒"。
 */
@Composable
internal fun LiveStatusItem(item: AgentTimelineItem.LiveStatus) {
    val colors = AppTheme.colors
    val reduceMotion = motionSettings().reduceMotion

    // 已工作时长：每秒刷新一次（开始时间由引擎在任务启动时写入）
    val elapsedMs by produceState(
        initialValue = elapsedOf(item.startedAtMillis),
        item.startedAtMillis,
    ) {
        while (item.startedAtMillis > 0L) {
            value = elapsedOf(item.startedAtMillis)
            delay(1_000L)
        }
    }

    val headline = if (item.message.isNotBlank()) {
        "${phaseWord(item.phase)} · ${item.message}"
    } else {
        phaseWord(item.phase)
    }
    val thinking = item.phase == AgentState.Phase.THINKING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 思考中：微光扫过；其余阶段普通弱化文字（reduceMotion 一律静态）
            if (thinking && !reduceMotion) {
                ShimmerStatusText(text = headline, modifier = Modifier.weight(1f))
            } else {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceRaised,
                    modifier = Modifier.weight(1f),
                )
            }
            if (elapsedMs >= 1_000L) {
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = "已工作 ${formatElapsed(elapsedMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceRaised,
                )
            }
            if (item.foldedCount > 0) {
                Spacer(Modifier.width(AppSpacing.Sm))
                StatusPill(text = "另有 ${item.foldedCount} 条动态", color = colors.onSurfaceRaised)
            }
        }

        // 1dp 细分线：把"进行中的过程"与上方"已完成的结果"在视觉上切开
        Spacer(Modifier.height(AppSpacing.Sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineSoft.copy(alpha = 0.62f)),
        )

        // AI 正在生成的正文：只贴尾部若干行（像终端 tail），让用户看到它"此刻在说什么"
        if (item.streaming.isNotBlank()) {
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = item.streaming.lines().takeLast(STREAM_LINES).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceRaised,
                maxLines = STREAM_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 实时回显最多展示的行数（超出取尾部） */
private const val STREAM_LINES = 10

/** 已工作时长（毫秒）；开始时间未知时返回 0 */
private fun elapsedOf(startedAtMillis: Long): Long {
    if (startedAtMillis <= 0L) return 0L
    return (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
}

/** 时长人话：秒 / 分秒 */
private fun formatElapsed(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1_000L} 秒"
    else -> "${ms / 60_000L} 分 ${(ms % 60_000L) / 1_000L} 秒"
}

/**
 * 微光扫过文字：用在"正在生成"的状态行上，替代常驻脉冲圆点。
 * 行程随文字长度伸缩（短句扫得慢、长句扫得快），扫完停顿一下再重来。
 * reduceMotion 时由调用方降级为静态文字，此处不再判断。
 */
@Composable
private fun ShimmerStatusText(text: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val travelDistance = (280f + text.length * 18f).coerceIn(280f, 760f)
    val sweepHalfWidth = 180f
    val travelMs = 1800
    val pauseMs = 1000

    val offset by rememberInfiniteTransition(label = "status-shimmer").animateFloat(
        initialValue = -travelDistance,
        targetValue = travelDistance,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = travelMs + pauseMs
                travelDistance at travelMs using LinearEasing
                travelDistance at travelMs + pauseMs
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "status-shimmer-offset",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            colors.onSurfaceRaised.copy(alpha = 0.42f),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.96f),
            colors.onSurfaceRaised.copy(alpha = 0.42f),
        ),
        start = Offset(offset - sweepHalfWidth, 0f),
        end = Offset(offset + sweepHalfWidth, 0f),
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(brush = brush),
        modifier = modifier,
    )
}

/**
 * 需要用户协助：敏感页只读保护 / 动作连续未生效。
 * 两个出口在底部输入区（指导 AI / 已手动处理），这里只负责把原因说清楚并把焦点引过去。
 */
@Composable
internal fun NeedsUserItem(
    item: AgentTimelineItem.NeedsUser,
    onFocusComposer: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.errorContainer)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.TouchApp,
                contentDescription = null,
                tint = colors.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.Xs))
            Text(
                text = "需要你的协助",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onErrorContainer,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(text = "第 ${item.step} 步", color = colors.onErrorContainer)
        }
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = item.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
        )
        Spacer(Modifier.height(AppSpacing.Md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentActionButton(
                text = "去输入框回复",
                icon = AppIcons.Edit,
                onClick = onFocusComposer,
            )
            Spacer(Modifier.width(AppSpacing.Md))
            Text(
                text = "或点「已手动处理」让 AI 继续",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onErrorContainer.copy(alpha = 0.8f),
            )
        }
    }
}

/** 任务完成摘要：步数 / token / 平均响应，全部来自真实执行数据 */
@Composable
internal fun DoneItem(item: AgentTimelineItem.Done) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.successContainer)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.CheckCircle,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Text(
                text = "任务完成",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.onSuccessContainer,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = "生效 ${item.okSteps}/${item.totalSteps} 步", color = colors.success)
        }
        if (item.note.isNotBlank()) {
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = item.note,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSuccessContainer,
            )
        }
        Spacer(Modifier.height(AppSpacing.Sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "共 ${item.totalSteps} 步 · ${item.tokens} tokens · 平均响应 ${formatMs(item.avgLatencyMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSuccessContainer.copy(alpha = 0.85f),
            )
        }
    }
}

/** 任务失败 / 达到步数上限 */
@Composable
internal fun FailedItem(
    message: String,
    vm: MainViewModel,
) {
    val colors = AppTheme.colors
    val text = rememberTranslated(message, vm)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.errorContainer)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.ErrorOutline,
                contentDescription = null,
                tint = colors.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Text(
                text = "任务已停止",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.onErrorContainer,
            )
        }
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = text.ifBlank { "执行过程中出错，可重新下达任务" },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
        )
    }
}

/** 缺权限等内嵌提示（不使用系统弹窗）。当前仅「无障碍服务未开启」一种 */
@Composable
internal fun NoticeItem(item: AgentTimelineItem.Notice) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.warningContainer)
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        Icon(
            imageVector = AppIcons.TouchApp,
            contentDescription = null,
            tint = colors.onWarningContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onWarningContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 毫秒 → 人话时长 */
private fun formatMs(ms: Long): String = when {
    ms <= 0 -> "—"
    ms >= 1000 -> "${"%.1f".format(ms / 1000.0)} 秒"
    else -> "$ms 毫秒"
}