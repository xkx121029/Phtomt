package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已交付但读不到页面」与「已交付、页面没变化」必须在结构上分开。
 *
 * 回归目标：同一个点击动作，止损那边（[com.phoneagent.engine.perception.PageFingerprint.compute]）
 * 看得见控件状态、成功判据那边却只比 type+label+粗坐标。真机场景是"可点的是行容器、
 * 状态变化在内部的 Switch 上"：验证判「页面与控件状态均无变化」，止损却看到指纹变了、
 * `pageStuckStreak` 复位——AI 于是被叫去「换入口」，对开关就是把它点回去。
 */
class ClickVerifierEffectTest {

    private fun row() = UiElement(
        index = 0,
        className = "android.widget.LinearLayout",
        type = "Layout",
        text = "WLAN",
        x = 540,
        y = 300,
        left = 40,
        top = 260,
        right = 1040,
        bottom = 340,
        clickable = true,
        viewId = "com.demo:id/row_wifi",
    )

    private fun innerSwitch(on: Boolean) = UiElement(
        index = 1,
        className = "android.widget.Switch",
        type = "Switch",
        text = "WLAN",
        isSelected = on,
        x = 960,
        y = 300,
        left = 900,
        top = 270,
        right = 1020,
        bottom = 330,
        clickable = false,
        viewId = "com.demo:id/switch_wlan",
    )

    private fun page(switchOn: Boolean) = ScreenSnapshot(
        packageName = "com.demo",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = listOf(row(), innerSwitch(switchOn)),
    )

    /** 行容器已经不在的一页（只剩一个无关元素） */
    private fun gonePage() = ScreenSnapshot(
        packageName = "com.demo",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = listOf(
            UiElement(
                index = 0,
                className = "android.widget.TextView",
                type = "Text",
                text = "详情页",
                x = 500,
                y = 900,
                left = 400,
                top = 880,
                right = 600,
                bottom = 920,
            ),
        ),
    )

    @Test
    fun `点行容器_内部开关状态变化也算生效`() = runTest {
        val verifier = ClickVerifier(capture = { page(switchOn = true) })
        val r = verifier.confirm(page(switchOn = false), row(), "点击")

        assertTrue("控件状态变了就是生效了，不能判「页面与控件状态均无变化」：${r.reason}", r.success)
        assertTrue("原因要点名是哪个层面变了：${r.reason}", r.reason.contains("页面已变化"))
    }

    @Test
    fun `滑动输入链路的整页指纹同样包含控件状态`() = runTest {
        val executor = VerifiedClickExecutor(capture = { page(switchOn = true) })
        val r = executor.executeAndVerify(page(switchOn = false), row()) { true }

        assertTrue("换到滑动/输入链路也必须看到开关变化：${r.reason}", r.success)
    }

    @Test
    fun `目标控件从新页面消失_不再静默当成没变化`() = runTest {
        // 新页面上已经找不到这个行容器（页面跳走/列表刷新）：这同样是"页面变了"的证据
        val verifier = ClickVerifier(capture = { gonePage() })
        val r = verifier.confirm(page(switchOn = false), row(), "点击")

        assertTrue(r.success)
        assertTrue("必须说清是目标消失，而不是笼统的「页面已变化」：${r.reason}", r.reason.contains("消失"))
    }

    @Test
    fun `页面和控件都没变_仍然是未生效`() = runTest {
        val verifier = ClickVerifier(capture = { page(switchOn = false) })
        val r = verifier.confirm(page(switchOn = false), row(), "点击")

        assertFalse(r.success)
        assertFalse("读到了页面就是证据：这里不是未确认", r.unconfirmed)
        assertEquals(Delivery.DELIVERED_NO_EFFECT, r.delivery)
        assertNotEquals(Delivery.DELIVERED_UNCONFIRMED, r.delivery)
    }
}
