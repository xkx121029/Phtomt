package com.phoneagent.engine.execution

import com.phoneagent.device.a11y.NodeHit
import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危险目标闸门的判据测试（纯 JVM）。
 *
 * 钉住的是「兜底判据到底读谁的标签」这件事：
 * - 坐标点击时 `action.target` 根本是 null（转译层拿不到元素就不填 target），
 *   过去 `ExecutionPhase.hazardWordOf` 只读 `action.target?.value`，于是整条旁路静默；
 *   现在证据来自落点探针 [NodeHit]，这才是"会落到谁身上"的答案。
 * - 判据按动作类型收敛：点击/长按/滑动都算，但**手势类型本身不是危险信号**，
 *   看的是目标控件——普通列表上滑动不该打扰用户。
 */
class DangerousTargetGateTest {

    private val asked = mutableListOf<AgentAction>()

    private fun gate(allow: Boolean = true) =
        DangerousTargetGate(confirm = { action -> asked += action; allow })

    private fun element(label: String) = UiElement(
        index = 0,
        className = "android.widget.Button",
        type = "Button",
        text = label,
        x = 10,
        y = 20,
        left = 0,
        top = 0,
        right = 20,
        bottom = 40,
        clickable = true,
    )

    private fun hit(label: String) = NodeHit(label = label, viewId = null, className = "android.widget.Button", clickable = true)

    // ---- 坐标点击：唯一能看见真实控件的就是落点探针 ----

    @Test
    fun 坐标点击_落点命中删除并退出_必须确认() = runTest {
        // 真实场景：AI 说 tap {by:"coordinate"}，转译层 elem==null → target==null
        val action = AgentAction(type = ActionType.TAP, x = 500, y = 800)
        assertNull("前提：坐标点击的动作里没有 target", action.target)
        val reason = gate(allow = false).check(action, element = null, selector = null, hit = hit("删除并退出"))
        assertNotNull("命中危险词必须走确认，而不是直接派发", reason)
        assertEquals("确认门必须被问一次：$asked", 1, asked.size)
        assertEquals(
            "确认框里要显示真实控件名，不能只剩「点按」两个字",
            "删除并退出",
            asked.single().target?.value,
        )
        assertTrue("原因文案要带上是哪个控件：$reason", reason!!.contains("删除并退出"))
    }

    @Test
    fun 坐标点击_落点命中普通控件_不询问() = runTest {
        val action = AgentAction(type = ActionType.TAP, x = 500, y = 800)
        val reason = gate().check(action, element = null, selector = null, hit = hit("下一页"))
        assertNull("普通目标不该打扰用户", reason)
        assertTrue("不该走到确认门：$asked", asked.isEmpty())
    }

    // ---- by=hint：定位到真实控件后，标签取自控件而不是模型的说法 ----

    @Test
    fun hint定位_已知控件标签命中_必须确认() = runTest {
        // by=hint 是 AgentIntentTarget.by 的默认值；模型可能只说"那个红色按钮"
        val action = AgentAction(type = ActionType.TAP, target = ActionTarget(method = "label", value = "那个红色按钮"))
        val reason = gate(allow = false).check(action, element = element("确认支付 23.00"))
        assertNotNull("真实控件命中危险词就必须确认，哪怕模型的说法很干净", reason)
        assertEquals("确认框显示的是真实控件名", "确认支付 23.00", asked.single().target?.value)
    }

    @Test
    fun hint定位_模型说法危险但真实控件普通_不询问() = runTest {
        // 反向误伤检查：AI 在 target 里写了个吓人的描述，但真正点的是「下一页」
        val action = AgentAction(type = ActionType.TAP, target = ActionTarget(method = "label", value = "删除"))
        val reason = gate().check(action, element = element("下一页"), selector = null, hit = hit("下一页"))
        // 真实控件证据优先于模型自报，上一个探针已经把「下一页」排在最前
        assertNull("真实控件不危险就不该按模型的说法拦：$reason", reason)
    }

    @Test
    fun tap_普通目标_不询问() = runTest {
        val action = AgentAction(type = ActionType.TAP, target = ActionTarget(method = "label", value = "搜索"))
        assertNull(gate().check(action, element("搜索")))
        assertTrue(asked.isEmpty())
    }

    // ---- 长按：同样按目标控件判定 ----

    @Test
    fun long_press_落在删除并退出_触发确认() = runTest {
        val action = AgentAction(type = ActionType.LONG_PRESS, x = 300, y = 900)
        val reason = gate(allow = false).check(action, element = null, selector = null, hit = hit("删除并退出"))
        assertNotNull("长按与点击同一条判据：目标控件危险就要问", reason)
        assertEquals(1, asked.size)
    }

    @Test
    fun long_press_普通控件_不询问() = runTest {
        val action = AgentAction(type = ActionType.LONG_PRESS, x = 300, y = 900)
        assertNull(gate().check(action, element = null, selector = null, hit = hit("长按复制")))
        assertTrue(asked.isEmpty())
    }

    // ---- 滑动：手势不是危险信号，落点下的控件才是 ----

    @Test
    fun swipe_落在普通列表上_不触发确认() = runTest {
        // 这是必须守住的边界：滑动绝大多数时候只是滚动页面，收进判据会让每次翻页都弹窗
        val action = AgentAction(type = ActionType.SWIPE, direction = "up")
        assertNull(gate().check(action, element = null, selector = null, hit = hit("消息列表")))
        assertTrue("普通滑动不能打扰用户：$asked", asked.isEmpty())
    }

    @Test
    fun swipe_落点是删除控件_触发确认() = runTest {
        val action = AgentAction(type = ActionType.SWIPE, direction = "left")
        val reason = gate(allow = false).check(action, element = null, selector = null, hit = hit("删除并退出"))
        assertNotNull("滑动落在「删除并退出」上同样是不可逆操作", reason)
        assertEquals(1, asked.size)
    }

    @Test
    fun 纯方向滑动_不进闸门() = runTest {
        // SWIPE_UP/DOWN/LEFT/RIGHT 是不带目标的纯滚动，没有"目标控件"可看，
        // 收进来等于"所有翻页都要确认"。这条测试把这个边界钉成契约。
        val action = AgentAction(type = ActionType.SWIPE_UP)
        assertNull(gate().check(action, element = null, selector = null, hit = hit("删除并退出")))
        assertTrue("方向滚动不该被当成危险动作：$asked", asked.isEmpty())
    }

    // ---- 不重复打扰 ----

    @Test
    fun 同一目标只问一次() = runTest {
        val g = gate()
        val action = AgentAction(type = ActionType.TAP, x = 1, y = 2)
        assertNull(g.check(action, element = null, selector = null, hit = hit("删除")))
        assertNull(g.check(action, element = null, selector = null, hit = hit("删除")))
        assertEquals("同一目标问两遍只会让人盲点允许：$asked", 1, asked.size)
    }

    @Test
    fun 上层确认门已问过_不重复问() = runTest {
        val action = AgentAction(
            type = ActionType.TAP,
            target = ActionTarget(method = "label", value = "删除"),
            needsUserConfirmation = true,
        )
        assertNull(gate().check(action, element("删除")))
        assertTrue("needsUserConfirmation 已在执行阶段问过，不能再弹一次：$asked", asked.isEmpty())
    }
}
