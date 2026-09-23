package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut

/** 气泡圆角：朝向说话者一侧的底角收窄，形成克制的对话尾，而不是四角一样的通用气泡 */
internal fun agentBubbleShape(isUser: Boolean) = RoundedCornerShape(
    topStart = AppRadii.Bubble,
    topEnd = AppRadii.Bubble,
    bottomStart = if (isUser) AppRadii.Bubble else 6.dp,
    bottomEnd = if (isUser) 6.dp else AppRadii.Bubble,
)

/**
 * AI 侧的身份标识：一枚小小的"AI"标 + 这条输出是什么。
 *
 * AI 的每一类输出（完成说明 / 思考 / 画面识别 / 实时回显）都从这一行开始，
 * 聊天流里才分得清"谁在说、说的是哪一类"，而不是一堆没有出处的文字块。
 */
@Composable
internal fun AgentSpeakerHeader(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(AppRadii.Chip))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AI",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                ),
                color = tint,
            )
        }
        Spacer(Modifier.width(AppSpacing.Sm))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 用户下达的任务（右对齐气泡）。排队中的任务降饱和显示，避免看起来像正在执行 */
@Composable
internal fun UserTaskItem(item: AgentTimelineItem.UserTask) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.queued) colors.onSurfaceRaised else colors.onMessageBubbleUser,
            modifier = Modifier
                .fillMaxWidth(if (item.queued) 0.84f else 0.9f)
                .clip(agentBubbleShape(isUser = true))
                .background(if (item.queued) colors.surfaceRaised else colors.messageBubbleUser)
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        )
        if (item.queued) {
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                text = "排队中 · 当前任务结束后执行",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
            )
        }
    }
}

/**
 * 助手真实文本（完成说明 / 放弃原因 / 画面识别）。
 * 全部来自引擎已有数据，默认折叠成一行预览，点开才展示全文。
 */
@Composable
internal fun AssistantNoteItem(
    item: AgentTimelineItem.AssistantNote,
    vm: MainViewModel,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val text = rememberTranslated(item.text, vm)
    val label = when (item.source) {
        AgentTimelineItem.NoteSource.FINISH -> "完成说明"
        AgentTimelineItem.NoteSource.GIVE_UP -> "放弃原因"
        AgentTimelineItem.NoteSource.THINKING -> "思考"
        AgentTimelineItem.NoteSource.VISION -> "画面识别"
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(agentBubbleShape(isUser = false))
                .background(colors.messageBubbleAgent)
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        ) {
            PressableScale(
                onPress = buzz,
                onClick = { expanded = !expanded },
            ) {
                AgentSpeakerHeader(
                    label = label,
                    tint = colors.onMessageBubbleAgent,
                    trailing = {
                        Icon(
                            imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = colors.onMessageBubbleAgent.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
            Spacer(Modifier.height(AppSpacing.Xs))
            // 折叠态只截图一行预览（不做解析，省一次 md 解析）；展开态整段走 md 渲染
            if (expanded) {
                AgentMessageText(
                    text = item.text,
                    vm = vm,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onMessageBubbleAgent.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                    expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                    shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
            ) {
                Column {
                    Spacer(Modifier.height(AppSpacing.Sm))
                    Text(
                        text = "来源：第 ${if (item.step > 0) item.step else 1} 步 · ${sourceHint(item.source)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onMessageBubbleAgent.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * AI 主动说的一句话（say 意图）：挂在任务流里的 AI 气泡，一次说完不折叠。
 *
 * 它不是一步操作，因此没有工具图标、没有步号——只作为一条对话消息出现；正文走 Markdown 渲染。
 */
@Composable
internal fun SayItem(
    item: AgentTimelineItem.Say,
    vm: MainViewModel,
) {
    val colors = AppTheme.colors
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(agentBubbleShape(isUser = false))
                .background(colors.messageBubbleAgent)
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        ) {
            AgentSpeakerHeader(label = "对我说", tint = colors.onMessageBubbleAgent)
            Spacer(Modifier.height(AppSpacing.Xs))
            AgentMessageText(
                text = item.text,
                vm = vm,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun sourceHint(source: AgentTimelineItem.NoteSource): String = when (source) {
    AgentTimelineItem.NoteSource.FINISH -> "AI 自述的完成依据"
    AgentTimelineItem.NoteSource.GIVE_UP -> "AI 自述的放弃理由"
    AgentTimelineItem.NoteSource.THINKING -> "AI 的思考过程"
    AgentTimelineItem.NoteSource.VISION -> "截图识别结果，非 AI 自述"
}

/** 列表项之间的统一纵向间距（供 AgentScreen 的 LazyColumn 使用） */
internal val AgentItemSpacing = 10.dp

/**
 * AI 生成的文档结果：直接嵌在任务流里预览，不再另开工作区页面。
 * 默认展开让用户一眼看到成果，长文限高内滚，可收起或关闭预览。
 */
@Composable
internal fun DocPreviewItem(
    item: AgentTimelineItem.DocPreview,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    var expanded by rememberSaveable(item.key) { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
        ) {
            AppIconTile(
                icon = AppIcons.Description,
                tint = colors.brand,
                background = colors.surfaceSunken,
                tileSize = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(AppSpacing.Md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已生成文档",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceRaised,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PressableScale(
                onPress = buzz,
                onClick = { expanded = !expanded },
            ) {
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = if (expanded) "收起文档" else "展开文档",
                    tint = colors.onSurfaceRaised,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(AppSpacing.Md))
            PressableScale(
                onPress = buzz,
                onClick = { buzz(); onDismiss() },
            ) {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = "关闭文档预览",
                    tint = colors.onSurfaceRaised,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(DurationFast, easing = EaseOut)) + expandVertically(tween(DurationFast, easing = EaseOut)),
            exit = fadeOut(tween(DurationFast, easing = EaseOut)) + shrinkVertically(tween(DurationFast, easing = EaseOut)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = AppSpacing.Lg, end = AppSpacing.Lg, bottom = AppSpacing.Lg),
            ) {
                MarkdownPreview(content = item.content, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 操作按钮语义：主操作 / 次要操作 / 危险操作 */
internal enum class AgentButtonTone { PRIMARY, NEUTRAL, DANGER }

/**
 * Agent 页统一操作按钮：按下即回弹缩放 + 即时振感（emilkowalski 规范的按钮反馈），
 * 圆角走 [AppRadii.Tile] 令牌，避免 M3 默认全圆胶囊带来的通用观感。
 */
@Composable
internal fun AgentActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tone: AgentButtonTone = AgentButtonTone.PRIMARY,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val container = when (tone) {
        AgentButtonTone.PRIMARY -> colors.brand
        AgentButtonTone.NEUTRAL -> colors.surfaceSunken
        AgentButtonTone.DANGER -> colors.errorContainer
    }
    val content = when (tone) {
        AgentButtonTone.PRIMARY -> colors.onBrand
        AgentButtonTone.NEUTRAL -> colors.onSurfaceRaised
        AgentButtonTone.DANGER -> colors.onErrorContainer
    }
    PressableScale(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(container)
            .then(
                if (tone == AgentButtonTone.NEUTRAL) {
                    Modifier.border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Tile))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = AppSpacing.Lg, vertical = 10.dp),
        enabled = enabled,
        onPress = buzz,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(AppSpacing.Xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = content,
                maxLines = 1,
            )
        }
    }
}