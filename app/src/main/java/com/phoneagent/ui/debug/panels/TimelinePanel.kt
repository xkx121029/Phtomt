package com.phoneagent.ui.debug.panels

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.model.AgentLog
import com.phoneagent.model.AgentMetrics
import com.phoneagent.model.ConversationMessage
import com.phoneagent.debug.HumanTranslator
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.debug.DebugEmptyHint
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// ========== 时间线：任务过程可视化（v2.2.1 第四章） ==========

/** 任务时间线主视图：执行摘要 + 按任务的叙事步骤卡 */
@Composable
internal fun TimelinePanel(
    traces: List<com.phoneagent.model.StepTrace>,
    logs: List<AgentLog>,
) {
    if (traces.isEmpty()) {
        DebugEmptyHint("暂无任务时间线：运行智能体后，这里会把每一步翻译成「看到→决定→做了→结果」")
        return
    }
    val issueCount = logs.count { it.level == AgentLog.Level.ERROR || it.level == AgentLog.Level.WARN }
    val lowConfSteps = traces.count {
        (HumanTranslator.extractConfidence(it.receivedText) ?: 1.0) < 0.6
    }
    val totalLatency = traces.sumOf { it.latencyMs }
    val groups = traces.groupBy { it.taskId }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "tls") { ExecutionSummaryCard(groups.size, traces.size, totalLatency, issueCount, lowConfSteps) }
        groups.keys.sortedByDescending { it }.forEach { tid ->
            val list = groups.getValue(tid).sortedBy { it.step }
            val name = list.first().taskName ?: "任务 #$tid"
            item(key = "tlhdr$tid") { TaskHeader(name, list.size) }
            items(list, key = { "tl$tid:${it.step}" }) { tr ->
                TimelineNarrativeCard(tr, logs.filter { it.taskId == tid })
            }
        }
    }
}

/** 执行摘要卡片（v2.2.1 4.7）：任务完成后的一页概览 */
@Composable
private fun ExecutionSummaryCard(taskCount: Int, stepCount: Int, totalLatencyMs: Long, issueCount: Int, lowConfSteps: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("📊 本次执行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("· 共 $taskCount 个任务 · $stepCount 步 · 合计 ${totalLatencyMs}ms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· 遇到问题 $issueCount 处 · 低把握步 $lowConfSteps 步", style = MaterialTheme.typography.bodyMedium,
                color = if (issueCount > 0 || lowConfSteps > 0) Warning else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 单步叙事卡：看到 → 决定 → 在做(第一人称) → 结果（四段式） */
@Composable
private fun TimelineNarrativeCard(tr: com.phoneagent.model.StepTrace, taskLogs: List<AgentLog>) {
    var expanded by remember { mutableStateOf(false) }
    val human = remember(tr.receivedText) { HumanTranslator.summarizeDecision(tr.receivedText) }
    val seen = remember(tr.sentText) { HumanTranslator.extractSeen(tr.sentText) }
    val thinking = remember(tr.receivedText) { HumanTranslator.extractReasoning(tr.receivedText) }
    val conf = remember(tr.receivedText) { HumanTranslator.extractConfidence(tr.receivedText) }
    val issues = taskLogs
        .filter { it.level == AgentLog.Level.ERROR || it.level == AgentLog.Level.WARN }
        .mapNotNull { HumanTranslator.translateError(it.message).takeIf { x -> x.isNotEmpty() && x != it.message } ?: it.message }
        .distinct()
        .take(2)
    val lowConf = conf != null && conf < 0.6
    val borderColor = when {
        lowConf -> Warning
        issues.isNotEmpty() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 头部：第 N 步 · 视觉 · 思考 · 耗时
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${tr.step} 步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${tr.latencyMs}ms · ${tr.totalTokens}token", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tr.thinking) {
                    Spacer(Modifier.width(6.dp))
                    Text("思考", color = Warning, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (lowConf) {
                Spacer(Modifier.height(6.dp))
                Text("⚠️ 这一步 AI 把握较低，可能容易出错", style = MaterialTheme.typography.labelMedium, color = Warning)
            }
            Spacer(Modifier.height(10.dp))
            NarrativeRow("AI 看到了", seen)
            if (human.isNotBlank()) NarrativeRow("AI 决定", human, accent = MaterialTheme.colorScheme.primary)
            if (!thinking.isNullOrBlank()) NarrativeRow("💭 AI 在想", "「$thinking」", accent = MaterialTheme.colorScheme.tertiary)
            if (issues.isNotEmpty()) {
                NarrativeRow("遇到的麻烦", issues.joinToString("；"), accent = MaterialTheme.colorScheme.error)
            } else {
                NarrativeRow("结果", "已执行（详情见「任务」页原始数据）", accent = Success)
            }
            conf?.let { c ->
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(c.toFloat())
                            .height(5.dp)
                            .clip(RoundedCornerShape(AppRadii.Chip))
                            .background(if (c >= 0.75) Success else if (c >= 0.6) Warning else MaterialTheme.colorScheme.error),
                    )
                }
            }
            // 展开原始信息
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("原始信息", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable { expanded = !expanded },
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(tr.receivedText, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun NarrativeRow(label: String, text: String, accent: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("$label：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 90.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = accent, modifier = Modifier.weight(1f))
    }
}