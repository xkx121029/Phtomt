package com.phoneagent.engine.execution

import com.phoneagent.device.capability.CapabilityProbe
import com.phoneagent.device.capability.DeviceCapabilitySnapshot
import com.phoneagent.domain.model.ActionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道策略/健康度/阶梯的纯逻辑回归。
 *
 * 这些判据决定了"同一个目标试哪几条路、按什么顺序、失败后要不要继续"，
 * 判错会直接变成重复点击（副作用翻倍）或干脆一步都不试。
 */
class ChannelPolicyTest {

    private fun snap(
        a11y: Boolean = true,
        shizuku: Boolean = false,
        adb: Boolean = false,
        termux: Boolean = false,
    ): DeviceCapabilitySnapshot = CapabilityProbe.capture(
        a11yConnected = a11y,
        a11yEnabled = a11y,
        shizukuReady = shizuku,
        adbConnected = adb,
        termuxAvailable = termux,
        sdkInt = 34,
        notificationAccess = false,
        usageAccess = false,
        virtualDisplaySupported = shizuku,
        nowMillis = 0L,
    )

    private fun kinds(goal: ChannelGoal, s: DeviceCapabilitySnapshot, pref: String = "AUTO", tool: Boolean = false) =
        ChannelPolicy.candidates(goal, s, preference = pref, termuxToolCommand = tool, includeLocalLaunch = true)
            .map { it.kind }

    // ---- 目标归类 ----

    @Test
    fun 动作归类到统一目标() {
        assertEquals(ChannelGoal.POINTER, ChannelGoal.of(ActionType.CLICK))
        assertEquals(ChannelGoal.POINTER, ChannelGoal.of(ActionType.TAP))
        assertEquals(ChannelGoal.TEXT, ChannelGoal.of(ActionType.TYPE_TEXT))
        assertEquals(ChannelGoal.LAUNCH, ChannelGoal.of(ActionType.LAUNCH))
        assertEquals(ChannelGoal.GESTURE, ChannelGoal.of(ActionType.SWIPE_UP))
        assertEquals(ChannelGoal.KEY, ChannelGoal.of(ActionType.BACK))
        assertEquals(ChannelGoal.SHELL, ChannelGoal.of(ActionType.SHELL))
        // 纯端侧动作不参与通道阶梯
        assertNull(ChannelGoal.of(ActionType.WRITE_DOC))
        assertNull(ChannelGoal.of(ActionType.SAY))
    }

    // ---- 候选顺序 ----

    @Test
    fun 启动候选按无障碍到shell到本应用直启() {
        val s = snap(shizuku = true, adb = true)
        assertEquals(
            listOf(
                ChannelKind.ACCESSIBILITY,
                ChannelKind.SHELL_SHIZUKU,
                ChannelKind.SHELL_ADB,
                ChannelKind.LOCAL_ACTIVITY,
            ),
            kinds(ChannelGoal.LAUNCH, s),
        )
    }

    @Test
    fun 无障碍不在线时启动直接从shell起步() {
        val s = snap(a11y = false, shizuku = true)
        assertEquals(listOf(ChannelKind.SHELL_SHIZUKU, ChannelKind.LOCAL_ACTIVITY), kinds(ChannelGoal.LAUNCH, s))
    }

    @Test
    fun 指针类候选绝不含Termux() {
        // Termux 跑不了 input，给出这个候选等于制造一次必然失败
        val s = snap(a11y = false, termux = true)
        assertTrue(kinds(ChannelGoal.POINTER, s).isEmpty())
        assertTrue(kinds(ChannelGoal.LONG_PRESS, s).isEmpty())
        assertTrue(kinds(ChannelGoal.KEY, s).isEmpty())
        assertTrue(kinds(ChannelGoal.TEXT, s).isEmpty())
    }

    @Test
    fun 截图只有无障碍一条路() {
        val s = snap(a11y = true, shizuku = true)
        assertEquals(listOf(ChannelKind.ACCESSIBILITY), kinds(ChannelGoal.SCREENSHOT, s))
        assertTrue(kinds(ChannelGoal.SCREENSHOT, snap(a11y = false, shizuku = true)).isEmpty())
    }

