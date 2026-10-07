package com.phoneagent.engine.memory

import com.phoneagent.data.store.PageMemoryOps
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.data.store.PageMemoryHotspot
import com.phoneagent.data.store.PageMemoryStore
import com.phoneagent.data.store.PagePathEdge
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.AnnotatedPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面记忆采集器单测：同页不重记、换页记新页、失败不记边、边 count 合并。
 * store 用内存假实现（PageMemoryStore 接口就是为了这个缝），scope 用 Unconfined
 * 让 fire-and-forget 落库同步跑完，便于断言。
 */
class PageMemoryRecorderTest {

    private val PKG = "com.example.app"

    /** 内存版存储：与 MemoryStore 同一套 PageMemoryOps 合并逻辑，行为一致 */
    private class FakeStore : PageMemoryStore {
        val pages = mutableListOf<PageMemoryEntry>()
        val edges = mutableListOf<PagePathEdge>()

        override suspend fun loadPageMemories(): List<PageMemoryEntry> = pages.toList()

        override suspend fun loadPageEdges(): List<PagePathEdge> = edges.toList()

        override suspend fun upsertPageMemory(
            appPackage: String, fingerprint: String, pageType: String, title: String,
            hotspots: List<PageMemoryHotspot>,
        ): PageMemoryEntry? {
            val (list, result) = PageMemoryOps.upsertEntry(pages, appPackage, fingerprint, pageType, title, hotspots, System.currentTimeMillis())
                ?: return null
            pages.clear(); pages.addAll(list)
            return result
        }

        override suspend fun upsertPageEdge(appPackage: String, fromFp: String, toFp: String, actionLabel: String): PagePathEdge? {
            val (list, result) = PageMemoryOps.upsertEdge(edges, appPackage, fromFp, toFp, actionLabel, System.currentTimeMillis())
                ?: return null
            edges.clear(); edges.addAll(list)
            return result
        }
    }

    private fun recorder(store: FakeStore): PageMemoryRecorder =
        PageMemoryRecorder(store, CoroutineScope(UnconfinedTestDispatcher()))

    private fun element(
        label: String, x: Int, y: Int, type: String = "Button",
        clickable: Boolean = true, semanticId: String? = null,
    ) = UiElement(
        index = 0, className = "android.widget.$type", type = type,
        text = label, x = x, y = y,
        left = x - 50, top = y - 20, right = x + 50, bottom = y + 20,
        clickable = clickable, semanticId = semanticId,
    )

    /** 一个可观测的页面快照：标题文字 + 若干可点击控件 */
    private fun page(vararg elements: UiElement, pkg: String = PKG, w: Int = 1080, h: Int = 2400): AnnotatedPage {
        val snapshot = ScreenSnapshot(packageName = pkg, screenWidth = w, screenHeight = h, elements = elements.toList())
        return AnnotatedPage(
            snapshot = snapshot, pageType = "generic", contextHint = "",
            elements = elements.toList(),
            fingerprint = com.phoneagent.engine.perception.PageFingerprint.computeMeaningful(snapshot),
        )
    }

    private fun tap(target: String) = AgentIntent(
        intent = IntentType.TAP,
        target = AgentIntentTarget(by = "text", value = target),
    )

    // ---- onPageObserved：新页入库 / 同页不重记 ----

