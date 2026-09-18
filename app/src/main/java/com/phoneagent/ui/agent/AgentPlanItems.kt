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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.EaseInOut
import com.phoneagent.ui.theme.motionSettings

/** 规划中的呼吸点：减少动画时静态常亮，不闪烁 */
@Composable
private fun PlanningPulseDot(color: Color) {
    val reduceMotion = motionSettings().reduceMotion
    val alpha = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "plan-pulse")
        val animated by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "plan-pulse-alpha",
        )
        animated
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

/** 规划流式文本：边生成边展示，只保留尾部片段避免长文本把列表项撑爆 */
@Composable
internal fun PlanStreamingItem(
    item: AgentTimelineItem.PlanStreaming,
    vm: MainViewModel,
) {
    val colors = AppTheme.colors
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(AppRadii.Bubble))
                .background(colors.messageBubbleAgent)
                .padding(AppSpacing.Lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlanningPulseDot(colors.brand)
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = "AI 正在规划",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onMessageBubbleAgent,
                )
            }
            if (item.text.isNotBlank()) {
                Spacer(Modifier.height(AppSpacing.Sm))
                // 半截 markdown 也能渲染（解析器会 flush 未闭合代码块），只保留尾部片段避免撑爆列表项
                AgentMessageText(text = item.text.takeLast(900), vm = vm)
            }
        }
    }
}

/**
 * 歧义澄清：选项 + 「我想自己说」。
 * 交互与原 PlanPanel 一一对应：选项点选走 [onAnswer]，手写答案用 id="manual"。
 */
@Composable
internal fun PlanClarifyItem(
    item: AgentTimelineItem.PlanClarify,
    vm: MainViewModel,
    onAnswer: (ClarificationOption) -> Unit,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    var manual by rememberSaveable(item.key) { mutableStateOf("") }
    val question = rememberTranslated(item.clarification.question, vm)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(colors.accentCoolContainer)
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = colors.onAccentCoolContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.Xs))
            Text(
                text = "需要向你确认",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onAccentCoolContainer,
            )
        }
        Spacer(Modifier.height(AppSpacing.Sm))
        Text(
            text = question,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onAccentCoolContainer,
        )
        Spacer(Modifier.height(AppSpacing.Md))

        item.clarification.options.forEach { option ->
            val label = rememberTranslated(option.label, vm)
            val desc = rememberTranslated(option.description, vm)
            PressableScale(
                onPress = buzz,
                onClick = { onAnswer(option) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.Xs)
                    .clip(RoundedCornerShape(AppRadii.Tile))
                    .background(colors.surfaceBase)
                    .padding(AppSpacing.Md),
            ) {
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (desc.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceRaised,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Sm))
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            label = { Text("我想自己说") },
            placeholder = { Text("用自己的话描述清楚需求") },
            shape = RoundedCornerShape(AppRadii.Tile),
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        AgentActionButton(
            text = "提交我的回答",
            enabled = manual.isNotBlank(),
            onClick = {
                onAnswer(ClarificationOption(id = "manual", label = manual))
                manual = ""
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 计划待批准：步数 / 预计时长 / 置信度 / 逐步清单，交互与原 PlanPanel 一致 */
@Composable
internal fun PlanApprovalItem(
    item: AgentTimelineItem.PlanApproval,
    vm: MainViewModel,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Card))
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "执行计划",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = "共 ${item.plan.steps.size} 步", color = colors.brand)
            Spacer(Modifier.width(AppSpacing.Xs))
            StatusPill(
                text = "把握 ${(item.plan.confidence * 100).toInt()}%",
                color = confidenceColor(item.plan.confidence),
            )
        }
        if (item.plan.estimatedTimeSeconds > 0) {
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                text = "预计耗时约 ${item.plan.estimatedTimeSeconds} 秒",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
            )
        }
        Spacer(Modifier.height(AppSpacing.Md))

        item.plan.steps.forEachIndexed { index, step ->
            val desc = rememberTranslated(step.description, vm)
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.brand,
                    modifier = Modifier.width(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (step.intent.isNotBlank()) {
                        val intent = rememberTranslated(step.intent, vm)
                        Text(
                            text = intent,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceRaised,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
            AgentActionButton(
                text = "取消",
                tone = AgentButtonTone.NEUTRAL,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            AgentActionButton(
                text = "批准并开始",
                icon = AppIcons.Play,
                onClick = onApprove,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 计划已批准：执行过程中的状态锚点，避免用户以为计划丢了 */
@Composable
internal fun PlanApprovedItem(item: AgentTimelineItem.PlanApproved) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.brandContainer)
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        PlanningPulseDot(colors.brand)
        Spacer(Modifier.width(AppSpacing.Sm))
        Text(
            text = "计划已批准，正在执行（共 ${item.plan.steps.size} 步）",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = colors.onBrandContainer,
        )
    }
}

/** 规划失败 */
@Composable
internal fun PlanFailedItem(
    message: String,
    vm: MainViewModel,
) {
    val colors = AppTheme.colors
    val text = rememberTranslated(message, vm)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(colors.errorContainer)
            .padding(AppSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.ErrorOutline,
                contentDescription = null,
                tint = colors.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.Xs))
            Text(
                text = "规划失败",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onErrorContainer,
            )
        }
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = text.ifBlank { "AI 没能给出计划，可换个说法再试一次" },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
        )
    }
}

/** 置信度分档配色（与调试页既有分档一致） */
@Composable
internal fun confidenceColor(confidence: Double?): Color {
    val colors = AppTheme.colors
    return when {
        confidence == null -> colors.onSurfaceRaised
        confidence >= 0.75 -> colors.success
        confidence >= 0.6 -> colors.warning
        else -> colors.error
    }
}