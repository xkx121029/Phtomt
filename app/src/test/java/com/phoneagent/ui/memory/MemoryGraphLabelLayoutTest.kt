package com.phoneagent.ui.memory

import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.ProfileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图谱标签的排布契约（UX-08）。
 *
 * 旧实现把分类/根节点的文字按圆心居中绘制、没有碰撞检测，三节点时三行字直接压在一起。
 * 这里钉住两个不变量：任何节点规模、任何缩放档位下（1）画出来的标签块两两不相交，
 * （2）也不压在自己的节点圆上；空间不够时隐藏标签，而不是叠上去。
 */
class MemoryGraphLabelLayoutTest {

    /** 模拟 TextMeasurer：中文主标签约 7px/字、副标签约 5px/字 */
    private fun measure(node: GraphLabelNode, isSub: Boolean): LabelMetrics =
        if (isSub) LabelMetrics(node.subLabel.length * 5f, 10f)
        else LabelMetrics(node.label.length * 7f, 14f)

    private fun anomaly(i: Int) = AnomalyMemoryEntry(
        id = i.toLong(),
        pageFingerprint = "fp-$i",
        pageLabels = listOf("设置页"),
        anomalyType = "弹窗遮挡",
        anomalyDescription = "保存按钮被浮层挡住",
        userSolution = "先关闭浮层",
        resolvedAction = "点击关闭",
        appPackage = "com.demo.app",
    )

    private fun profile(i: Int) = ProfileEntry(
        key = "常用应用$i",
        category = "habit",
        value = "美团",
    )

    /** 用生产代码的 buildGraphNodes 生成真实几何，避免在测试里复述一套坐标 */
    private fun graph(anomalies: Int, profiles: Int): List<GraphLabelNode> =
        buildGraphNodes(List(anomalies) { anomaly(it) }, List(profiles) { profile(it) })
            .map { GraphLabelNode(it.id, it.label, it.subLabel, it.xRatio, it.yRatio, it.radius) }

    private fun assertNoOverlap(labels: List<PlacedGraphLabel>) {
        for (i in labels.indices) {
            for (j in i + 1 until labels.size) {
                val a = labels[i]
                val b = labels[j]
                val overlap = a.left < b.left + b.width && b.left < a.left + a.width &&
                    a.top < b.top + b.height && b.top < a.top + a.height
                assertTrue("${a.nodeId} 与 ${b.nodeId} 的标签块重叠", !overlap)
            }
        }
    }

    private fun assertInsideCanvas(labels: List<PlacedGraphLabel>, width: Float, height: Float) {
        labels.forEach {
            assertTrue("${it.nodeId} 越出左边界", it.left >= 0f)
            assertTrue("${it.nodeId} 越出上边界", it.top >= 0f)
            assertTrue("${it.nodeId} 越出右边界", it.left + it.width <= width)
            assertTrue("${it.nodeId} 越出下边界", it.top + it.height <= height)
        }
    }

    @Test
    fun `三节点图谱在三档缩放下都不叠字`() {
        val nodes = graph(anomalies = 0, profiles = 0)
        listOf(GraphLabelDetail.FULL, GraphLabelDetail.PRIMARY_ONLY, GraphLabelDetail.CATEGORY_ONLY)
            .forEach { detail ->
                val placed = planGraphLabels(nodes, 1080f, 900f, detail, ::measure)
                assertNoOverlap(placed)
                assertInsideCanvas(placed, 1080f, 900f)
                // 根与两个分类是所有档位都必须画出来的骨架
                assertEquals(
                    "$detail 档丢了骨架标签",
                    setOf("root", "cat-anomaly", "cat-profile"),
                    placed.map { it.nodeId }.toSet(),
                )
            }
    }

    @Test
    fun `十条条目在小屏上仍然不叠字`() {
        val nodes = graph(anomalies = 5, profiles = 5)
        listOf(GraphLabelDetail.FULL, GraphLabelDetail.PRIMARY_ONLY, GraphLabelDetail.CATEGORY_ONLY)
            .forEach { detail ->
                val placed = planGraphLabels(nodes, 360f, 640f, detail, ::measure)
                assertNoOverlap(placed)
                assertInsideCanvas(placed, 360f, 640f)
            }
    }

