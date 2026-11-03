package com.phoneagent.engine.loop

import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.runtime.LoopGuard
import com.phoneagent.engine.runtime.StepObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「动作即观察」的接线回归：复用真的省掉读屏与截图，稀疏页真的回退完整观察。
 *
 * 顺带给出模拟真机耗时（读屏 35ms + 截图 90ms）的对比数据，
 * 用于回答「每步是否省掉一轮观察、省多少」。
 */
class AfterObservationReuseTest {

    private class Counters {
        var reads = 0
        var shots = 0
        val logs = mutableListOf<String>()
    }

    private fun element(
        index: Int,
        label: String,
        clickable: Boolean = true,
    ) = UiElement(
        index = index,
        className = "android.widget.TextView",
        type = "text",
        text = label,
        x = (index % 5) * 100,
        y = (index / 5) * 100,
        left = (index % 5) * 100,
        top = (index / 5) * 100,
        right = (index % 5) * 100 + 80,
        bottom = (index / 5) * 100 + 60,
        clickable = clickable,
    )

    private fun snapshot(count: Int, prefix: String = "控件", packageName: String = "com.example.app") =
        ScreenSnapshot(
            packageName = packageName,
            screenWidth = 1080,
            screenHeight = 1920,
            elements = (0 until count).map { element(it, "$prefix$it") },
        )

    private fun session(
        step: Int = 1,
        pending: AfterObservation? = null,
        settings: AppSettings.Settings = AppSettings.Settings(),
    ) = StepSession(
        task = "动作即观察测试",
        plan = null,
        step = step,
        settingsVal = settings,
        lang = PromptLang.CN,
        messages = mutableListOf(),
    ).also { it.pendingAfterObservation = pending }

    private fun phase(
        counters: Counters,
        readDelayMs: Long = 0,
        shotDelayMs: Long = 0,
        readSnapshot: () -> ScreenSnapshot,
    ): ObservationPhase {
        val hooks = ObservationHooks(
            log = { _: AgentLog.Level, message: String -> counters.logs += message },
            pushFloating = { _: String, _: String -> },
            showInteraction = { _: String, _: String, _: String -> },
            setMessage = { _: String -> },
            setNeedsUser = { _: Boolean -> },
            emitHintRequest = { _: String -> },
            awaitUserHint = { "" },
        )
        val observer = StepObserver(
            capture = {
                counters.shots++
                if (shotDelayMs > 0) delay(shotDelayMs)
                null
            },
            log = { _, _ -> },
            treeStats = { null },
        )
        return ObservationPhase(
            readScreen = {
                counters.reads++
                if (readDelayMs > 0) delay(readDelayMs)
                readSnapshot()
            },
            observer = observer,
            loopGuard = LoopGuard(),
            a11yOnline = { true },
            clickPoint = { _, _ -> },
            virtualDisplayActive = { false },
            onPageObserved = { _, _ -> null },
            pageMemoryHintFor = { _, _ -> "" },
            updatePageMarks = { _ -> },
            bumpSensitiveTakeover = { 1 },
            hooks = hooks,
        )
    }

    private fun afterOf(snapshot: ScreenSnapshot, capturedAtMs: Long = System.currentTimeMillis()) =
        AfterObservation(
            snapshot = snapshot,
            actionType = "click",
            step = 1,
            diffText = "元素 0 → ${snapshot.elements.size}",
            capturedAtMs = capturedAtMs,
        )

    @Test
    fun `有动作后快照时跳过读屏与截图`() = runTest {
        AfterObservationStats.reset()
        val counters = Counters()
        val page = snapshot(40, "页面")
        val phase = phase(counters, readSnapshot = { page })
        val session = session(pending = afterOf(page))

        val outcome = phase.run(session)

        assertEquals(StepOutcome.Continue, outcome)
        assertEquals("复用快照不应再读屏", 0, counters.reads)
        assertEquals("复用快照不应再截图", 0, counters.shots)
        assertNull("复用轮不该带上一帧截图", session.screenshot)
        assertEquals("快照应原样成为本轮观察结果", page, session.snapshot)
        assertTrue("应记录复用日志：${counters.logs}", counters.logs.any { it.contains("复用动作后快照") })
        assertEquals("消费一次即清空", null, session.pendingAfterObservation)
        assertTrue(AfterObservationStats.summaryText().contains("复用 1/1"))
    }

