package com.phoneagent.shizuku.adb

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsAdbResolverTest {

    /** 编码 DNS 名称（长度前缀标签，末尾 0）。 */
    private fun writeName(out: ByteArrayOutputStream, name: String) {
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
    }

    private fun u16(v: Int) = byteArrayOf(((v shr 8) and 0xff).toByte(), (v and 0xff).toByte())

    /** 构造一条含 PTR 问题 + SRV/TXT 应答的 mDNS 响应。 */
    private fun buildResponse(srvPort: Int, txtSalt: String?): ByteArray {
        val out = ByteArrayOutputStream()
        // header: ID=0, flags=0x8400(Response), QD=1, AN=2, NS=0, AR=0
        out.write(u16(0)); out.write(u16(0x8400)); out.write(u16(1)); out.write(u16(2)); out.write(u16(0)); out.write(u16(0))
        val qnameOffset = out.size() // 12
        // Question: PTR
        writeName(out, "_adb-tls-pairing._tcp.local.")
        out.write(u16(12)); out.write(u16(1)) // QTYPE=PTR, QCLASS=IN

        // Answer 1: PTR → instance name (压缩指针指向 qname)
        out.write(byteArrayOf(0xC0.toByte(), qnameOffset.toByte())) // NAME = ptr to qname
        out.write(u16(12)); out.write(u16(1)) // TYPE=PTR, CLASS=IN
        out.write(u32(120)) // TTL
        out.write(u16(0)) // RDLENGTH=0（测试中忽略实例名）

        // Answer 2: SRV → 端口
        out.write(byteArrayOf(0xC0.toByte(), qnameOffset.toByte())) // NAME
        out.write(u16(33)); out.write(u16(1)) // TYPE=SRV, CLASS=IN
        out.write(u32(120)) // TTL
        out.write(u16(0)) // RDLENGTH 占位，RDATA 完成后回填
        val rdataStart = out.size()
        out.write(u16(0)); out.write(u16(0)) // priority, weight
        out.write(u16(srvPort)) // port
        writeName(out, "localhost.") // target
        val rdataLen = out.size() - rdataStart
        // 回填 RDLENGTH：位于 RDATA 前 2 字节（TYPE/CLASS/TTL 之后）
        val bytes = out.toByteArray()
        val rdlenOffset = rdataStart - 2
        bytes[rdlenOffset] = ((rdataLen shr 8) and 0xff).toByte()
        bytes[rdlenOffset + 1] = (rdataLen and 0xff).toByte()

        if (txtSalt != null) {
            // 追加 TXT 记录（并把 AN 计数从 2 更新为 3）
            val out2 = ByteArrayOutputStream()
            out2.write(bytes)
            val finalBytes = out2.toByteArray()
            finalBytes[6] = 0; finalBytes[7] = 3 // AN = 3
            val out3 = ByteArrayOutputStream()
            out3.write(finalBytes)
            out3.write(byteArrayOf(0xC0.toByte(), qnameOffset.toByte())) // NAME
            out3.write(u16(16)); out3.write(u16(1)) // TYPE=TXT
            out3.write(u32(120)) // TTL
            val saltBytes = txtSalt.toByteArray()
            out3.write(u16(saltBytes.size + 1)) // RDLENGTH
            out3.write(saltBytes.size)
            out3.write(saltBytes)
            return out3.toByteArray()
        }
        return bytes
    }

    private fun u32(v: Int) = byteArrayOf(
        ((v shr 24) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        (v and 0xff).toByte(),
    )

    @Test
    fun 解析SRV端口() {
        val resp = buildResponse(srvPort = 37042, txtSalt = null)
        val svc = MdnsAdbResolver.parseResponse(resp, resp.size)
        assertNotNull(svc)
        assertEquals(37042, svc!!.port)
    }

    @Test
    fun 解析SRV端口与TXTsalt() {
        val resp = buildResponse(srvPort = 38223, txtSalt = "0123456789abcdef")
        val svc = MdnsAdbResolver.parseResponse(resp, resp.size)
        assertNotNull(svc)
        assertEquals(38223, svc!!.port)
        assertNotNull(svc.salt)
        assertEquals("0123456789abcdef", String(svc.salt!!))
    }

    @Test
    fun 无法解析时返回null() {
        // 仅含 PTR 应答、无 SRV → 端口未知 → null
        val out = ByteArrayOutputStream()
        out.write(u16(0)); out.write(u16(0x8400)); out.write(u16(1)); out.write(u16(1)); out.write(u16(0)); out.write(u16(0))
        val q = out.size()
        writeName(out, "_adb-tls-pairing._tcp.local.")
        out.write(u16(12)); out.write(u16(1))
        out.write(byteArrayOf(0xC0.toByte(), q.toByte()))
        out.write(u16(12)); out.write(u16(1))
        out.write(u32(120)); out.write(u16(0))
        val resp = out.toByteArray()
        assertNull(MdnsAdbResolver.parseResponse(resp, resp.size))
    }
}
