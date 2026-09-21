package com.phoneagent.ui.debug

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationInstant
import com.phoneagent.ui.theme.EaseOut
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
    var actionsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 进入调试页时刷新能力/权限状态，保证「能力状态条」准确
    LaunchedEffect(Unit) { runCatching { vm.refreshPermissions(context) } }

    // 用外挂视觉对本次任务所有截图画框的结果（step → 框选图）
    var annotatedMap by remember { mutableStateOf<Map<Int, android.graphics.Bitmap>>(emptyMap()) }
    var annotating by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun runExternalAnnotate() {
        scope.launch {
            annotating = true
            toast("正在用外挂视觉画框…")
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
            toast(
                if (tasks.isEmpty()) "本任务暂无可画框的截图"
                else "已用${if (connected) "端侧3B" else "本地OCR"}对 ${tasks.size} 张截图画框（含控件 ${count} 张）"
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        AppTopBar(
            title = "调试",
            subtitle = "每一步的决策 · 发送与返回 · Token · 视觉与截图",
            trailingContent = {
                // 导出 / 清空 / 画框统一收进内嵌菜单，页头只留一个入口，避免三四个图标并排堆叠
                Box {
                    IconButton(onClick = { actionsOpen = true }) {
                        Icon(
                            AppIcons.More,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DebugActionsMenu(
                        expanded = actionsOpen,
                        onDismiss = { actionsOpen = false },
                        annotating = annotating,
                        onAnnotate = { actionsOpen = false; runExternalAnnotate() },
                        onExportReport = { actionsOpen = false; toast(vm.exportDiagnosticReport(context)) },
                        onExportJson = { actionsOpen = false; toast(vm.exportLogsJsonAll(context)) },
                        onClear = { actionsOpen = false; vm.clearDebug() },
                    )
                }
            },
        )

        // 能力状态条：无障碍 / 悬浮窗 / 截屏 / 自启动 / Shizuku
        CapabilityStrip(permissions, Modifier.padding(horizontal = AppSpacing.Lg))
        Spacer(Modifier.height(AppSpacing.Md))
        SegmentedTabs(selected = tab, onSelect = { tab = it }, modifier = Modifier.padding(horizontal = AppSpacing.Lg))
        Spacer(Modifier.height(AppSpacing.Md))

        // 面板切换只做「自下而上的淡入」，不做横向滑动；面板统一 16dp 左右留白（原来卡片是贴边的）
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (
                    fadeIn(tween(DurationFast, easing = EaseOut)) +
                        slideInVertically(tween(DurationFast, easing = EaseOut)) { it / 8 }
                    ).togetherWith(fadeOut(tween(DurationInstant, easing = EaseOut)))
            },
            modifier = Modifier.weight(1f),
            label = "debug-panel",
        ) { current ->
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.Lg)) {
                when (current) {
                    DebugTab.STEPS -> StepsPanel(
                        traces = traces,
                        annotatedMap = annotatedMap,
                        humanMode = humanMode,
                        onHumanModeChange = { humanMode = it },
                        stepShot = vm.stepShot.collectAsState().value,
                    )
                    DebugTab.TIMELINE -> TimelinePanel(traces, logs)
                    DebugTab.METRICS -> MetricsPanel(metrics)
                    DebugTab.CHAT -> ChatPanel(conversation)
                    DebugTab.LOGS -> LogPanel(logs)
                    DebugTab.HISTORY -> HistoryPanel(history)
                }
            }
        }
    }
}

/** 调试页「更多」：把导出与清空收进内嵌菜单，页头只留一个入口 */
@Composable
private fun DebugActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    annotating: Boolean,
    onAnnotate: () -> Unit,
    onExportReport: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DebugActionItem(
            icon = AppIcons.Insights,
            label = if (annotating) "画框中…" else "用外挂视觉画框",
            enabled = !annotating,
            onClick = onAnnotate,
        )
        DebugActionItem(AppIcons.Description, "导出诊断报告（人话+原始）", onClick = onExportReport)
        DebugActionItem(AppIcons.FileDownload, "导出 JSON（分任务）", onClick = onExportJson)
        HorizontalDivider(Modifier.padding(vertical = AppSpacing.Xs))
        DebugActionItem(AppIcons.Delete, "清空调试数据", danger = true, onClick = onClear)
    }
}

@Composable
private fun DebugActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = tint) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) },
        enabled = enabled,
        onClick = onClick,
    )
}