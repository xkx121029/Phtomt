package com.phoneagent.shizuku.adb

import com.phoneagent.device.shell.ShizukuManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuBootstrapTest {

    private fun shizukuManager(available: () -> Boolean): ShizukuManager =
        mockk<ShizukuManager>().apply {
            every { isAvailable() } answers { available() }
        }

    @Test
    fun shizuku直连直接就绪() = runTest {
        val sm = shizukuManager { true }
        val transport = mockk<AdbBootstrapTransport>(relaxed = true)
        val bootstrap = ShizukuBootstrap(sm, transport)
        assertTrue(bootstrap.ensureReady() is ShizukuBootstrap.ReadyResult.Ready)
        assertEquals(AdbPhase.READY, bootstrap.status.value.phase)
    }

    @Test
    fun 未连接且无权限返回提示() = runTest {
        val sm = shizukuManager { false }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns false
        }
        val bootstrap = ShizukuBootstrap(sm, transport)
        val r = bootstrap.ensureReady()
        assertTrue(r is ShizukuBootstrap.ReadyResult.Error)
        assertTrue((r as ShizukuBootstrap.ReadyResult.Error).message.contains("无线 ADB 配对"))
    }

    @Test
    fun 配对成功后拉起Shizuku就绪() = runTest {
        val ready = AtomicBoolean(false)
        val sm = shizukuManager { ready.get() }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns false
            coEvery { pair("123456") } coAnswers {
                ready.set(true)
                AdbPairOutcome.Success("abc")
            }
            coEvery { startShizukuService() } returns AdbStartOutcome.Success("started")
        }
        val bootstrap = ShizukuBootstrap(sm, transport, waitShizukuDelayMs = 5, shizukuReadyTimeoutMs = 100)
        val r = bootstrap.pairAndEnsureReady("123456")
        assertTrue(r is ShizukuBootstrap.ReadyResult.Ready)
        assertEquals(AdbPhase.READY, bootstrap.status.value.phase)
    }

    @Test
    fun 配对失败返回明确错误() = runTest {
        val sm = shizukuManager { false }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { pair("000000") } returns AdbPairOutcome.Failure(AdbError.PAIRING_FAILED, "码错误")
        }
        val bootstrap = ShizukuBootstrap(sm, transport)
        val r = bootstrap.pairAndEnsureReady("000000")
        assertTrue(r is ShizukuBootstrap.ReadyResult.Error)
        val e = r as ShizukuBootstrap.ReadyResult.Error
        assertEquals(AdbError.PAIRING_FAILED, e.error)
        assertEquals(AdbPhase.FAILED, bootstrap.status.value.phase)
        assertTrue(bootstrap.status.value.isUserActionRequired)
    }

    @Test
    fun 无线ADB连接Shizuku不可用自动模式下就绪() = runTest {
        // 主通道语义：AUTO 下无线 ADB 已连接，Shizuku 可选且未就绪，仍返回 Ready
        val sm = shizukuManager { false }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns true
        }
        val bootstrap = ShizukuBootstrap(sm, transport, waitShizukuDelayMs = 2, shizukuReadyTimeoutMs = 40)
        assertTrue(bootstrap.ensureReady() is ShizukuBootstrap.ReadyResult.Ready)
        assertEquals(AdbPhase.READY, bootstrap.status.value.phase)
    }

    @Test
    fun Shizuku优先模式启动失败返回错误() = runTest {
        // SHIZUKU 模式：Shizuku 是唯一 shell 通路，启动失败仍返回错误
        val sm = shizukuManager { false }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns true
            coEvery { startShizukuService() } returns AdbStartOutcome.Failure("no shizuku")
        }
        val bootstrap = ShizukuBootstrap(sm, transport, waitShizukuDelayMs = 2, shizukuReadyTimeoutMs = 40) {
            "SHIZUKU"
        }
        val r = bootstrap.ensureReady()
        assertTrue(r is ShizukuBootstrap.ReadyResult.Error)
        assertEquals(AdbError.SHIZUKU_START_FAILED, (r as ShizukuBootstrap.ReadyResult.Error).error)
    }

    @Test
    fun Shizuku优先模式等待就绪超时返回错误() = runTest {
        // SHIZUKU 模式：启动后一直不就绪 → 超时错误
        val sm = shizukuManager { false }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns true
            coEvery { startShizukuService() } returns AdbStartOutcome.Success("ok")
        }
        val bootstrap = ShizukuBootstrap(sm, transport, waitShizukuDelayMs = 2, shizukuReadyTimeoutMs = 40) {
            "SHIZUKU"
        }
        assertTrue(bootstrap.ensureReady() is ShizukuBootstrap.ReadyResult.Error)
    }

    @Test
    fun ADB优先模式仅用无线ADB() = runTest {
        // ADB 模式：ADB 已连接、Shizuku 不可用 → Ready
        val sm = shizukuManager { false }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns true
        }
        val bootstrap = ShizukuBootstrap(sm, transport, waitShizukuDelayMs = 2, shizukuReadyTimeoutMs = 40) {
            "ADB"
        }
        assertTrue(bootstrap.ensureReady() is ShizukuBootstrap.ReadyResult.Ready)
    }

    @Test
    fun ADB优先模式未连接Shizuku可用仍引导配对() = runTest {
        // ADB 模式：ADB 未连接，即便 Shizuku 可用也不走 Shizuku → 引导配对
        val sm = shizukuManager { true }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns false
        }
        val bootstrap = ShizukuBootstrap(sm, transport) { "ADB" }
        val r = bootstrap.ensureReady()
        assertTrue(r is ShizukuBootstrap.ReadyResult.Error)
        assertEquals(AdbError.WIRELESS_DEBUG_DISABLED, (r as ShizukuBootstrap.ReadyResult.Error).error)
    }

    @Test
    fun Shizuku优先模式直连可用就绪() = runTest {
        val sm = shizukuManager { true }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns false
        }
        val bootstrap = ShizukuBootstrap(sm, transport) { "SHIZUKU" }
        assertTrue(bootstrap.ensureReady() is ShizukuBootstrap.ReadyResult.Ready)
    }

    @Test
    fun 未连接提示后可重新配对成功() = runTest {
        // 首次 ensureReady：未连接、Shizuku 不可用 → 引导提示
        val ready = AtomicBoolean(false)
        val sm = shizukuManager { ready.get() }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns false
            coEvery { pair("123456") } coAnswers {
                ready.set(true)
                AdbPairOutcome.Success("abc")
            }
            coEvery { startShizukuService() } returns AdbStartOutcome.Success("started")
        }
        val bootstrap = ShizukuBootstrap(sm, transport, waitShizukuDelayMs = 5, shizukuReadyTimeoutMs = 100)

        val first = bootstrap.ensureReady()
        assertTrue(first is ShizukuBootstrap.ReadyResult.Error)
        assertTrue((first as ShizukuBootstrap.ReadyResult.Error).message.contains("无线 ADB 配对"))

        // 用户补配后成功拉起（对应「断线→重新配对」场景）
        val second = bootstrap.pairAndEnsureReady("123456")
        assertTrue(second is ShizukuBootstrap.ReadyResult.Ready)
        assertEquals(AdbPhase.READY, bootstrap.status.value.phase)
    }
}