    @Test
    fun `稀疏页快照不复用，仍走完整观察与截图兜底`() = runTest {
        AfterObservationStats.reset()
        val counters = Counters()
        val sparse = snapshot(2, "稀疏")
        val phase = phase(counters, readSnapshot = { snapshot(30, "完整") })
        val session = session(pending = afterOf(sparse))

        phase.run(session)

        assertEquals("稀疏页必须重新读屏", 1, counters.reads)
        assertEquals("稀疏页必须保留截图兜底", 1, counters.shots)
        assertTrue("应记录不可复用原因：${counters.logs}", counters.logs.any { it.contains("元素稀疏") })
        assertTrue(AfterObservationStats.summaryText().contains("不可复用 1 步"))
    }

    @Test
    fun `过期快照不复用`() = runTest {
        AfterObservationStats.reset()
        val counters = Counters()
        val page = snapshot(40, "页面")
        val phase = phase(counters, readSnapshot = { page })
        val session = session(pending = afterOf(page, capturedAtMs = System.currentTimeMillis() - 10_000))

        phase.run(session)

        assertEquals(1, counters.reads)
        assertTrue(counters.logs.any { it.contains("快照已过期") })
    }

    @Test
    fun `StepRunner 把动作后快照只交给紧邻下一步`() = runTest {
        val after = afterOf(snapshot(20, "动作后"))
        val seen = mutableListOf<AfterObservation?>()
        val runner = StepRunner(
            observe = { session ->
                seen += session.pendingAfterObservation
                StepOutcome.Continue
            },
            decide = { StepOutcome.Continue },
            execute = { session ->
                session.afterObservation = if (session.step == 1) after else null
                StepOutcome.Continue
            },
        )
        val first = session(step = 1)
        val second = session(step = 2)
        val third = session(step = 3)

        runner.run(first)
        runner.run(second)
        runner.run(third)

        assertEquals(listOf(null, after, null), seen)
    }

    /**
     * 模拟真机耗时的对比：读屏 35ms、截图 90ms（均为保守下限），
     * 页面 160 个元素（长列表页的量级）。
     *
     * 这组数据只用于说明「跳过一轮观察能省多少」，不代表所有页面的固定值；
     * 真机平均值仍以 StageTiming 汇总日志为准。
     */
    @Test
    fun `复用快照比完整观察快`() = runBlocking {
        AfterObservationStats.reset()
        val readDelayMs = 35L
        val shotDelayMs = 90L
        val counters = Counters()
        var page = snapshot(160, "列表")
        val phase = phase(
            counters,
            readDelayMs = readDelayMs,
            shotDelayMs = shotDelayMs,
            readSnapshot = {
                // 每次读屏都换一页，避免截图指纹缓存命中，按最坏情况计时
                page = snapshot(160, if (counters.reads % 2 == 0) "列表A" else "列表B")
                page
            },
        )

        val fullSteps = 6
        val fullStart = System.nanoTime()
        repeat(fullSteps) { i ->
            phase.run(session(step = i + 1))
        }
        val fullMs = (System.nanoTime() - fullStart) / 1_000_000

        val reuseSteps = 6
        val reuseStart = System.nanoTime()
        repeat(reuseSteps) { i ->
            phase.run(session(step = fullSteps + i + 1, pending = afterOf(page)))
        }
        val reuseMs = (System.nanoTime() - reuseStart) / 1_000_000

        val fullAvg = fullMs / fullSteps
        val reuseAvg = reuseMs / reuseSteps
        val savedRatio = if (fullAvg > 0) (fullAvg - reuseAvg) * 100 / fullAvg else 0
        println(
            "动作即观察实测（160 元素，读屏 ${readDelayMs}ms + 截图 ${shotDelayMs}ms）：" +
                "完整观察 ${fullSteps} 次 ${fullMs}ms（均值 ${fullAvg}ms），" +
                "复用快照 ${reuseSteps} 次 ${reuseMs}ms（均值 ${reuseAvg}ms），" +
                "单步节省 ${savedRatio}%；读屏 ${counters.reads} 次 / 截图 ${counters.shots} 次",
        )
        assertTrue("复用应显著快于完整观察（复用 ${reuseMs}ms / 完整 ${fullMs}ms）", reuseMs < fullMs)
        assertTrue("跳过观察比例应为 6/6", AfterObservationStats.summaryText().contains("复用 6/6"))
    }
}
