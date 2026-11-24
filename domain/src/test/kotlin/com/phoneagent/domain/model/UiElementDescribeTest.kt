package com.phoneagent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 元素描述行格式回归。
 *
 * `describe()` 的每一行都直接进入决策提示词（元素树章节），本轮把它从
 * 「List<String> + joinToString」改成 `buildString`，输出字节必须完全一致：
 * 顺序固定为 [编号] 类名(类型) → id → label → center/bounds，缺省项整段省略。
 */
class UiElementDescribeTest {

    @Test
    fun `完整字段`() {
        val e = UiElement(
            index = 12,
            className = "android.widget.Button",
            type = "Button",
            text = "去支付",
            viewId = "btn_pay",
            x = 540, y = 1200,
            left = 400, top = 1160, right = 680, bottom = 1240,
            clickable = true,
        )
        assertEquals(
            "[#12] android.widget.Button(Button) id=btn_pay label=\"去支付\" " +
                "center=(540,1200) bounds=(400,1160)-(680,1240)",
            e.describe(),
        )
    }

    @Test
    fun `text 为空时用 contentDescription`() {
        val e = UiElement(
            index = 3,
            className = "android.widget.ImageView",
            type = "Image",
            text = " ",
            contentDescription = "关闭",
            x = 0, y = 0, left = 0, top = 0, right = 10, bottom = 10,
        )
        assertEquals(
            "[#3] android.widget.ImageView(Image) label=\"关闭\" center=(5,5) bounds=(0,0)-(10,10)",
            e.describe(),
        )
    }

    @Test
    fun `无 id 无标签时只保留结构字段`() {
        val e = UiElement(
            index = 0,
            className = "android.view.View",
            type = "View",
            x = 0, y = 0, left = 0, top = 0, right = 4, bottom = 8,
        )
        assertEquals(
            "[#0] android.view.View(View) center=(2,4) bounds=(0,0)-(4,8)",
            e.describe(),
        )
    }
}