    @Test
    fun `窄画布上分类与条目争位置时也不叠字`() {
        // 200×200 是极端窄屏：根、分类、条目的标签块必然争同一片区域。
        // 这正是审查报告里三节点「文字压在一起」的场景，避让必须让它们分开或让位
        val nodes = graph(anomalies = 1, profiles = 1)
        val placed = planGraphLabels(nodes, 200f, 200f, GraphLabelDetail.FULL, ::measure)
        assertNoOverlap(placed)
        assertInsideCanvas(placed, 200f, 200f)
        assertTrue("根节点必须还在", placed.any { it.nodeId == "root" })
    }

    @Test
    fun `空间不足时隐藏标签而不是叠在一起`() {
        val nodes = graph(anomalies = 4, profiles = 4)
        // 每块都大到放不下几张：此时正确行为是少画，而不是都画在同一点
        val huge = { _: GraphLabelNode, _: Boolean -> LabelMetrics(120f, 20f) }
        val placed = planGraphLabels(nodes, 300f, 160f, GraphLabelDetail.FULL, huge)
        assertNoOverlap(placed)
        assertInsideCanvas(placed, 300f, 160f)
        assertTrue("放不下时应当隐藏部分标签，实际全画了", placed.size < nodes.size)
        assertTrue("根节点必须还在", placed.any { it.nodeId == "root" })
    }

    @Test
    fun `缩放越小留下的信息越少`() {
        assertEquals(GraphLabelDetail.FULL, labelDetailFor(2.5f))
        assertEquals(GraphLabelDetail.FULL, labelDetailFor(1.15f))
        assertEquals(GraphLabelDetail.PRIMARY_ONLY, labelDetailFor(1.0f))
        assertEquals(GraphLabelDetail.PRIMARY_ONLY, labelDetailFor(0.85f))
        assertEquals(GraphLabelDetail.CATEGORY_ONLY, labelDetailFor(0.6f))

        val nodes = graph(anomalies = 1, profiles = 1)
        val full = planGraphLabels(nodes, 1080f, 900f, GraphLabelDetail.FULL, ::measure)
        val primary = planGraphLabels(nodes, 1080f, 900f, GraphLabelDetail.PRIMARY_ONLY, ::measure)
        val category = planGraphLabels(nodes, 1080f, 900f, GraphLabelDetail.CATEGORY_ONLY, ::measure)

        // 全档：条目节点也带计数
        assertTrue("全档下条目节点应当带计数", full.filter { isGraphItemNode(it.nodeId) }.all { it.showSub })
        // 中档：条目节点只留名称，计数只留在根与分类上
        assertTrue(
            "中档下条目节点不该再画计数",
            primary.filter { isGraphItemNode(it.nodeId) }.none { it.showSub },
        )
        assertTrue("中档下分类节点仍应画计数", primary.filter { !isGraphItemNode(it.nodeId) }.all { it.showSub })
        // 低档：条目节点整个不画字，只留骨架
        assertTrue(
            "低档下条目节点不该再有标签",
            category.none { isGraphItemNode(it.nodeId) },
        )
    }

    @Test
    fun `标签不压在自己的节点圆上`() {
        val nodes = graph(anomalies = 3, profiles = 3)
        val placed = planGraphLabels(nodes, 1080f, 900f, GraphLabelDetail.FULL, ::measure)
        val byId = nodes.associateBy { it.id }
        placed.forEach { label ->
            val node = byId.getValue(label.nodeId)
            val cx = node.xRatio * 1080f
            val cy = node.yRatio * 900f
            val r = node.radius * minOf(1080f, 900f)
            val overlapsCircle = label.left < cx + r &&
                cx - r < label.left + label.width &&
                label.top < cy + r &&
                cy - r < label.top + label.height
            assertTrue("${label.nodeId} 的标签压在节点圆上", !overlapsCircle)
        }
    }
}
