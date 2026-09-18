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
import com.phoneagent.ui.debug.DebugEmptyHint
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import com.phoneagent.ui.icons.AppIcons

/** 「任务」页：按任务分组展示每一步决策的发送/返回/Token/延迟/视觉/思考/截图 */
@Composable
internal fun StepsPanel(
    traces: List<com.phoneagent.domain.model.StepTrace>,
    annotatedMap: Map<Int, android.graphics.Bitmap>,
    humanMode: Boolean,
) {
    if (traces.isEmpty()) {
        DebugEmptyHint("暂无任务步骤：运行智能体后，每个决策步骤都会记录在这里")
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
internal fun TaskHeader(name: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        Text("$count 步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 单个决策步骤的详细卡：截图/发送/返回/Token/延迟/视觉模型/思考/AI图片描述 */
@Composable
private fun StepTraceCard(tr: com.phoneagent.domain.model.StepTrace, annotated: android.graphics.Bitmap?, humanMode: Boolean) {
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
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
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
internal fun drawBoxes(src: android.graphics.Bitmap, controls: List<com.phoneagent.device.vision.DetectedControl>): android.graphics.Bitmap {
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