    @Test
    fun `首次观察入库新页面并返回null`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        val hit = r.onPageObserved(
            page(element("首页", 100, 200), element("搜索", 300, 200)).snapshot,
            page(element("首页", 100, 200), element("搜索", 300, 200)),
        )
        assertNull(hit)
        assertEquals(1, store.pages.size)
        assertEquals(PKG, store.pages.single().appPackage)
        assertEquals(2, store.pages.single().hotspots.size)
    }

    @Test
    fun `同页反复观察不重复入库`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        val p = page(element("首页", 100, 200))
        r.onPageObserved(p.snapshot, p)
        r.onPageObserved(p.snapshot, p)
        r.onPageObserved(p.snapshot, p)
        assertEquals(1, store.pages.size)
    }

    @Test
    fun `换页记第二条且返回命中旧记忆`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        // 首个元素非可点击纯文字，作为页面标题
        val home = page(element("首页", 100, 200, clickable = false), element("搜索", 300, 200))
        r.onPageObserved(home.snapshot, home)
        val detail = page(element("详情", 100, 500))
        val hit = r.onPageObserved(detail.snapshot, detail)
        assertNull(hit) // 详情页是新页，不算命中
        assertEquals(2, store.pages.size)

        // 第三次任务（新 recorder，模拟跨任务）再遇到首页 → 命中已记忆页面
        val store2 = FakeStore().apply { pages.addAll(store.pages) }
        val r2 = recorder(store2)
        val hit2 = r2.onPageObserved(home.snapshot, home)
        assertNotNull(hit2)
        assertEquals("首页", hit2?.title)
    }

    @Test
    fun `跨应用同构页面各自入库`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        val appOne = page(element("首页", 100, 200), pkg = "app.one")
        r.onPageObserved(appOne.snapshot, appOne)
        val appTwo = page(element("首页", 100, 200), pkg = "app.two")
        val hit = r.onPageObserved(appTwo.snapshot, appTwo)
        assertNull(hit)
        assertEquals(2, store.pages.size)
    }

    // ---- 路径边：动作验证成功才记 ----

    @Test
    fun `动作成功带来换页则记边`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        val home = page(element("首页", 100, 200), element("搜索", 300, 200))
        r.onPageObserved(home.snapshot, home)
        r.onIntent(tap("搜索"))
        r.onStepResult(verified = true)
        val detail = page(element("详情", 100, 500))
        r.onPageObserved(detail.snapshot, detail)
        assertEquals(1, store.edges.size)
        val edge = store.edges.single()
        assertEquals("点击『搜索』", edge.actionLabel)
        assertEquals(1, edge.count)
    }

    @Test
    fun `动作失败不记边`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        val home = page(element("首页", 100, 200))
        r.onPageObserved(home.snapshot, home)
        r.onIntent(tap("搜索"))
        r.onStepResult(verified = false)
        val detail = page(element("详情", 100, 500))
        r.onPageObserved(detail.snapshot, detail)
        assertTrue(store.edges.isEmpty())
    }

    @Test
    fun `同路径同动作边count合并`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        repeat(2) {
            val home = page(element("首页", 100, 200))
            r.onPageObserved(home.snapshot, home)
            r.onIntent(tap("搜索"))
            r.onStepResult(verified = true)
            val detail = page(element("详情", 100, 500))
            r.onPageObserved(detail.snapshot, detail)
        }
        assertEquals(1, store.edges.size)
        assertEquals(2, store.edges.single().count)
    }

    @Test
    fun `敏感页绝不入库`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        // 微信支付页命中敏感词表（SensitivePageDetector 双保险）
        val pay = page(
            element("支付", 100, 200), element("确认付款", 300, 200),
            pkg = "com.tencent.mm",
        )
        val hit = r.onPageObserved(pay.snapshot, pay)
        assertNull(hit)
        assertTrue(store.pages.isEmpty())
    }

    @Test
    fun `reset清空跨步快照`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        val home = page(element("首页", 100, 200))
        r.onPageObserved(home.snapshot, home)
        r.onIntent(tap("搜索"))
        r.onStepResult(verified = true)
        r.reset()
        // reset 后 pending 边丢失，即使再观察换页也不该记上一轮的边
        val detail = page(element("详情", 100, 500))
        r.onPageObserved(detail.snapshot, detail)
        assertTrue(store.edges.isEmpty())
    }

    @Test
    fun `跨包名跳转不记边`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        val home = page(element("首页", 100, 200), pkg = "app.one")
        r.onPageObserved(home.snapshot, home)
        r.onIntent(tap("搜索"))
        r.onStepResult(verified = true)
        // 跳到了另一个应用：跳转不是同应用内路径，不记
        val other = page(element("其他页", 100, 500), pkg = "app.two")
        r.onPageObserved(other.snapshot, other)
        assertTrue(store.edges.isEmpty())
    }
}
