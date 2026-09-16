package com.phoneagent.perception

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.PageFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 页面指纹测试：稳定性与敏感性。
 * - 相同页面 → 指纹一致（含元素顺序无关）
 * - 页面变化 → 指纹必变
 * - computeMeaningful 忽略微小坐标抖动（<10px 归一到 /10）
 */
class PageFingerprintTest {

    private fun elem(
        index: Int,
        left: Int, top: Int, right: Int, bottom: Int,
        text: String? = null,
        viewId: String? = null,
        type: String = "Button",
    ) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = type,
        text = text,
        x = (left + right) / 2,
        y = (top + bottom) / 2,
        left = left, top = top, right = right, bottom = bottom,
        viewId = viewId,
        clickable = true,
    )

    private fun snapshot(vararg elements: UiElement) = ScreenSnapshot(
        packageName = "com.test.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
    )

    @Test
    fun 相同页面_指纹一致() {
        val s1 = snapshot(
            elem(0, 0, 0, 100, 60, text = "确定", viewId = "btn_ok"),
            elem(1, 0, 100, 200, 160, text = "取消", viewId = "btn_cancel"),
        )
        val s2 = snapshot(
            elem(0, 0, 0, 100, 60, text = "确定", viewId = "btn_ok"),
            elem(1, 0, 100, 200, 160, text = "取消", viewId = "btn_cancel"),
        )
        assertEquals(PageFingerprint.compute(s1), PageFingerprint.compute(s2))
        assertEquals(PageFingerprint.computeMeaningful(s1), PageFingerprint.computeMeaningful(s2))
    }

    @Test
    fun 元素顺序不同_指纹一致() {
        // compute 内部按坐标排序，元素列表顺序不应影响指纹
        val s1 = snapshot(
            elem(0, 0, 0, 100, 60, text = "确定"),
            elem(1, 0, 100, 200, 160, text = "取消"),
        )
        val s2 = snapshot(
            elem(1, 0, 100, 200, 160, text = "取消"),
            elem(0, 0, 0, 100, 60, text = "确定"),
        )
        assertEquals(PageFingerprint.compute(s1), PageFingerprint.compute(s2))
    }

    @Test
    fun 文字变化_指纹必变() {
        val s1 = snapshot(elem(0, 0, 0, 100, 60, text = "确定"))
        val s2 = snapshot(elem(0, 0, 0, 100, 60, text = "取消"))
        assertNotEquals(PageFingerprint.compute(s1), PageFingerprint.compute(s2))
        assertNotEquals(PageFingerprint.computeMeaningful(s1), PageFingerprint.computeMeaningful(s2))
    }

    @Test
    fun 位置大幅变化_指纹必变() {
        val s1 = snapshot(elem(0, 0, 0, 100, 60, text = "确定"))
        val s2 = snapshot(elem(0, 500, 500, 600, 560, text = "确定"))
        assertNotEquals(PageFingerprint.compute(s1), PageFingerprint.compute(s2))
        assertNotEquals(PageFingerprint.computeMeaningful(s1), PageFingerprint.computeMeaningful(s2))
    }

    @Test
    fun meaningful指纹_忽略十像素内抖动() {
        val s1 = snapshot(elem(0, 0, 0, 100, 60, text = "确定", viewId = "btn_ok"))
        val s2 = snapshot(elem(0, 5, 5, 105, 65, text = "确定", viewId = "btn_ok"))
        assertEquals(PageFingerprint.computeMeaningful(s1), PageFingerprint.computeMeaningful(s2))
    }

    @Test
    fun compute指纹_对抖动敏感_而meaningful不敏感() {
        val s1 = snapshot(elem(0, 0, 0, 100, 60, text = "确定"))
        val s2 = snapshot(elem(0, 3, 3, 103, 63, text = "确定"))
        assertNotEquals(PageFingerprint.compute(s1), PageFingerprint.compute(s2))
        assertEquals(PageFingerprint.computeMeaningful(s1), PageFingerprint.computeMeaningful(s2))
    }

    @Test
    fun 空元素页面_指纹可计算() {
        val s = snapshot()
        assertEquals(16, PageFingerprint.compute(s).length)
        assertEquals(16, PageFingerprint.computeMeaningful(s).length)
    }

    @Test
    fun 指纹长度为16个字符() {
        val s = snapshot(elem(0, 0, 0, 100, 60, text = "确定"))
        assertEquals(16, PageFingerprint.compute(s).length)
        assertEquals(16, PageFingerprint.computeMeaningful(s).length)
    }
}
