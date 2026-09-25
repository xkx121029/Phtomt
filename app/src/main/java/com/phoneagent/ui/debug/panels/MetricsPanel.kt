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
import com.phoneagent.ui.components.StatTile
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun MetricsPanel(m: AgentMetrics) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("请求", "${m.requestCount}", MaterialTheme.colorScheme.primary, Modifier.weight(1f), unit = "次")
                StatTile("总 Tokens", "${m.totalTokens}", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("本轮耗时", "${m.lastLatencyMs}", MaterialTheme.colorScheme.secondary, Modifier.weight(1f), unit = "ms")
                StatTile("平均耗时", "${m.avgLatencyMs}", Success, Modifier.weight(1f), unit = "ms")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("输入 Tokens", "${m.promptTokens}", Warning, Modifier.weight(1f))
                StatTile("输出 Tokens", "${m.completionTokens}", MaterialTheme.colorScheme.error, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("视觉识别", "${m.visionCount}次/均${m.avgVisionMs}ms", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                StatTile("动作执行", "${m.execCount}次/均${m.avgExecMs}ms", Success, Modifier.weight(1f))
            }
        }
        item {
            MethodCard(m)
        }
        item {
            StatTile("生成速度", String.format(Locale.US, "%.1f", m.tokensPerSec), MaterialTheme.colorScheme.tertiary, Modifier.fillMaxWidth(), unit = "tok/s")
        }
    }
}

@Composable
private fun MethodCard(m: AgentMetrics) {
    // 各环节耗时占比（决策 + 视觉 + 执行），P2 观测性埋点聚合展示
    val total = m.avgLatencyMs + m.avgVisionMs + m.avgExecMs
    if (total <= 0) return
    // 某一环节耗时为 0 时权重不能直接传 0（Row 的 weight 要求大于 0），统一给一个极小值兜底
    val decisionFrac = (m.avgLatencyMs.toFloat() / total).coerceAtLeast(0.001f)
    val visionFrac = (m.avgVisionMs.toFloat() / total).coerceAtLeast(0.001f)
    val execFrac = (m.avgExecMs.toFloat() / total).coerceAtLeast(0.001f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("单步耗时构成（决策/视觉/执行）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            // 横向占比条
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(AppRadii.Chip))) {
                Box(Modifier.weight(decisionFrac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                Box(Modifier.weight(visionFrac).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
                Box(Modifier.weight(execFrac).fillMaxHeight().background(Success))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(MaterialTheme.colorScheme.primary, "决策 ${m.avgLatencyMs}ms")
                LegendDot(MaterialTheme.colorScheme.secondary, "视觉 ${m.avgVisionMs}ms")
                LegendDot(Success, "执行 ${m.avgExecMs}ms")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}