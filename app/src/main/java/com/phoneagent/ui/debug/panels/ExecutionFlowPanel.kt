package com.phoneagent.ui.debug.panels

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.domain.model.StepShot
import com.phoneagent.domain.model.StepTrace
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.debug.ConfidenceBar
import com.phoneagent.ui.debug.DebugEmptyHint
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning

/**
 * 「执行流」：以「一次执行」为单位组织调试数据。
 *
 * 重构前调试页把同一批数据切成三个平级页签——「任务」（决策层 StepTrace）、
 * 「时间线」（决策层再讲一遍）、「历史」（执行层 StepRecord）——想知道"某一步
 * 到底怎么决定的、又是怎么执行的"，得在三个页签之间来回对照，而且执行层记录
 * 因为不带 taskId 根本没法归到具体某次执行里。
 *
 * 现在：StepRecord 补了 taskId，决策层与执行层按 (taskId, step) 合并成同一批
 * 步骤节点，按执行倒序排成一条条纵向时间轴；每次执行是一张卡，展开即时间轴。
 */

/** 一步的合并视图：决策层（怎么决定的）+ 执行层（怎么执行的），两侧都可能缺失 */
private data class FlowStep(
    val step: Int,
    val trace: StepTrace?,
    val record: StepRecord?,
)

/** 一次执行的聚合 */
private data class FlowExecution(
    val taskId: Long,
    val name: String,
    val steps: List<FlowStep>,
    val tokens: Int,
    val decideMs: Long,
    val execMs: Long,
    /** 执行层未确认生效的步数 */
    val unverified: Int,
    /** 决策把握度低于 0.6 的步数 */
    val lowConfidence: Int,
    /** AI 是否已输出 task_done（从原始返回里判定，不额外引入状态字段） */
    val finished: Boolean,
)

/** 把两类记录按 (taskId, step) 归并成执行列表（执行倒序，最新一次在最上面） */
private fun buildExecutions(traces: List<StepTrace>, records: List<StepRecord>): List<FlowExecution> {
    val traceGroups = traces.groupBy { it.taskId }
    val recordGroups = records.groupBy { it.taskId }
    return (traceGroups.keys + recordGroups.keys).sortedDescending().map { tid ->
        val ts = traceGroups[tid].orEmpty().sortedBy { it.step }
        val rs = recordGroups[tid].orEmpty().sortedBy { it.step }
        val name = ts.firstNotNullOfOrNull { it.taskName }
            ?: rs.firstNotNullOfOrNull { it.taskName }
            ?: if (tid < 0) "无归属步骤" else "任务 #$tid"
        val steps = (ts.map { it.step } + rs.map { it.step }).distinct().sorted().map { s ->
            FlowStep(s, ts.firstOrNull { it.step == s }, rs.firstOrNull { it.step == s })
        }
        FlowExecution(
            taskId = tid,
            name = name,
            steps = steps,
            tokens = ts.sumOf { it.totalTokens },
            decideMs = ts.sumOf { it.latencyMs },
            execMs = rs.sumOf { it.durationMs },
            unverified = rs.count { !it.isConfirmed },
            lowConfidence = ts.count { (HumanTranslator.extractConfidence(it.receivedText) ?: 1.0) < 0.6 },
            finished = ts.lastOrNull()?.receivedText?.contains("task_done") == true,
        )
    }
}

