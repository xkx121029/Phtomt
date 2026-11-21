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

    // 参数一一对应 UiElement 的字段，构造用例时按字段顺序读最直观
    @Suppress("LongParameterList")
    private fun elem(
        index: Int,
        left: Int, top: Int, right: Int, bottom: Int,
        text: String? = null,
        viewId: String? = null,
        type: String = "Button",
        selected: Boolean? = null,
        currentValue: String? = null,
    ) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = type,
        text = text,
        isSelected = selected,
        currentValue = currentValue,
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

    // ---- 控件状态进指纹：isSelected 与 currentValue 两项 ----
    //
    // 这两项是「同页面、同文字、同坐标，唯一变化在控件自身状态」的全部证据来源：
    // 开关（isSelected）、滑块/输入框（currentValue）。指纹里少了任何一项，
    // 连续点开关都会被算成同一页，止损判"路径已死"、成功判据判"没生效"，两条路一起错。

    @Test
    fun 同页面只差选中态_指纹必须不同() {
        val off = snapshot(elem(0, 0, 0, 100, 60, text = "WLAN", viewId = "wifi_toggle", selected = false))
        val on = snapshot(elem(0, 0, 0, 100, 60, text = "WLAN", viewId = "wifi_toggle", selected = true))

        // 成功判据与止损判据都必须看得见这一次状态变化
        assertNotEquals(
            "止损看不到选中态，连点开关会被判成同一页面",
            PageFingerprint.compute(off),
            PageFingerprint.compute(on),
        )
        assertNotEquals(
            "成功判据看不到选中态，点开关会被判成没生效",
            PageFingerprint.computeEffect(off),
            PageFingerprint.computeEffect(on),
        )
        // 页面记忆刻意不并状态：否则开关一变就把记忆碎成一堆只差一个开关的节点
        assertEquals(PageFingerprint.computeMeaningful(off), PageFingerprint.computeMeaningful(on))
    }

    @Test
    fun 同页面只差控件值_指纹必须不同() {
        val before = snapshot(
            elem(0, 0, 0, 100, 60, text = "亮度", viewId = "seek_brightness", currentValue = "30%"),
        )
        val after = snapshot(
            elem(0, 0, 0, 100, 60, text = "亮度", viewId = "seek_brightness", currentValue = "70%"),
        )

        assertNotEquals(
            "止损看不到滑块值，拖动滑块会被判成同一页面",
            PageFingerprint.compute(before),
            PageFingerprint.compute(after),
        )
        assertNotEquals(
            "成功判据看不到滑块值，拖动滑块会被判成没生效",
            PageFingerprint.computeEffect(before),
            PageFingerprint.computeEffect(after),
        )
        assertEquals(PageFingerprint.computeMeaningful(before), PageFingerprint.computeMeaningful(after))
    }

    @Test
    fun 选中态与控件值的四种组合两两不同指纹() {
        // 单项进指纹还不够：两项各占一个字段，混写（例如把 currentValue 拼进 label 的位置）
        // 会让两组不同的状态算出同一个哈希
        val combos = listOf(
            elem(0, 0, 0, 100, 60, text = "推送", selected = false, currentValue = "关"),
            elem(0, 0, 0, 100, 60, text = "推送", selected = true, currentValue = "关"),
            elem(0, 0, 0, 100, 60, text = "推送", selected = false, currentValue = "开"),
            elem(0, 0, 0, 100, 60, text = "推送", selected = true, currentValue = "开"),
        ).map { PageFingerprint.compute(snapshot(it)) }

        assertEquals("四种状态必须算出四个不同指纹：$combos", 4, combos.toSet().size)
    }

    @Test
    fun 控件状态为空与有值也必须区分() {
        // 旧快照里没有 currentValue（null），新快照读到了值：这同样是一次状态变化
        val unknown = snapshot(elem(0, 0, 0, 100, 60, text = "音量", viewId = "seek_volume"))
        val known = snapshot(
            elem(0, 0, 0, 100, 60, text = "音量", viewId = "seek_volume", currentValue = "50%"),
        )

        assertNotEquals(PageFingerprint.compute(unknown), PageFingerprint.compute(known))
        assertNotEquals(PageFingerprint.computeEffect(unknown), PageFingerprint.computeEffect(known))
    }
}
