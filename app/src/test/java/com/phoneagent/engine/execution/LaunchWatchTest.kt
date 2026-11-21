package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动后的前台确认窗（[LaunchWatch]）：三态必须各自走到，且**读不到页面不得报成功**。
 *
 * 回归目标（真机可复现）：无障碍拉起应用后读不到页面（无障碍断开 / Provider 取树超时），
 * 旧实现把"观察窗内没见到变化"与"一次都没读到页面"混成同一个 `false`，
 * 于是启动阶梯把应用又拉起一遍；更早的版本则折成 `true`，执行历史记 ✅ 而应用其实没起来。
 *
 * 这里的页面是逐次回读的**时间序列**：三个出口由"读到过什么"决定，不是由调用方预先声明。
 */
class LaunchWatchTest {

    private fun page(
        packageName: String = "com.test",
        text: String = "首页",
        missingAccessibility: Boolean = false,
    ) = ScreenSnapshot(
        packageName = packageName,
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
                text = text,
            ),
        ),
        missingAccessibility = missingAccessibility,
    )

    private val before = page(packageName = "com.test.launcher", text = "桌面")

    @Test
    fun `观察到目标应用在前台_立即确认`() = runTest {
        var calls = 0
        val observed = LaunchWatch.watch(
            capture = { calls++; page(packageName = "com.demo") },
            targetPkg = "com.demo",
            before = before,
        )

        assertTrue("读到目标应用在前台就是已确认", observed == true)
        // 轮询一旦确认立即返回，不再走满观察窗
        assertEquals("确认后必须立刻停止轮询", 1, calls)
    }

    @Test
    fun `包名没变但页面变了_也算确认`() = runTest {
        // 冷启动首帧常常还是启动器/白屏：包名未更新，但页面已经不是启动前那一页
        var calls = 0
        val observed = LaunchWatch.watch(
            capture = { calls++; page(packageName = "com.test.launcher", text = "应用启动中") },
            targetPkg = "com.demo",
            before = before,
        )

        assertTrue("页面已经变了说明启动生效：${calls} 次回读", observed == true)
        assertEquals(1, calls)
    }

    @Test
    fun `全程读得到页面但没见到目标_算未交付`() = runTest {
        var calls = 0
        val observed = LaunchWatch.watch(
            capture = { calls++; page(packageName = "com.test.launcher", text = "桌面", missingAccessibility = false) },
            targetPkg = "com.demo",
            before = before,
        )

        assertEquals("页面能读、且从头到尾没变，才能判这一级没交付", false, observed)
        // 观察窗长度是行为的一部分：SETTLE_MS / POLL_MS = 12 次回读
        assertEquals(
            "观察窗应走满 ${LaunchWatch.SETTLE_MS / LaunchWatch.POLL_MS} 次回读",
            (LaunchWatch.SETTLE_MS / LaunchWatch.POLL_MS).toInt(),
            calls,
        )
    }

    @Test
    fun `一次都没读到页面_返回null而不是false`() = runTest {
        // missingAccessibility 的快照不能算"读到了页面"：它只有壳，没有任何前台证据
        var calls = 0
        val observed = LaunchWatch.watch(
            capture = { calls++; page(missingAccessibility = true) },
            targetPkg = "com.demo",
            before = before,
        )

        assertNull("读不到页面既不能确认生效、也不能说没交付——必须是第三态 null", observed)
        assertEquals("读不到页面也要走满观察窗", (LaunchWatch.SETTLE_MS / LaunchWatch.POLL_MS).toInt(), calls)
    }

    @Test
    fun `回读整体失败_同样按读不到页面处理`() = runTest {
        // 生产里 capture 把异常收口成 null（runCatchingLogged(...).getOrNull()）
        val observed = LaunchWatch.watch(
            capture = { null },
            targetPkg = "com.demo",
            before = before,
        )

        assertNull("取树失败不是「没交付」的证据", observed)
    }

    @Test
    fun `先读不到再读到目标_仍算确认`() = runTest {
        var calls = 0
        val observed = LaunchWatch.watch(
            capture = {
                calls++
                when (calls) {
                    1 -> null
                    2 -> page(missingAccessibility = true)
                    else -> page(packageName = "com.demo")
                }
            },
            targetPkg = "com.demo",
            before = before,
        )

        assertTrue("读不到的那几次只是没证据，不该终止观察", observed == true)
        assertEquals(3, calls)
    }

    @Test
    fun `读不到与没变化混合_以「读到过页面」为准_判未交付`() = runTest {
        // 三态的分界是"整个窗口里有没有读到过页面"，不是最后一次读到什么
        var calls = 0
        val observed = LaunchWatch.watch(
            capture = {
                calls++
                if (calls % 2 == 0) null else page(packageName = "com.test.launcher", text = "桌面")
            },
            targetPkg = "com.demo",
            before = before,
        )

        assertEquals("只要读到过一次页面且确实没到前台，就该换下一级通道", false, observed)
    }
}
