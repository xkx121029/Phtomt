package com.phoneagent.perception

import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTreeBuilderTest {

    private fun elem(index: Int, text: String?, clickable: Boolean = false,
                     editable: Boolean = false, selected: Boolean? = null,
                     semantic: String? = null, type: String = "Button") = UiElement(
        index = index, className = "android.widget.$type", type = type, text = text,
        isSelected = selected, x = 50, y = 100, left = 0, top = 80, right = 100, bottom = 120,
        clickable = clickable, editable = editable, semanticId = semantic, isVisibleToUser = true,
    )

    @Test
    fun 生成标准ID与字段() {
        val snapshot = ScreenSnapshot(packageName = "com.a", screenWidth = 1080, screenHeight = 2400,
            elements = listOf(elem(0, "首页", clickable = true)))
        val controls = ControlTreeBuilder.build(snapshot)
        assertEquals(1, controls.size)
        val n = controls[0]
        assertEquals("ctl_0", n.id)
        assertEquals("首页", n.name)
        assertEquals("首页", n.visibleText)
        assertEquals(0, n.index)
        assertEquals("android.widget.Button", n.extra?.className)
        // elementIndex 记录原树索引，供端侧回映射
        assertEquals(0, n.extra?.elementIndex)
    }

    @Test
    fun 过滤无价值控件() {
        val snapshot = ScreenSnapshot(packageName = "com.a", screenWidth = 1080, screenHeight = 2400,
            elements = listOf(
                elem(0, "点我", clickable = true),            // 保留
                elem(1, null, clickable = false).copy(isVisibleToUser = false), // 惰性+不可见 → 过滤
            ))
        val controls = ControlTreeBuilder.build(snapshot)
        assertEquals(1, controls.size)
        assertEquals("ctl_0", controls[0].id)
    }

    @Test
    fun includeAll保留全部并顺序编号() {
        val snapshot = ScreenSnapshot(packageName = "com.a", screenWidth = 1080, screenHeight = 2400,
            elements = listOf(
                elem(5, null), elem(6, "x"), elem(9, "y"),
            ))
        val controls = ControlTreeBuilder.build(snapshot, includeAll = true)
        assertEquals(3, controls.size)
        assertEquals("ctl_0", controls[0].id)
        assertEquals("ctl_1", controls[1].id)
        assertEquals("ctl_2", controls[2].id)
    }

    @Test
    fun 选中态当前值语义标注透传() {
        val e = elem(0, "夜间模式", clickable = true, selected = true, semantic = "switch_toggle", type = "Switch")
            .copy(currentValue = "开")
        val snapshot = ScreenSnapshot(packageName = "com.a", screenWidth = 1080, screenHeight = 2400,
            elements = listOf(e))
        val n = ControlTreeBuilder.build(snapshot)[0]
        assertEquals(true, n.isSelected)
        assertEquals("开", n.currentValue)
        assertEquals("switch_toggle", n.comment?.removePrefix("semantic="))
        assertEquals("switch_toggle", n.extra?.semantic)
    }

    @Test
    fun toAiText输出JSON风格() {
        val snapshot = ScreenSnapshot(packageName = "com.a", screenWidth = 1080, screenHeight = 2400,
            elements = listOf(elem(0, "确定", clickable = true)))
        val text = ControlTreeBuilder.toAiText(ControlTreeBuilder.build(snapshot))
        assertTrue(text.contains("\"id\":\"ctl_0\""))
        assertTrue(text.contains("\"name\":\"确定\""))
        assertTrue(text.contains("\"type\":\"Button\""))
        assertEquals("[]", ControlTreeBuilder.toAiText(emptyList()))
    }

    @Test
    fun idle时微信无isSelected保持空() {
        val e = elem(0, "确定", clickable = true)
        val n = ControlTreeBuilder.build(
            ScreenSnapshot(packageName = "com.a", screenWidth = 1080, screenHeight = 2400, elements = listOf(e)))[0]
        assertNull(n.isSelected)
        assertTrue(n.toAiLine().contains("\"id\":\"ctl_0\""))
    }
}