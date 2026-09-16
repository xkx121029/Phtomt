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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 执行链路模拟闭环。
 *
 * ⚠ 本环境为 Windows，无法在真机/模拟器跑完整 AgentEngine（依赖无障碍/Shizuku/悬浮窗）。
 * 本测试模拟"决策 → 转译 → 执行(软件反馈) → 观察"的回环，驱动真实 IntentResolver + IntentTranslator，
 * 用于：记录 Agent 跑任务时的高频命令、暴露转译/语义接口在闭环里的问题、验证可优化点。
 *
 * 决策器为确定性规则（模拟 AI 每步选意图），页面由软件状态机反馈。
 */
class SimulatedTaskRunTest {

    private fun translatorWith(capability: CapabilityManager, appResolver: AppNameResolver): IntentTranslator =
        IntentTranslator(capability, appResolver, IntentResolver())

    private fun page(vararg elems: UiElement) = ScreenSnapshot(
        packageName = "com.mock.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elems.toList(),
    )

    private fun btn(index: Int, text: String, semanticId: String? = null, editable: Boolean = false) = UiElement(
        index = index,
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        type = "Button",
        text = text,
        x = 500, y = 200 + index * 100,
        left = 400, top = 200 + index * 100, right = 600, bottom = 240 + index * 100,
        clickable = true,
        semanticId = semanticId,
    )

    private data class RunResult(
        val steps: List<Pair<AgentIntent, AgentAction>>,
        val failures: List<String>,
    ) {
        val frequency: Map<String, Int> = steps.groupingBy { it.second.type }.eachCount()
        override fun toString(): String = buildString {
            append("  steps:\n")
            steps.forEach { (i, a) ->
                append("    intent=${i.intent}")
                if (i.target != null) append(" target(${i.target.by}=${i.target.value})")
                if (!i.text.isNullOrBlank()) append(" text=\"${i.text}\"")
                append("  ->  ${a.type}")
                if (a.elementIndex != null) append("#${a.elementIndex}")
                if (a.keycode != null) append(" key=${a.keycode}")
                if (a.needsUserConfirmation) append(" [confirm]")
                append("\n")
            }
            if (failures.isNotEmpty()) {
                append("  failures(${failures.size}):\n")
                failures.forEach { append("    x ").append(it).append("\n") }
            }
            append("  freq: ").append(
                frequency.entries.sortedByDescending { it.value }.joinToString { "${it.key}x${it.value}" },
            )
        }
    }

    private fun runTask(translator: IntentTranslator, script: List<AgentIntent>, pages: Map<String, () -> ScreenSnapshot>): RunResult {
        val steps = mutableListOf<Pair<AgentIntent, AgentAction>>()
        val failures = mutableListOf<String>()
        script.forEachIndexed { i, intent ->
            val snapshot = pages[(i + 1).toString()]?.invoke() ?: return@forEachIndexed
            when (val r = translator.translate(intent, snapshot)) {
                is IntentTranslator.TranslationResult.Command -> steps.add(intent to r.action)
                is IntentTranslator.TranslationResult.Failed ->
                    failures.add("step${i + 1} ${intent.intent}: ${r.reason}")
                is IntentTranslator.TranslationResult.MissingParam ->
                    failures.add("step${i + 1} ${intent.intent}: 缺参[${r.field}] ${r.reason}")
            }
        }
        return RunResult(steps, failures)
    }

    @Test
    fun 任务1_刷新并收藏() {
        val cap = mockk<CapabilityManager>()
        every { cap.currentMode() } returns Mode.ACCESSIBILITY
        val translator = translatorWith(cap, mockk())
        val p = page(btn(0, "刷新", "refresh_btn"), btn(1, "收藏", "collect_btn"), btn(2, "返回", "back_btn"))
        val result = runTask(
            translator,
            listOf(
                AgentIntent(intent = IntentType.REFRESH),
                AgentIntent(intent = IntentType.COLLECT),
                AgentIntent(intent = IntentType.BACK),
                AgentIntent(intent = IntentType.FINISH),
            ),
            mapOf("1" to { p }, "2" to { p }, "3" to { p }, "4" to { p }),
        )
        println("=== 任务1 刷新并收藏 ===\n$result")
        assertEquals(4, result.steps.size)
        assertTrue(result.failures.isEmpty())
        assertEquals("任务完成", result.steps.last().second.summary)
        assertEquals(3, result.frequency[ActionType.TAP] ?: 0)
    }

    @Test
    fun 任务2_发送消息() {
        val cap = mockk<CapabilityManager>()
        every { cap.currentMode() } returns Mode.ACCESSIBILITY
        val translator = translatorWith(cap, mockk())
        val p = page(btn(0, "说点什么", editable = true), btn(1, "发送", "send_btn"))
        val result = runTask(
            translator,
            listOf(
                AgentIntent(IntentType.INPUT, target = AgentIntentTarget("text", "说点什么"), text = "你好"),
                AgentIntent(intent = IntentType.SEND),
                AgentIntent(intent = IntentType.BACK),
                AgentIntent(intent = IntentType.FINISH),
            ),
            mapOf("1" to { p }, "2" to { p }, "3" to { p }, "4" to { p }),
        )
        println("=== 任务2 发送消息 ===\n$result")
        assertEquals(4, result.steps.size)
        assertTrue(result.failures.isEmpty())
        assertTrue(result.steps.any { it.second.type == ActionType.TYPE_TEXT })
        assertTrue(result.steps.any { it.second.type == ActionType.TAP })
    }

    @Test
    fun 任务3_语义控件缺失_记录失败() {
        val cap = mockk<CapabilityManager>()
        every { cap.currentMode() } returns Mode.ACCESSIBILITY
        val translator = translatorWith(cap, mockk())
        val p = page(btn(0, "搜索", "search_box"))
        val result = runTask(
            translator,
            listOf(
                AgentIntent(intent = IntentType.SEARCH),
                AgentIntent(intent = IntentType.COLLECT),
                AgentIntent(intent = IntentType.FINISH),
            ),
            mapOf("1" to { p }, "2" to { p }, "3" to { p }),
        )
        println("=== 任务3 控件缺失 ===\n$result")
        assertEquals(2, result.steps.size)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].contains("语义控件"))
    }

    @Test
    fun 删除接口_自动请求确认() {
        val cap = mockk<CapabilityManager>()
        every { cap.currentMode() } returns Mode.ACCESSIBILITY
        val translator = translatorWith(cap, mockk())
        val p = page(btn(0, "删除", "delete_btn"))
        val result = runTask(
            translator,
            listOf(
                AgentIntent(intent = IntentType.DELETE),
                AgentIntent(intent = IntentType.BACK),
                AgentIntent(intent = IntentType.FINISH),
            ),
            mapOf("1" to { p }, "2" to { p }, "3" to { p }),
        )
        println("=== 删除接口(需确认) ===\n$result")
        assertEquals(3, result.steps.size)
        assertTrue(result.steps[0].second.needsUserConfirmation)
    }

    @Test
    fun 只读模式下语义接口被拒绝() {
        val cap = mockk<CapabilityManager>()
        every { cap.currentMode() } returns Mode.READONLY
        val translator = translatorWith(cap, mockk())
        val p = page(btn(0, "刷新", "refresh_btn"))
        val result = runTask(
            translator,
            listOf(AgentIntent(intent = IntentType.REFRESH)),
            mapOf("1" to { p }),
        )
        println("=== 只读模式 ===\n$result")
        assertTrue(result.steps.isEmpty())
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].contains("只读模式"))
    }
}