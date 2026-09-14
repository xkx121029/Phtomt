package com.phoneagent.shizuku.adb

import com.phoneagent.shizuku.ShizukuManager
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
    fun 启动失败返回错误() = runTest {
        val sm = shizukuManager { false }
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns true
            coEvery { startShizukuService() } returns AdbStartOutcome.Failure("no shizuku")
        }
        val bootstrap = ShizukuBootstrap(sm, transport)
        val r = bootstrap.ensureReady()
        assertTrue(r is ShizukuBootstrap.ReadyResult.Error)
        assertEquals(AdbError.SHIZUKU_START_FAILED, (r as ShizukuBootstrap.ReadyResult.Error).error)
    }

    @Test
    fun 启动后等待就绪超时() = runTest {
        val sm = shizukuManager { false } // 一直不就绪
        val transport = mockk<AdbBootstrapTransport>().apply {
            coEvery { isConnected() } returns true
            coEvery { startShizukuService() } returns AdbStartOutcome.Success("ok")
        }
        val bootstrap = ShizukuBootstrap(sm, transport, waitShizukuDelayMs = 2, shizukuReadyTimeoutMs = 40)
        val r = bootstrap.ensureReady()
        assertTrue(r is ShizukuBootstrap.ReadyResult.Error)
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