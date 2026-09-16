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
import com.phoneagent.ui.components.formatClock
import com.phoneagent.ui.debug.DebugEmptyHint
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun LogPanel(logs: List<AgentLog>) {
    val listState = rememberLazyListState()
    var keyword by remember { mutableStateOf("") }
    var activeLevels by remember { mutableStateOf(AgentLog.Level.entries.toSet()) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    if (logs.isEmpty()) {
        DebugEmptyHint("暂无日志")
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
            DebugEmptyHint("无匹配日志")
            return@Column
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // timestamp+message 可能完全一致（重试/环形回看），叠加 index 保证 key 唯一，避免滚动时 key 冲突闪退
        itemsIndexed(filtered, key = { i, e -> "${e.timestamp}:${e.level}:${e.message}:$i" }) { _, entry ->
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
    AgentLog.Level.WARN -> Warning
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
                shape = RoundedCornerShape(AppRadii.Inline),
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
                formatClock(entry.timestamp),
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
                    .background(color.copy(alpha = 0.06f), RoundedCornerShape(AppRadii.Chip))
                    .padding(10.dp),
            )
        }
    }
}