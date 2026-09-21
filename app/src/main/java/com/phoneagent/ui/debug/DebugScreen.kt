package com.phoneagent.ui.debug

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.debug.panels.ChatPanel
import com.phoneagent.ui.debug.panels.ExecutionFlowPanel
import com.phoneagent.ui.debug.panels.LogPanel
import com.phoneagent.ui.debug.panels.MetricsPanel
import com.phoneagent.ui.debug.panels.drawBoxes
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationInstant
import com.phoneagent.ui.theme.EaseOut
import kotlinx.coroutines.launch

/**
 * 调试页的四个视角。
 *
 * 重构前是六个平级页签（任务/时间线/指标/对话/日志/历史），「任务」与「时间线」本质是
 * 同一批决策数据的两种讲法，「历史」是执行层记录且因缺 taskId 无法归属到具体某次执行。
 * 现在「执行流」把这三者在 (taskId, step) 上合并成一条时间轴，页签收敛为四个视角。
 */
internal enum class DebugTab(val label: String) {
    FLOW("执行流"),
    METRICS("指标"),
    CHAT("对话"),
    LOGS("日志"),
}

@Composable
fun DebugScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val metrics by vm.metrics.collectAsState()
    val conversation by vm.conversation.collectAsState()
    val logs by vm.logs.collectAsState()
    val history by vm.executionHistory.collectAsState()
    val traces by vm.traces.collectAsState()
    val stepShot by vm.stepShot.collectAsState()
    val permissions by vm.permissions.collectAsState()
    val agentState by vm.agentState.collectAsState()
    var tab by remember { mutableStateOf(DebugTab.FLOW) }
    var actionsOpen by remember { mutableStateOf(false) }
    var showCapabilities by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 进入调试页时刷新能力/权限状态，保证「能力缺失提示条」准确
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

    val missing = permissions.count { !it.granted }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        AppTopBar(
            title = "调试",
            subtitle = "每一步怎么决定 · 怎么执行 · Token 与视觉",
            trailingContent = {
                // 导出 / 清空 / 画框 / 能力状态统一收进内嵌菜单，页头只留一个入口
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
                        onShowCapabilities = { actionsOpen = false; showCapabilities = true },
                        onAnnotate = { actionsOpen = false; runExternalAnnotate() },
                        onExportReport = { actionsOpen = false; toast(vm.exportDiagnosticReport(context)) },
                        onExportJson = { actionsOpen = false; toast(vm.exportLogsJsonAll(context)) },
                        onClear = { actionsOpen = false; vm.clearDebug() },
                    )
                }
            },
        )

        // 能力项齐全时这一整块不占位；有缺失才出现提示条（完整状态在「更多」菜单里）
        if (missing > 0) {
            CapabilityNotice(
                permissions = permissions,
                onFix = { vm.openPermissionSettings(context, it) },
                modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            )
            Spacer(Modifier.height(AppSpacing.Md))
        }

        SegmentedTabs(selected = tab, onSelect = { tab = it }, modifier = Modifier.padding(horizontal = AppSpacing.Lg))
        Spacer(Modifier.height(AppSpacing.Md))

        // 视角切换只做「自下而上的淡入」，不做横向滑动；面板统一 16dp 左右留白
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
                    DebugTab.FLOW -> ExecutionFlowPanel(
                        traces = traces,
                        records = history,
                        stepShot = stepShot,
                        annotatedMap = annotatedMap,
                        isRunning = agentState.isRunning,
                    )
                    DebugTab.METRICS -> MetricsPanel(metrics)
                    DebugTab.CHAT -> ChatPanel(conversation)
                    DebugTab.LOGS -> LogPanel(logs)
                }
            }
        }
    }

    if (showCapabilities) {
        CapabilityStatusDialog(
            permissions = permissions,
            onFix = { vm.openPermissionSettings(context, it) },
            onDismiss = { showCapabilities = false },
        )
    }
}

/** 调试页「更多」：能力状态、画框、导出与清空都收进内嵌菜单，页头只留一个入口 */
@Composable
private fun DebugActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    annotating: Boolean,
    onShowCapabilities: () -> Unit,
    onAnnotate: () -> Unit,
    onExportReport: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DebugActionItem(AppIcons.Report, "查看能力状态", onClick = onShowCapabilities)
        HorizontalDivider(Modifier.padding(vertical = AppSpacing.Xs))
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