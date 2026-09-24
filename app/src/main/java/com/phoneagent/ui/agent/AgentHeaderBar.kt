package com.phoneagent.ui.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/**
 * 固定顶栏：标题 / 副标题 + 记忆与任务抽屉两个入口。
 *
 * 这里**刻意只留两个入口**：
 * - 运行状态由副标题「正在执行：…」+ 底部常驻状态条承担，顶栏不再挂一枚「运行中」胶囊
 *   （同一件事说三遍，且胶囊在窄屏上会把副标题挤掉）；
 * - 待执行队列的明细入口在输入区上方的队列细条上（就近且只在真有排队时才出现），
 *   顶栏不再另设一个 overflow 菜单装同一份内容。
 * 悬浮窗是可选能力，不在顶栏做权限引导（改由设置页开关控制）；
 * 审核 AI 开关放在底部输入区（与输入行为就近），此处也不重复。
 *
 * 任务入口用三横线而不是历史图标：它打开的是从左侧拉出的抽屉，"三横线 = 拉出侧栏"
 * 是全局通行的约定，比一枚只能表示"看过什么"的时钟图标更能说明点下去会发生什么。
 * 也不再挂任务数角标——数量本身不构成要不要打开抽屉的理由，反而是顶栏里唯一
 * 会随任务增减而跳动的东西。
 */
@Composable
internal fun AgentHeaderBar(
    running: Boolean,
    runningTask: String,
    modifier: Modifier = Modifier,
    /** 打开任务抽屉（历史任务列表 + 新建任务入口） */
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
                    PressableScale(onClick = onOpenMemory) {
                        Icon(
                            imageVector = AppIcons.Memory,
                            contentDescription = "记忆",
                            tint = colors.onSurfaceRaised,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.Md))
                    PressableScale(onClick = onOpenTasks) {
                        Icon(
                            imageVector = AppIcons.Menu,
                            contentDescription = "任务列表",
                            tint = colors.onSurfaceRaised,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )
    }
}
