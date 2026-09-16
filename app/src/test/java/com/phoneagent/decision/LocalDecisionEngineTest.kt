package com.phoneagent.decision

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.domain.rules.LocalDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 端侧决策引擎测试：四类本地可直接处理的页面 + 防死循环回环保护。
 * 注意：JUnit4 同一测试类复用实例，引擎必须在每个用例前重建，避免连续计数状态串扰。
 */
class LocalDecisionEngineTest {

    private lateinit var engine: LocalDecisionEngine

    @Before
    fun freshEngine() {
        engine = LocalDecisionEngine()
    }

    private fun elem(
        index: Int,
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 60,
        text: String? = null,
        type: String = "Button",
    ) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = type,
        text = text,
        x = (left + right) / 2,
        y = (top + bottom) / 2,
        left = left, top = top, right = right, bottom = bottom,
        clickable = true,
    )

    private fun snapshot(vararg elements: UiElement) = ScreenSnapshot(
        packageName = "com.test.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements.toList(),
    )

    // ---- dialog_overlay ----

    @Test
    fun 弹窗_优先点击允许按钮() {
        val s = snapshot(
            elem(0, text = "系统提醒"),
            elem(1, text = "允许", type = "Button"),
            elem(2, text = "取消", type = "Button"),
        )
        val intent = engine.decide(s)
        assertNotNull(intent)
        assertEquals(IntentType.TAP, intent?.intent)
        assertEquals("允许", intent?.target?.value)
    }

    @Test
    fun 仅关闭按钮无正向按钮_不判为弹窗_交回云端() {
        // inferPageType 判定弹窗需要正向+关闭按钮同时存在，因此该分支不经由 dialog_overlay
        val s = snapshot(
            elem(0, text = "取消", type = "Button"),
        )
        assertNull(engine.decide(s))
    }

    // ---- ad_with_countdown ----

    @Test
    fun 倒计时广告_点击跳过() {
        val s = snapshot(
            elem(0, text = "广告", type = "TextView"),
            elem(1, text = "3 秒后可跳过", type = "Button"),
        )
        val intent = engine.decide(s)
        assertNotNull(intent)
        assertEquals(IntentType.TAP, intent?.intent)
        assertTrue(intent?.target?.value?.contains("跳过") == true || intent?.target?.by == "text")
    }

    // ---- loading ----

    @Test
    fun 加载中_输出等待意图() {
        val s = snapshot(
            elem(0, text = "加载中", type = "ProgressBar"),
        )
        val intent = engine.decide(s)
        assertNotNull(intent)
        assertEquals(IntentType.WAIT, intent?.intent)
    }

    // ---- error ----

    @Test
    fun 异常页_有点击重试时点击重试() {
        val s = snapshot(
            elem(0, text = "网络异常"),
            elem(1, text = "点击重试", type = "Button"),
        )
        val intent = engine.decide(s)
        assertNotNull(intent)
        assertEquals(IntentType.TAP, intent?.intent)
        assertTrue(intent?.target?.value?.contains("重试") == true)
    }

    @Test
    fun 异常页_无重试按钮时按返回键() {
        val s = snapshot(
            elem(0, text = "连接失败", type = "TextView"),
        )
        val intent = engine.decide(s)
        assertNotNull(intent)
        assertEquals(IntentType.PRESS, intent?.intent)
        assertEquals("BACK", intent?.key)
    }

    // ---- completion ----

    @Test
    fun 完成页_元素稀疏时直接结束() {
        val s = snapshot(
            elem(0, text = "支付成功", type = "TextView"),
            elem(1, text = "完成", type = "Button"),
        )
        val intent = engine.decide(s)
        assertNotNull(intent)
        assertEquals(IntentType.FINISH, intent?.intent)
    }

    @Test
    fun 完成页_元素过多时交还云端判断() {
        val elements = (0 until 8).map { i ->
            elem(i, left = i * 100, right = i * 100 + 90, text = "项目$i", type = "TextView")
        } + elem(99, left = 0, top = 2000, right = 300, bottom = 2060, text = "提交成功", type = "TextView")
        val s = snapshot(*elements.toTypedArray())
        assertNull(engine.decide(s))
    }

    // ---- 普通页面 ----

    @Test
    fun 普通列表页_返回null交还云端() {
        val s = snapshot(
            elem(0, text = "首页", type = "Tab"),
            elem(1, text = "我的", type = "Tab"),
            elem(2, text = "消息", type = "Tab"),
            elem(3, text = "设置", type = "Tab"),
            elem(4, text = "搜索", type = "Tab"),
        )
        assertNull(engine.decide(s))
    }

    // ---- 防死循环 ----

    @Test
    fun 连续五次本地决策后_强制走云端() {
        val s = snapshot(
            elem(0, text = "系统提醒"),
            elem(1, text = "允许", type = "Button"),
            elem(2, text = "取消", type = "Button"),
        )
        // 前 4 次均返回本地意图
        repeat(4) { assertNotNull(engine.decide(s)) }
        // 第 5 次：达到阈值，强制返回 null（走云端）
        assertNull(engine.decide(s))
    }

    @Test
    fun 一次云端决策_重置连续计数() {
        val dialog = snapshot(
            elem(0, text = "系统提醒"),
            elem(1, text = "允许", type = "Button"),
            elem(2, text = "取消", type = "Button"),
        )
        val normal = snapshot(elem(0, text = "首页", type = "Tab"))
        // 两次本地决策（计数=2）
        assertNotNull(engine.decide(dialog))
        assertNotNull(engine.decide(dialog))
        // 一次云端决策（计数清零）
        assertNull(engine.decide(normal))
        // 再连续 5 次本地：第 5 次达到阈值触发防死循环
        repeat(4) { engine.decide(dialog) }
        assertNull(engine.decide(dialog))
    }
}
