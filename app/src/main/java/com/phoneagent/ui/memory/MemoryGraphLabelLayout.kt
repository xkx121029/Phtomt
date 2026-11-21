package com.phoneagent.ui.memory

import kotlin.math.max
import kotlin.math.min

/**
 * 标签布局的输入：只保留与「画在哪」有关的信息，不含主题色。
 * 这样避让算法可以在纯 JVM 单测里按不同节点规模 × 缩放级别跑，不需要画布。
 */
internal data class GraphLabelNode(
    val id: String,
    val label: String,
    val subLabel: String,
    val xRatio: Float,
    val yRatio: Float,
    val radius: Float,
)

/**
 * 图谱标签的三档详略，按缩放级别降级：缩得越小，留在节点旁的次要信息越少。
 *
 * 它决定「画哪些字」，[planGraphLabels] 的避让决定「画在哪里」。两道闸都通过才落笔，
 * 所以任何缩放级别下都不会出现两块文字叠在一起——挤不下时少画一行字，
 * 具体内容仍然可以点节点在详情卡里看。
 */
internal enum class GraphLabelDetail {
    /** 名称 + 计数/说明 */
    FULL,

    /** 名称全画，计数只留在根与分类节点上 */
    PRIMARY_ONLY,

    /** 只画根与分类节点，条目节点留白 */
    CATEGORY_ONLY,
}

/** 缩放 → 详略档位：1.15 倍以上给全部信息，0.85 倍以下只留骨架 */
internal fun labelDetailFor(scale: Float): GraphLabelDetail = when {
    scale >= 1.15f -> GraphLabelDetail.FULL
    scale >= 0.85f -> GraphLabelDetail.PRIMARY_ONLY
    else -> GraphLabelDetail.CATEGORY_ONLY
}

/** 一段文字在给定字号下的像素尺寸（由调用方用 TextMeasurer 量出来） */
internal data class LabelMetrics(val width: Float, val height: Float)

/** 定好位置的标签块：文字在块内水平居中，副标签紧跟主标签之下 */
internal data class PlacedGraphLabel(
    val nodeId: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** 主标签自身高度：副标签从 top + labelHeight + [GRAPH_LABEL_GAP] 开始画 */
    val labelHeight: Float,
    val showSub: Boolean,
)

/** 标签块与画布边缘的最小留白 */
internal const val GRAPH_LABEL_PAD = 4f

/** 主标签与副标签之间的行距 */
internal const val GRAPH_LABEL_GAP = 3f

/** 两块文字之间至少留出的空隙：贴边拼接看起来仍然是一团 */
private const val LABEL_SEPARATION = 2f

/** 条目节点（异常经验 / 用户画像里的具体项）与根、分类节点的区分 */
internal fun isGraphItemNode(id: String): Boolean =
    id.startsWith("anomaly-") || id.startsWith("profile-")

/**
 * 摆放所有标签：根与分类先占位，条目节点依次尝试「下方 → 上方 → 右侧 → 左侧」，
 * 四个位置都被占住就这一档不画——宁可少一行字，也不让两行字压在一起。
 *
 * 返回的每一块都不与其它块、画布边缘、以及自己的节点圆相交；
 * 坐标是画布坐标（未经缩放），整块画布缩放后重叠关系不变，因此缩放不改变结论。
 */
internal fun planGraphLabels(
    nodes: List<GraphLabelNode>,
    canvasWidth: Float,
    canvasHeight: Float,
    detail: GraphLabelDetail,
    measure: (node: GraphLabelNode, isSub: Boolean) -> LabelMetrics,
): List<PlacedGraphLabel> {
    if (canvasWidth <= 2 * GRAPH_LABEL_PAD || canvasHeight <= 2 * GRAPH_LABEL_PAD) return emptyList()

    val occupied = mutableListOf<FloatArray>()
    val result = mutableListOf<PlacedGraphLabel>()
    val unit = min(canvasWidth, canvasHeight)
    val maxBlockWidth = canvasWidth - 2 * GRAPH_LABEL_PAD

    for (node in nodes.sortedBy { detailRank(it) }) {
        val item = isGraphItemNode(node.id)
        if (detail == GraphLabelDetail.CATEGORY_ONLY && item) continue
        val wantSub = node.subLabel.isNotBlank() && when (detail) {
            GraphLabelDetail.FULL -> true
            GraphLabelDetail.PRIMARY_ONLY -> !item
            GraphLabelDetail.CATEGORY_ONLY -> false
        }

        val labelMetrics = measure(node, false)
        val subMetrics = if (wantSub) measure(node, true) else null
        val blockWidth = max(labelMetrics.width, subMetrics?.width ?: 0f)
        val blockHeight = labelMetrics.height +
            if (subMetrics != null) GRAPH_LABEL_GAP + subMetrics.height else 0f
        // 一块比画布还大就无处可放：直接跳过，避免压到边缘外
        if (blockWidth <= 0f || blockWidth > maxBlockWidth) continue
        if (blockHeight > canvasHeight - 2 * GRAPH_LABEL_PAD) continue

        val cx = node.xRatio * canvasWidth
        val cy = node.yRatio * canvasHeight
        val r = node.radius * unit
        val circle = floatArrayOf(cx - r, cy - r, cx + r, cy + r)
        val candidates = listOf(
            floatArrayOf(cx - blockWidth / 2f, cy + r + GRAPH_LABEL_GAP),
            floatArrayOf(cx - blockWidth / 2f, cy - r - GRAPH_LABEL_GAP - blockHeight),
            floatArrayOf(cx + r + GRAPH_LABEL_GAP, cy - blockHeight / 2f),
            floatArrayOf(cx - r - GRAPH_LABEL_GAP - blockWidth, cy - blockHeight / 2f),
        )
        val rect = candidates.firstNotNullOfOrNull { candidate ->
            val left = candidate[0].coerceIn(GRAPH_LABEL_PAD, maxBlockWidth - blockWidth + GRAPH_LABEL_PAD)
            val top = candidate[1].coerceIn(
                GRAPH_LABEL_PAD,
                canvasHeight - blockHeight - GRAPH_LABEL_PAD,
            )
            val candidateRect = floatArrayOf(left, top, left + blockWidth, top + blockHeight)
            when {
                // 压在自己的圆上同样算「文字叠在图上」，试下一个位置
                intersects(candidateRect, circle, 0f) -> null
                occupied.any { intersects(candidateRect, it, LABEL_SEPARATION) } -> null
                else -> candidateRect
            }
        } ?: continue

        occupied += rect
        result += PlacedGraphLabel(
            nodeId = node.id,
            left = rect[0],
            top = rect[1],
            width = blockWidth,
            height = blockHeight,
            labelHeight = labelMetrics.height,
            showSub = subMetrics != null,
        )
    }
    return result
}

/** 占位顺序：根 → 分类 → 条目。同级保持传入顺序，图谱结构才稳定 */
private fun detailRank(node: GraphLabelNode): Int = when {
    node.id == "root" -> 0
    node.id.startsWith("cat-") -> 1
    else -> 2
}

/** 两个矩形是否相交（[gap] 为额外要求的净空） */
private fun intersects(a: FloatArray, b: FloatArray, gap: Float): Boolean =
    a[0] < b[2] + gap && b[0] < a[2] + gap && a[1] < b[3] + gap && b[1] < a[3] + gap
