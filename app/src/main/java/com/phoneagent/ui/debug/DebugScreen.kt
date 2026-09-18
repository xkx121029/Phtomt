package com.phoneagent.ui.debug

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
import com.phoneagent.ui.debug.panels.ChatPanel
import com.phoneagent.ui.debug.panels.HistoryPanel
import com.phoneagent.ui.debug.panels.LogPanel
import com.phoneagent.ui.debug.panels.MetricsPanel
import com.phoneagent.ui.debug.panels.StepShotPanel
import com.phoneagent.ui.debug.panels.StepsPanel
import com.phoneagent.ui.debug.panels.TimelinePanel
import com.phoneagent.ui.debug.panels.drawBoxes
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

internal enum class DebugTab(val label: String) {
    STEPS("任务"),
    TIMELINE("时间线"),
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
    val traces by vm.traces.collectAsState()
    val permissions by vm.permissions.collectAsState()
    var tab by remember { mutableStateOf(DebugTab.STEPS) }
    /** 人话 / 原始 展示模式（v2.2.1）：人话模式在步骤卡顶部显示翻译摘要；原始模式显示完整技术数据 */
    var humanMode by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 进入调试页时刷新能力/权限状态，保证「能力状态条」准确
    LaunchedEffect(Unit) { runCatching { vm.refreshPermissions(context) } }

    // 用外挂视觉对本次任务所有截图画框的结果（step → 框选图）
    var annotatedMap by remember { mutableStateOf<Map<Int, android.graphics.Bitmap>>(emptyMap()) }
    var annotating by remember { mutableStateOf(false) }
    var annotateMsg by remember { mutableStateOf<String?>(null) }

    fun runExternalAnnotate() {
        scope.launch {
            annotating = true
            annotateMsg = null
            val out = mutableMapOf<Int, android.graphics.Bitmap>()
            val tasks = traces.filter { it.taskId >= 0 }
            var count = 0
            tasks.forEach { t ->
                val shot = t.screenshot ?: return@forEach
                val controls = com.phoneagent.device.vision.ExternalVisionProvider.detectControls(context, shot, 20_000)
                if (controls.isNotEmpty()) count++
                out[t.step] = drawBoxes(shot, controls)
            }
            annotatedMap = out
            annotating = false
            val connected = com.phoneagent.device.vision.ExternalVisionProvider.isConnected
            annotateMsg = if (tasks.isEmpty()) "本任务暂无可画框的截图"
            else "已用${if (connected) "端侧3B" else "本地OCR"}对 ${tasks.size} 张截图画框（含控件 ${count} 张）"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        AppTopBar(
            title = "调试",
            subtitle = "按任务的每步决策 · 发送/返回 · Token · 视觉与截图",
            trailingContent = {
                IconButton(onClick = {
                    android.widget.Toast.makeText(context, vm.exportDiagnosticReport(context), android.widget.Toast.LENGTH_LONG).show()
                }) {
                    Icon(AppIcons.Description, contentDescription = "导出诊断报告(人话+原始)", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    android.widget.Toast.makeText(context, vm.exportLogsJsonAll(context), android.widget.Toast.LENGTH_LONG).show()
                }) {
                    Icon(AppIcons.FileDownload, contentDescription = "导出JSON(分任务)", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.clearDebug() }) {
                    Icon(AppIcons.Delete, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )

        Spacer(Modifier.height(8.dp))
        CapabilityStrip(permissions)
        Spacer(Modifier.height(8.dp))
        StepShotPanel(vm.stepShot.collectAsState().value)
        // 用外挂视觉对本次任务所有截图画框
        Surface(
            shape = RoundedCornerShape(AppRadii.Chip),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(AppIcons.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("外挂视觉画框", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        annotateMsg ?: "对本次任务所有截图，用本地 3B 视觉一键画框（类型+用途+坐标）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                androidx.compose.material3.TextButton(onClick = { runExternalAnnotate() }, enabled = !annotating) {
                    Text(if (annotating) "画框中…" else "一键画框")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 人话 / 原始 双语展示切换（v2.2.1）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(true to "人话", false to "原始").forEach { (human, label) ->
                FilterChip(
                    selected = humanMode == human,
                    onClick = { humanMode = human },
                    label = {
                        Text("$label${if (human) "（每步一句）" else "（完整数据）"}")
                    },
                    leadingIcon = if (human) null else {
                        {
                            Icon(AppIcons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SegmentedTabs(selected = tab, onSelect = { tab = it })

        Spacer(Modifier.height(16.dp))
        when (tab) {
            DebugTab.STEPS -> StepsPanel(traces, annotatedMap, humanMode)
            DebugTab.TIMELINE -> TimelinePanel(traces, logs)
            DebugTab.METRICS -> MetricsPanel(metrics)
            DebugTab.CHAT -> ChatPanel(conversation)
            DebugTab.LOGS -> LogPanel(logs)
            DebugTab.HISTORY -> HistoryPanel(history)
        }
    }
}