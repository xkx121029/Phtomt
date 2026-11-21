package com.phoneagent.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.phoneagent.ui.components.StatTile
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.MemoryAnomaly
import com.phoneagent.ui.theme.MemoryProfile
import com.phoneagent.ui.theme.MemoryRoot

/**
 * 统计概览：只给有内容的分类留位置。
 *
 * 0 条的分类在下面的分区卡片里已经写明「暂无…」，统计格再摆一个 0 只是重复的空壳；
 * 三类都为空时整行不出现（记忆页此时显示的是空态卡）。
 */
@Composable
internal fun StatsRow(taskCount: Int, anomalyCount: Int, profileCount: Int) {
    val tiles = listOf(
        Triple("任务记忆", taskCount, MemoryRoot),
        Triple("异常经验", anomalyCount, MemoryAnomaly),
        Triple("用户画像", profileCount, MemoryProfile),
    ).filter { it.second > 0 }
    if (tiles.isEmpty()) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        tiles.forEach { (title: String, value: Int, accent: Color) ->
            StatTile(
                title = title,
                value = value.toString(),
                accent = accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