    // ---- shell 目标（尊重偏好与 Termux 工具链分类） ----

    @Test
    fun shell目标AUTO顺位与ShellRules一致() {
        val s = snap(shizuku = true, adb = true, termux = true)
        assertEquals(
            listOf(ChannelKind.SHELL_SHIZUKU, ChannelKind.SHELL_ADB, ChannelKind.SHELL_TERMUX),
            kinds(ChannelGoal.SHELL, s),
        )
    }

    @Test
    fun shell目标显式偏好只认自己那条() {
        val s = snap(shizuku = true, adb = true, termux = true)
        assertEquals(listOf(ChannelKind.SHELL_SHIZUKU), kinds(ChannelGoal.SHELL, s, pref = "SHIZUKU"))
        assertEquals(listOf(ChannelKind.SHELL_TERMUX), kinds(ChannelGoal.SHELL, s, pref = "TERMUX"))
        // 偏好通道不可用时不给替代（与 ShellRules 的既有语义一致）
        assertTrue(kinds(ChannelGoal.SHELL, snap(adb = true, termux = true), pref = "SHIZUKU").isEmpty())
    }

    @Test
    fun Termux工具链命令优先走Termux() {
        val s = snap(shizuku = true, termux = true)
        assertEquals(listOf(ChannelKind.SHELL_TERMUX), kinds(ChannelGoal.SHELL, s, pref = "SHIZUKU", tool = true))
    }

    @Test
    fun Termux工具链命令在Termux不可用时回落到系统shell() {
        val s = snap(shizuku = true, termux = false)
        assertEquals(listOf(ChannelKind.SHELL_SHIZUKU), kinds(ChannelGoal.SHELL, s, pref = "AUTO", tool = true))
    }

    // ---- 健康度 ----

    @Test
    fun 连续失败两次后降权且成功一次即清零() {
        val health = ChannelHealth(demoteAfter = 2)
        assertFalse(health.isDemoted(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY))
        health.recordFailure(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY)
        assertFalse(health.isDemoted(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY))
        health.recordFailure(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY)
        assertTrue(health.isDemoted(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY))
        health.recordSuccess(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY)
        assertEquals(0, health.consecutiveFailures(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY))
    }

    @Test
    fun 降权只后移不丢候选() {
        val health = ChannelHealth(demoteAfter = 1)
        health.recordFailure(ChannelGoal.LAUNCH, ChannelKind.ACCESSIBILITY)
        val ranked = health.rank(
            ChannelGoal.LAUNCH,
            listOf(
                ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍"),
                ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直启"),
            ),
        )
        assertEquals(listOf(ChannelKind.LOCAL_ACTIVITY, ChannelKind.ACCESSIBILITY), ranked.map { it.kind })
        // 全部被降权时仍保留原顺序，不会变成空列表
        val allBad = health.rank(
            ChannelGoal.LAUNCH,
            listOf(
                ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍"),
                ChannelCandidate(ChannelKind.SHELL_SHIZUKU, "Shizuku"),
            ),
        )
        assertEquals(2, allBad.size)
    }

    @Test
    fun 健康度按目标隔离() {
        val health = ChannelHealth(demoteAfter = 1)
        health.recordFailure(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY)
        assertFalse(health.isDemoted(ChannelGoal.LAUNCH, ChannelKind.ACCESSIBILITY))
        assertTrue(health.isDemoted(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY))
    }

    @Test
    fun 重置清空全部统计() {
        val health = ChannelHealth(demoteAfter = 1)
        health.recordFailure(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY)
        health.reset()
        assertFalse(health.isDemoted(ChannelGoal.POINTER, ChannelKind.ACCESSIBILITY))
    }

    // ---- 阶梯执行 ----

