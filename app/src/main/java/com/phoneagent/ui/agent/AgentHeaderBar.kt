package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.runningIndicatorColor

/**
 * 固定顶栏：标题 / 副标题 + 运行状态 + overflow。
 *
 * overflow 用**内嵌浮层**（不是 Popup / Dialog）：展示待执行队列。
 * 悬浮窗是可选能力，不在顶栏做权限引导（改由设置页开关控制）；
 * 审核 AI 开关放在底部输入区（与输入行为就近），此处不再重复。
 */
@Composable
internal fun AgentHeaderBar(
    running: Boolean,
    runningTask: String,
    queue: List<String>,
    modifier: Modifier = Modifier,
    /** 打开全屏记忆页（原底部「记忆」Tab 已并入 Agent 页） */
    onOpenMemory: () -> Unit = {},
) {
    val colors = AppTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        AppTopBar(
            title = "Agent",
            subtitle = if (running && runningTask.isNotBlank()) {
                "正在执行：$runningTask"
            } else {
                "描述任务，AI 将逐步接管手机"
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        StatusPill(text = "运行中", color = runningIndicatorColor())
                        Spacer(Modifier.width(AppSpacing.Sm))
                    }
                    PressableScale(onClick = onOpenMemory) {
                        Icon(
                            imageVector = AppIcons.Memory,
                            contentDescription = "记忆",
                            tint = colors.onSurfaceRaised,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.Md))
                    PressableScale(onClick = { menuOpen = !menuOpen }) {
                        Icon(
                            imageVector = AppIcons.More,
                            contentDescription = if (menuOpen) "收起更多设置" else "更多设置",
                            tint = colors.onSurfaceRaised,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )

        AnimatedVisibility(
            visible = menuOpen,
            enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = AppSpacing.Lg)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadii.Card))
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Card))
                    .padding(AppSpacing.Lg),
            ) {
                Text(
                    text = "待执行队列（${queue.size}）",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(AppSpacing.Sm))
                if (queue.isEmpty()) {
                    Text(
                        text = "暂无排队任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceRaised,
                    )
                } else {
                    queue.forEachIndexed { index, text ->
                        Text(
                            text = "${index + 1}. $text",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceRaised,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}