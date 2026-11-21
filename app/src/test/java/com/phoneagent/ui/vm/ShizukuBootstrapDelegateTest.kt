package com.phoneagent.ui.vm

import com.phoneagent.device.shell.AdbError
import com.phoneagent.device.shell.ShizukuBootstrap
import com.phoneagent.device.shell.WirelessAdbPairingFlow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配对码校验。
 *
 * 6 位数字是唯一合法输入；非法输入必须当场拒绝并给出提示，**不能**下探到引导流程
 * （否则会向 Provider 发起一次必然失败的配对，用户看到的是底层错误而不是「格式不对」）。
 * 合法输入则原样透传。
 */
class ShizukuBootstrapDelegateTest {

    private val bootstrap = mockk<ShizukuBootstrap>(relaxed = true)
    private val pairing = mockk<WirelessAdbPairingFlow>(relaxed = true)
    private val delegate = ShizukuBootstrapDelegate(
        bootstrap, pairing, CoroutineScope(Dispatchers.Unconfined),
    )

    private fun pair(code: String): String {
        var msg: String? = null
        delegate.pairShizuku(code) { msg = it }
        return msg ?: error("回调未触发")
    }

    @Test
    fun `非 6 位数字一律拒绝且不发起配对`() {
        assertEquals("配对码须为 6 位数字", pair(""))
        assertEquals("配对码须为 6 位数字", pair("12345"))
        assertEquals("配对码须为 6 位数字", pair("1234567"))
        assertEquals("配对码须为 6 位数字", pair("12345a"))
        assertEquals("配对码须为 6 位数字", pair("12 456"))
        coVerify(exactly = 0) { bootstrap.pairAndEnsureReady(any()) }
    }

    @Test
    fun `合法 6 位码透传给引导流程并回显就绪`() {
        coEvery { bootstrap.pairAndEnsureReady("123456") } returns ShizukuBootstrap.ReadyResult.Ready

        assertEquals("启动成功，内置 Shizuku 已就绪", pair("123456"))
        coVerify(exactly = 1) { bootstrap.pairAndEnsureReady("123456") }
    }

    @Test
    fun `引导失败时把底层错误原样回显`() {
        coEvery { bootstrap.pairAndEnsureReady("654321") } returns
            ShizukuBootstrap.ReadyResult.Error("配对超时", AdbError.PAIRING_FAILED)

        assertEquals("配对超时", pair("654321"))
    }
}