    @Test
    fun 已交付即停止不重复执行后续通道() = runBlocking {
        val health = ChannelHealth()
        val calls = mutableListOf<ChannelKind>()
        val r = ChannelLadder(health).run(
            ChannelGoal.LAUNCH,
            listOf(
                ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍"),
                ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直启"),
            ),
        ) { c ->
            calls += c.kind
            ChannelStepResult.Delivered(confirmed = true)
        }
        assertTrue(r.delivered)
        assertTrue(r.confirmed)
        assertEquals(ChannelKind.ACCESSIBILITY, r.channel)
        assertEquals(listOf(ChannelKind.ACCESSIBILITY), calls)
    }

    @Test
    fun 未交付才升级并记录降级来源() = runBlocking {
        val r = ChannelLadder(ChannelHealth()).run(
            ChannelGoal.LAUNCH,
            listOf(
                ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍"),
                ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直启"),
            ),
        ) { c ->
            if (c.kind == ChannelKind.ACCESSIBILITY) {
                ChannelStepResult.NotDelivered("未到前台")
            } else {
                ChannelStepResult.Delivered(confirmed = true)
            }
        }
        assertTrue(r.delivered)
        assertEquals(ChannelKind.LOCAL_ACTIVITY, r.channel)
        assertEquals(ChannelKind.ACCESSIBILITY, r.degradedFrom())
        assertTrue(r.trace().contains("未到前台"))
    }

    @Test
    fun 已交付未确认也停止不再升级() = runBlocking {
        val r = ChannelLadder(ChannelHealth()).run(
            ChannelGoal.LAUNCH,
            listOf(
                ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍"),
                ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直启"),
            ),
        ) { ChannelStepResult.Delivered(confirmed = false, detail = "读到页面前超时") }
        assertTrue(r.delivered)
        assertFalse(r.confirmed)
        assertEquals(ChannelKind.ACCESSIBILITY, r.channel)
        assertEquals(1, r.attempts.size)
    }

    @Test
    fun 全部未交付返回失败且带轨迹() = runBlocking {
        val r = ChannelLadder(ChannelHealth()).run(
            ChannelGoal.LAUNCH,
            listOf(ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直启")),
        ) { ChannelStepResult.NotDelivered("未找到可启动入口") }
        assertFalse(r.delivered)
        assertNull(r.channel)
        assertEquals("未找到可启动入口", r.detail)
        assertTrue(r.trace().contains("本应用直启 未交付"))
    }

    @Test
    fun 跳过不记为失败但仍继续下一条() = runBlocking {
        val health = ChannelHealth()
        val r = ChannelLadder(health).run(
            ChannelGoal.LAUNCH,
            listOf(
                ChannelCandidate(ChannelKind.SHELL_SHIZUKU, "Shizuku"),
                ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直启"),
            ),
        ) { c ->
            if (c.kind == ChannelKind.SHELL_SHIZUKU) {
                ChannelStepResult.Skipped("通道未就绪")
            } else {
                ChannelStepResult.Delivered(confirmed = true)
            }
        }
        assertTrue(r.delivered)
        assertEquals(0, health.consecutiveFailures(ChannelGoal.LAUNCH, ChannelKind.SHELL_SHIZUKU))
    }

    @Test
    fun 空候选直接失败且不执行任何通道() = runBlocking {
        val r = ChannelLadder(ChannelHealth()).run(ChannelGoal.LAUNCH, emptyList()) {
            ChannelStepResult.Delivered(confirmed = true)
        }
        assertFalse(r.delivered)
        assertTrue(r.attempts.isEmpty())
        assertTrue(r.detail.contains("无可用"))
    }

    @Test
    fun 通道抛异常视为未交付并继续下一条() = runBlocking {
        val r = ChannelLadder(ChannelHealth()).run(
            ChannelGoal.LAUNCH,
            listOf(
                ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍"),
                ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直启"),
            ),
        ) { c ->
            if (c.kind == ChannelKind.ACCESSIBILITY) error("binder 已断开")
            ChannelStepResult.Delivered(confirmed = true)
        }
        assertTrue(r.delivered)
        assertEquals(ChannelKind.LOCAL_ACTIVITY, r.channel)
    }
}
