package com.phoneagent.ui.debug

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.model.AgentLog
import com.phoneagent.model.AgentMetrics
import com.phoneagent.model.ConversationMessage
import com.phoneagent.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 语义化颜色常量
private val SuccessColor = Color(0xFF2E9E6B)
private val WarningColor = Color(0xFFE8A33D)

private enum class DebugTab(val label: String) {
    METRICS("指标"),
    CHAT("对话"),
    LOGS("日志"),
    HISTORY("历史"),
}

@Composable
fun DebugScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val metrics by vm.metrics.collectAsState()
    val conversation by vm.conversation.collectAsState()
    val logs by vm.logs.collectAsState()
    val history by vm.executionHistory.collectAsState()
    var tab by remember { mutableStateOf(DebugTab.METRICS) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("调试", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "运行状态、AI 对话与性能指标",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.clearDebug() }) {
                Icon(Icons.Rounded.Delete, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))
        SegmentedTabs(selected = tab, onSelect = { tab = it })

        Spacer(Modifier.height(16.dp))
        when (tab) {
            DebugTab.METRICS -> MetricsPanel(metrics)
            DebugTab.CHAT -> ChatPanel(conversation)
            DebugTab.LOGS -> LogPanel(logs)
            DebugTab.HISTORY -> HistoryPanel(history)
        }
    }
}

@Composable
private fun SegmentedTabs(selected: DebugTab, onSelect: (DebugTab) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            DebugTab.entries.forEach { t ->
                val isSelected = selected == t
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .padding(2.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            t.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onSelect(t) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsPanel(m: AgentMetrics) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("请求", "${m.requestCount}", "次", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                MetricCard("总 Tokens", "${m.totalTokens}", "", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("本轮耗时", "${m.lastLatencyMs}", "ms", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                MetricCard("平均耗时", "${m.avgLatencyMs}", "ms", SuccessColor, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("输入 Tokens", "${m.promptTokens}", "", WarningColor, Modifier.weight(1f))
                MetricCard("输出 Tokens", "${m.completionTokens}", "", MaterialTheme.colorScheme.error, Modifier.weight(1f))
            }
        }
        item {
            MetricCard("生成速度", String.format(Locale.US, "%.1f", m.tokensPerSec), "tok/s", MaterialTheme.colorScheme.tertiary, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, unit: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineMedium, color = accent, fontWeight = FontWeight.Bold)
                if (unit.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(messages: List<ConversationMessage>) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    if (messages.isEmpty()) {
        EmptyHint("暂无对话，运行智能体后显示")
        return
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(messages, key = { "${it.timestamp}:${it.role}" }) { msg ->
            ChatBubble(msg)
        }
    }
}

@Composable
private fun ChatBubble(msg: ConversationMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 18.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                msg.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            if (msg.hasImage) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("含屏幕截图", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<AgentLog>) {
    val listState = rememberLazyListState()
    var keyword by remember { mutableStateOf("") }
    var activeLevels by remember { mutableStateOf(AgentLog.Level.entries.toSet()) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    if (logs.isEmpty()) {
        EmptyHint("暂无日志")
        return
    }

    val filtered = logs.filter { entry ->
        val levelOk = entry.level in activeLevels
        val kw = keyword.trim()
        val kwOk = kw.isEmpty() ||
            entry.message.contains(kw, ignoreCase = true) ||
            entry.detail?.contains(kw, ignoreCase = true) == true
        levelOk && kwOk
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选栏：关键词 + 级别
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("搜索日志内容…") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AgentLog.Level.entries.forEach { lvl ->
                FilterChip(
                    selected = lvl in activeLevels,
                    onClick = {
                        activeLevels = if (lvl in activeLevels) activeLevels - lvl else activeLevels + lvl
                    },
                    label = { Text(levelLabel(lvl)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = levelColor(lvl).copy(alpha = 0.18f),
                        selectedLabelColor = levelColor(lvl),
                    ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "共 ${filtered.size} 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            EmptyHint("无匹配日志")
            return@Column
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { "${it.timestamp}:${it.message}" }) { entry ->
                LogRow(entry)
            }
        }
    }
}

private fun levelLabel(lvl: AgentLog.Level): String = when (lvl) {
    AgentLog.Level.ERROR -> "ERR"
    AgentLog.Level.WARN -> "WRN"
    AgentLog.Level.AI -> "AI"
    AgentLog.Level.INFO -> "INF"
    AgentLog.Level.API -> "API"
}

@Composable
private fun levelColor(lvl: AgentLog.Level): Color = when (lvl) {
    AgentLog.Level.ERROR -> MaterialTheme.colorScheme.error
    AgentLog.Level.WARN -> WarningColor
    AgentLog.Level.AI -> MaterialTheme.colorScheme.primary
    AgentLog.Level.INFO -> MaterialTheme.colorScheme.secondary
    AgentLog.Level.API -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun LogRow(entry: AgentLog) {
    val color = levelColor(entry.level)
    val tag = levelLabel(entry.level)
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = entry.detail?.isNotBlank() == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = color.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                formatTime(entry.timestamp),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = color,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { expanded = !expanded },
                )
            }
        }
        if (expanded && hasDetail) {
            Spacer(Modifier.height(8.dp))
            Text(
                entry.detail.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun HistoryPanel(history: List<com.phoneagent.model.StepRecord>) {
    if (history.isEmpty()) {
        EmptyHint("暂无执行历史")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(history, key = { "${it.step}:${it.action?.type}" }) { rec ->
            val color = when (rec.verificationResult) {
                "verified_success" -> SuccessColor
                "failed" -> MaterialTheme.colorScheme.error
                else -> WarningColor
            }
            val status = when (rec.verificationResult) {
                "verified_success" -> "已确认"
                "failed" -> "失败"
                else -> "未确认"
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("步骤 ${rec.step}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(status, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        rec.action?.let { "${it.type}${it.reason?.let { r -> " · $r" } ?: ""}" } ?: "-",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (rec.beforeFingerprint.isNotBlank() && rec.afterFingerprint.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "指纹 ${rec.beforeFingerprint.take(8)} → ${rec.afterFingerprint.take(8)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(t: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))