package com.phoneagent.ui.agent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.engine.TaskSession
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.formatFileTime
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.motionSettings

/**
 * Agent 页「任务」侧边栏：列出一份份任务会话，点谁看谁，并给出「新建任务」入口。
 *
 * 交互模型：主区域一次只铺开**一个任务**。默认跟随实时任务（最新一次执行 + 正在进行的规划），
 * 从这里点选某个历史任务后主区域改为只看那一次，顶部会出现「返回当前任务」提示条。
 *
 * 为什么放左侧：右侧返回手势被系统占用，底部又被输入区占据，左下抽出是本页唯一不打架的位置。
 * 动画遵守全站约定——只做由下向上的位移 + 淡入，不做横向滑入、不做缩放。
 *
 * [open] 为 false 时本组件仍留在组合树里（只有一层空 Box，不吃触摸事件），
 * 这样打开时能真正播放入场动画。
 */
@Composable
internal fun AgentTaskDrawer(
    open: Boolean,
    sessions: List<TaskSession>,
    /** 正在规划、尚未产生会话的新任务标题（规划期它不在 sessions 里，单独置顶展示） */
    pendingTitle: String?,
    /** 正在回看的历史任务；null = 跟随实时 */
    viewingTaskId: Long?,
    onSelect: (Long?) -> Unit,
    onNewTask: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reduceMotion = motionSettings().reduceMotion
    // 用 MutableTransitionState 而不是 visible 布尔：本组件常驻组合，首帧就是可见态的话
    // updateTransition 会把初始态当作"已经落位"，入场动画整段看不到。
    val scrimState = remember { MutableTransitionState(false) }
    val panelState = remember { MutableTransitionState(false) }
    LaunchedEffect(open) {
        scrimState.targetState = open
        panelState.targetState = open
    }
    BackHandler(enabled = open) { onDismiss() }

    val shiftPx = with(LocalDensity.current) { (if (reduceMotion) 0 else 28).dp.roundToPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidth = minOf(maxWidth * 0.84f, 336.dp)

        AnimatedVisibility(
            visibleState = scrimState,
            enter = fadeIn(tween(DurationNormal, easing = EaseOut)),
            exit = fadeOut(tween(DurationFast, easing = EaseOut)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.36f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visibleState = panelState,
            enter = fadeIn(tween(DurationNormal, easing = EaseOut)) +
                slideInVertically(tween(DurationNormal, easing = EaseOut)) { shiftPx },
            exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                slideOutVertically(tween(DurationFast, easing = EaseOut)) { shiftPx },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            TaskPanel(
                width = panelWidth,
                sessions = sessions,
                pendingTitle = pendingTitle,
                viewingTaskId = viewingTaskId,
                onSelect = onSelect,
                onNewTask = onNewTask,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun TaskPanel(
    width: Dp,
    sessions: List<TaskSession>,
    pendingTitle: String?,
    viewingTaskId: Long?,
    onSelect: (Long?) -> Unit,
    onNewTask: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(topEnd = AppRadii.Card, bottomEnd = AppRadii.Card)
    // 主区域看的就是最新那一次执行，所以"实时"与"最新会话"本是同一件事：
    // 只有规划中的新任务例外——它还没有会话，此时不该把上一条历史任务显示成选中。
    val selectedId: Long? = when {
        viewingTaskId != null -> viewingTaskId
        pendingTitle != null -> null
        else -> sessions.firstOrNull()?.taskId
    }

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineSoft, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpacing.Lg, end = AppSpacing.Sm, top = AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "任务",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (sessions.isEmpty()) "还没有历史任务" else "共 ${sessions.size} 个任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceRaised,
                )
            }
            PressableScale(onClick = onDismiss) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "关闭任务列表",
                        tint = colors.onSurfaceRaised,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Md))

        PressableScale(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg),
            onClick = onNewTask,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadii.Item))
                    .background(colors.brand)
                    .padding(vertical = AppSpacing.Md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = null,
                    tint = colors.onBrand,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = "新建任务",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onBrand,
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        if (sessions.isEmpty() && pendingTitle == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) { NoTasksHint() }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = AppSpacing.Lg, end = AppSpacing.Lg, bottom = AppSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            ) {
                if (pendingTitle != null) {
                    item(key = "pending") {
                        TaskRow(
                            title = pendingTitle,
                            pillText = "规划中",
                            pillColor = colors.warning,
                            meta = "AI 正在生成计划",
                            timeText = "",
                            selected = viewingTaskId == null,
                            onClick = { onSelect(null) },
                        )
                    }
                }
                items(items = sessions, key = { it.taskId }) { session ->
                    TaskRow(
                        title = session.title.ifBlank { "未命名任务" },
                        pillText = statusTextOf(session.status),
                        pillColor = statusColorOf(session.status),
                        meta = metaOf(session),
                        timeText = formatFileTime(session.startedAt),
                        selected = session.taskId == selectedId,
                        onClick = { onSelect(session.taskId) },
                    )
                }
            }
        }
    }
}

/** 单条任务会话：状态胶囊 + 时间 / 标题 / 规模。选中态用品牌色底 + 品牌色描边，不靠加粗或位移暗示 */
@Composable
private fun TaskRow(
    title: String,
    pillText: String,
    pillColor: Color,
    meta: String,
    timeText: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppRadii.Item)
    PressableScale(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.brandContainer else colors.surfaceBase)
                .border(1.dp, if (selected) colors.brand else colors.outlineSoft, shape)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(text = pillText, color = pillColor)
                if (timeText.isNotBlank()) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceRaised,
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (selected) colors.onBrandContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoTasksHint() {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.padding(horizontal = AppSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconTile(
            icon = AppIcons.History,
            tint = colors.emptyStateIcon,
            background = colors.surfaceBase,
        )
        Spacer(Modifier.height(AppSpacing.Md))
        Text(
            text = "还没有历史任务",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.emptyStateText,
        )
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = "下达第一个任务后，它会归档到这里",
            style = MaterialTheme.typography.bodySmall,
            color = colors.emptyStateText,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun statusColorOf(status: TaskSession.Status): Color {
    val colors = AppTheme.colors
    return when (status) {
        TaskSession.Status.RUNNING -> colors.runningIndicator
        TaskSession.Status.DONE -> colors.success
        TaskSession.Status.ABORTED -> colors.onSurfaceRaised
        TaskSession.Status.FAILED -> colors.error
    }
}

private fun statusTextOf(status: TaskSession.Status): String = when (status) {
    TaskSession.Status.RUNNING -> "进行中"
    TaskSession.Status.DONE -> "已完成"
    TaskSession.Status.ABORTED -> "已停止"
    TaskSession.Status.FAILED -> "失败"
}

/** 规模摘要：步数沉底计数（明细被环形缓冲淘汰后仍能显示这次任务干了多少事） */
private fun metaOf(session: TaskSession): String {
    val parts = ArrayList<String>(2)
    if (session.steps > 0) parts += "${session.steps} 步"
    if (session.okSteps > 0) parts += "${session.okSteps} 步已生效"
    if (parts.isEmpty()) parts += "无执行步骤"
    return parts.joinToString(" · ")
}