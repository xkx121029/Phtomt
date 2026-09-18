package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut

/**
 * 单步工具调用项：trace（决策追踪）与 record（执行结果）已按 step 合并为一条。
 *
 * 信息层级（一级最重、逐级弱化）：
 * 1. 动作人话 —— AI 这一步做了什么；
 * 2. 生效状态 —— 结果如何（已生效 / 未生效）；
 * 3. 因为 —— AI 给出的依据；
 * 4. 命令与输出 —— shell 类动作的真实执行证据（用户此前完全看不到）；
 * 5. 元信息（耗时 / tokens / 置信度）与原始数据 —— 默认最弱，原始数据折叠。
 */
@Composable
internal fun StepCallItem(item: AgentTimelineItem.StepCall) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }

    val statusColor = when {
        item.failed -> colors.error
        item.verified -> colors.success
        else -> colors.onSurfaceRaised
    }
    val statusIcon = when {
        item.failed -> AppIcons.ErrorOutline
        item.verified -> AppIcons.CheckCircle
        else -> AppIcons.Bolt
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item))
            .padding(AppSpacing.Lg),
    ) {
        // 第 1 层：步骤序号 + 动作 + 生效状态（一眼看清"做了什么、成没成"）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Text(
                text = if (item.actionVerb.isNotBlank()) "第 ${item.step} 步 · ${item.actionVerb}" else "第 ${item.step} 步",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // 端侧决策的步骤标一下来源：用户能分清"AI 想的"和"端侧规则直接做的"
            if (item.fromLocalDecision) {
                StatusPill(text = "端侧", color = colors.onSurfaceRaised)
                Spacer(Modifier.width(AppSpacing.Xs))
            }
            // 状态徽标平滑切换：执行中 → 已生效 / 未生效 不再是瞬间跳变
            AnimatedContent(
                targetState = when {
                    item.failed -> "未生效"
                    item.verified -> "已生效"
                    else -> ""
                },
                transitionSpec = {
                    fadeIn(tween(DurationFast, easing = EaseOut)) togetherWith
                        fadeOut(tween(DurationFast, easing = EaseOut))
                },
                label = "step-status-pill",
            ) { statusLabel ->
                if (statusLabel.isNotBlank()) {
                    StatusPill(
                        text = statusLabel,
                        color = if (item.failed) colors.error else colors.success,
                    )
                }
            }
        }

        if (item.human.isNotBlank()) {
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = item.human,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 第 3 层：依据（弱化，不与动作抢注意力）
        if (!item.thinking.isNullOrBlank()) {
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                text = "因为：${item.thinking}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceRaised,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 结果说明：失败时把"为什么没成"说清楚（红），成功时是执行层说明（弱化）
        // 与 shell 输出相同时不重复展示
        if (item.detail.isNotBlank() && item.detail != item.shellOutput) {
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                text = if (item.failed) "原因：${item.detail}" else item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.failed) colors.error else colors.onSurfaceRaised,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 第 4 层：命令与输出（shell 类动作的执行证据）
        if (item.shellCommand.isNotBlank() || item.shellOutput.isNotBlank()) {
            Spacer(Modifier.height(AppSpacing.Sm))
            CommandBlock(command = item.shellCommand, output = item.shellOutput)
        }

        // 第 5 层：元信息（耗时 / tokens / 置信度）—— 置信度从顶行下移到此，让顶行只留结论
        Spacer(Modifier.height(AppSpacing.Sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = metaLine(item),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
                modifier = Modifier.weight(1f),
            )
            PressableScale(onPress = buzz, onClick = { expanded = !expanded }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "收起原始数据" else "原始数据",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.brand,
                    )
                    Spacer(Modifier.width(AppSpacing.Xs))
                    Icon(
                        imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = null,
                        tint = colors.brand,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(DurationFast, easing = EaseOut)) + expandVertically(tween(DurationFast, easing = EaseOut)),
            exit = fadeOut(tween(DurationFast, easing = EaseOut)) + shrinkVertically(tween(DurationFast, easing = EaseOut)),
        ) {
            Column {
                RawBlock(
                    title = "AI 的决策",
                    body = item.rawReceived.ifBlank { "（本步没有留下决策正文）" },
                )
                if (item.rawSent.isNotBlank()) {
                    Spacer(Modifier.height(AppSpacing.Sm))
                    RawBlock(title = "本轮发给模型的上下文", body = item.rawSent)
                }
                if (item.hasScreenshot) {
                    Spacer(Modifier.height(AppSpacing.Sm))
                    Text(
                        text = "本步有截图留档，可在调试页按步查看原图与识别对比",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceRaised,
                    )
                }
            }
        }
    }
}

/**
 * 命令与输出回显块：这是"AI 到底跑了什么、拿到什么"的直接证据。
 * 失败时输出位显示可读原因（由执行层写入），因此同一个块既能看结果也能看失败原因。
 */
@Composable
private fun CommandBlock(command: String, output: String) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(colors.surfaceSunken)
            .padding(AppSpacing.Md),
    ) {
        if (command.isNotBlank()) {
            Text(
                text = "执行的命令",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurfaceRaised,
            )
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (output.isNotBlank()) {
            if (command.isNotBlank()) Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = "输出",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurfaceRaised,
            )
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                text = output,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 折叠区里的原始数据块：下沉底色 + 限高，避免超长文本把列表项撑成整屏 */
@Composable
private fun RawBlock(title: String, body: String) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(colors.surfaceSunken)
            .padding(AppSpacing.Md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onSurfaceRaised,
        )
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 60,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 步内元信息（弱化层）：耗时 / token / 置信度 / 是否留档截图 */
private fun metaLine(item: AgentTimelineItem.StepCall): String {
    val parts = ArrayList<String>(4)
    if (item.durationMs > 0) {
        parts += if (item.durationMs >= 1000) {
            "耗时 ${"%.1f".format(item.durationMs / 1000.0)} 秒"
        } else {
            "耗时 ${item.durationMs} 毫秒"
        }
    }
    if (item.tokens > 0) parts += "$item.tokens tokens"
    item.confidence?.let { parts += "置信度 ${(it * 100).toInt()}%" }
    if (item.hasScreenshot) parts += "有截图留档"
    return if (parts.isEmpty()) "暂无更多信息" else parts.joinToString(" · ")
}