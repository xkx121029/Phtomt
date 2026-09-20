package com.phoneagent.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.StatTile
import com.phoneagent.ui.theme.MemoryAnomaly
import com.phoneagent.ui.theme.MemoryProfile
import com.phoneagent.ui.theme.MemoryRoot

/** 统计概览 */
@Composable
internal fun StatsRow(taskCount: Int, anomalyCount: Int, profileCount: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        StatTile(
            title = "任务记忆",
            value = taskCount.toString(),
            accent = MemoryRoot,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            title = "异常经验",
            value = anomalyCount.toString(),
            accent = MemoryAnomaly,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            title = "用户画像",
            value = profileCount.toString(),
            accent = MemoryProfile,
            modifier = Modifier.weight(1f),
        )
    }
}