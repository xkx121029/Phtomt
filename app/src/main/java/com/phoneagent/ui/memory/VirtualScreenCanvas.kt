package com.phoneagent.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.data.store.PageShapeNode
import com.phoneagent.ui.theme.AppTheme

/**
 * 虚拟画面画布：「AI 眼里的手机」纯线框重建。
 *
 * 数据来自页面记忆里的控件空间树（PageShapeNode，ratio 坐标 0~1），
 * 不存截图、不碰真实内容——只按 kind 画结构框：
 * - text：文字直接绘制（矩形过矮时退化为圆角内容条，示意"这里有一行字"）
 * - button：品牌色描边 + 四角 L 形角标（蓝图测绘感）
 * - input：雾蓝下划线（输入框的经典抽象）
 * - image：弱描边 + 对角双叉线（图片占位的通用画法）
 * - toggle：琥珀竖排胶囊描边
 *
 * [linkedControls] 里的控件（semanticId 优先、label 兜底两段式命中）视为"有出边"：
 * 品牌容器色填充 + 描边加粗 + 右上角导航三角，一眼看出这条记忆能跳去哪。
 *
 * [onTapControl] 非 null 时进入可点模式：命中可点击控件后回调（供下钻面板）；
 * 为 null 则是纯缩略展示，不挂手势。
 */
