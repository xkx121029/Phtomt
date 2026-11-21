package com.phoneagent.engine.execution

import android.content.Context
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.context.ContextInjector
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.UiBridge
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本应用直启」兜底的确认口径。
 *
 * 回归目标：通道阶梯切到 `LOCAL_ACTIVITY` 时，旧实现只要 `startActivity` 没抛异常就
 * `Delivered(confirmed = true)` + `VerifyResult(true, …)`——但 startActivity 返回成功只说明
 * Intent 被本应用发出去了，不代表目标应用真的到了前台（这也是同一个文件里 LAUNCH 阶梯要
 * `awaitLaunched` 回读前台的原因）。没有页面级证据时不许声称「已确认」。
 *
 * 这里只走 OPEN 链路：OPEN 在无障碍不可用时唯一可用的兜底就是 LOCAL_ACTIVITY，
 * 因此可以把「页面没变」这条输入完整地喂到该分支。
 */
class ExecutionCoreLocalFallbackTest {

    private fun snapshot() = ScreenSnapshot(
        packageName = "com.test",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(
            UiElement(
                index = 0,
                className = "android.widget.TextView",
                type = "Text",
                x = 100,
                y = 100,
                left = 0,
                top = 50,
                right = 200,
                bottom = 150,
                text = "首页",
            ),
        ),
    )

    /** 每次回读都返回同一份页面：等价于「直启之后页面没有任何变化」 */
    private fun core(page: () -> ScreenSnapshot): ExecutionCore = ExecutionCore(
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
        observe = { page() },
    )

    @Test
    fun `本应用直启兜底_页面没变时不得算确认生效`() = runTest {
        val snap = snapshot()

        val result = core { snap }.executeWithVerify(
            AgentAction(type = ActionType.OPEN, uri = "https://example.com"),
            snap,
        )

        assertFalse("startActivity 成功不等于生效：${result.reason}", result.success)
        assertFalse(
            "动作确实交付过，标成未交付会让外层换通道重复执行：${result.reason}",
            result.notDelivered,
        )
        // 措辞取自 ShellFallbackPolicy 的未确认后缀（"已交付，但页面没有变化，未能确认生效"）
        assertTrue("必须如实说明未确认：${result.reason}", result.reason.contains("未能确认生效"))
    }
}
