package com.phoneagent.provider

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分片传输的边界行为。
 *
 * 这段代码是「显示已连接却发不出命令」的修复核心：一旦分片边界算错，
 * 主应用拼回来的字节就会截断或错位，表现为截图解码失败、元素树 JSON 解析失败，
 * 且不会抛任何异常——所以边界必须由单测钉住，而不是靠真机观察。
 */
class PayloadStoreTest {

    /** 主应用侧的分片大小（与 ProviderManager.PAYLOAD_CHUNK 一致） */
    private val chunk = 128 * 1024

    @Test
    fun `按 128KB 逐片读取能完整拼回原字节`() {
        val payload = ByteArray(300 * 1024) { (it % 251).toByte() }
        val token = PayloadStore.put(payload)
        assertEquals(payload.size, PayloadStore.length(token))

        val out = ByteArrayOutputStream(payload.size)
        var offset = 0
        while (offset < payload.size) {
            val part = PayloadStore.read(token, offset, chunk) ?: break
            out.write(part)
            offset += part.size
        }

        assertEquals(payload.size, offset)
        assertArrayEquals(payload, out.toByteArray())
        PayloadStore.remove(token)
    }

    @Test
    fun `越界与失效 token 一律返回失败而不是空片`() {
        val token = PayloadStore.put(ByteArray(10) { it.toByte() })

        // 正常：从 2 开始取 3 字节
        assertArrayEquals(byteArrayOf(2, 3, 4), PayloadStore.read(token, 2, 3))
        // offset == size（读完）与负数 offset 都算越界，必须返回 null，避免调用方把空片当成功
        assertNull(PayloadStore.read(token, 10, 3))
        assertNull(PayloadStore.read(token, -1, 3))
        // 失效 token
        assertNull(PayloadStore.read(-999, 0, 3))
        assertEquals(-1, PayloadStore.length(-999))

        PayloadStore.remove(token)
        assertNull(PayloadStore.read(token, 0, 3))
        assertEquals(-1, PayloadStore.length(token))
    }

    @Test
    fun `单片上限收敛到 CHUNK_LIMIT 以内`() {
        // 调用方传超大 maxBytes 时，必须被 clamp，否则又回到「一次回包超过 Binder 事务上限」
        val payload = ByteArray(PayloadStore.CHUNK_LIMIT + 4096)
        val token = PayloadStore.put(payload)
        val part = PayloadStore.read(token, 0, PayloadStore.CHUNK_LIMIT * 8)
        assertEquals(PayloadStore.CHUNK_LIMIT, part?.size)
        PayloadStore.remove(token)
    }

    @Test
    fun `超出保留份数时淘汰最旧的一份`() {
        // 主应用异常退出可能留下未 endPayload 的 token，缓存不能无限堆积
        val first = PayloadStore.put(byteArrayOf(1))
        val second = PayloadStore.put(byteArrayOf(2))
        val third = PayloadStore.put(byteArrayOf(3))

        assertEquals(-1, PayloadStore.length(first))
        assertEquals(1, PayloadStore.length(second))
        assertEquals(1, PayloadStore.length(third))

        PayloadStore.remove(second)
        PayloadStore.remove(third)
    }
}
