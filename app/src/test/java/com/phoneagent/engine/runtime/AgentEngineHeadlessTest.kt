package com.phoneagent.engine.runtime

import android.content.Context
import com.phoneagent.core.ai.AiClient
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.domain.model.AgentState
import com.phoneagent.engine.AgentEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试基建（阶段 B0）：引擎 headless 构造冒烟。
 *
 * 为什么不引入 Robolectric：本仓库单元测试只依赖 junit + mockk + coroutines-test，
 * 离线 Gradle 缓存里没有 Robolectric / androidx.test，加依赖会直接断掉离线构建。
 * 因此测试基建走「接口可注入 + mockk relaxed」：Android 依赖（Context / DataStore / 无障碍）
 * 一律由 mocks 或可注入回调替代。
 *
 * 这个用例只证明「引擎能在 JVM 里构造并读到初始状态」，真正的端到端回归由
 * [TaskRunnerHarnessTest] / [StepRunnerHarnessTest] 用假观察/假决策/假执行跑通整条主循环。
 */
class AgentEngineHeadlessTest {

    /** 构造一个不依赖 Android 运行时、也不订阅真实 DataStore 的引擎。 */
    private fun headlessEngine(
        settingsFlow: Flow<AppSettings.Settings> = flowOf(AppSettings.Settings()),
    ): AgentEngine {
        val context = mockk<Context>(relaxed = true) {
            every { packageName } returns "com.phoneagent.test"
        }
        val settings = mockk<AppSettings>(relaxed = true) {
            every { settings } returns settingsFlow
        }
        val aiClient = mockk<AiClient>(relaxed = true)
        val memory = mockk<MemoryStore>(relaxed = true)
        return AgentEngine(
            settings = settings,
            aiClient = aiClient,
            appContext = context,
            memoryStore = memory,
        )
    }

    @Test
    fun `引擎在 JVM 中可构造且初始状态为空闲`() {
        val engine = headlessEngine()
        val state = engine.state.value
        assertEquals(AgentState.Phase.IDLE, state.phase)
        assertFalse(state.isRunning)
        assertTrue(engine.executionHistory.value.isEmpty())
        assertTrue(engine.taskSessions.value.isEmpty())
    }

    @Test
    fun `空设置流不会阻塞构造，预检入口可读`() = runBlocking {
        val engine = headlessEngine(settingsFlow = emptyFlow())
        // init 里的 settings 订阅不能把构造挂住：空流直接结束即可
        withTimeout(2_000) {
            engine.state.value
        }
        assertEquals(AgentState.Phase.IDLE, engine.state.value.phase)
    }
}
