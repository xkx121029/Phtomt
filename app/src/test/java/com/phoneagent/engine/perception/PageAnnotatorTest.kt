package com.phoneagent.engine.perception

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语义 id 标注回归。
 *
 * 本轮把 `assignSemanticIds` 从「26 个类别各扫一遍全量元素、每个谓词反复算 label」
 * 改为「元素特征预计算一次 + 每类一次扫描」。判定口径（首个命中、同元素只占一个类别、
 * 真弹窗才标 dlg_dismiss）必须一字不差，这里用典型页面把口径钉住。
 */
class PageAnnotatorTest {

    @Test
    fun `真弹窗标出正向与关闭按钮`() {
        val page = snapshot(
            el(0, text = "允许", clickable = true),
            el(1, text = "取消", clickable = true),
        )
        val ids = PageAnnotator.annotate(page).semanticIds
        assertEquals("dlg_allow", ids[0])
        assertEquals("dlg_dismiss", ids[1])
    }

    @Test
    fun `元素稀疏但无正向按钮时不标弹窗关闭`() {
        // 普通页右上角「取消」不应被当成弹窗关闭按钮
        val page = snapshot(
            el(0, text = "商品列表", clickable = true),
            el(1, text = "取消", clickable = true),
        )
        assertNull(PageAnnotator.annotate(page).semanticIds[1])
    }

    @Test
    fun `同类别只取首个命中元素`() {
        val page = snapshot(
            el(0, text = "发送", clickable = true),
            el(1, text = "发送", clickable = true),
        )
        val ids = PageAnnotator.annotate(page).semanticIds
        assertEquals("send_btn", ids[0])
        assertNull(ids[1])
    }

    @Test
    fun `同一元素只占用首个命中的类别`() {
        // 「确认」既命中 confirm_btn 的确认词，元素本身也没有其它更早的类别
        val page = snapshot(el(0, text = "保存", clickable = true))
        val ids = PageAnnotator.annotate(page).semanticIds
        assertEquals("confirm_btn", ids[0])
        assertEquals(1, ids.size)
    }

    @Test
    fun `可编辑搜索框优先识别为搜索框`() {
        val page = snapshot(
            el(0, text = "搜索", editable = true, clickable = true),
            el(1, text = "搜索", clickable = true),
        )
        val ids = PageAnnotator.annotate(page).semanticIds
        assertEquals("search_box", ids[0])
        assertEquals("search_btn", ids[1])
    }

    @Test
    fun `标注结果按优先级排序_可编辑最前`() {
        val page = snapshot(
            el(0, text = "普通文本"),
            el(1, text = "输入框", editable = true),
            el(2, text = "按钮", clickable = true),
        )
        val sorted = PageAnnotator.annotate(page).elements.map { it.index }
        assertEquals(listOf(1, 2, 0), sorted)
    }

    private fun snapshot(vararg elements: UiElement) = ScreenSnapshot(
        packageName = "com.example.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
    )

    private fun el(
        index: Int,
        text: String? = null,
        viewId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
    ) = UiElement(
        index = index,
        className = "android.widget.TextView",
        type = if (editable) "EditText" else "Text",
        text = text,
        x = 0,
        y = 0,
        left = 0,
        top = 0,
        right = 10,
        bottom = 10,
        clickable = clickable,
        editable = editable,
        viewId = viewId,
    )
}
