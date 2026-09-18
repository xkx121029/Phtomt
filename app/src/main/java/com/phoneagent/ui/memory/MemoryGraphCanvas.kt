package com.phoneagent.ui.memory

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.skeleton
import com.phoneagent.ui.theme.Accent
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.MemoryAnomaly
import com.phoneagent.ui.theme.MemoryAnomalySoft
import com.phoneagent.ui.theme.MemoryProfile
import com.phoneagent.ui.theme.MemoryProfileSoft
import com.phoneagent.ui.theme.MemoryRoot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 图谱节点 */
internal data class GraphNode(
    val id: String,
    val label: String,
    val subLabel: String,
    val color: Color,
    val xRatio: Float,
    val yRatio: Float,
    val radius: Float,
)

/** 构建图谱节点布局：中心 + 分类环 + 条目层 */
internal fun buildGraphNodes(
    anomalies: List<AnomalyMemoryEntry>,
    profiles: List<ProfileEntry>,
): List<GraphNode> {
    val nodes = mutableListOf<GraphNode>()

    // 中心根节点
    nodes.add(
        GraphNode("root", "AI 记忆", "", MemoryRoot, 0.5f, 0.5f, 0.09f),
    )

    // 异常记忆分类节点（中心左侧）
    val anomalyCat = GraphNode(
        "cat-anomaly", "异常经验", "${anomalies.size} 条",
        MemoryAnomaly, 0.22f, 0.30f, 0.07f,
    )
    nodes.add(anomalyCat)

    // 用户画像分类节点（中心右侧）
    val profileCat = GraphNode(
        "cat-profile", "用户画像", "${profiles.size} 条",
        MemoryProfile, 0.78f, 0.30f, 0.07f,
    )
    nodes.add(profileCat)

    // 异常条目：围绕左下象限
    anomalies.forEachIndexed { i, a ->
        val angle = Math.PI * (0.6 + 0.28 * i / maxOf(anomalies.size - 1, 1))
        nodes.add(
            GraphNode(
                "anomaly-$i", a.anomalyType.take(12), a.appPackage.take(10),
                MemoryAnomalySoft, 0.22f + 0.16f * cos(angle).toFloat(),
                0.55f + 0.16f * sin(angle).toFloat(),
                0.045f,
            ),
        )
    }

    // 画像条目：围绕右下象限
    profiles.forEachIndexed { i, p ->
        val angle = Math.PI * (0.28 + 0.28 * i / maxOf(profiles.size - 1, 1))
        nodes.add(
            GraphNode(
                "profile-$i", p.key.take(12), p.value.take(10),
                MemoryProfileSoft, 0.62f + 0.16f * cos(angle).toFloat(),
                0.55f + 0.16f * sin(angle).toFloat(),
                0.045f,
            ),
        )
    }

    return nodes
}

/** 图谱画布：连线 + 节点 + 标签（支持缩放与节点点击） */
@Composable
internal fun GraphCanvas(nodes: List<GraphNode>) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var selectedNode by remember { mutableStateOf<GraphNode?>(null) }
    val textMeasurer = rememberTextMeasurer()

    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = tween(150),
        label = "graph-scale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // 将屏幕坐标转换为图谱坐标
                    val graphX = (tapOffset.x - w / 2 - offsetX) / animatedScale + w / 2
                    val graphY = (tapOffset.y - h / 2 - offsetY) / animatedScale + h / 2
                    // 找到最近的节点
                    val closest = nodes.minByOrNull { n ->
                        val nx = n.xRatio * w
                        val ny = n.yRatio * h
                        val r = n.radius * min(w, h)
                        val dx = graphX - nx
                        val dy = graphY - ny
                        kotlin.math.sqrt(dx * dx + dy * dy) - r
                    }
                    if (closest != null) {
                        val nx = closest.xRatio * w
                        val ny = closest.yRatio * h
                        val r = closest.radius * min(w, h)
                        val dx = graphX - nx
                        val dy = graphY - ny
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (dist < r * 1.5f) {
                            selectedNode = closest
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.6f, 2.5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawContext.canvas.save()
            drawContext.canvas.translate(w / 2 + offsetX, h / 2 + offsetY)
            drawContext.canvas.scale(animatedScale, animatedScale)

            // 连线段：从中心到分类，分类到条目
            val center = nodes.firstOrNull { it.id == "root" }
            if (center != null) {
                val cx = center.xRatio * w - w / 2
                val cy = center.yRatio * h - h / 2
                nodes.filter { it.id != "root" }.forEach { n ->
                    val px = n.xRatio * w - w / 2
                    val py = n.yRatio * h - h / 2
                    drawLine(
                        color = Color(0x552979FF),
                        start = Offset(cx, cy),
                        end = Offset(px, py),
                        strokeWidth = 2f,
                    )
                }
            }

            // 节点 + 标签
            nodes.forEach { n ->
                val px = n.xRatio * w - w / 2
                val py = n.yRatio * h - h / 2
                val r = n.radius * min(w, h)
                val isSelected = selectedNode?.id == n.id

                // 选中光环
                if (isSelected) {
                    drawCircle(
                        color = n.color.copy(alpha = 0.3f),
                        radius = r * 1.4f,
                        center = Offset(px, py),
                    )
                }
                // 外层光晕
                drawCircle(
                    color = n.color.copy(alpha = 0.25f),
                    radius = r * 1.15f,
                    center = Offset(px, py),
                )
                // 节点主体
                drawCircle(
                    color = n.color,
                    radius = r,
                    center = Offset(px, py),
                )
                // 内层高光
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f),
                    radius = r * 0.72f,
                    center = Offset(px, py),
                )

                // 中文标签（使用 DrawScope.drawText）
                val labelStyle = TextStyle(
                    color = Color.White,
                    fontSize = if (n.id == "root") 16.sp else 12.sp,
                    fontWeight = if (n.id == "root") FontWeight.Bold else FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif,
                )
                drawText(
                    text = n.label.take(8),
                    textMeasurer = textMeasurer,
                    topLeft = Offset(px - 40, py - 8),
                    style = labelStyle,
                )
                // 子标签
                if (n.subLabel.isNotBlank()) {
                    val subStyle = TextStyle(
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                    )
                    drawText(
                        text = n.subLabel.take(10),
                        textMeasurer = textMeasurer,
                        topLeft = Offset(px - 40, py + r / 2 + 2),
                        style = subStyle,
                    )
                }
            }
            drawContext.canvas.restore()
        }
    }

    // 选中节点详情卡片
    selectedNode?.let { node ->
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(AppRadii.Card),
            color = node.color.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(node.color),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        node.label,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (node.subLabel.isNotBlank()) {
                        Text(
                            node.subLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                androidx.compose.material3.TextButton(onClick = { selectedNode = null }) {
                    Text("关闭", color = node.color)
                }
            }
        }
    }

    Text(
        "双指缩放图谱 · 点击节点查看详情",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}