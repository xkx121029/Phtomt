package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.looksLikeMarkdown
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut

/**
 * 单步工具调用行：作为 [ToolChainItem] 展开后的内容出现，自身不带卡片外壳
 * （外壳由工具链统一给），保证"一条链 = 一张卡"。
 *
 * 信息层级（一级最重、逐级弱化）：
 * 1. 工具 —— 第几步、调用了哪个工具（图标 + 工具名，与折叠行同一套口径）；
 * 2. 结果 —— 已生效 / 未生效；
 * 3. 动作人话 —— 这一步具体做了什么；
 * 4. 依据、命令与输出 —— 展开后的执行证据；
 * 5. 元信息与原始数据 —— 默认最弱，原始数据折叠。
 */
@Composable
internal fun ToolStepRow(
    step: StepCall,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    var expanded by rememberSaveable(step.key) { mutableStateOf(false) }
    var rawOpen by rememberSaveable(step.key) { mutableStateOf(false) }

    val statusColor = when {
        step.failed -> colors.error
        step.verified -> colors.success
        else -> colors.onSurfaceRaised
    }
    val human = rememberTranslated(step.human, vm)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        PressableScale(
            modifier = Modifier.fillMaxWidth(),
            onPress = buzz,
            onClick = { expanded = !expanded },
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "第 ${step.step} 步",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceRaised,
                        modifier = Modifier.width(40.dp),
                    )
                    Icon(
                        imageVector = AgentToolStyle.icon(step.toolType),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Text(
                        text = AgentToolStyle.name(step.toolType),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    // 端侧决策的步骤标一下来源：用户能分清"AI 想的"和"端侧规则直接做的"
                    if (step.fromLocalDecision) {
                        StatusPill(text = "端侧", color = colors.onSurfaceRaised)
                        Spacer(Modifier.width(AppSpacing.Xs))
                    }
                    if (step.failed || step.verified) {
                        StatusPill(
                            text = if (step.failed) "未生效" else "已生效",
                            color = statusColor,
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.Xs))
                    Icon(
                        imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = if (expanded) "收起这一步" else "展开这一步",
                        tint = colors.onSurfaceRaised,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (human.isNotBlank()) {
                    Spacer(Modifier.height(AppSpacing.Xs))
                    Text(
                        text = human,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
        ) {
            Column(modifier = Modifier.padding(start = 40.dp)) {
                // 目的（AI 输出的 reasoning 就是这个动作要干什么；弱化，不与动作抢注意力）
                if (!step.thinking.isNullOrBlank()) {
                    Spacer(Modifier.height(AppSpacing.Sm))
                    Text(
                        text = "目的：${step.thinking}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceRaised,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 结果说明：失败时把"为什么没成"说清楚（红），成功时是执行层说明（弱化）
                // 与 shell 输出相同时不重复展示
                if (step.detail.isNotBlank() && step.detail != step.shellOutput) {
                    Spacer(Modifier.height(AppSpacing.Xs))
                    Text(
                        text = if (step.failed) "原因：${step.detail}" else step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.failed) colors.error else colors.onSurfaceRaised,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 命令与输出（shell 类动作的执行证据）
                if (step.shellCommand.isNotBlank() || step.shellOutput.isNotBlank()) {
                    Spacer(Modifier.height(AppSpacing.Sm))
                    CommandBlock(command = step.shellCommand, output = step.shellOutput)
                }

                // 元信息（耗时 / tokens / 置信度）
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    text = metaLine(step),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceRaised,
                )
                Spacer(Modifier.height(AppSpacing.Sm))
                PressableScale(onPress = buzz, onClick = { rawOpen = !rawOpen }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (rawOpen) "收起原始数据" else "查看原始数据",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.brand,
                        )
                        Spacer(Modifier.width(AppSpacing.Xs))
                        Icon(
                            imageVector = if (rawOpen) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = rawOpen,
                    enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                        expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                        shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
                ) {
                    Column {
                        Spacer(Modifier.height(AppSpacing.Sm))
                        RawBlock(
                            title = "AI 的决策",
                            body = step.rawReceived.ifBlank { "（本步没有留下决策正文）" },
                        )
                        if (step.rawSent.isNotBlank()) {
                            Spacer(Modifier.height(AppSpacing.Sm))
                            RawBlock(title = "本轮发给模型的上下文", body = step.rawSent)
                        }
                        if (step.hasScreenshot) {
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

/**
 * 折叠区里的原始数据块：下沉底色，避免超长文本把列表项撑成整屏。
 *
 * 正文带 Markdown 结构（如发给模型的上下文：`#` 章节 + `-` 条目 + 逐行元素树）时按 Markdown 排版，
 * 并保留原始换行——否则元素树会被软换行连成一整段，反而比纯文本更难读；
 * 不像 Markdown 的（如决策 JSON）仍按纯文本限行显示。
 */
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
        if (looksLikeMarkdown(body)) {
            MarkdownPreview(
                content = body,
                modifier = Modifier.fillMaxWidth(),
                keepLineBreaks = true,
            )
        } else {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 60,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 步内元信息（弱化层）：耗时 / token / 置信度 / 是否留档截图 */
private fun metaLine(step: StepCall): String {
    val parts = ArrayList<String>(4)
    if (step.durationMs > 0) {
        parts += if (step.durationMs >= 1000) {
            "耗时 ${"%.1f".format(step.durationMs / 1000.0)} 秒"
        } else {
            "耗时 ${step.durationMs} 毫秒"
        }
    }
    if (step.tokens > 0) parts += "${step.tokens} tokens"
    step.confidence?.let { parts += "置信度 ${(it * 100).toInt()}%" }
    if (step.hasScreenshot) parts += "有截图留档"
    return if (parts.isEmpty()) "暂无更多信息" else parts.joinToString(" · ")
}