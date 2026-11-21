package com.phoneagent.coding.device

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * XKX-46 的核心断言：历史硬编码常量已经不能通过宿主桥鉴权。
 *
 * 单独立一个类而不是塞进 [HostBridgeAuthTest]，是因为这条不是"鉴权函数的一般行为"，
 * 而是一次**升级契约**：装过老版本的设备升级后，旧密钥必须立刻失效。
 * 如果为了兼容把它留下，这次修复等于没做。
 */
class HostBridgeLegacyTokenRevokedTest {

    @Test
    fun `升级后首启解析出的密钥不是历史常量`() = runTest {
        // 老用户升级上来：存储里还没有这个键
        val resolved = HostBridgeTokenProvider(FakeHostBridgeTokenStore()).ensure()

        assertNotEquals(HostBridgeToken.LEGACY_COMPROMISED, resolved.token)
        assertFalse(
            "带着历史常量的请求必须被拒",
            HostBridgeAuth.allows(resolved.token, HostBridgeToken.LEGACY_COMPROMISED),
        )
    }

    @Test
    fun `即便两端都写着历史常量也不放行`() {
        // 覆盖"配置被写回旧值"的退化场景：以前这条会通过（非空且相等），
        // 现在常量本身在判据层就是作废值，谁也不认它。
        assertFalse(
            HostBridgeAuth.allows(
                expectedToken = HostBridgeToken.LEGACY_COMPROMISED,
                providedToken = HostBridgeToken.LEGACY_COMPROMISED,
            ),
        )
    }

    @Test
    fun `坏存储里残留的历史常量会被覆盖成新密钥`() = runTest {
        val store = FakeHostBridgeTokenStore(HostBridgeToken.LEGACY_COMPROMISED)
        val resolved = HostBridgeTokenProvider(store).ensure()

        assertNotEquals(HostBridgeToken.LEGACY_COMPROMISED, store.snapshot())
        assertFalse(HostBridgeAuth.allows(HostBridgeToken.LEGACY_COMPROMISED, HostBridgeToken.LEGACY_COMPROMISED))
        assertFalse(HostBridgeAuth.allows(HostBridgeToken.LEGACY_COMPROMISED, resolved.token))
    }
}
