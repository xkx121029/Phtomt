package com.phoneagent.execution

import com.phoneagent.execution.CapabilityManager.Mode
import com.phoneagent.model.AgentAction
import com.phoneagent.model.AgentIntent
import com.phoneagent.model.AgentIntentTarget
import com.phoneagent.model.ActionType
import com.phoneagent.model.IntentType
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.UiElement
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 策略化拆分后的补充用例：针对只读横切守卫、默认值分支、滚动方向分支、深链字段透传与定位失败路径。
 * 通过公开 [IntentTranslator.translate] 断言，验证重构未改变行为约定。
 */
class IntentTranslatorStrategyTest {

    private lateinit var capabilityManager: CapabilityManager
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var translator: IntentTranslator

    @Before
    fun setUp() {
        capabilityManager = mockk()
        appNameResolver = mockk()
        translator = IntentTranslator(capabilityManager, appNameResolver, IntentResolver())
    }

    private fun mode(m: Mode) = every { capabilityManager.currentMode() } returns m

    private fun elem(
        index: Int,
        text: String,
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 60,
    ) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = "Button",
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

    private fun command(intent: AgentIntent, snapshot: ScreenSnapshot): AgentAction {
        val result = translator.translate(intent, snapshot)
        assertTrue("期望转译成功，实际: ${result}", result is IntentTranslator.TranslationResult.Command)
        return (result as IntentTranslator.TranslationResult.Command).action
    }

    private fun failure(intent: AgentIntent, snapshot: ScreenSnapshot): String {
        val result = translator.translate(intent, snapshot)
        assertTrue("期望转译失败", result is IntentTranslator.TranslationResult.Failed)
        return (result as IntentTranslator.TranslationResult.Failed).reason
    }

    @Test
    fun 只读模式_滑动也被拒绝() {
        mode(Mode.READONLY)
        val reason = failure(AgentIntent(intent = IntentType.SWIPE, direction = "up"), snapshot())
        assertTrue(reason.contains("只读模式"))
    }

    @Test
    fun finish_未给摘要_默认任务完成() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.FINISH), snapshot())
        assertEquals(ActionType.TASK_DONE, action.type)
        assertEquals("任务完成", action.summary)
    }

    @Test
    fun giveUp_未给原因_默认已放弃任务() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.GIVE_UP), snapshot())
        assertEquals(ActionType.TASK_DONE, action.type)
        assertEquals("已放弃任务", action.summary)
    }

    @Test
    fun scrollTo_目标含下方_向下滚() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "列表"))
        val action = command(
            AgentIntent(intent = IntentType.SCROLL_TO, target = AgentIntentTarget("text", "下方的入口")),
            s,
        )
        assertEquals(ActionType.SCROLL, action.type)
        assertEquals("down", action.direction)
    }

    @Test
    fun open_透传页面索引() {
        mode(Mode.SHIZUKU)
        val action = command(AgentIntent(intent = IntentType.OPEN, app = "QQ", page = 3), snapshot())
        assertEquals(ActionType.OPEN, action.type)
        assertEquals(3, action.page)
    }

    @Test
    fun longPress_目标未定位_转译失败() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "首页"))
        val reason = failure(
            AgentIntent(intent = IntentType.LONG_PRESS, target = AgentIntentTarget("text", "不存在的项")),
            s,
        )
        assertTrue(reason.contains("目标定位失败"))
    }
}