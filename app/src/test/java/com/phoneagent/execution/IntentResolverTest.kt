package com.phoneagent.execution

import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目标定位器测试：id / text / coordinate / hint+视觉坐标 三级定位。
 */
class IntentResolverTest {

    private val resolver = IntentResolver()

    private fun elem(
        index: Int,
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 60,
        text: String? = null,
        viewId: String? = null,
        semanticId: String? = null,
    ) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = "Button",
        text = text,
        x = (left + right) / 2,
        y = (top + bottom) / 2,
        left = left, top = top, right = right, bottom = bottom,
        viewId = viewId,
        semanticId = semanticId,
        clickable = true,
    )

    private fun snapshot(vararg elements: UiElement) = ScreenSnapshot(
        packageName = "com.test.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
    )

    @Test
    fun byId_命中语义id() {
        val s = snapshot(
            elem(0, text = "发送", semanticId = "send_btn"),
            elem(1, text = "取消"),
        )
        val r = resolver.resolve(AgentIntentTarget(by = "id", value = "send_btn"), s)
        assertNotNull(r.element)
        assertEquals(0, r.element?.index)
        assertEquals(50, r.x)
        assertEquals(30, r.y)
    }

    @Test
    fun byId_匹配viewId后缀() {
        val s = snapshot(
            elem(0, text = "确定", viewId = "com.app:id/btn_confirm"),
        )
        val r = resolver.resolve(AgentIntentTarget(by = "id", value = "btn_confirm"), s)
        assertNotNull(r.element)
        assertEquals(0, r.element?.index)
    }

    @Test
    fun byText_包含匹配() {
        val s = snapshot(elem(0, text = "保存并发布"))
        val r = resolver.resolve(AgentIntentTarget(by = "text", value = "发布"), s)
        assertNotNull(r.element)
        assertEquals(0, r.element?.index)
    }

    @Test
    fun byCoordinate_比例坐标换算像素() {
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "0.5,0.25"), s)
        assertEquals(540, r.x)
        assertEquals(600, r.y)
        assertNull(r.element)
    }

    @Test
    fun byCoordinate_像素坐标原样使用() {
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "500,800"), s)
        assertEquals(500, r.x)
        assertEquals(800, r.y)
    }

    @Test
    fun byCoordinate_越界坐标被裁剪() {
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "2000,-50"), s)
        assertEquals(1080, r.x)
        assertEquals(0, r.y)
    }

    @Test
    fun byCoordinate_非法坐标返回空() {
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "abc,def"), s)
        assertNull(r.x)
        assertNull(r.y)
    }

    @Test
    fun byHint_直接使用视觉坐标() {
        // hint 语义：由调用方决策阶段已用视觉模型算出坐标，元素树内容不作为依据
        val s = snapshot(elem(0, text = "确认支付"))
        val r = resolver.resolve(AgentIntentTarget(by = "hint", value = "确认支付按钮"), s, visualCoordinate = 500 to 800)
        assertNull(r.element)
        assertEquals(500, r.x)
        assertEquals(800, r.y)
    }

    @Test
    fun byHint_元素树未命中时用视觉坐标() {
        val s = snapshot(elem(0, text = "首页"))
        val r = resolver.resolve(AgentIntentTarget(by = "hint", value = "右上角关闭按钮"), s, visualCoordinate = 1000 to 100)
        assertNull(r.element)
        assertEquals(1000, r.x)
        assertEquals(100, r.y)
    }

    @Test
    fun byText_找不到元素时回退视觉坐标() {
        val s = snapshot(elem(0, text = "首页"))
        val r = resolver.resolve(AgentIntentTarget(by = "text", value = "不存在按钮"), s, visualCoordinate = 90 to 90)
        assertNull(r.element)
        assertEquals(90, r.x)
        assertEquals(90, r.y)
    }

    @Test
    fun 无目标_返回空结果() {
        val s = snapshot(elem(0, text = "首页"))
        val r = resolver.resolve(null, s)
        assertNull(r.element)
        assertNull(r.x)
        assertNull(r.y)
    }

    @Test
    fun 找不到目标且无视觉坐标_返回空结果() {
        val s = snapshot(elem(0, text = "首页"))
        val r = resolver.resolve(AgentIntentTarget(by = "text", value = "不存在"), s)
        assertNull(r.element)
        assertNull(r.x)
        assertNull(r.y)
    }
}