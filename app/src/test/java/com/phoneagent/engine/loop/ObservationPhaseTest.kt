package com.phoneagent.engine.loop

import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.perception.AnnotatedPage
import com.phoneagent.engine.runtime.LoopGuard
import com.phoneagent.engine.runtime.StepObserver
import com.phoneagent.engine.runtime.EngineLimits.MAX_SENSITIVE_TAKEOVERS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观察阶段回归：广告自动关闭、敏感页接管、无障碍掉线、页面记忆开关。
 *
 * 这些分支原先都直接在主循环里 `continue` / `return`，迁到 ObservationPhase 后
 * 必须验证它们仍按原语义返回 Continue / Fatal。
 */
class ObservationPhaseTest {

    private class FakeHooks {
        val logs = mutableListOf<String>()
        val floating = mutableListOf<String>()
        val interactions = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var needsUser = false
        val hintRequests = mutableListOf<String>()
        var hint = ""

        val hooks = ObservationHooks(
            log = { level: AgentLog.Level, message: String -> logs += "$level:$message" },
            pushFloating = { status: String, _: String -> floating += status },
            showInteraction = { type: String, title: String, _: String -> interactions += "$type:$title" },
            setMessage = { messages += it },
            setNeedsUser = { needsUser = it },
            emitHintRequest = { hintRequests += it },
            awaitUserHint = { hint },
        )
    }

    /** 只回固定快照、其余全部为空实现的阶段夹具 */
    // 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
    @Suppress("LongParameterList")
    private fun phase(
        snapshot: ScreenSnapshot,
        hooks: FakeHooks = FakeHooks(),
        a11yOnline: Boolean = true,
        virtualDisplay: Boolean = false,
        sensitiveBump: () -> Int = { 1 },
        onPageObserved: suspend (ScreenSnapshot, AnnotatedPage) -> PageMemoryEntry? = { _, _ -> null },
        updateMarks: (PageMemoryEntry?) -> Unit = {},
        clicked: MutableList<Pair<Int, Int>> = mutableListOf(),
    ): Pair<ObservationPhase, FakeHooks> {
        val observer = StepObserver(
            capture = { null },
            log = { _, _ -> },
            treeStats = { null },
        )
        val p = ObservationPhase(
            readScreen = { snapshot },
            observer = observer,
            loopGuard = LoopGuard(),
            a11yOnline = { a11yOnline },
            clickPoint = { x, y -> clicked += x to y },
            virtualDisplayActive = { virtualDisplay },
            onPageObserved = onPageObserved,
            pageMemoryHintFor = { _, _ -> "hint" },
            updatePageMarks = updateMarks,
            bumpSensitiveTakeover = sensitiveBump,
            hooks = hooks.hooks,
        )
        // settings 由会话传入，这里返回给调用方组装
        return p to hooks
    }

    private fun session(settings: AppSettings.Settings = AppSettings.Settings()) = StepSession(
        task = "测试",
        plan = null,
        step = 1,
        settingsVal = settings,
        lang = PromptLang.CN,
        messages = mutableListOf(),
    )

    private fun element(index: Int, text: String, x: Int = index * 100 + 10) = UiElement(
        index = index,
        className = "TextView",
        type = "text",
        text = text,
        x = x,
        y = 20,
        left = x,
        top = 20,
        right = x + 80,
        bottom = 80,
        clickable = true,
    )

    @Test
    fun `广告有跳过按钮时点击并跳过本轮`() = runTest {
        val snapshot = ScreenSnapshot(
            packageName = "com.example",
            screenWidth = 1080,
            screenHeight = 1920,
            elements = listOf(element(0, "广告"), element(1, "跳过", x = 100)),
        )
        val clicks = mutableListOf<Pair<Int, Int>>()
        val (p, hooks) = phase(snapshot, clicked = clicks)
        val outcome = p.run(session())
        assertEquals(StepOutcome.Continue, outcome)
        assertTrue("应点击广告关闭按钮", clicks.isNotEmpty())
        assertTrue(hooks.floating.any { it.contains("自动关闭") })
    }

    @Test
    fun `敏感页接管超限直接失败`() = runTest {
        val snapshot = ScreenSnapshot(
            packageName = "com.alipay",
            screenWidth = 1080,
            screenHeight = 1920,
            elements = listOf(element(0, "转账")),
        )
        val (p, _) = phase(snapshot, sensitiveBump = { MAX_SENSITIVE_TAKEOVERS + 1 })
        val outcome = p.run(session())
        assertTrue(outcome is StepOutcome.Fatal)
    }

    @Test
    fun `敏感页用户处理后继续下一轮`() = runTest {
        val snapshot = ScreenSnapshot(
            packageName = "com.alipay",
            screenWidth = 1080,
            screenHeight = 1920,
            elements = listOf(element(0, "转账")),
        )
        val (p, hooks) = phase(snapshot)
        hooks.hint = "已处理"
        val outcome = p.run(session())
        assertEquals(StepOutcome.Continue, outcome)
        assertTrue(hooks.hintRequests.isNotEmpty())
    }

    @Test
    fun `无障碍中途掉线且用户离开时失败收尾`() = runTest {
        val snapshot = ScreenSnapshot(screenWidth = 1080, screenHeight = 1920, missingAccessibility = true)
        val (p, hooks) = phase(snapshot, a11yOnline = false)
        hooks.hint = ""
        val s = session().also { it.a11ySeenAlive = true }
        val outcome = p.run(s)
        assertTrue(outcome is StepOutcome.Fatal)
    }

    @Test
    fun `副屏模式无障碍掉线不中断任务`() = runTest {
        val snapshot = ScreenSnapshot(screenWidth = 1080, screenHeight = 1920)
        val (p, _) = phase(snapshot, a11yOnline = false, virtualDisplay = true)
        val s = session().also { it.a11ySeenAlive = true }
        val outcome = p.run(s)
        assertEquals(StepOutcome.Continue, outcome)
    }

    @Test
    fun `页面记忆开关关闭时不采集`() = runTest {
        var observed = 0
        val snapshot = ScreenSnapshot(packageName = "com.example", elements = listOf(element(0, "首页")))
        val (p, _) = phase(snapshot, onPageObserved = { _, _ -> observed++; null })
        val s = session(AppSettings.Settings(pageMemoryEnabled = false))
        p.run(s)
        assertEquals(0, observed)

        val p2 = phase(snapshot, onPageObserved = { _, _ -> observed++; PageMemoryEntry(appPackage = "com.example",
            fingerprint = "fp") }).first
        p2.run(session(AppSettings.Settings(pageMemoryEnabled = true)))
        assertEquals(1, observed)
    }
}
