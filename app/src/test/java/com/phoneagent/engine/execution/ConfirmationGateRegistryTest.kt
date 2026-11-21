package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.CapabilityRegistry
import com.phoneagent.domain.model.CapabilitySpec
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.execution.CapabilityManager.Mode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 确认门的唯一权威是能力注册表（[com.phoneagent.domain.model.CapabilitySpec.needsConfirmation]）。
 *
 * 缺陷现场：注册表里 SEND / CONFIRM / NOTIF_CLEAR 标了 needsConfirmation=true，提示词也告诉 AI
 * 「发送/确认/清理通知要先问用户」，但转译层只有 DELETE 一颗策略从注册表读该字段——
 * 这三条转译出来 needsUserConfirmation 恒为 false，[com.phoneagent.engine.loop.ExecutionPhase]
 * 的确认门（`needsUserConfirmation || 危险词`）压根不会触发，发送消息、结算页确认、清空通知静默执行。
 *
 * 这里不按「注册表里有哪些类型」手写清单断言，而是**遍历注册表本身**：将来谁新增一条
 * needsConfirmation=true 的能力，只要转译路径没把它置位，这条测试就红。
 */
class ConfirmationGateRegistryTest {

    private lateinit var capabilityManager: CapabilityManager
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var translator: IntentTranslator

    @Before
    fun setUp() {
        capabilityManager = mockk()
        appNameResolver = mockk()
        every { capabilityManager.currentMode() } returns Mode.ACCESSIBILITY
        translator = IntentTranslator(
            capabilityManager,
            appNameResolver,
            IntentResolver(),
            // SHELL 是自由模式专属意图，主门控拒绝时转译本身就会失败（不涉及"要不要确认"）；
            // 这里开自由档，让注册表里每一条 needsConfirmation 意图都真正走到可执行产物上
            actionMode = { ActionMode.FREE },
        )
    }

    private fun elem(index: Int, text: String, semanticId: String? = null) = UiElement(
        index = index,
        className = "android.widget.Button",
        type = "Button",
        text = text,
        x = 500,
        y = 500 + index * 100,
        left = 0,
        top = 400 + index * 100,
        right = 1000,
        bottom = 600 + index * 100,
        clickable = true,
        semanticId = semanticId,
        viewId = semanticId,
    )

    /** 端侧标注器在真实页面上会给出 semantic_id；SEND/CONFIRM/DELETE 靠它定位 */
    private fun snapshot() = ScreenSnapshot(
        packageName = "com.test.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(
            elem(0, "发送", semanticId = "send_btn"),
            elem(1, "确认支付", semanticId = "confirm_btn"),
            elem(2, "删除", semanticId = "delete_btn"),
            elem(3, "下一页"),
        ),
    )

    /**
     * 给一条注册表能力构造**最小可执行**意图。
     *
     * 只有需要专属字段的几条要在这里补参；其余走空意图即可（点击类靠快照里的 semantic_id）。
     * 将来新增的 needsConfirmation 能力若既不在此列、又无法用空意图转译，本测试会红并点出它的名字——
     * 那时补一行参数即可，而不是去改断言口径。
     */
    private fun intentFor(spec: CapabilitySpec): AgentIntent = when (spec.intent) {
        IntentType.FILE_WRITE -> AgentIntent(
            intent = spec.intent,
            op = "write",
            args = mapOf("path" to "/sdcard/note.txt", "text" to "hi"),
        )
        IntentType.APP_MANAGE -> AgentIntent(intent = spec.intent, op = "force_stop", app = "com.test.app")
        IntentType.SHELL -> AgentIntent(intent = spec.intent, command = "pm list packages")
        else -> AgentIntent(intent = spec.intent)
    }

    private fun commandOf(intent: AgentIntent): AgentAction? {
        val result = translator.translate(intent, snapshot())
        return (result as? IntentTranslator.TranslationResult.Command)?.action
    }

    @Test
    fun 注册表声明需要确认的意图_转译产物一律置needsUserConfirmation() {
        val confirmed = CapabilityRegistry.all.filter { it.needsConfirmation }
        // 守卫：注册表读空了（或字段被改名）时，这条测试会变成永远绿
        assertTrue("注册表里没有任何 needsConfirmation=true 的能力，测试失去意义", confirmed.size >= 7)

        val problems = mutableListOf<String>()
        confirmed.forEach { spec ->
            val action = commandOf(intentFor(spec))
            when {
                action == null -> problems += "${spec.intent}: 未能转译成可执行命令（无法断言确认门）"
                !action.needsUserConfirmation -> problems += "${spec.intent}: needsUserConfirmation=false"
            }
        }
        assertTrue(
            "注册表声明 needsConfirmation=true，转译产物却没置位：${problems.joinToString("；")}",
            problems.isEmpty(),
        )
    }

    @Test
    fun 注册表未声明的意图_不会被统一规则误置为需要确认() {
        val samples = listOf(
            AgentIntent(intent = IntentType.TAP, target = com.phoneagent.domain.model.AgentIntentTarget("text", "下一页")),
            AgentIntent(intent = IntentType.SWIPE, direction = "up"),
            AgentIntent(intent = IntentType.WAIT, waitMs = 800),
            AgentIntent(intent = IntentType.CLIPBOARD, op = "get"),
        )
        samples.forEach { intent ->
            assertEquals(
                "${intent.intent} 不该在注册表里被标为需要确认，测试前提不成立",
                false,
                CapabilityRegistry.specOf(intent.intent)?.needsConfirmation,
            )
            val action = commandOf(intent)
            assertNotNull("${intent.intent} 未能转译成可执行命令", action)
            assertFalse(
                "${intent.intent} 在注册表里不需要确认，转译产物却被置成了 needsUserConfirmation",
                action!!.needsUserConfirmation,
            )
        }
    }

    /** AI 自己标了 needs_confirmation 的动作，注册表没标的（如 open）也要照旧透传——别把这一层弄丢 */
    @Test
    fun 模型自报需要确认_即使注册表没标也保持确认() {
        val action = commandOf(
            AgentIntent(
                intent = IntentType.TAP,
                target = com.phoneagent.domain.model.AgentIntentTarget("text", "下一页"),
                needsConfirmation = true,
            ),
        )
        assertNotNull(action)
        assertTrue("模型标了 needs_confirmation 的动作必须保留确认", action!!.needsUserConfirmation)
        assertEquals(ActionType.TAP, action.type)
    }
}
