package com.phoneagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/**
 * 固定顶栏：标题 / 副标题 + 任务与记忆两个入口。
 *
 * 这里**刻意只留两个入口**：
 * - 运行状态由副标题「正在执行：…」+ 底部常驻状态条承担，顶栏不再挂一枚「运行中」胶囊
 *   （同一件事说三遍，且胶囊在窄屏上会把副标题挤掉）；
 * - 待执行队列的明细入口在输入区上方的队列细条上（就近且只在真有排队时才出现），
 *   顶栏不再另设一个 overflow 菜单装同一份内容。
 * 悬浮窗是可选能力，不在顶栏做权限引导（改由设置页开关控制）；
 * 审核 AI 开关放在底部输入区（与输入行为就近），此处也不重复。
 */
@Composable
internal fun AgentHeaderBar(
    running: Boolean,
    runningTask: String,
    modifier: Modifier = Modifier,
    /** 归档的任务会话数，作为「任务」按钮上的角标（0 不显示角标） */
    taskCount: Int = 0,
    /** 打开任务侧边栏（历史任务列表 + 新建任务入口） */
    onOpenTasks: () -> Unit = {},
    /** 打开全屏记忆页（原底部「记忆」Tab 已并入 Agent 页） */
    onOpenMemory: () -> Unit = {},
) {
    val colors = AppTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        AppTopBar(
            title = "Agent",
            subtitle = if (running && runningTask.isNotBlank()) {
                "正在执行：$runningTask"
            } else {
                "描述任务，AI 将逐步接管手机"
            },
            // 顶栏是一块有外边距的浮动玻璃卡片，页头留白在这里扣掉那层边距，
            // 标题才会与其他页面一样落在距屏幕 20dp 的竖直线上
            contentPadding = PaddingValues(horizontal = AppSpacing.Sm, vertical = AppSpacing.Sm),
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PressableScale(onClick = onOpenTasks) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                imageVector = AppIcons.History,
                                contentDescription = "任务列表",
                                tint = colors.onSurfaceRaised,
                                modifier = Modifier.size(20.dp),
                            )
                            // 有历史任务才带角标：空的时候一个点都没有，不制造无意义的装饰
                            if (taskCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = 6.dp, y = (-4).dp)
                                        .clip(RoundedCornerShape(AppRadii.Chip))
                                        .background(colors.brand)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = if (taskCount > 9) "9+" else "$taskCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onBrand,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(AppSpacing.Md))
                    PressableScale(onClick = onOpenMemory) {
                        Icon(
                            imageVector = AppIcons.Memory,
                            contentDescription = "记忆",
                            tint = colors.onSurfaceRaised,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )
    }
}
