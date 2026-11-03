package com.phoneagent.engine.runtime

import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.runtime.EngineLimits.VISION_FALLBACK_THRESHOLD
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观察段纯决策（重构阶段 B1）。
 *
 * 这些判定决定「要不要截图、要不要走端侧视觉、能不能复用上一帧」：
 * 判错不会崩，但会悄悄烧掉截图耗时/模型额度，或让复杂页面彻底看不见。
 * 阶段 B 把观察段迁出 AgentEngine 前，先用真值表锁住口径。
 */
class ObservationPolicyTest {

    private fun snapshot(elements: Int): ScreenSnapshot = ScreenSnapshot(
        elements = (0 until elements).map {
            UiElement(
                index = it, className = "android.widget.TextView", type = "Text",
                x = 0, y = 0, left = 0, top = 0, right = 0, bottom = 0,
            )
        },
    )

    private fun settings(
        attachScreenshot: Boolean = false,
        enableExternalVision: Boolean = false,
        smartVisionRoute: Boolean = true,
    ) = AppSettings.Settings(
        attachScreenshot = attachScreenshot,
        enableExternalVision = enableExternalVision,
        smartVisionRoute = smartVisionRoute,
    )

    // ---- 元素稀疏判定 ----

    @Test
    fun 元素数等于阈值即视为稀疏() {
        assertTrue(ObservationPolicy.isTreeSparse(snapshot(VISION_FALLBACK_THRESHOLD)))
    }

    @Test
    fun 元素数超过阈值不视为稀疏() {
        assertFalse(ObservationPolicy.isTreeSparse(snapshot(VISION_FALLBACK_THRESHOLD + 1)))
    }

    // ---- 端侧 3B 路由 ----

    @Test
    fun 开启端侧视觉且页面不稀疏时走3b() {
        assertTrue(ObservationPolicy.needExternal3b(settings(enableExternalVision = true), treeSparse = false))
    }

    @Test
    fun 混合路由下稀疏页面跳过3b() {
        assertFalse(ObservationPolicy.needExternal3b(settings(enableExternalVision = true), treeSparse = true))
    }

    @Test
    fun 关闭混合路由时稀疏页面仍走3b() {
        assertTrue(
            ObservationPolicy.needExternal3b(
                settings(enableExternalVision = true, smartVisionRoute = false),
                treeSparse = true,
            ),
        )
    }

    @Test
    fun 未开启端侧视觉永远不走3b() {
        assertFalse(ObservationPolicy.needExternal3b(settings(enableExternalVision = false), treeSparse = false))
    }

    // ---- 截图需求（按需抓帧，不再每步必截）----

    @Test
    fun 主模型能识图且开了附送截图时要图() {
        assertTrue(
            ObservationPolicy.shotNeeded(
                settings(attachScreenshot = true),
                needExternal3b = false,
                treeSparse = false,
                mainSeesImage = true,
            ),
        )
    }

    @Test
    fun 主模型不能识图时不截图即使开了附送截图() {
        // 图没有消费者：文本主模型看不到，截了只是白花一次抓帧耗时
        assertFalse(
            ObservationPolicy.shotNeeded(
                settings(attachScreenshot = true),
                needExternal3b = false,
                treeSparse = false,
                mainSeesImage = false,
            ),
        )
    }

    @Test
    fun 混合路由下页面可读时不截图() {
        // 端侧 3B 只在稀疏页才需要图；可读页面不再为「每步 3B」抓帧
        assertFalse(
            ObservationPolicy.shotNeeded(
                settings(attachScreenshot = false, enableExternalVision = true),
                needExternal3b = true,
                treeSparse = false,
                mainSeesImage = false,
            ),
        )
    }

    @Test
    fun 关闭混合路由时每个页面都要图() {
        assertTrue(
            ObservationPolicy.shotNeeded(
                settings(attachScreenshot = false, enableExternalVision = true, smartVisionRoute = false),
                needExternal3b = true,
                treeSparse = false,
                mainSeesImage = false,
            ),
        )
    }

    @Test
    fun 元素稀疏时无条件要图() {
        assertTrue(
            ObservationPolicy.shotNeeded(
                settings(attachScreenshot = false),
                needExternal3b = false,
                treeSparse = true,
                mainSeesImage = false,
            ),
        )
    }

    @Test
    fun 三者都不命中时不截图() {
        assertFalse(
            ObservationPolicy.shotNeeded(
                settings(attachScreenshot = false),
                needExternal3b = false,
                treeSparse = false,
                mainSeesImage = false,
            ),
        )
    }

    // ---- 截图复用 ----

    @Test
    fun 指纹一致且缓存可用时复用上一帧() {
        assertTrue(ObservationPolicy.canReuseShot(true, "sig-1", "sig-1", true))
    }

    @Test
    fun 本轮不需要图时不谈复用() {
        assertFalse(ObservationPolicy.canReuseShot(false, "sig-1", "sig-1", true))
    }

    @Test
    fun 指纹变化时必须重拍() {
        assertFalse(ObservationPolicy.canReuseShot(true, "sig-2", "sig-1", true))
    }

    @Test
    fun 缓存位图已回收时必须重拍() {
        assertFalse(ObservationPolicy.canReuseShot(true, "sig-1", "sig-1", false))
    }
}