@Composable
internal fun VirtualScreenCanvas(
    shapes: List<PageShapeNode>,
    linkedControls: Set<String> = emptySet(),
    onTapControl: ((PageShapeNode) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    /** 低于这个高度画不下文字（如单行小标签），退化为内容条 */
    val textBarMinPx = with(density) { 14.dp.toPx() }

    // 文字布局预计算：shapes 或画布尺寸变化才重测，绘制帧内零测量开销
    val layouts: Map<PageShapeNode, TextLayoutResult> =
        remember(shapes, canvasPx, colors) {
            if (canvasPx == IntSize.Zero || shapes.isEmpty()) {
                emptyMap()
            } else {
                shapes.filter { it.label.isNotBlank() }.associateWith { node ->
                    val maxW = ((node.r - node.l).coerceAtLeast(0.01f) * canvasPx.width).toInt()
                    val maxH = ((node.b - node.t).coerceAtLeast(0.01f) * canvasPx.height).toInt()
                    textMeasurer.measure(
                        text = AnnotatedString(node.label),
                        style = TextStyle(
                            color = colors.onSurfaceBase,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.SansSerif,
                        ),
                        constraints = Constraints(maxWidth = maxW, maxHeight = maxH),
                        maxLines = 1,
                    )
                }
            }
        }

    // 点击命中：从后往前取（绘制顺序即层级，后画的在上层），只命中可点击控件
    fun hitShape(tap: Offset): PageShapeNode? {
        if (canvasPx.width <= 0 || canvasPx.height <= 0) return null
        val rx = tap.x / canvasPx.width
        val ry = tap.y / canvasPx.height
        return shapes.lastOrNull { n ->
            n.clickable && rx >= n.l && rx <= n.r && ry >= n.t && ry <= n.b
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(9f / 19.5f)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.phoneShellBorder, RoundedCornerShape(20.dp))
            .onSizeChanged { canvasPx = it }
            .then(
                if (onTapControl != null) {
                    Modifier.pointerInput(shapes, onTapControl) {
                        detectTapGestures { tap ->
                            hitShape(tap)?.let(onTapControl)
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (shapes.isEmpty()) {
            Text(
                "暂无控件记忆",
                style = MaterialTheme.typography.labelMedium,
                color = colors.emptyStateText,
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawBlueprintDots(colors)
                shapes.forEach { n ->
                    drawShapeNode(
                        node = n,
                        linked = (n.semanticId.isNotBlank() && n.semanticId in linkedControls) ||
                            (n.semanticId.isBlank() && n.label in linkedControls),
                        layout = layouts[n],
                        textBarMinPx = textBarMinPx,
                        colors = colors,
                    )
                }
            }
        }
    }
}

/** 蓝图点阵底纹：测绘纸质感，弱到不抢控件框的戏 */
private fun DrawScope.drawBlueprintDots(colors: com.phoneagent.ui.theme.AppColors) {
    val gap = 22.dp.toPx()
    val dotR = 1.dp.toPx()
    val dotColor = colors.outlineSoft.copy(alpha = 0.35f)
    var y = gap / 2
    while (y < size.height) {
        var x = gap / 2
        while (x < size.width) {
            drawCircle(dotColor, radius = dotR, center = Offset(x, y))
            x += gap
        }
        y += gap
    }
}

/** 绘制单个控件框（kind 分型 + 出边高亮） */
private fun DrawScope.drawShapeNode(
    node: PageShapeNode,
    linked: Boolean,
    layout: TextLayoutResult?,
    textBarMinPx: Float,
    colors: com.phoneagent.ui.theme.AppColors,
) {
    val l = node.l.coerceIn(0f, 1f) * size.width
    val t = node.t.coerceIn(0f, 1f) * size.height
    val r = node.r.coerceIn(0f, 1f) * size.width
    val b = node.b.coerceIn(0f, 1f) * size.height
    val rect = Rect(l, t, r, b)
    if (rect.width < 1f || rect.height < 1f) return

    // 出边命中：先铺品牌容器色底
    if (linked) {
        drawRoundRect(
            color = colors.brandContainer.copy(alpha = 0.6f),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
    }

    when (node.kind) {
        "button" -> {
            drawRoundRect(
                color = colors.brand,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = (if (linked) 2.5.dp else 1.5.dp).toPx()),
            )
            drawCornerMarks(rect, colors.brand)
            drawCenteredLabel(rect, layout, colors.onSurfaceBase.copy(alpha = 0.9f))
        }
        "input" -> {
            // 输入框经典抽象：一条底边下划线
            drawLine(
                color = colors.accentCool.copy(alpha = if (linked) 1f else 0.85f),
                start = Offset(rect.left, rect.bottom),
                end = Offset(rect.right, rect.bottom),
                strokeWidth = (if (linked) 2.5.dp else 2.dp).toPx(),
            )
            drawCenteredLabel(rect, layout, colors.onSurfaceBase.copy(alpha = 0.55f))
        }
        "image" -> {
            drawRoundRect(
                color = colors.outlineSoft,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            // 对角双叉线：图片占位通用画法
            drawLine(
                colors.outlineSoft, Offset(rect.left, rect.top), Offset(rect.right, rect.bottom),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                colors.outlineSoft, Offset(rect.right, rect.top), Offset(rect.left, rect.bottom),
                strokeWidth = 1.dp.toPx(),
            )
        }
        "toggle" -> {
            // 竖排胶囊：开关的抽象
            drawRoundRect(
                color = colors.accentWarm,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(minOf(rect.width, rect.height) / 2f),
                style = Stroke(width = (if (linked) 2.dp else 1.5.dp).toPx()),
            )
        }
        "text" -> {
            if (rect.height < textBarMinPx) {
                // 太矮画不下字：退化为居中圆角内容条，示意"一行字"
                val barH = (rect.height * 0.5f).coerceAtLeast(1.5f)
                drawRoundRect(
                    color = colors.onSurfaceBase.copy(alpha = 0.28f),
                    topLeft = Offset(rect.left, rect.center.y - barH / 2),
                    size = Size(rect.width, barH),
                    cornerRadius = CornerRadius(barH / 2),
                )
            } else {
                layout?.let {
                    drawText(
                        it,
                        topLeft = Offset(rect.left + 4f, rect.top + 3f),
                        color = colors.onSurfaceBase.copy(alpha = 0.8f),
                    )
                }
            }
        }
        else -> {
            // other：弱描边示意 + 居中文字（如有）
            drawRoundRect(
                color = colors.outlineSoft.copy(alpha = 0.45f),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCenteredLabel(rect, layout, colors.onSurfaceBase.copy(alpha = 0.6f))
        }
    }

    // 出边高亮收尾：右上角导航三角（指向"跳走"的方向感）
    if (linked) {
        val a = 8.dp.toPx()
        val tri = Path().apply {
            moveTo(rect.right - a * 1.6f, rect.top)
            lineTo(rect.right, rect.top)
            lineTo(rect.right, rect.top + a * 1.6f)
            close()
        }
        drawPath(tri, colors.brand)
    }
}

/** 按钮四角 L 形角标：蓝图测绘感 */
private fun DrawScope.drawCornerMarks(rect: Rect, color: androidx.compose.ui.graphics.Color) {
    val len = (minOf(rect.width, rect.height) * 0.3f).coerceIn(4f, 14f)
    val sw = 1.5.dp.toPx()
    // 左上
    drawLine(color, Offset(rect.left, rect.top + len), Offset(rect.left, rect.top), sw)
    drawLine(color, Offset(rect.left, rect.top), Offset(rect.left + len, rect.top), sw)
    // 右上
    drawLine(color, Offset(rect.right - len, rect.top), Offset(rect.right, rect.top), sw)
    drawLine(color, Offset(rect.right, rect.top), Offset(rect.right, rect.top + len), sw)
    // 左下
    drawLine(color, Offset(rect.left, rect.bottom - len), Offset(rect.left, rect.bottom), sw)
    drawLine(color, Offset(rect.left, rect.bottom), Offset(rect.left + len, rect.bottom), sw)
    // 右下
    drawLine(color, Offset(rect.right - len, rect.bottom), Offset(rect.right, rect.bottom), sw)
    drawLine(color, Offset(rect.right, rect.bottom - len), Offset(rect.right, rect.bottom), sw)
}

/** 矩形内居中绘制预排版的文字（无布局则跳过） */
private fun DrawScope.drawCenteredLabel(
    rect: Rect,
    layout: TextLayoutResult?,
    color: androidx.compose.ui.graphics.Color,
) {
    layout ?: return
    drawText(
        layout,
        color = color,
        topLeft = Offset(
            rect.center.x - layout.size.width / 2f,
            rect.center.y - layout.size.height / 2f,
        ),
    )
}
