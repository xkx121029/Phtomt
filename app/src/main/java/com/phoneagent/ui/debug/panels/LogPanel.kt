package com.phoneagent.ui.debug.panels

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.formatClock
import com.phoneagent.ui.debug.DebugEmptyHint
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

@Composable
internal fun LogPanel(logs: List<AgentLog>) {
    val listState = rememberLazyListState()
    var keyword by remember { mutableStateOf("") }
    var activeLevels by remember { mutableStateOf(AgentLog.Level.entries.toSet()) }

    if (logs.isEmpty()) {
        DebugEmptyHint("暂无日志：运行智能体后，这里会按级别滚动记录")
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

    // 自动跟随到底部：按「筛选后」的条数定位，原来用 logs.size 会在筛选状态下滚到越界位置
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选栏：关键词 + 级别
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            singleLine = true,
            shape = RoundedCornerShape(AppRadii.Inline),
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            placeholder = { Text("搜索日志内容…") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        // 五个级别标签在窄屏上会挤在一起，允许横向滚动
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
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
        Spacer(Modifier.height(AppSpacing.Md))
        Text(
            "共 ${filtered.size} 条" + if (filtered.size != logs.size) "（全部 ${logs.size} 条）" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        if (filtered.isEmpty()) {
            DebugEmptyHint("无匹配日志", Modifier.fillMaxWidth().heightIn(min = 160.dp))
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // timestamp+message 可能完全一致（重试/环形回看），叠加 index 保证 key 唯一，避免滚动时 key 冲突闪退
            itemsIndexed(filtered, key = { i, e -> "${e.timestamp}:${e.level}:${e.message}:$i" }) { _, entry ->
                LogRow(entry)
            }
        }
    }
}

private fun levelLabel(lvl: AgentLog.Level): String = when (lvl) {
    AgentLog.Level.ERROR -> "错误"
    AgentLog.Level.WARN -> "警告"
    AgentLog.Level.AI -> "AI"
    AgentLog.Level.INFO -> "信息"
    AgentLog.Level.API -> "接口"
}

/** 日志行左侧的级别缩写（列表内空间有限，保留三字母短标签） */
private fun levelTag(lvl: AgentLog.Level): String = when (lvl) {
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
    // 行内仍用三字母级别缩写（中文标签太长会把消息挤到换行），筛选条上用中文
    val tag = levelTag(entry.level)
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
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
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