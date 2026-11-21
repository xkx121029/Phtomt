package com.phoneagent.engine.execution

import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.context.ContextInjector
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.UiBridge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.content.Context

/**
 * input 的 text 缺参守卫（HAPP-32）。
 *
 * 能力注册表把 `text` 标成 required，转译层却只校验 target：缺 text 的 input 照样变成
 * 一条可执行动作，执行层 `action.text ?: ""` 把它变成 `ACTION_SET_TEXT("")`——
 * 输入框整段覆写为空，用户已写好的内容被抹掉，回执却是「已向输入框填充文本」。
 */
class IntentParamGateTest {

    private lateinit var capabilityManager: CapabilityManager
    private lateinit var translator: IntentTranslator

    @Before
    fun setUp() {
        capabilityManager = mockk()
        every { capabilityManager.currentMode() } returns CapabilityManager.Mode.SHIZUKU
        translator = IntentTranslator(capabilityManager, mockk(relaxed = true), IntentResolver())
    }

    private fun element(text: String) = UiElement(
        index = 0,
        className = "android.widget.EditText",
        type = "EditText",
        text = text,
        x = 540, y = 300,
        left = 100, top = 260, right = 980, bottom = 340,
        clickable = true,
    )

    private fun snapshot() = ScreenSnapshot(
        packageName = "com.test.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(element("搜索")),
    )

    private fun searchTarget() = AgentIntentTarget(by = "text", value = "搜索")

    // ---- P1-1 input 缺 text ----

    @Test
    fun `input 缺 text 按缺参补全_不下发输入动作`() {
        val result = translator.translate(
            AgentIntent(intent = IntentType.INPUT, target = searchTarget()),
            snapshot(),
        )
        assertTrue("缺 text 必须是可补全的缺参，实际: $result", result is IntentTranslator.TranslationResult.MissingParam)
        assertEquals("text", (result as IntentTranslator.TranslationResult.MissingParam).field)
    }

    @Test
    fun `input 的 text 只有空白也按缺参补全`() {
        val result = translator.translate(
            AgentIntent(intent = IntentType.INPUT, target = searchTarget(), text = "   "),
            snapshot(),
        )
        assertTrue("空白 text 会整段覆写为空，不能当输入，实际: $result", result is IntentTranslator.TranslationResult.MissingParam)
    }

    @Test
    fun `input 带 text 正常转译并带上文本`() {
        val result = translator.translate(
            AgentIntent(intent = IntentType.INPUT, target = searchTarget(), text = "耳机"),
            snapshot(),
        )
        assertTrue("带 text 应正常转译，实际: $result", result is IntentTranslator.TranslationResult.Command)
        val action = (result as IntentTranslator.TranslationResult.Command).action
        assertEquals(ActionType.TYPE_TEXT, action.type)
        assertEquals("耳机", action.text)
    }

    /**
     * 执行层纵深防御：即使动作绕开转译层（引导路径、本地回退、旧检查点）直接带空文本进来，
     * 也必须明确失败，而不是把「无内容」当成「已填充」。
     */
    @Test
    fun `空文本的动作在执行层被拒绝_不再整段覆写输入框`() = runTest {
        val snap = snapshot()
        val core = ExecutionCore(
            appContext = mockk<Context>(relaxed = true),
            settings = mockk<AppSettings>(relaxed = true),
            shizukuManager = null,
            adbTransport = null,
            termuxBridge = null,
            documentEngine = null,
            intentResolver = IntentResolver(),
            appNameResolver = AppNameResolver(mockk<Context>(relaxed = true)) { emptyList() },
            store = EngineStore(),
            ui = mockk<UiBridge>(relaxed = true),
            ctx = mockk<ContextInjector>(relaxed = true),
            recordExecMs = {},
            observe = { snap },
        )
        val result = core.executeWithVerify(
            AgentAction(type = ActionType.TYPE_TEXT, text = "", x = 540, y = 300),
            snap,
        )
        assertFalse("空文本不得算成功：${result.reason}", result.success)
        assertTrue("必须点明是「空内容」被拒绝，实际: ${result.reason}", result.reason.contains("输入内容为空"))
    }
}
