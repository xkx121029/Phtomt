package com.phoneagent.engine.execution

import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.execution.IntentResolver
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
    fun byCoordinate_像素口径已移除_越界值被拒绝() {
        // 旧实现在这里断言的正是"像素原样使用"（500,800 → 500,800），把两套口径钉成了契约。
        // 两套口径混在一个边界上就是 bug 本身：判定式是 f < 1.0，比例 1.0 会被当成"1 像素"。
        // 现在只认 0~1 比例，像素值越界即拒绝，交由上层报"坐标非法"而不是点去屏幕边缘。
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "500,800"), s)
        assertNull(r.x)
        assertNull(r.y)
    }

    @Test
    fun byCoordinate_比例上界落在屏幕最后一个像素() {
        // 旧实现上界写的是 dim 而不是 dim-1：1.0 会产出 (1080, 2400)，那是屏幕外 1px 的点，
        // 副屏出口不做验证直接回"已在副屏点击"——假成功。
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "1.0,1.0"), s)
        assertEquals(1079, r.x)
        assertEquals(2399, r.y)
    }

    @Test
    fun byCoordinate_底部中央不是屏幕顶端() {
        // "0.5,1.0" 是屏幕底部中央。旧实现把 1.0 当"1 像素"，y 变成 1，点到了屏幕顶端。
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "0.5,1.0"), s)
        assertEquals(540, r.x)
        assertEquals(2399, r.y)
    }

    @Test
    fun byCoordinate_负比例被拒绝() {
        val s = snapshot(elem(0, text = "无关"))
        val r = resolver.resolve(AgentIntentTarget(by = "coordinate", value = "0.5,-0.1"), s)
        assertNull(r.x)
        assertNull(r.y)
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

    // ---- 节点直点的定位线索：位置不是标识 ----
    // 无字图标（elementIndex 选中、没有 viewId 也没有文字）曾经能产出"只有 centerHint"的线索，
    // Provider 拿它去 NodesAtPoint 找"此刻压在这个点上的控件"并上溯祖先点下去，而这条直点路径
    // 不做落点审计：AI 选中图标、页面滚过一屏之后，点中的是邻居还判成功。

    @Test
    fun 无viewId无文字的元素不产出节点线索() {
        val icon = elem(3, left = 960, top = 200, right = 1040, bottom = 280)
        val action = com.phoneagent.domain.model.AgentAction(
            type = com.phoneagent.domain.model.ActionType.CLICK,
            elementIndex = 3,
        )
        assertNull(resolver.nodeSelectorOf(action, icon))
    }

    @Test
    fun 有viewId的元素产出节点线索() {
        val btn = elem(4, viewId = "com.app:id/btn_more")
        val action = com.phoneagent.domain.model.AgentAction(
            type = com.phoneagent.domain.model.ActionType.CLICK,
            elementIndex = 4,
        )
        val selector = resolver.nodeSelectorOf(action, btn)
        assertNotNull(selector)
        assertEquals("com.app:id/btn_more", selector?.viewId)
    }

    @Test
    fun 有文字的元素产出节点线索并保留位置线索() {
        val btn = elem(5, text = "更多", left = 900, top = 200, right = 1000, bottom = 260)
        val action = com.phoneagent.domain.model.AgentAction(
            type = com.phoneagent.domain.model.ActionType.CLICK,
            elementIndex = 5,
        )
        val selector = resolver.nodeSelectorOf(action, btn)
        assertNotNull(selector)
        assertEquals("更多", selector?.label)
        // 位置仍然保留：同 viewId / 同文字的控件常出现在列表每一行，Provider 靠它选最近的那个
        assertEquals(950 to 230, selector?.centerHint)
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
