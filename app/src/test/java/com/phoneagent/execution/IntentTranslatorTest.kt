package com.phoneagent.execution

import com.phoneagent.engine.execution.CapabilityManager.Mode
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 意图转译矩阵测试：覆盖全部意图类型 × 通道模式 × 定位成功/失败。
 * 目标定位使用真实 IntentResolver，通道与包名解析使用 MockK 打桩。
 */
class IntentTranslatorTest {

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

    /** 断言转译成功并返回非空命令（失败时测试断言即失败，因此返回非空） */
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

    // ---- open_app ----

    @Test
    fun open_app_shizuku通道_转译为shell启动命令() {
        mode(Mode.SHIZUKU)
        every { appNameResolver.resolve("微信") } returns "com.tencent.mm"
        val action = command(AgentIntent(intent = IntentType.OPEN_APP, app = "微信"), snapshot())
        assertEquals(ActionType.SHELL, action.type)
        assertEquals("launch com.tencent.mm", action.command)
        assertEquals("com.tencent.mm", action.packageName)
    }

    @Test
    fun open_app_无障碍通道_转译为launch() {
        mode(Mode.ACCESSIBILITY)
        every { appNameResolver.resolve("微信") } returns "com.tencent.mm"
        val action = command(AgentIntent(intent = IntentType.OPEN_APP, app = "微信"), snapshot())
        assertEquals(ActionType.LAUNCH, action.type)
        assertEquals("com.tencent.mm", action.packageName)
    }

    @Test
    fun open_app_未匹配已安装应用_转译失败() {
        mode(Mode.SHIZUKU)
        every { appNameResolver.resolve("不存在的应用") } returns null
        val reason = failure(AgentIntent(intent = IntentType.OPEN_APP, app = "不存在的应用"), snapshot())
        assertTrue(reason.contains("无法解析应用"))
    }

    // ---- open（深链直达）----

    @Test
    fun open深链_直接透传uri() {
        mode(Mode.SHIZUKU)
        val action = command(
            AgentIntent(intent = IntentType.OPEN, uri = "mqqapi://chat", app = "QQ", page = 0),
            snapshot(),
        )
        assertEquals(ActionType.OPEN, action.type)
        assertEquals("mqqapi://chat", action.uri)
    }

    // ---- tap / long_press / input ----

