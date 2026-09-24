package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.ActionType
import com.phoneagent.ui.MainViewModel
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
 * 工具调用的图标与名称：这里是"调用了什么工具"的唯一口径。
 *
 * 图标一律线性描边、同一尺寸，几个连在一起时读到的是"一排工具"，而不是一堆图。
 * 名称直接复用 [HumanTranslator] 的动作人话，避免同一动作在两处叫两个名字。
 */
internal object AgentToolStyle {

    fun icon(type: String): ImageVector = when (type) {
        ActionType.CLICK, ActionType.TAP -> AppIcons.Tap
        ActionType.LONG_CLICK, ActionType.LONG_PRESS -> AppIcons.LongPress
        ActionType.TYPE_TEXT -> AppIcons.Keyboard
        ActionType.KEY -> AppIcons.EnterKey
        ActionType.SCROLL, ActionType.SWIPE, ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
        ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT,
        -> AppIcons.ScrollVertical

        ActionType.SCROLL_TO -> AppIcons.ScrollSearch
        ActionType.WAIT -> AppIcons.Loading
        ActionType.LAUNCH -> AppIcons.Launch
        ActionType.OPEN -> AppIcons.Globe
        ActionType.BACK -> AppIcons.ArrowBack
        ActionType.HOME -> AppIcons.Home
        ActionType.RECENTS -> AppIcons.ListTodo
        ActionType.REFRESH -> AppIcons.Refresh
        ActionType.SHELL -> AppIcons.Terminal
        ActionType.WRITE_DOC -> AppIcons.Description
        ActionType.REMEMBER -> AppIcons.Remember
        ActionType.DEVICE_QUERY -> AppIcons.Memory
        ActionType.MCP_CALL -> AppIcons.Bolt
        ActionType.TASK_DONE, ActionType.TASK_COMPLETE -> AppIcons.CheckCircle
        ActionType.ABORT -> AppIcons.Cancel
        else -> AppIcons.SmartToy
    }

    /** 工具名（中文）。认不出的动作不硬编一个名字，如实说"未知操作" */
    fun name(type: String): String =
        if (HumanTranslator.knowsAction(type)) HumanTranslator.actionVerb(type) else "未知操作"
}

/** 图标条最多连排几个，超出的用 +N 交代，避免长任务把这一行撑爆 */
private const val MAX_CHIPS = 4

/** 单个工具图标方块边长 / 相邻两个的横向前进量（小于边长 → 连排而非平铺） */
private val ChipSize = 26.dp
private val ChipAdvance = 19.dp

/**
 * 连续若干步工具调用：聊天流里折叠成一条「调用了 N 个工具」的回显行。
 *
 * 折叠态一眼看到"调用了几个工具、分别是什么"（图标连排 + 工具名），
 * 点开才铺开每一步的细节，任务再长也不会把对话刷成一片卡片。
 */
@Composable
internal fun ToolChainItem(
    item: AgentTimelineItem.ToolChain,
    vm: MainViewModel,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }

    val failedCount = item.steps.count { it.failed }
    val okCount = item.steps.count { it.verified }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Item)),
    ) {
        PressableScale(
            modifier = Modifier.fillMaxWidth(),
            onPress = buzz,
            onClick = { expanded = !expanded },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            ) {
                ToolIconStrip(item.steps)
                Spacer(Modifier.width(AppSpacing.Md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chainTitle(item.steps),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = chainSubtitle(item.steps),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceRaised,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (failedCount > 0) {
                    Spacer(Modifier.width(AppSpacing.Xs))
                    StatusPill(text = "$failedCount 步未生效", color = colors.error)
                } else if (okCount == item.steps.size && okCount > 0) {
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Icon(
                        imageVector = AppIcons.CheckCircle,
                        contentDescription = "全部生效",
                        tint = colors.success,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(AppSpacing.Xs))
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = if (expanded) "收起步骤" else "展开步骤",
                    tint = colors.onSurfaceRaised,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
        ) {
            Column {
                ChainDivider()
                item.steps.forEachIndexed { index, step ->
                    if (index > 0) ChainDivider()
                    ToolStepRow(step = step, vm = vm)
                }
            }
        }
    }
}

/**
 * 折叠行的主文案：一个工具报到第几步 + 工具名，多个工具报数量。
 *
 * 单步刻意不用"调用了「点击」工具"这种句式：绕了一圈才说出"点了哪里"，
 * 而步号是用户与轨道、与展开后的步骤行对齐的唯一坐标，值得占这个位置。
 */
private fun chainTitle(steps: List<StepCall>): String {
    if (steps.size != 1) return "调用了 ${steps.size} 个工具"
    val first = steps.first()
    return "第 ${first.step} 步 · ${AgentToolStyle.name(first.toolType)}"
}

/** 折叠行的副文案：单步给这步做了什么，多步给这串工具的顺序 */
private fun chainSubtitle(steps: List<StepCall>): String = if (steps.size == 1) {
    steps.first().human.ifBlank { "本步没有留下动作说明" }
} else {
    steps.joinToString("、") { AgentToolStyle.name(it.toolType) }
}

/** 图标连排：几个图标连在一起，就是调用了几个工具 */
@Composable
private fun ToolIconStrip(steps: List<StepCall>) {
    val colors = AppTheme.colors
    val shown = steps.take(MAX_CHIPS)
    val extra = steps.size - shown.size

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(ChipAdvance * (shown.size - 1) + ChipSize)
                .height(ChipSize),
        ) {
            shown.forEachIndexed { index, step ->
                val failed = step.failed
                Box(
                    modifier = Modifier
                        .offset(x = ChipAdvance * index)
                        .size(ChipSize)
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(if (failed) colors.errorContainer else colors.surfaceSunken)
                        .border(
                            1.dp,
                            if (failed) colors.error.copy(alpha = 0.45f) else colors.outlineSoft,
                            RoundedCornerShape(AppRadii.Chip),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AgentToolStyle.icon(step.toolType),
                        contentDescription = AgentToolStyle.name(step.toolType),
                        tint = if (failed) colors.error else colors.brand,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (extra > 0) {
            Spacer(Modifier.width(AppSpacing.Xs))
            Text(
                text = "+$extra",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
            )
        }
    }
}

/** 链内分隔线：1dp，比卡片边框更淡，只用来切开步骤 */
@Composable
private fun ChainDivider() {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.outlineSoft.copy(alpha = 0.6f)),
    )
}