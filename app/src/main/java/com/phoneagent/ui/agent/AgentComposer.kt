package com.phoneagent.ui.agent

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut

/**
 * 输入区状态机。
 *
 * 关键约束来自引擎真实行为（见方案 2.5）：
 * - 运行中不禁用输入，走「排队下一条」而不是并发执行；
 * - Planning / AwaitingApproval 阶段必须锁定——此时引擎的规划 job 尚空闲，
 *   若下发新任务会与规划并发跑，所以这两个阶段一律 LOCKED。
 */
internal enum class ComposerMode { NEW_TASK, QUEUE_FOLLOW_UP, GUIDE_AGENT, ANSWER_CLARIFY, LOCKED }

/** 细行开关：比 M3 默认 Switch 紧凑，匹配 28dp 行高 */
@Composable
private fun MiniSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val trackShape = RoundedCornerShape(AppRadii.Chip)
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 19.dp else 3.dp,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "mini-switch-knob",
    )
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(trackShape)
            .background(if (checked) colors.brand else colors.surfaceSunken)
            .border(1.dp, if (checked) colors.brand else colors.outlineSoft, trackShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                buzz()
                onToggle(!checked)
            },
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = 3.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(if (checked) colors.onBrand else colors.outlineStrong),
        )
    }
}

/** 等待执行队列入口：只在有排队任务时出现，点开查看明细 */
@Composable
private fun QueueStrip(count: Int, onOpen: () -> Unit) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    PressableScale(
        onPress = buzz,
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.Sm)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(colors.brandContainer)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "还有 $count 条任务排队等待执行",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.onBrandContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.brand,
            )
        }
    }
}

/**
 * 固定底部输入区：圆角与内边距随聚焦形变，按钮随 [mode] 切换。
 * 不使用系统弹窗，队列入口为内嵌细条。
 */
@Composable
internal fun AgentComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    mode: ComposerMode,
    lockHint: String,
    queueCount: Int,
    onOpenQueue: () -> Unit,
    reviewEnabled: Boolean,
    onToggleReview: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDismissUser: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val locked = mode == ComposerMode.LOCKED
    var focused by remember { mutableStateOf(false) }

    val fieldShape = RoundedCornerShape(if (focused && !locked) AppRadii.Hero else AppRadii.Card)
    val fieldPad by animateDpAsState(
        targetValue = if (focused && !locked) AppSpacing.Lg else AppSpacing.Md,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "composer-pad",
    )
    val fieldElevation by animateDpAsState(
        targetValue = if (focused && !locked) 6.dp else 0.dp,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "composer-elevation",
    )

    val placeholder = when (mode) {
        ComposerMode.LOCKED -> lockHint.ifBlank { "请稍候…" }
        ComposerMode.ANSWER_CLARIFY -> "也可以自己说答案"
        ComposerMode.QUEUE_FOLLOW_UP -> "追加下一条任务（当前任务结束后执行）"
        ComposerMode.GUIDE_AGENT -> "直接告诉我该怎么做"
        ComposerMode.NEW_TASK -> "描述任务，AI 将逐步接管手机"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceBase)
            .imePadding()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        if (queueCount > 0) {
            QueueStrip(count = queueCount, onOpen = onOpenQueue)
        }

        if (mode == ComposerMode.NEW_TASK) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.Sm),
            ) {
                Text(
                    text = "审核 AI",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = "独立复核每个动作是否真有页面证据",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceRaised,
                    modifier = Modifier.weight(1f),
                )
                MiniSwitch(checked = reviewEnabled, onToggle = onToggleReview)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(fieldElevation, fieldShape)
                .clip(fieldShape)
                .background(colors.surfaceRaised)
                .border(
                    width = 1.dp,
                    color = if (focused && !locked) colors.brand.copy(alpha = 0.45f) else colors.outlineSoft,
                    shape = fieldShape,
                )
                .padding(fieldPad)
                .alpha(if (locked) 0.7f else 1f),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceRaised,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { if (!locked) onDraftChange(it) },
                enabled = !locked,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(colors.brand),
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
            )
        }

        Spacer(Modifier.height(AppSpacing.Md))

        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (mode) {
                ComposerMode.LOCKED -> AgentActionButton(
                    text = "发送",
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )

                ComposerMode.NEW_TASK, ComposerMode.ANSWER_CLARIFY -> AgentActionButton(
                    text = if (mode == ComposerMode.NEW_TASK) "发送" else "提交回答",
                    icon = AppIcons.Send,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = onSend,
                )

                ComposerMode.GUIDE_AGENT -> {
                    AgentActionButton(
                        text = "指导 AI",
                        icon = AppIcons.Send,
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = onSend,
                    )
                    AgentActionButton(
                        text = "已手动处理",
                        tone = AgentButtonTone.NEUTRAL,
                        onClick = onDismissUser,
                    )
                }

                ComposerMode.QUEUE_FOLLOW_UP -> {
                    if (draft.isNotBlank()) {
                        AgentActionButton(
                            text = "排队",
                            icon = AppIcons.Add,
                            modifier = Modifier.weight(1f),
                            onClick = onSend,
                        )
                    }
                    AgentActionButton(
                        text = "停止",
                        icon = AppIcons.Stop,
                        tone = AgentButtonTone.DANGER,
                        modifier = if (draft.isNotBlank()) Modifier else Modifier.weight(1f),
                        onClick = onStop,
                    )
                }
            }
        }
    }
}