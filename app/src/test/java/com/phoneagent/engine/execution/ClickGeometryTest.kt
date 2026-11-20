package com.phoneagent.engine.execution

import com.phoneagent.engine.execution.ClickGeometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 点击落点几何：落点必须落在目标控件身上，而不是"屏幕边缘"。
 *
 * 对应真机失败：点击命中区偏移，点到目标旁边的控件。
 */
class ClickGeometryTest {

    private val screen = Rect(0, 0, 1080, 2400)
    private val safe = Rect(24, 80, 1056, 2336)

    @Test
    fun 落点已在目标内时原样返回() {
        val target = Rect(100, 300, 300, 400)
        assertEquals(150 to 350, ClickGeometry.clampInto(150, 350, target, safe, screen))
    }

    @Test
    fun 落点偏出目标时收回目标内() {
        val target = Rect(100, 300, 300, 400)
        val (x, y) = ClickGeometry.clampInto(900, 350, target, safe, screen)
        assertEquals(299, x)
        assertEquals(350, y)
    }

    @Test
    fun 贴边只露一小条时仍落在控件上() {
        // 控件横跨 x∈[-100,10]，只露右边 10px：夹进安全区边距(24)会落到控件外面（点到邻居）
        val target = Rect(-100, 300, 10, 400)
        val (x, _) = ClickGeometry.clampInto(0, 350, target, safe, screen)
        assertEquals(0, x)
    }

    @Test
    fun 控件完全在屏幕外时退回安全区() {
        val target = Rect(-300, -300, -100, -100)
        val (x, y) = ClickGeometry.clampInto(0, 0, target, safe, screen)
        assertEquals(24, x)
        assertEquals(80, y)
    }

    @Test
    fun 底部半露的行按可见部分夹取() {
        // 中心已在屏幕外（y=2500），可见部分 2300..2400，且安全区到 2336 为止
        val target = Rect(100, 2300, 900, 2600)
        val (_, y) = ClickGeometry.clampInto(500, 2500, target, safe, screen)
        assertEquals(2335, y)
    }

    @Test
    fun 快照坐标没有目标时只夹进安全区() {
        val (x, y) = ClickGeometry.clampInto(-50, 2500, null, safe, screen)
        assertEquals(24, x)
        assertEquals(2335, y)
    }

    @Test
    fun 可见交集为空说明控件点不到() {
        assertNull(ClickGeometry.visiblePart(Rect(0, 0, 10, 10), safe))
        assertEquals(
            Rect(24, 80, 100, 200),
            ClickGeometry.visiblePart(Rect(0, 0, 100, 200), safe),
        )
    }
}
