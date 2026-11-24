package com.phoneagent.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面记忆/路径边纯合并逻辑单测（PageMemoryOps）。
 * DataStore 读写依赖 Android Context 不在单测范围，这里覆盖：
 * merge 去重、visitCount 累计、上限淘汰、入参非法拒绝。
 */
class PageMemoryStoreOpsTest {

    private val PKG = "com.sankuai.meituan"
    private val FP_A = "fp_page_a"
    private val FP_B = "fp_page_b"

    private fun hotspot(label: String, rx: Float, ry: Float, sid: String = "") =
        PageMemoryHotspot(label = label, semanticId = sid, ratioX = rx, ratioY = ry)

    // ---- upsertEntry：合并与新建 ----

    @Test
    fun `同包名同指纹命中则合并并累计访问次数`() {
        val (list1, first) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "search_page", "搜索页", listOf(hotspot("搜索", 0.5f, 0.1f)), shapes = emptyList(), now = 1L,
        )!!
        val (list2, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "search_page", "搜索页", listOf(hotspot("取消", 0.9f, 0.9f)), shapes = emptyList(), now = 2L,
        )!!
        assertEquals(1, list2.size)
        assertEquals(2, merged.visitCount)
        // 热点并集：两次观察的控件都在
        assertEquals(listOf("搜索", "取消"), merged.hotspots.map { it.label })
        assertEquals(first.id, merged.id)
    }

    @Test
    fun `热点去重_同label同粗坐标视为同一控件`() {
        val (list1, _) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.502f, 0.101f)), shapes = emptyList(), now = 1L,
        )!!
        // 同 label、粗坐标一致（ratio×20 取整相同），只是比例坐标有细微抖动
        val (list2, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.508f, 0.104f)), shapes = emptyList(), now = 2L,
        )!!
        assertEquals(1, list2.size)
        assertEquals(1, merged.hotspots.size)
        assertEquals("搜索", merged.hotspots.single().label)
    }

    @Test
    fun `同label不同粗坐标视为两个控件`() {
        val (list1, _) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.5f, 0.1f)), shapes = emptyList(), now = 1L,
        )!!
        val (_, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.1f, 0.1f)), shapes = emptyList(), now = 2L,
        )!!
        assertEquals(2, merged.hotspots.size)
    }

    @Test
    fun `不同包名同指纹是两个页面`() {
        // 指纹不含包名，跨应用的同构页面必须各存一条
        val (list1, _) = PageMemoryOps.upsertEntry(
            emptyList(), "app.one", FP_A, "generic", "页", emptyList(), shapes = emptyList(), now = 1L,
        )!!
        val (list2, second) = PageMemoryOps.upsertEntry(
            list1, "app.two", FP_A, "generic", "页", emptyList(), shapes = emptyList(), now = 2L,
        )!!
        assertEquals(2, list2.size)
        assertEquals("app.two", second.appPackage)
        assertEquals(1, second.visitCount)
    }

    @Test
    fun `空白包名或指纹被拒绝`() {
        assertNull(PageMemoryOps.upsertEntry(emptyList(), "", FP_A, "generic", "页", emptyList(), shapes = emptyList(), now = 1L))
        assertNull(PageMemoryOps.upsertEntry(emptyList(), PKG, "  ", "generic", "页", emptyList(), shapes = emptyList(), now = 1L))
    }

    @Test
    fun `新建时空白pageType回落generic_空白title保留`() {
        val (_, created) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "", "", emptyList(), shapes = emptyList(), now = 1L,
        )!!
        assertEquals("generic", created.pageType)
        assertEquals("", created.title)
    }

    @Test
    fun `id自增且不为负`() {
        val (list1, _) = PageMemoryOps.upsertEntry(emptyList(), PKG, FP_A, "generic", "页", emptyList(), shapes = emptyList(), now = 1L)!!
        val (list2, second) = PageMemoryOps.upsertEntry(list1, PKG, FP_B, "generic", "页", emptyList(), shapes = emptyList(), now = 2L)!!
        assertEquals(1L, list2[0].id)
        assertEquals(2L, second.id)
    }

    @Test
    fun `空shapes合并时保留旧快照_新建时落空表`() {
        val (list1, created) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "generic", "页", emptyList(),
            shapes = listOf(PageShapeNode(kind = "button", label = "搜索", l = 0.1f, t = 0.1f, r = 0.3f, b = 0.15f)),
            now = 1L,
        )!!
        assertEquals(1, created.shapes.size)
        // 同指纹合并但本轮没抓到形态（空快照）：不覆盖旧值，避免改版后误清空
        val (_, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "generic", "页", emptyList(), shapes = emptyList(), now = 2L,
        )!!
        assertEquals(1, merged.shapes.size)
    }

    @Test
    fun `非空shapes合并时整页替换_不残留改版残影`() {
        val (list1, _) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "generic", "页", emptyList(),
            shapes = listOf(PageShapeNode(kind = "button", label = "旧按钮", l = 0.1f, t = 0.1f, r = 0.3f, b = 0.15f)),
            now = 1L,
        )!!
        val (_, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "generic", "页", emptyList(),
            shapes = listOf(
                PageShapeNode(kind = "text", label = "新标题", l = 0.1f, t = 0.05f, r = 0.9f, b = 0.1f),
                PageShapeNode(kind = "button", label = "新按钮", l = 0.1f, t = 0.2f, r = 0.9f, b = 0.25f),
            ),
            now = 2L,
        )!!
        assertEquals(listOf("新标题", "新按钮"), merged.shapes.map { it.label })
    }

    @Test
    fun `shapes超上限截断到32个`() {
        val shapes = (1..40).map { i ->
            // 错开纵排避免触发重叠淘汰，纯粹测上限截断
            PageShapeNode(kind = "text", label = "项$i", l = 0.0f, t = (i - 1) * 0.02f, r = 0.2f, b = (i - 1) * 0.02f + 0.01f)
        }
        val (_, created) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "generic", "页", emptyList(), shapes = shapes, now = 1L,
        )!!
        assertEquals(PageMemoryOps.MAX_PAGE_SHAPES, created.shapes.size)
    }

    @Test
    fun `重叠淘汰_无文字容器被内部小控件替代_有字容器保留`() {
        // 无文字容器（内含一个覆盖超六成的带字控件）：容器应被淘汰，小控件保留
        val kept = PageMemoryOps.dropOverlapping(
            listOf(
                PageShapeNode(kind = "other", label = "", l = 0.1f, t = 0.05f, r = 0.5f, b = 0.15f),
                PageShapeNode(kind = "button", label = "搜索", l = 0.1f, t = 0.05f, r = 0.5f, b = 0.13f),
            ),
        )
        assertEquals(listOf("搜索"), kept.map { it.label })
    }

    @Test
    fun `重叠淘汰_自带文字的容器保留`() {
        val kept = PageMemoryOps.dropOverlapping(
            listOf(
                PageShapeNode(kind = "other", label = "卡片标题", l = 0.0f, t = 0.0f, r = 1.0f, b = 0.2f),
                PageShapeNode(kind = "button", label = "搜索", l = 0.1f, t = 0.05f, r = 0.3f, b = 0.1f),
            ),
        )
        assertEquals(2, kept.size)
    }

    @Test
    fun `schema迁移判定_缺版本或旧版本都需清空`() {
        assertTrue(PageMemoryOps.needsShapeReset(null))
        assertTrue(PageMemoryOps.needsShapeReset(1))
        assertTrue(!PageMemoryOps.needsShapeReset(PageMemoryOps.PAGE_SHAPE_SCHEMA))
        assertTrue(!PageMemoryOps.needsShapeReset(PageMemoryOps.PAGE_SHAPE_SCHEMA + 1))
    }

    // ---- shapeSignature：同形页面去重 ----

    private fun buttonShape(label: String, sid: String = "", t: Float = 0.1f) =
        PageShapeNode(kind = "button", label = label, semanticId = sid, l = 0.1f, t = t, r = 0.9f, b = t + 0.05f)

    @Test
    fun `结构签名忽略文字标签_结构相同同签名`() {
        // 列表行内容不同（label 不同），控件结构一致 → 同签名
        val sigA = PageMemoryOps.shapeSignature(
            listOf(PageShapeNode(kind = "text", label = "首页", l = 0.1f, t = 0.02f, r = 0.9f, b = 0.06f), buttonShape("搜索")),
        )
        val sigB = PageMemoryOps.shapeSignature(
            listOf(PageShapeNode(kind = "text", label = "物品甲", l = 0.1f, t = 0.02f, r = 0.9f, b = 0.06f), buttonShape("确认")),
        )
        assertEquals(sigA, sigB)
    }

    @Test
    fun `结构签名_控件位置或类别不同则签名不同`() {
        val base = PageMemoryOps.shapeSignature(listOf(buttonShape("搜索")))
        assertNotEquals(base, PageMemoryOps.shapeSignature(listOf(buttonShape("搜索", t = 0.3f))))
        assertNotEquals(base, PageMemoryOps.shapeSignature(listOf(buttonShape("搜索", sid = "s1"))))
        assertNotEquals(base, PageMemoryOps.shapeSignature(listOf(PageShapeNode(kind = "input", label = "搜索", l = 0.1f, t = 0.1f, r = 0.9f, b = 0.15f))))
    }

    @Test
    fun `纯文字页无结构签名_不参与去重`() {
        assertEquals("", PageMemoryOps.shapeSignature(emptyList()))
        assertEquals(
            "",
            PageMemoryOps.shapeSignature(listOf(PageShapeNode(kind = "text", label = "纯文字", l = 0.1f, t = 0.1f, r = 0.9f, b = 0.2f))),
        )
    }

    @Test
    fun `指纹未命中但同包名同结构签名则归并进旧条目`() {
        val shapes = listOf(buttonShape("搜索"))
        val sig = PageMemoryOps.shapeSignature(shapes)
        val (list1, first) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "generic", "列表页", emptyList(), shapes = shapes, now = 1L, shapeSig = sig,
        )!!
        // 指纹不同（内容不同）、结构签名一致 → 归并，不新建
        val (list2, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_B, "generic", "另一列表页", emptyList(), shapes = shapes, now = 2L, shapeSig = sig,
        )!!
        assertEquals(1, list2.size)
        assertEquals(2, merged.visitCount)
        assertEquals(first.fingerprint, merged.fingerprint) // 规范指纹保持旧条目
    }

    @Test
    fun `结构签名归并只在同包名内生效`() {
        val shapes = listOf(buttonShape("搜索"))
        val sig = PageMemoryOps.shapeSignature(shapes)
        val (list1, _) = PageMemoryOps.upsertEntry(
            emptyList(), "app.one", FP_A, "generic", "页", emptyList(), shapes = shapes, now = 1L, shapeSig = sig,
        )!!
        val (list2, second) = PageMemoryOps.upsertEntry(
            list1, "app.two", FP_B, "generic", "页", emptyList(), shapes = shapes, now = 2L, shapeSig = sig,
        )!!
        assertEquals(2, list2.size)
        assertEquals("app.two", second.appPackage)
    }

    // ---- upsertEdge：合并与新建 ----

    @Test
    fun `同包名同起止同动作合并并累计count`() {
        val (list1, first) = PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "点击『搜索』", fromControl = "", now = 1L)!!
        val (list2, merged) = PageMemoryOps.upsertEdge(list1, PKG, FP_A, FP_B, "点击『搜索』", fromControl = "", now = 2L)!!
        assertEquals(1, list2.size)
        assertEquals(2, merged.count)
        assertEquals(first.id, merged.id)
    }

    @Test
    fun `动作文案不同是两条边`() {
        val (list1, _) = PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "点击『搜索』", fromControl = "", now = 1L)!!
        val (list2, _) = PageMemoryOps.upsertEdge(list1, PKG, FP_A, FP_B, "返回", fromControl = "", now = 2L)!!
        assertEquals(2, list2.size)
    }

    @Test
    fun `空白入参与自环边被拒绝`() {
        assertNull(PageMemoryOps.upsertEdge(emptyList(), "", FP_A, FP_B, "点击", fromControl = "", now = 1L))
        assertNull(PageMemoryOps.upsertEdge(emptyList(), PKG, "", FP_B, "点击", fromControl = "", now = 1L))
        assertNull(PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_A, "点击", fromControl = "", now = 1L))
        assertNull(PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "   ", fromControl = "", now = 1L))
    }

    @Test
    fun `动作文案超24字截断`() {
        val (_, edge) = PageMemoryOps.upsertEdge(
            emptyList(), PKG, FP_A, FP_B, "这是一个非常长的动作文案超过二十四个字的长度限制会被截断掉", fromControl = "", now = 1L,
        )!!
        assertTrue(edge.actionLabel.length <= 24)
    }

    @Test
    fun `来源控件非空覆盖空_补全早期数据`() {
        val (list1, first) = PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "点击『搜索』", fromControl = "", now = 1L)!!
        assertEquals("", first.fromControl)
        val (_, merged) = PageMemoryOps.upsertEdge(list1, PKG, FP_A, FP_B, "点击『搜索』", fromControl = "搜索按钮", now = 2L)!!
        assertEquals("搜索按钮", merged.fromControl)
    }

    // ---- trimByUpdatedAt：上限淘汰 ----

    @Test
    fun `超上限淘汰最旧的保留最新的`() {
        val entries = (1L..5L).map { i ->
            PageMemoryEntry(id = i, appPackage = PKG, fingerprint = "fp$i", pageType = "generic", updatedAt = i)
        }
        val trimmed = PageMemoryOps.trimByUpdatedAt(entries, 3) { it.updatedAt }
        assertEquals(listOf(5L, 4L, 3L), trimmed.map { it.id })
    }

    @Test
    fun `未超上限原样返回`() {
        val entries = (1L..3L).map { i ->
            PageMemoryEntry(id = i, appPackage = PKG, fingerprint = "fp$i", pageType = "generic", updatedAt = i)
        }
        assertEquals(entries, PageMemoryOps.trimByUpdatedAt(entries, 100) { it.updatedAt })
    }

    // ---- 常量：唯一截断实现点 ----

    @Test
    fun `上限常量在合理范围`() {
        assertTrue(PageMemoryOps.MAX_PAGE_MEMORIES in 10..1000)
        assertTrue(PageMemoryOps.MAX_PAGE_EDGES in 10..1000)
        assertNotNull(PageMemoryOps.MAX_PAGE_HOTSPOTS)
    }
}
