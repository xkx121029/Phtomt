package com.phoneagent.coding.device

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存版存储：把"落盘"变成一次赋值，判据不碰 DataStore/Keystore。 */
internal class FakeHostBridgeTokenStore(
    private var value: String = "",
    private val failRead: Boolean = false,
    private val failWrite: Boolean = false,
) : HostBridgeTokenStore {

    var writes = 0
        private set

    override suspend fun read(): String {
        if (failRead) error("storage unavailable")
        return value
    }

    override suspend fun write(token: String) {
        if (failWrite) error("disk full")
        value = token
        writes++
    }

    fun snapshot(): String = value
}

/**
 * 密钥解析：首启生成、跨重启复用、坏值/旧常量一律重新生成。
 *
 * 用假存储而不是真 DataStore：判据（该不该生成、生成到不该落盘）跟 Android 无关，
 * 这里要钉的是逻辑本身，不需要 Keystore。
 */
class HostBridgeTokenProviderTest {

    @Test
    fun `首次使用生成并落盘`() = runTest {
        val store = FakeHostBridgeTokenStore()
        val resolved = HostBridgeTokenProvider(store).ensure()

        assertTrue("首启必须新生成", resolved.fresh)
        assertEquals(64, resolved.token.length)
        assertEquals("新密钥必须写进存储，否则下次重启会再生成一套", resolved.token, store.snapshot())
        assertEquals(1, store.writes)
    }

    @Test
    fun `跨重启复用同一个密钥`() = runTest {
        val store = FakeHostBridgeTokenStore()
        val first = HostBridgeTokenProvider(store).ensure()

        // 新进程 = 新 provider，存储不变
        val second = HostBridgeTokenProvider(store).ensure()

        assertEquals("跨重启必须一致，否则已启动的宿主立刻失联", first.token, second.token)
        assertFalse("复用不该再生成", second.fresh)
        assertEquals(1, store.writes)
    }

    @Test
    fun `进程内多次取用返回同一个值且只生成一次`() = runTest {
        val store = FakeHostBridgeTokenStore()
        val provider = HostBridgeTokenProvider(store)

        val first = provider.ensure()
        val second = provider.ensure()

        assertEquals(first.token, second.token)
        assertTrue(first.fresh)
        assertFalse(second.fresh)
        assertEquals(1, store.writes)
    }

    @Test
    fun `存储里是历史常量时重新生成并覆盖掉它`() = runTest {
        val store = FakeHostBridgeTokenStore(HostBridgeToken.LEGACY_COMPROMISED)
        val resolved = HostBridgeTokenProvider(store).ensure()

        assertNotEquals(HostBridgeToken.LEGACY_COMPROMISED, resolved.token)
        assertTrue(resolved.fresh)
        assertEquals(
            "旧常量必须被覆盖，不能留在存储里继续当密钥",
            resolved.token,
            store.snapshot(),
        )
    }

    @Test
    fun `存储里是空值或损坏值时也不放行空密钥`() = runTest {
        for (stored in listOf("", "   ", "deadbeef")) {
            val store = FakeHostBridgeTokenStore(stored)
            val resolved = HostBridgeTokenProvider(store).ensure()

            assertTrue("坏值 [$stored] 必须重新生成", resolved.fresh)
            assertEquals(64, resolved.token.length)
            assertFalse(
                "空密钥不是免鉴权",
                HostBridgeAuth.allows(resolved.token, ""),
            )
        }
    }

    @Test
    fun `读取失败时不回退历史常量`() = runTest {
        val store = FakeHostBridgeTokenStore(failRead = true)
        val resolved = HostBridgeTokenProvider(store).ensure()

        assertNotEquals(HostBridgeToken.LEGACY_COMPROMISED, resolved.token)
        assertFalse(
            "带上历史常量的请求不能因为读存储失败而通过",
            HostBridgeAuth.allows(resolved.token, HostBridgeToken.LEGACY_COMPROMISED),
        )
        assertTrue(HostBridgeAuth.allows(resolved.token, resolved.token))
    }

    @Test
    fun `写入失败时本次进程仍用同一个密钥`() = runTest {
        val store = FakeHostBridgeTokenStore(failWrite = true)
        val provider = HostBridgeTokenProvider(store)

        val first = provider.ensure()
        val second = provider.ensure()

        assertEquals(64, first.token.length)
        assertEquals("写失败不能让进程内密钥跟着抖动", first.token, second.token)
        assertEquals(0, store.writes)
    }

    @Test
    fun `错误的 token 仍然被拒`() = runTest {
        val resolved = HostBridgeTokenProvider(FakeHostBridgeTokenStore()).ensure()

        assertFalse(HostBridgeAuth.allows(resolved.token, "wrong-token"))
        assertFalse(HostBridgeAuth.allows(resolved.token, null))
        assertTrue(HostBridgeAuth.allows(resolved.token, resolved.token))
    }
}
