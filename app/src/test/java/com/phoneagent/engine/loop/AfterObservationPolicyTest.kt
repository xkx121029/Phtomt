package com.phoneagent.engine.loop

import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「动作即观察」的判定与差异摘要回归。
 *
 * 重点覆盖用户明确要求的两条边界：
 * 1) 只有真正交付、且会改变页面的动作才抓快照；
 * 2) 稀疏页 / 空树 / 加载中 / 强制视觉 / 过期一律不跳过完整观察（截图兜底不受影响）。
 */
class AfterObservationPolicyTest {

    private fun element(
        index: Int,
        label: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        className: String = "android.widget.TextView",
    ) = UiElement(
        index = index,
        className = className,
        type = "text",
        text = label,
        x = 10,
        y = 10,
        left = 0,
        top = 0,
        right = 100,
        bottom = 100,
        clickable = clickable,
        editable = editable,
    )

    private fun snapshot(
        packageName: String = "com.example.app",
        vararg elements: UiElement,
    ) = ScreenSnapshot(
        packageName = packageName,
        screenWidth = 1080,
        screenHeight = 1920,
        elements = elements.toList(),
    )

    private fun after(
        snap: ScreenSnapshot,
        capturedAtMs: Long = System.currentTimeMillis(),
    ) = AfterObservation(
        snapshot = snap,
        actionType = ActionType.CLICK,
        step = 1,
        diffText = "",
        capturedAtMs = capturedAtMs,
    )

    @Test
    fun `未验证交付的动作不抓快照`() {
        assertFalse(AfterObservationPolicy.shouldCapture(ActionType.CLICK, verified = false))
    }

    @Test
    fun `不改变页面的动作不抓快照`() {
        listOf(
            ActionType.WAIT,
            ActionType.SAY,
            ActionType.REMEMBER,
            ActionType.DEVICE_QUERY,
            ActionType.MCP_CALL,
            ActionType.WRITE_DOC,
            ActionType.TASK_DONE,
        ).forEach { type ->
            assertFalse("$type 不应抓快照", AfterObservationPolicy.shouldCapture(type, verified = true))
        }
    }

    @Test
    fun `会改变页面的动作才抓快照`() {
        listOf(ActionType.CLICK, ActionType.TAP, ActionType.TYPE_TEXT, ActionType.BACK, ActionType.OPEN)
            .forEach { type ->
                assertTrue("$type 应抓快照", AfterObservationPolicy.shouldCapture(type, verified = true))
            }
    }

    @Test
    fun `正常页面可以复用`() {
        val snap = snapshot(
            "",
            element(0, "返回", clickable = true),
            element(1, "搜索", clickable = true),
            element(2, "设置", clickable = true),
            element(3, "标题"),
        )
        assertNull(AfterObservationPolicy.isReusable(after(snap), AppSettings.Settings()))
    }

    @Test
    fun `元素树为空时不可复用`() {
        val snap = snapshot()
        assertEquals(AfterRejectReason.EMPTY, AfterObservationPolicy.isReusable(after(snap), AppSettings.Settings()))
    }

    @Test
    fun `元素稀疏时不可复用（截图兜底优先）`() {
        val snap = snapshot("", element(0, "确定", clickable = true), element(1, "取消", clickable = true))
        assertEquals(AfterRejectReason.SPARSE, AfterObservationPolicy.isReusable(after(snap), AppSettings.Settings()))
    }

    @Test
    fun `加载中页面不可复用`() {
        val snap = snapshot(
            "",
            element(0, "加载中…"),
            element(1, "请稍候"),
        )
        assertEquals(AfterRejectReason.LOADING, AfterObservationPolicy.isReusable(after(snap), AppSettings.Settings()))
    }

    @Test
    fun `强制视觉模式下每步都要截图`() {
        val snap = snapshot(
            "",
            element(0, "返回", clickable = true),
            element(1, "搜索", clickable = true),
            element(2, "设置", clickable = true),
            element(3, "标题"),
        )
        val settings = AppSettings.Settings(enableExternalVision = true, smartVisionRoute = false)
        assertEquals(AfterRejectReason.VISION_PRIMARY, AfterObservationPolicy.isReusable(after(snap), settings))
    }

    @Test
    fun `快照过期不可复用`() {
        val snap = snapshot(
            "",
            element(0, "返回", clickable = true),
            element(1, "搜索", clickable = true),
            element(2, "设置", clickable = true),
            element(3, "标题"),
        )
        val stale = after(snap, capturedAtMs = System.currentTimeMillis() - 10_000)
        assertEquals(AfterRejectReason.STALE, AfterObservationPolicy.isReusable(stale, AppSettings.Settings()))
    }

    @Test
    fun `差异摘要能识别新增与消失`() {
        val before = snapshot(
            "",
            element(0, "登录", clickable = true),
            element(1, "注册", clickable = true),
        )
        val now = snapshot(
            "",
            element(0, "登录", clickable = true),
            element(1, "注册", clickable = true),
            element(2, "欢迎回来"),
            element(3, "退出登录", clickable = true),
        )
        val diff = AfterObservationPolicy.buildDiff(before, now)
        assertTrue("应报告新增：$diff", diff.contains("新增"))
        assertTrue("应列出新增标签：$diff", diff.contains("欢迎回来") || diff.contains("退出登录"))
        assertTrue("元素数量应变化：$diff", diff.contains("2 → 4"))
    }

    @Test
    fun `页面未变化时摘要明确说明`() {
        val snap = snapshot("", element(0, "确定", clickable = true), element(1, "取消", clickable = true))
        val diff = AfterObservationPolicy.buildDiff(snap, snap)
        assertTrue("应说明内容未变：$diff", diff.contains("基本未变"))
    }

    @Test
    fun `前台应用变化会写进摘要`() {
        val before = snapshot("com.a", element(0, "首页"))
        val now = snapshot("com.b", element(0, "首页"))
        val diff = AfterObservationPolicy.buildDiff(before, now)
        assertTrue("应报告前台应用切换：$diff", diff.contains("com.a") && diff.contains("com.b"))
    }

    @Test
    fun `差异摘要限长且不超节点上限`() {
        val before = snapshot("")
        val added = (0 until 60).map { element(it, "控件$it", clickable = true) }
        val now = snapshot("", *added.toTypedArray())
        val diff = AfterObservationPolicy.buildDiff(before, now)
        assertTrue("摘要应限长", diff.length <= AfterObservationPolicy.MAX_DIFF_TEXT)
        // 最多列 8 个标签 + "等 N 个"，不该把 60 个标签全铺出来
        assertTrue("标签应截断：$diff", diff.contains("等"))
    }

    @Test
    fun `统计口径给出复用比例`() {
        AfterObservationStats.reset()
        AfterObservationStats.onReused()
        AfterObservationStats.onReused()
        AfterObservationStats.onRejected(AfterRejectReason.SPARSE)
        val summary = AfterObservationStats.summaryText()
        assertTrue("应报告 2/3：$summary", summary.contains("复用 2/3"))
        assertTrue("应报告 67%：$summary", summary.contains("67%"))
        assertTrue("应报告不可复用原因：$summary", summary.contains("元素稀疏"))
        assertEquals(2, AfterObservationStats.counts().first)
        assertNotNull(summary)
    }
}
