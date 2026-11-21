package com.phoneagent.coding.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主桥鉴权判据的单测。
 *
 * 钉住的行为：**没配密钥 ≠ 免鉴权**。以前 `handle` 只在「密钥非空」时才比对，
 * 于是 token 为空（CodingAgentConfig 的默认值）等于整条管道零鉴权。
 *
 * 为什么测这个纯函数而不是 `HostBridgeServer.handle`：`org.json.JSONObject` 在
 * JVM 单测里是 android.jar 的空实现（`put()` 返回 null、不落值），拿它搭请求
 * 只会在桩上 NPE，测不到行为。
 */
class HostBridgeAuthTest {

    @Test
    fun `空密钥不做任何放行`() {
        // 两边都空也不能算匹配成功
        assertFalse(HostBridgeAuth.allows(expectedToken = "", providedToken = ""))
        assertFalse(HostBridgeAuth.allows(expectedToken = "", providedToken = "anything"))
        assertFalse(HostBridgeAuth.allows(expectedToken = "", providedToken = null))
    }

    @Test
    fun `请求没带 token 时拒绝`() {
        assertFalse(HostBridgeAuth.allows("s3cret-token", null))
        assertFalse(HostBridgeAuth.allows("s3cret-token", ""))
    }

    @Test
    fun `密钥不符时拒绝`() {
        assertFalse(HostBridgeAuth.allows("s3cret-token", "other-token"))
        assertFalse(HostBridgeAuth.allows("s3cret-token", "s3cret-toke"))
    }

    @Test
    fun `密钥一致才放行`() {
        assertTrue(HostBridgeAuth.allows("s3cret-token", "s3cret-token"))
    }
}
