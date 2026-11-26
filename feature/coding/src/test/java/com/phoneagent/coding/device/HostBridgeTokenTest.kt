package com.phoneagent.coding.device

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主桥密钥的生成与归一判据。
 *
 * 钉住的行为：每装机随机、足够长、且历史常量绝不被当成可用密钥。
 */
class HostBridgeTokenTest {

    @Test
    fun `两次生成互不相同`() {
        // 256 bit 撞车的概率可以忽略，这条钉的是"真的走了随机源"，不是固定值
        val first = HostBridgeToken.generate()
        val second = HostBridgeToken.generate()
        assertNotEquals(first, second)
        assertNotEquals(HostBridgeToken.LEGACY_COMPROMISED, first)
    }

    @Test
    fun `生成结果够长且是小写 hex`() {
        val token = HostBridgeToken.generate()
        assertEquals(64, token.length)
        assertTrue("含非 hex 字符：$token", token.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `字节展开不丢高位零`() {
        // 0x00 / 0x0f 这种"看起来短"的字节必须补成两位，否则密钥长度会随内容抖动
        val fixed = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x0F, 0x10)
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                fixed.copyInto(bytes)
            }
        }
        assertEquals("0001ff0f10" + "0".repeat(54), HostBridgeToken.generate(random))
    }

    @Test
    fun `历史常量不算可用密钥`() {
        assertEquals("", HostBridgeToken.usable(HostBridgeToken.LEGACY_COMPROMISED))
    }

    @Test
    fun `空值与损坏值不算可用密钥`() {
        assertEquals("", HostBridgeToken.usable(""))
        assertEquals("", HostBridgeToken.usable("   "))
        // 截断/垃圾值：长度远不到 32，不可能出自 generate
        assertEquals("", HostBridgeToken.usable("deadbeef"))
    }

    @Test
    fun `自己生成的值原样可用`() {
        val token = HostBridgeToken.generate()
        assertEquals(token, HostBridgeToken.usable(token))
    }
}