    @Test
    fun tap_命中元素_生成点击坐标() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "发送"))
        val action = command(AgentIntent(intent = IntentType.TAP, target = AgentIntentTarget("text", "发送")), s)
        assertEquals(ActionType.TAP, action.type)
        assertEquals(50, action.x)
        assertEquals(30, action.y)
        assertEquals(0, action.elementIndex)
    }

    @Test
    fun tap_目标未定位_转译失败() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "首页"))
        val reason = failure(AgentIntent(intent = IntentType.TAP, target = AgentIntentTarget("text", "不存在的按钮")), s)
        assertTrue(reason.contains("目标定位失败"))
    }

    @Test
    fun tap_无目标描述_转为缺参可追问() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "首页"))
        // 新语义：AI 只输出 tap 但缺 target → 返回 MissingParam（可追问补全），而非直接 Failed
        val result = translator.translate(AgentIntent(intent = IntentType.TAP), s)
        assertTrue("期望 MissingParam（缺 target 可追问），实际: $result", result is IntentTranslator.TranslationResult.MissingParam)
        assertEquals("target", (result as IntentTranslator.TranslationResult.MissingParam).field)
    }

    @Test
    fun longPress_命中元素_带时长() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "图标"))
        val action = command(
            AgentIntent(intent = IntentType.LONG_PRESS, target = AgentIntentTarget("text", "图标"), durationMs = 1500),
            s,
        )
        assertEquals(ActionType.LONG_CLICK, action.type)
        assertEquals(1500L, action.durationMs)
    }

    @Test
    fun input_命中输入框_生成输入命令() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "请输入手机号"))
        val action = command(
            AgentIntent(intent = IntentType.INPUT, target = AgentIntentTarget("text", "请输入手机号"), text = "13800138000"),
            s,
        )
        assertEquals(ActionType.TYPE_TEXT, action.type)
        assertEquals("13800138000", action.text)
        assertEquals(0, action.elementIndex)
    }

    // ---- swipe / press / wait / scroll_to ----

    @Test
    fun swipe_透传方向() {
        mode(Mode.ACCESSIBILITY)
        val action = command(AgentIntent(intent = IntentType.SWIPE, direction = "up", distancePx = 400), snapshot())
        assertEquals(ActionType.SWIPE, action.type)
        assertEquals("up", action.direction)
    }

    @Test
    fun press_back_归一化为BACK() {
        mode(Mode.ACCESSIBILITY)
        val action = command(AgentIntent(intent = IntentType.PRESS, key = "back"), snapshot())
        assertEquals(ActionType.KEY, action.type)
        assertEquals("BACK", action.keycode)
    }

    @Test
    fun press_ok_归一化为ENTER() {
        mode(Mode.ACCESSIBILITY)
        val action = command(AgentIntent(intent = IntentType.PRESS, key = "ok"), snapshot())
        assertEquals(ActionType.KEY, action.type)
        assertEquals("ENTER", action.keycode)
    }

    @Test
    fun wait_使用waitMs() {
        mode(Mode.ACCESSIBILITY)
        val action = command(AgentIntent(intent = IntentType.WAIT, waitMs = 1200), snapshot())
        assertEquals(ActionType.WAIT, action.type)
        assertEquals(1200L, action.timeoutMs)
    }

    @Test
    fun scrollTo_转译为滚动() {
        mode(Mode.ACCESSIBILITY)
        val s = snapshot(elem(0, "商品列表"))
        val action = command(
            AgentIntent(intent = IntentType.SCROLL_TO, target = AgentIntentTarget("text", "查看更多")),
            s,
        )
        assertEquals(ActionType.SCROLL, action.type)
        assertEquals("up", action.direction)
    }

    // ---- write_doc / finish / give_up ----

    @Test
    fun writeDoc_携带正文与文件名() {
        mode(Mode.SHIZUKU)
        val action = command(
            AgentIntent(intent = IntentType.WRITE_DOC, text = "会议纪要正文", summary = "会议纪要.md"),
            snapshot(),
        )
        assertEquals(ActionType.WRITE_DOC, action.type)
        assertEquals("会议纪要正文", action.text)
        assertEquals("会议纪要.md", action.summary)
    }

    @Test
    fun finish_转译为task_done() {
        mode(Mode.ACCESSIBILITY)
        val action = command(AgentIntent(intent = IntentType.FINISH, summary = "任务完成"), snapshot())
        assertEquals(ActionType.TASK_DONE, action.type)
        assertEquals("任务完成", action.summary)
    }

    @Test
    fun giveUp_转译为task_done并带原因() {
        mode(Mode.ACCESSIBILITY)
        val action = command(AgentIntent(intent = IntentType.GIVE_UP, reason = "找不到目标"), snapshot())
        assertEquals(ActionType.TASK_DONE, action.type)
        assertEquals("找不到目标", action.summary)
    }

    // ---- 未知意图 / 只读模式 ----

    @Test
    fun 未知意图_转译失败() {
        mode(Mode.SHIZUKU)
        val reason = failure(AgentIntent(intent = "do_something_impossible"), snapshot())
        assertTrue(reason.contains("未知意图"))
    }

    @Test
    fun 只读模式_点击被拒绝() {
        mode(Mode.READONLY)
        val s = snapshot(elem(0, "发送"))
        val result = translator.translate(AgentIntent(intent = IntentType.TAP, target = AgentIntentTarget("text", "发送")), s)
        assertTrue(result is IntentTranslator.TranslationResult.Failed)
        assertTrue((result as IntentTranslator.TranslationResult.Failed).reason.contains("只读模式"))
    }

    @Test
    fun 只读模式_等待被允许() {
        mode(Mode.READONLY)
        val action = command(AgentIntent(intent = IntentType.WAIT, waitMs = 500), snapshot())
        assertEquals(ActionType.WAIT, action.type)
    }

    @Test
    fun 只读模式_文档写入被允许() {
        mode(Mode.READONLY)
        val action = command(AgentIntent(intent = IntentType.WRITE_DOC, text = "草稿"), snapshot())
        assertEquals(ActionType.WRITE_DOC, action.type)
    }

    @Test
    fun 意图携带期望与置信度_透传到命令() {
        mode(Mode.SHIZUKU)
        val action = command(
            AgentIntent(
                intent = IntentType.OPEN,
                uri = "alipays://platformapi",
                expected = "应打开支付宝",
                confidence = 0.92,
                needsConfirmation = true,
            ),
            snapshot(),
        )
        assertEquals("应打开支付宝", action.expected)
        assertEquals(0.92, action.confidence ?: 0.0, 0.001)
        assertNotNull(action.expected)
        assertTrue(action.needsUserConfirmation)
    }
}