package com.phoneagent.device.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动几何：分屏下方向不能反过来，贴边时不能把位移缩成一次点击。
 *
 * 对应真机失败：分屏下"往下滚"变成往上滚；贴边滚动实际是一次点击，而 scroll 返回成功。
 */
class ScrollGestureTest {

    /** 主屏：竖屏物理尺寸 */
    private val screenW = 1080
    private val screenH = 2400

    /** 分屏时本应用窗口的高度（旧实现拿它钳制，于是两个刻度混用） */
    private val splitWindowH = 1200

    @Test
    fun 往下滚位移朝下且足够大() {
        // 元素中心 y=1200 正好是分屏窗口的下边界：旧实现用窗口高度 1200 钳制，
        // 终点被夹到 1199，比起点还高 1px——"往下滚"变成了往上滚 1px（也就是一次点击）
        val stroke = ScrollGesture.plan(540, 1200, screenW, screenH, "down")
        assertEquals(1800, stroke?.y2)
        assertEquals(1200, stroke?.y1)
        assertTrue("终点必须真的在起点下方", stroke!!.y2 > stroke.y1)
        assertTrue("位移必须远大于触摸阈值", stroke.travel >= ScrollGesture.MIN_TRAVEL_PX)
        // 旧实现用分屏窗口高度 splitWindowH 钳制，终点被夹到 1199 < 起点 1200，
        // 位移是 -1px（方向反了，而且只会被系统当成一次点击）
        assertTrue(
            "位移必须远大于旧实现给出的 1px",
            stroke.travel > 1 && stroke.y2 > splitWindowH,
        )
    }

    @Test
    fun 往上滚位移朝上() {
        val stroke = ScrollGesture.plan(540, 1200, screenW, screenH, "up")
        assertEquals(1200, stroke?.y1)
        assertEquals(600, stroke?.y2)
        assertTrue(stroke!!.y2 < stroke.y1)
        assertTrue(stroke.travel >= ScrollGesture.MIN_TRAVEL_PX)
    }

    @Test
    fun 起点贴下边时挪起点而不是缩成1像素() {
        // 元素中心贴屏幕底部（y=2390）：旧实现终点被夹到 2399，只剩 9px 位移（<触摸阈值）
        val stroke = ScrollGesture.plan(540, 2390, screenW, screenH, "down")
        assertEquals(2399, stroke?.y2)
        assertEquals(1799, stroke?.y1)
        assertEquals(ScrollGesture.STEP_PX, stroke?.travel)
    }

    @Test
    fun 起点贴上边时挪起点而不是缩成1像素() {
        val stroke = ScrollGesture.plan(540, 10, screenW, screenH, "up")
        assertEquals(0, stroke?.y2)
        assertEquals(ScrollGesture.STEP_PX, stroke?.y1)
        assertEquals(ScrollGesture.STEP_PX, stroke?.travel)
    }

    @Test
    fun 没有元素时从屏幕中心划() {
        val stroke = ScrollGesture.plan(null, null, screenW, screenH, "down")
        assertEquals(540, stroke?.x)
        assertEquals(1200, stroke?.y1)
        assertEquals(1800, stroke?.y2)
    }

    @Test
    fun 横向越界坐标被夹进屏内() {
        // 元素中心来自别的显示/旧尺寸时可能越界：落点必须仍在屏内
        val stroke = ScrollGesture.plan(2000, 1200, screenW, screenH, "down")
        assertEquals(1079, stroke?.x)
    }

    @Test
    fun 屏幕太小时明确失败而不是划1像素() {
        assertNull(ScrollGesture.plan(540, 20, screenW, 20, "down"))
        assertNull(ScrollGesture.plan(540, 20, 0, screenH, "down"))
    }
}
