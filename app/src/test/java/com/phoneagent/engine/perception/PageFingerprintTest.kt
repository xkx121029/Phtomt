package com.phoneagent.engine.perception

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
        selected: Boolean? = null,
    ) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = type,
        text = text,
        isSelected = selected,
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
        assertEquals(16, PageFingerprint.computeEffect(s).length)
    }

    // ---- 动作判据与止损判据的同源关系 ----

    @Test
    fun 控件状态变化_成功判据必须看得见() {
        val off = snapshot(elem(0, 0, 0, 100, 60, text = "WLAN", selected = false))
        val on = snapshot(elem(0, 0, 0, 100, 60, text = "WLAN", selected = true))

        // meaningful 服务页面记忆：开关状态一变就换节点会把记忆碎成一堆近似页
        assertEquals(PageFingerprint.computeMeaningful(off), PageFingerprint.computeMeaningful(on))
        // 止损看得见
        assertNotEquals(PageFingerprint.compute(off), PageFingerprint.compute(on))
        // 成功判据也必须看得见，否则「止损看到有推进、成功判据说没变化」，AI 被叫去换入口
        assertNotEquals(PageFingerprint.computeEffect(off), PageFingerprint.computeEffect(on))
    }

    @Test
    fun 止损看到的变化不少于成功判据() {
        // 不变式：凡 computeEffect 看得到的变化，compute 也必须看得到
        val cases = listOf(
            snapshot(elem(0, 0, 0, 100, 60, text = "确定")) to
                snapshot(elem(0, 0, 0, 100, 60, text = "取消")),
            snapshot(elem(0, 0, 0, 100, 60, text = "确定")) to
                snapshot(elem(0, 500, 500, 600, 560, text = "确定")),
            snapshot(elem(0, 0, 0, 100, 60, text = "WLAN", selected = false)) to
                snapshot(elem(0, 0, 0, 100, 60, text = "WLAN", selected = true)),
        )
        cases.forEach { (before, after) ->
            if (PageFingerprint.computeEffect(before) != PageFingerprint.computeEffect(after)) {
                assertNotEquals(
                    "成功判据看到了变化，止损也必须看到",
                    PageFingerprint.compute(before),
                    PageFingerprint.compute(after),
                )
            }
        }
    }

    @Test
    fun 动作判据忽略十像素内的抖动_止损仍然敏感() {
        val a = snapshot(elem(0, 0, 0, 100, 60, text = "确定"))
        val b = snapshot(elem(0, 3, 3, 103, 63, text = "确定"))

        // 动画/涟漪造成的几像素位移不该被报成「生效了」
        assertEquals(PageFingerprint.computeEffect(a), PageFingerprint.computeEffect(b))
        // 止损宁可敏感（多算一次"有推进"），也不能把推进漏掉
        assertNotEquals(PageFingerprint.compute(a), PageFingerprint.compute(b))
    }
}
