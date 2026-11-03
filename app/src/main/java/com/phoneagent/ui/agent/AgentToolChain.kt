package com.phoneagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.SkillCard
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.ToolChip
import com.phoneagent.ui.components.ToolIconStrip
import com.phoneagent.ui.components.agentToolIdentity
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppTheme

/**
 * 连续若干步工具调用：聊天流里折叠成一条「调用了 N 个工具」的回显行。
 * 折叠态一眼看到"调用了几个工具、分别是什么"（图标连排 + 工具名），
 * 点开才铺开每一步的细节，任务再长也不会把对话刷成一片卡片。
 */
@Composable
internal fun ToolChainItem(
    item: AgentTimelineItem.ToolChain,
    vm: MainViewModel,
) {
    val colors = AppTheme.colors
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }

    val failedCount = item.steps.count { it.failed }
    val okCount = item.steps.count { it.verified }

    SkillCard(
        title = chainTitle(item.steps),
        subtitle = chainSubtitle(item.steps),
        expandable = true,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        leading = {
            ToolIconStrip(
                chips = item.steps.map {
                    val identity = agentToolIdentity(it.toolType)
                    ToolChip(identity.icon, identity.label, it.failed)
                },
            )
        },
        trailing = {
            if (failedCount > 0) {
                StatusPill(text = "$failedCount 步未生效", color = colors.error)
            } else if (okCount == item.steps.size && okCount > 0) {
                Icon(
                    imageVector = AppIcons.CheckCircle,
                    contentDescription = "全部生效",
                    tint = colors.success,
                    modifier = Modifier.size(15.dp),
                )
            }
        },
    ) {
        Column {
            item.steps.forEachIndexed { index, step ->
                if (index > 0) ChainDivider()
                ToolStepRow(step = step, vm = vm)
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
    return "第 ${first.step} 步 · ${agentToolIdentity(first.toolType).label}"
}

/** 折叠行的副文案：单步给这步做了什么，多步给这串工具的顺序 */
private fun chainSubtitle(steps: List<StepCall>): String = if (steps.size == 1) {
    steps.first().human.ifBlank { "本步没有留下动作说明" }
} else {
    steps.joinToString("、") { agentToolIdentity(it.toolType).label }
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