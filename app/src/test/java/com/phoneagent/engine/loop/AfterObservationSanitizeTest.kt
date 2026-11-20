package com.phoneagent.engine.loop

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作后差异摘要的脱敏回归。
 *
 * 差异文本（`新增 N 个：<label>`）与页面文本走同一条上云通道，原先只过 `sanitize`：
 * 若新增的正是「884213」这种独立数字控件，它自身既没有引导词、也不到 11 位数字，
 * 两条规则都不会命中。这里用整页上下文口径（页面存在密码框）兜住。
 */
class AfterObservationSanitizeTest {

    private fun el(
        index: Int,
        text: String? = null,
        editable: Boolean = false,
        isPassword: Boolean = false,
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
        editable = editable,
        isPassword = isPassword,
    )

    private fun snapshot(pkg: String, vararg elements: UiElement) = ScreenSnapshot(
        packageName = pkg,
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
    )

    @Test
    fun `页面含密码框时差异文本里的独立验证码被掩码`() {
        val before = snapshot("com.cmbchina", el(0, text = "请输入一卡通密码", editable = true, isPassword = true))
        val after = snapshot(
            "com.cmbchina",
            el(0, text = "请输入一卡通密码", editable = true, isPassword = true),
            el(1, text = "884213"),
        )
        val diff = AfterObservationPolicy.buildDiff(before, after)
        assertFalse("差异文本仍含明文验证码：\n$diff", diff.contains("884213"))
        assertTrue("差异文本未出现掩码：\n$diff", diff.contains("******"))
    }

    @Test
    fun `没有密码框上下文时差异文本里的数字保持原样`() {
        val before = snapshot("com.example.shop", el(0, text = "订单"))
        val after = snapshot("com.example.shop", el(0, text = "订单"), el(1, text = "4829"))
        val diff = AfterObservationPolicy.buildDiff(before, after)
        assertTrue("普通页面把金额误掩码了：\n$diff", diff.contains("4829"))
    }
}
