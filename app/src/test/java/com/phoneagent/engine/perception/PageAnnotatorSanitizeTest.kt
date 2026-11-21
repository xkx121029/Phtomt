package com.phoneagent.engine.perception

import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控件清单（端侧已识别控件）的脱敏回归。
 *
 * 这条文本与页面 toAiText 走同一条上云通道，原来逐控件过 DataSanitizer：
 * 「验证码」标签和「884213」分处两个控件时，数字那条自身既无引导词也无 11 位数字，
 * 两条正则都不会命中，6 位验证码原样进提示词。
 */
class PageAnnotatorSanitizeTest {

    private fun el(index: Int, text: String, editable: Boolean = false, semanticId: String? = null) = UiElement(
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
        editable = editable,
        semanticId = semanticId,
    )

    @Test
    fun `验证码与标签分处两个控件时也被掩码`() {
        val text = PageAnnotator.knownControlsText(
            listOf(
                el(0, text = "获取验证码", semanticId = "send_btn"),
                el(1, text = "884213", editable = true, semanticId = "confirm_btn"),
            )
        )
        assertFalse("控件清单仍含明文验证码：\n$text", text.contains("884213"))
        assertTrue("控件清单未出现掩码：\n$text", text.contains("******"))
    }

    @Test
    fun `无验证码上下文的普通控件文字保持原样`() {
        val text = PageAnnotator.knownControlsText(
            listOf(el(0, text = "确认", semanticId = "confirm_btn"))
        )
        assertTrue(text.contains("confirm_btn: 确认"))
    }
}
