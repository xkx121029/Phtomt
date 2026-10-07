package com.phoneagent.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            emptyList(), PKG, FP_A, "search_page", "搜索页", listOf(hotspot("搜索", 0.5f, 0.1f)), now = 1L,
        )!!
        val (list2, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "search_page", "搜索页", listOf(hotspot("取消", 0.9f, 0.9f)), now = 2L,
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
            emptyList(), PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.502f, 0.101f)), now = 1L,
        )!!
        // 同 label、粗坐标一致（ratio×20 取整相同），只是比例坐标有细微抖动
        val (list2, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.508f, 0.104f)), now = 2L,
        )!!
        assertEquals(1, list2.size)
        assertEquals(1, merged.hotspots.size)
        assertEquals("搜索", merged.hotspots.single().label)
    }

    @Test
    fun `同label不同粗坐标视为两个控件`() {
        val (list1, _) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.5f, 0.1f)), now = 1L,
        )!!
        val (_, merged) = PageMemoryOps.upsertEntry(
            list1, PKG, FP_A, "generic", "页", listOf(hotspot("搜索", 0.1f, 0.1f)), now = 2L,
        )!!
        assertEquals(2, merged.hotspots.size)
    }

    @Test
    fun `不同包名同指纹是两个页面`() {
        // 指纹不含包名，跨应用的同构页面必须各存一条
        val (list1, _) = PageMemoryOps.upsertEntry(
            emptyList(), "app.one", FP_A, "generic", "页", emptyList(), now = 1L,
        )!!
        val (list2, second) = PageMemoryOps.upsertEntry(
            list1, "app.two", FP_A, "generic", "页", emptyList(), now = 2L,
        )!!
        assertEquals(2, list2.size)
        assertEquals("app.two", second.appPackage)
        assertEquals(1, second.visitCount)
    }

    @Test
    fun `空白包名或指纹被拒绝`() {
        assertNull(PageMemoryOps.upsertEntry(emptyList(), "", FP_A, "generic", "页", emptyList(), now = 1L))
        assertNull(PageMemoryOps.upsertEntry(emptyList(), PKG, "  ", "generic", "页", emptyList(), now = 1L))
    }

    @Test
    fun `新建时空白pageType回落generic_空白title保留`() {
        val (_, created) = PageMemoryOps.upsertEntry(
            emptyList(), PKG, FP_A, "", "", emptyList(), now = 1L,
        )!!
        assertEquals("generic", created.pageType)
        assertEquals("", created.title)
    }

    @Test
    fun `id自增且不为负`() {
        val (list1, _) = PageMemoryOps.upsertEntry(emptyList(), PKG, FP_A, "generic", "页", emptyList(), now = 1L)!!
        val (list2, second) = PageMemoryOps.upsertEntry(list1, PKG, FP_B, "generic", "页", emptyList(), now = 2L)!!
        assertEquals(1L, list2[0].id)
        assertEquals(2L, second.id)
    }

    // ---- upsertEdge：合并与新建 ----

    @Test
    fun `同包名同起止同动作合并并累计count`() {
        val (list1, first) = PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "点击『搜索』", now = 1L)!!
        val (list2, merged) = PageMemoryOps.upsertEdge(list1, PKG, FP_A, FP_B, "点击『搜索』", now = 2L)!!
        assertEquals(1, list2.size)
        assertEquals(2, merged.count)
        assertEquals(first.id, merged.id)
    }

    @Test
    fun `动作文案不同是两条边`() {
        val (list1, _) = PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "点击『搜索』", now = 1L)!!
        val (list2, _) = PageMemoryOps.upsertEdge(list1, PKG, FP_A, FP_B, "返回", now = 2L)!!
        assertEquals(2, list2.size)
    }

    @Test
    fun `空白入参与自环边被拒绝`() {
        assertNull(PageMemoryOps.upsertEdge(emptyList(), "", FP_A, FP_B, "点击", now = 1L))
        assertNull(PageMemoryOps.upsertEdge(emptyList(), PKG, "", FP_B, "点击", now = 1L))
        assertNull(PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_A, "点击", now = 1L))
        assertNull(PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "   ", now = 1L))
    }

    @Test
    fun `动作文案超24字截断`() {
        val (_, edge) = PageMemoryOps.upsertEdge(emptyList(), PKG, FP_A, FP_B, "这是一个非常长的动作文案超过二十四个字的长度限制会被截断掉", now = 1L)!!
        assertTrue(edge.actionLabel.length <= 24)
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
