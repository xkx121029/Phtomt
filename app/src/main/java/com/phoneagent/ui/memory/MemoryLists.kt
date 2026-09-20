package com.phoneagent.ui.memory

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionCard
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.skeleton
import com.phoneagent.ui.theme.Accent
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.MemoryAnomaly
import com.phoneagent.ui.theme.MemoryAnomalySoft
import com.phoneagent.ui.theme.MemoryProfile
import com.phoneagent.ui.theme.MemoryProfileSoft
import com.phoneagent.ui.theme.MemoryRoot
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.phoneagent.ui.icons.AppIcons

/** 空记忆态 */
@Composable
internal fun EmptyMemoryCard(onRefresh: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                AppIcons.Memory, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("暂无积累的记忆", style = MaterialTheme.typography.titleMedium)
            Text(
                "Agent 完成任务、遇到异常时会沉淀记忆，形成图谱",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(AppRadii.Tile)) {
                Text("刷新")
            }
        }
    }
}

/** 异常记忆明细 */
@Composable
internal fun AnomalyList(anomalies: List<AnomalyMemoryEntry>, onClear: () -> Unit) {
    SectionCard(
        title = "异常经验",
        count = anomalies.size,
        countColor = MemoryAnomaly,
        onClear = onClear,
    ) {
        if (anomalies.isEmpty()) {
            Text("暂无异常记忆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        anomalies.forEach { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MemoryAnomaly),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.anomalyType, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "${a.anomalyDescription.take(40)} · 命中 ${a.hitCount} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 用户画像明细 */
@Composable
internal fun ProfileList(profiles: List<ProfileEntry>, onClear: () -> Unit) {
    SectionCard(
        title = "用户画像",
        count = profiles.size,
        countColor = MemoryProfile,
        onClear = onClear,
    ) {
        if (profiles.isEmpty()) {
            Text("暂无画像记忆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        profiles.forEach { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Icon(
                    AppIcons.Person, contentDescription = null,
                    tint = MemoryProfile, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "${p.value} · 置信 ${(p.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 任务记忆状态徽标配色：进行中用品牌色，终态按结果区分 */
@Composable
private fun taskMemoryStatusColor(status: String): Color = when (status) {
    TaskMemoryEntry.STATUS_SUCCESS -> Success
    TaskMemoryEntry.STATUS_FAILED -> MaterialTheme.colorScheme.error
    TaskMemoryEntry.STATUS_ABORTED -> Warning
    else -> Accent
}

/** 任务记忆明细：按任务展示目标 / 用户要求 / 已验证做法 */
@Composable
internal fun TaskMemoryList(
    items: List<TaskMemoryEntry>,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        title = "任务记忆",
        count = items.size,
        countColor = MemoryRoot,
        onClear = onClear,
    ) {
        if (items.isEmpty()) {
            Text("暂无任务记忆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.forEach { m ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(AppRadii.Tile))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppRadii.Tile))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.taskName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(text = m.statusLabel(), color = taskMemoryStatusColor(m.status))
                    PressableScale(onClick = { onDelete(m.id) }) {
                        Icon(
                            AppIcons.DeleteOutline, contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(6.dp).size(16.dp),
                        )
                    }
                }
                if (m.completedSteps > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "已验证 ${m.completedSteps} 步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TaskMemorySection("目标", listOf(m.goal))
                if (m.requirements.isNotEmpty()) TaskMemorySection("用户要求", m.requirements)
                if (m.methods.isNotEmpty()) TaskMemorySection("完成方法", m.methods)
            }
        }
    }
}

/** 任务记忆的一个小节：标题 + 逐条内容。每条限 2 行，避免一条长要求把整张卡撑满 */
@Composable
private fun TaskMemorySection(label: String, lines: List<String>) {
    Spacer(Modifier.height(8.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
    lines.forEachIndexed { i, line ->
        Text(
            if (lines.size > 1) "${i + 1}. $line" else line,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}