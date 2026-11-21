package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * swipe 的方向守卫（HAPP-32）。
 *
 * 能力注册表把 `direction` 标成 required，转译层却原样下传：缺方向或方向写错时
 * `EngineRules.swipeEndpoints` 落到 `else -> sx to sy`（起终点相同），
 * 400ms 的零长手势在系统侧就是一次定点按压——在长按菜单类界面上是纯副作用动作。
 * 方向解析还要接受大小写与中文写法，不能因为模型写了 `UP` / `向上` 就退化成按压。
 */
class SwipeDirectionGateTest {

    private lateinit var capabilityManager: CapabilityManager
    private lateinit var translator: IntentTranslator

    @Before
    fun setUp() {
        capabilityManager = mockk()
        every { capabilityManager.currentMode() } returns CapabilityManager.Mode.SHIZUKU
        translator = IntentTranslator(capabilityManager, mockk(relaxed = true), IntentResolver())
    }

    private fun snapshot() = ScreenSnapshot(
        packageName = "com.test.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(
            UiElement(
                index = 0,
                className = "android.widget.TextView",
                type = "Text",
                text = "首页",
                x = 540, y = 300,
                left = 100, top = 260, right = 980, bottom = 340,
                clickable = true,
            ),
        ),
    )

    @Test
    fun `swipe 缺 direction 按缺参补全_不下发零长手势`() {
        val result = translator.translate(AgentIntent(intent = IntentType.SWIPE), snapshot())
        assertTrue("缺方向必须停下，实际: $result", result is IntentTranslator.TranslationResult.MissingParam)
        assertEquals("direction", (result as IntentTranslator.TranslationResult.MissingParam).field)
    }

    @Test
    fun `swipe 方向写错明确失败_不回落到原地按压`() {
        val result = translator.translate(AgentIntent(intent = IntentType.SWIPE, direction = "diag"), snapshot())
        assertTrue("无法识别的方向必须失败，实际: $result", result is IntentTranslator.TranslationResult.Failed)
        assertTrue(
            "失败原因要点明合法取值，实际: ${(result as IntentTranslator.TranslationResult.Failed).reason}",
            result.reason.contains("up/down/left/right"),
        )
    }

    @Test
    fun `swipe 方向接受大小写与中文写法`() {
        val cases = mapOf(
            "UP" to "up", "Down" to "down", "向上" to "up", "向下" to "down", "LEFT" to "left", "向右" to "right",
        )
        for ((raw, expected) in cases) {
            val result = translator.translate(AgentIntent(intent = IntentType.SWIPE, direction = raw), snapshot())
            assertTrue("$raw 应被接受，实际: $result", result is IntentTranslator.TranslationResult.Command)
            val action = (result as IntentTranslator.TranslationResult.Command).action
            assertEquals("$raw 应归一化成 $expected", ActionType.SWIPE, action.type)
            assertEquals("$raw 应归一化成 $expected", expected, action.direction)
        }
    }
}
