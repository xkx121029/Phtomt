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
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private enum class DebugTab(val label: String) {
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
                val controls = com.phoneagent.vision.ExternalVisionProvider.detectControls(context, shot, 20_000)
                if (controls.isNotEmpty()) count++
                out[t.step] = drawBoxes(shot, controls)
            }
            annotatedMap = out
            annotating = false
            val connected = com.phoneagent.vision.ExternalVisionProvider.isConnected
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
                    Icon(Icons.Rounded.Description, contentDescription = "导出诊断报告(人话+原始)", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    android.widget.Toast.makeText(context, vm.exportLogsJsonAll(context), android.widget.Toast.LENGTH_LONG).show()
                }) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = "导出JSON(分任务)", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.clearDebug() }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Icon(Icons.Rounded.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
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
                            Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
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

@Composable
private fun SegmentedTabs(selected: DebugTab, onSelect: (DebugTab) -> Unit) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Hero),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            DebugTab.entries.forEach { t ->
                val isSelected = selected == t
                Surface(
                    shape = RoundedCornerShape(AppRadii.Item),
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
private fun StepShotPanel(shot: com.phoneagent.model.StepShot) {
    if (shot.step == 0 && shot.screenshot == null) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "最新一步 · 步骤 ${shot.step}${if (shot.verified) " · 已确认 ✅" else " · 待确认"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (shot.screenshot != null && shot.annotatedScreenshot != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "← 原图 / 识别图 →（左右拖动对比）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                DragCompare(shot.screenshot!!, shot.annotatedScreenshot!!)
            } else if (shot.screenshot != null) {
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = shot.screenshot!!.asImageBitmap(),
                    contentDescription = "原截图",
                    modifier = Modifier
                        .width(160.dp)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(AppRadii.Chip)),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                shot.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 能力状态条（v2.2.1 面板一）：无障碍/悬浮窗/截屏/自启动/Shizuku 一键灰度查看 */
@Composable
private fun CapabilityStrip(permissions: List<com.phoneagent.model.PermissionItem>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        permissions.forEach { p ->
            val ok = p.granted
            Surface(
                shape = RoundedCornerShape(AppRadii.Chip),
                color = if (ok) Success.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        p.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ok) Success else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DragCompare(bmpA: android.graphics.Bitmap, bmpB: android.graphics.Bitmap) {
    var frac by remember { mutableStateOf(0.5f) }
    val aspect = if (bmpA.height > 0) bmpA.width.toFloat() / bmpA.height.toFloat() else 1f
    val imgA = bmpA.asImageBitmap()
    val imgB = bmpB.asImageBitmap()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val w = this.size.width.toFloat()
                    if (w > 0) frac = (frac + dragAmount / w).coerceIn(0f, 1f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.toInt()
            val h = size.height.toInt()
            val cutW = (frac * w).toInt().coerceAtLeast(1)
            // 左区：bmpA 左侧 frac 区域（保持原图比例，不拉伸）
            drawImage(
                image = imgA,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize((imgA.width * frac).toInt().coerceAtLeast(1), imgA.height),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(cutW, h),
            )
            // 右区：bmpB 右侧 1-frac 区域
            val srcLeft = (imgB.width * frac).toInt().coerceIn(0, (imgB.width - 1).coerceAtLeast(0))
            drawImage(
                image = imgB,
                srcOffset = IntOffset(srcLeft, 0),
                srcSize = IntSize((imgB.width - srcLeft).coerceAtLeast(1), imgB.height),
                dstOffset = IntOffset(cutW, 0),
                dstSize = IntSize((w - cutW).coerceAtLeast(1), h),
            )
            val cut = frac * size.width
            drawLine(Color.White, Offset(cut, 0f), Offset(cut, size.height), strokeWidth = 3f)
            drawCircle(Color.White, radius = 10f, center = Offset(cut, size.height / 2f))
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
                MetricCard("平均耗时", "${m.avgLatencyMs}", "ms", Success, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("输入 Tokens", "${m.promptTokens}", "", Warning, Modifier.weight(1f))
                MetricCard("输出 Tokens", "${m.completionTokens}", "", MaterialTheme.colorScheme.error, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("视觉识别", "${m.visionCount}次/均${m.avgVisionMs}ms", "", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                MetricCard("动作执行", "${m.execCount}次/均${m.avgExecMs}ms", "", Success, Modifier.weight(1f))
            }
        }
        item {
            MethodCard(m)
        }
        item {
            MetricCard("生成速度", String.format(Locale.US, "%.1f", m.tokensPerSec), "tok/s", MaterialTheme.colorScheme.tertiary, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MethodCard(m: AgentMetrics) {
    // 各环节耗时占比（决策 + 视觉 + 执行），P2 观测性埋点聚合展示
    val total = m.avgLatencyMs + m.avgVisionMs + m.avgExecMs
    if (total <= 0) return
    val decisionFrac = m.avgLatencyMs.toFloat() / total
    val visionFrac = m.avgVisionMs.toFloat() / total
    val execFrac = m.avgExecMs.toFloat() / total
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("单步耗时构成（决策/视觉/执行）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            // 横向占比条
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
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

@Composable
private fun MetricCard(title: String, value: String, unit: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(AppRadii.Item),
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
                        topStart = AppRadii.Bubble,
                        topEnd = AppRadii.Bubble,
                        bottomStart = if (isUser) AppRadii.Bubble else AppRadii.Chip,
                        bottomEnd = if (isUser) AppRadii.Chip else AppRadii.Bubble,
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
                    .background(color.copy(alpha = 0.06f), RoundedCornerShape(AppRadii.Chip))
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
                "verified_success" -> Success
                "failed" -> MaterialTheme.colorScheme.error
                else -> Warning
            }
            val status = when (rec.verificationResult) {
                "verified_success" -> "已确认"
                "failed" -> "失败"
                else -> "未确认"
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadii.Item),
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

/** 「任务」页：按任务分组展示每一步决策的发送/返回/Token/延迟/视觉/思考/截图 */
@Composable
private fun StepsPanel(
    traces: List<com.phoneagent.model.StepTrace>,
    annotatedMap: Map<Int, android.graphics.Bitmap>,
    humanMode: Boolean,
) {
    if (traces.isEmpty()) {
        EmptyHint("暂无任务步骤：运行智能体后，每个决策步骤都会记录在这里")
        return
    }
    val groups = traces.groupBy { it.taskId }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.keys.sortedByDescending { it }.forEach { tid ->
            val list = groups.getValue(tid).sortedBy { it.step }
            val name = list.first().taskName ?: "任务 #$tid"
            item(key = "hdr$tid") { TaskHeader(name, list.size) }
            items(list, key = { "$tid:${it.step}" }) { tr ->
                StepTraceCard(tr, annotatedMap[tr.step], humanMode)
            }
        }
    }
}

/** 任务分组头：任务名 + 步数 */
@Composable
private fun TaskHeader(name: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        Text("$count 步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 单个决策步骤的详细卡：截图/发送/返回/Token/延迟/视觉模型/思考/AI图片描述 */
@Composable
private fun StepTraceCard(tr: com.phoneagent.model.StepTrace, annotated: android.graphics.Bitmap?, humanMode: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val img = annotated ?: tr.screenshot
    val visionColor = when (tr.visionSource) {
        "外挂3B" -> Success
        "云端" -> MaterialTheme.colorScheme.primary
        "本地OCR" -> Warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 人话摘要 + 置信度（v2.2.1 双语展示）
    val humanSummary = remember(tr.receivedText) { HumanTranslator.summarizeDecision(tr.receivedText) }
    val confidence = remember(tr.receivedText) { HumanTranslator.extractConfidence(tr.receivedText) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 头部：步骤 + 视觉来源 + 思考标记
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("步骤 ${tr.step}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(tr.visionSource, color = visionColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(if (tr.thinking) "思考" else "未思考",
                    color = if (tr.thinking) Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
            // 人话摘要区（默认展示；原始信息仍在下方折叠/展开可看）
            if (humanMode && humanSummary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "AI 决策：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    humanSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                confidence?.let { c ->
                    Spacer(Modifier.height(6.dp))
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
                                .background(
                                    when {
                                        c >= 0.75 -> Success
                                        c >= 0.6 -> Warning
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                ),
                        )
                    }
                }
            }
            // 截图（框选后优先展示框选图）
            if (img != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = "步骤截图",
                        modifier = Modifier
                            .width(112.dp)
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(AppRadii.Chip)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Token  ${tr.totalTokens}（入 ${tr.promptTokens} / 出 ${tr.completionTokens}）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(3.dp))
                        Text("耗时 ${tr.latencyMs} ms", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tr.visionModel.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text("视觉：${tr.visionModel}", style = MaterialTheme.typography.labelMedium, color = visionColor)
                        }
                        if (annotated != null) {
                            Spacer(Modifier.height(3.dp))
                            Text("（已用外挂视觉画框）", style = MaterialTheme.typography.labelMedium, color = Success)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "（本步无截图：未开启「屏幕捕获」权限，AI 决策将无法拿到画面）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // AI 对图片的描述
            if (tr.visionDescription.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("AI 图片描述：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(tr.visionDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            // 发送/返回（可展开）
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发送 / 返回", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable { expanded = !expanded },
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text("═══ 发送给 AI ═══", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.secondary)
                Text(tr.sentText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("═══ AI 返回 ═══", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                Text(tr.receivedText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** 在截图上用外挂/OCR 识别的控件画框并标注用途、文字 */
private fun drawBoxes(src: android.graphics.Bitmap, controls: List<com.phoneagent.vision.DetectedControl>): android.graphics.Bitmap {
    val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val strokeW = (out.width / 220f).coerceIn(2f, 5f)
    val paint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokeW
        color = 0xFF00BFA5.toInt()
    }
    val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00BFA5.toInt()
        textSize = strokeW * 5f
    }
    controls.forEach { c ->
        val b = c.bounds
        if (b.size < 4) return@forEach
        val l = b[0] * out.width; val t = b[1] * out.height
        val r = b[2] * out.width; val bot = b[3] * out.height
        canvas.drawRect(l, t, r, bot, paint)
        val label = "${c.role}·${c.purpose}".take(18)
        canvas.drawText(label, l + 2, (t - 2).coerceAtLeast(labelPaint.textSize), labelPaint)
    }
    return out
}

private fun formatTime(t: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))

// ========== 时间线：任务过程可视化（v2.2.1 第四章） ==========

/** 任务时间线主视图：执行摘要 + 按任务的叙事步骤卡 */
@Composable
private fun TimelinePanel(
    traces: List<com.phoneagent.model.StepTrace>,
    logs: List<AgentLog>,
) {
    if (traces.isEmpty()) {
        EmptyHint("暂无任务时间线：运行智能体后，这里会把每一步翻译成「看到→决定→做了→结果」")
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