@Composable
internal fun ExecutionFlowPanel(
    traces: List<StepTrace>,
    records: List<StepRecord>,
    stepShot: StepShot,
    annotatedMap: Map<Int, Bitmap>,
    isRunning: Boolean,
) {
    if (traces.isEmpty() && records.isEmpty()) {
        DebugEmptyHint("暂无执行记录：运行智能体后，每一次执行都会在这里按时间倒序成一条时间轴")
        return
    }
    val executions = remember(traces, records) { buildExecutions(traces, records) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        // 实时画面只属于"正在跑的那次执行"，放在列表最上方，避免塞进执行卡里嵌套卡片
        if (isRunning) item(key = "live") { StepShotPanel(stepShot) }
        item(key = "flow-caption") {
            Text(
                "${executions.size} 次执行 · 共 ${executions.sumOf { it.steps.size }} 步",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        executions.forEachIndexed { i, ex ->
            item(key = "ex${ex.taskId}") {
                ExecutionCard(
                    ex = ex,
                    annotatedMap = annotatedMap,
                    running = isRunning && i == 0,
                    // 长列表里逐项延迟会越滚越慢，限定前 6 项参与级联
                    modifier = Modifier.animateListItem(index = i.coerceAtMost(6)),
                )
            }
        }
    }
}

@Composable
private fun ExecutionCard(
    ex: FlowExecution,
    annotatedMap: Map<Int, Bitmap>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    // 最新一次执行默认展开（打开调试页就是想看刚刚发生了什么），更早的默认折叠
    var expanded by remember(ex.taskId) { mutableStateOf(running) }
    val statusText = when {
        running -> "进行中"
        ex.finished -> "已完成"
        else -> "已结束"
    }
    val statusColor = when {
        running -> MaterialTheme.colorScheme.primary
        ex.finished -> Success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(
            1.dp,
            if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Lg)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        ex.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${ex.steps.size} 步 · ${ex.tokens} tokens · 决策 ${ex.decideMs}ms · 执行 ${ex.execMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(AppSpacing.Md))
                StatusPill(statusText, statusColor)
                Spacer(Modifier.width(AppSpacing.Sm))
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = if (expanded) "收起这条执行" else "展开这条执行",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            // 只在这条执行真有异常时提示，正常执行不占一行
            if (ex.unverified > 0 || ex.lowConfidence > 0) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    listOfNotNull(
                        ex.unverified.takeIf { it > 0 }?.let { "${it} 步未生效" },
                        ex.lowConfidence.takeIf { it > 0 }?.let { "${it} 步把握偏低" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ex.unverified > 0) MaterialTheme.colorScheme.error else Warning,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                    expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                    shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
            ) {
                Column(modifier = Modifier.padding(top = AppSpacing.Md)) {
                    ex.steps.forEachIndexed { i, s ->
                        StepNode(
                            step = s,
                            annotated = annotatedMap[s.step],
                            first = i == 0,
                            last = i == ex.steps.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

/** 一步：左侧轴线 + 右侧「决策 → 执行」合并内容 */
@Composable
private fun StepNode(step: FlowStep, annotated: Bitmap?, first: Boolean, last: Boolean) {
    var rawOpen by remember(step.step) { mutableStateOf(false) }
    var shotOpen by remember(step.step) { mutableStateOf(false) }
    val trace = step.trace
    val record = step.record
    val human = remember(trace?.receivedText) {
        trace?.let { HumanTranslator.summarizeDecision(it.receivedText) }.orEmpty()
    }
    val confidence = remember(trace?.receivedText) {
        trace?.let { HumanTranslator.extractConfidence(it.receivedText) }
    }
    val img = annotated ?: trace?.screenshot
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        StepRail(first = first, last = last, confirmed = record?.isConfirmed == true)
        Spacer(Modifier.width(AppSpacing.Md))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (last) 0.dp else AppSpacing.Lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "步骤 ${step.step}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                record?.let { StepResultPill(it) }
                Spacer(Modifier.width(AppSpacing.Md))
                Text(
                    if (rawOpen) "收起原始" else "原始",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { rawOpen = !rawOpen },
                )
            }

            if (human.isNotBlank()) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    human,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            } else if (record != null) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    "（这一步只有执行记录，没有决策记录）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            confidence?.let { c ->
                Spacer(Modifier.height(6.dp))
                ConfidenceBar(c)
            }

            // 元信息：Token / 决策耗时 / 执行耗时 / 视觉来源 / 是否思考
            val meta = listOfNotNull(
                trace?.takeIf { it.totalTokens > 0 }?.let { "Token ${it.totalTokens}" },
                trace?.takeIf { it.latencyMs > 0 }?.let { "决策 ${it.latencyMs}ms" },
                record?.takeIf { it.durationMs > 0 }?.let { "执行 ${it.durationMs}ms" },
                trace?.takeIf { it.visionSource.isNotBlank() && it.visionSource != "无" }?.let { "视觉 ${it.visionSource}" },
                trace?.takeIf { it.thinking }?.let { "思考" },
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 执行层的"为什么成/不成"：失败时用错误色，成功时降低强调
            if (!record?.detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    record!!.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.isConfirmed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }

            if (img != null) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = "步骤 ${step.step} 截图",
                        modifier = Modifier
                            .width(46.dp)
                            .heightIn(max = 84.dp)
                            .clip(RoundedCornerShape(AppRadii.Chip))
                            .clickable(enabled = annotated != null || trace?.screenshot != null) { shotOpen = !shotOpen },
                    )
                    Spacer(Modifier.width(AppSpacing.Md))
                    Text(
                        when {
                            shotOpen -> "收起画面"
                            annotated != null -> "查看识别框"
                            else -> "查看截图"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { shotOpen = !shotOpen },
                    )
                }
                AnimatedVisibility(
                    visible = shotOpen,
                    enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                        expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                        shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
                ) {
                    Column(modifier = Modifier.padding(top = AppSpacing.Sm)) {
                        val raw = trace?.screenshot
                        if (annotated != null && raw != null) {
                            DragCompare(raw, annotated)
                        } else {
                            Image(
                                bitmap = img.asImageBitmap(),
                                contentDescription = "步骤 ${step.step} 截图",
                                modifier = Modifier
                                    .height(CompareHeight)
                                    .clip(RoundedCornerShape(AppRadii.Item)),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = rawOpen,
                enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                    expandVertically(tween(DurationFast, easing = EaseOut), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(DurationFast, easing = EaseOut)) +
                    shrinkVertically(tween(DurationFast, easing = EaseOut), shrinkTowards = Alignment.Bottom),
            ) {
                Column(modifier = Modifier.padding(top = AppSpacing.Sm)) {
                    RawBlock(trace)
                }
            }
        }
    }
}

/** 步骤轴线：上段线 + 序号点 + 下段线（首尾各去掉一半，让整条时间轴看起来是连续的） */
@Composable
private fun StepRail(first: Boolean, last: Boolean, confirmed: Boolean) {
    val tone = if (confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .width(14.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .weight(1f)
                .background(if (first) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (confirmed) tone else Color.Transparent)
                .border(1.dp, tone, CircleShape),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .weight(1f)
                .background(if (last) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** 执行结果徽标：已生效 / 未生效 / 发送失败 */
@Composable
private fun StepResultPill(record: StepRecord) {
    val (text, color) = when {
        record.isConfirmed -> "已生效" to Success
        record.sendResult == "send_failed" -> "发送失败" to MaterialTheme.colorScheme.error
        else -> "未生效" to Warning
    }
    StatusPill(text, color, containerAlpha = 0.12f)
}

/** 原始数据块：发送 / 返回 / 图片描述（等宽小字，只在这步展开时渲染） */
@Composable
private fun RawBlock(trace: StepTrace?) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Tile),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            RawLine("发送给主模型", trace?.sentText)
            RawLine("主模型返回", trace?.receivedText)
            if (!trace?.visionDescription.isNullOrBlank()) RawLine("AI 图片描述", trace.visionDescription)
            if (!trace?.visionModel.isNullOrBlank()) RawLine("视觉模型", trace.visionModel)
        }
    }
}

@Composable
private fun RawLine(label: String, value: String?) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value?.takeIf { it.isNotBlank() } ?: "（无记录）",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 14,
            overflow = TextOverflow.Ellipsis,
        )
    }
}