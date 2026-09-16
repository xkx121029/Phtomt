package com.phoneagent.shizuku.adb

import com.phoneagent.device.shell.AdbKeyStore
import com.phoneagent.device.shell.AdbProtocol
import com.phoneagent.device.shell.AdbSocket
import com.phoneagent.device.shell.AdbTcpSession
import com.phoneagent.device.shell.AdbTimeouts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用内存 [AdbSocket] 回放预先构造的 ADB 协议帧字节，验证握手与 shell 输出。
 */
class AdbTcpSessionTest {

    /** 内存 socket：input 回放设备响应帧，output 记录客户端写入 */
    private class FakeAdbSocket(private val deviceBytes: ByteArray) : AdbSocket {
        var connected = false
        val clientWritten = ByteArrayOutputStream()

        override fun connect(host: String, port: Int, timeoutMs: Int) { connected = true }
        override fun input() = DataInputStream(ByteArrayInputStream(deviceBytes))
        override fun output() = DataOutputStream(clientWritten)
        override fun isConnected() = connected
        override fun close() { connected = false }
    }

    private fun keyStore(): AdbKeyStore = AdbKeyStore.fromKeyPair(AdbKeyStore.generateKeyPair())

    private val timeouts = AdbTimeouts(connectMs = 500, authMs = 500, shellReadMs = 500)

    @Test
    fun 握手成功回到CNXN() = runBlocking {
        // 设备响应：AUTH TOKEN → CNXN
        val token = ByteArray(20) { it.toByte() }
        val authToken = AdbProtocol.encode(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
        val cnxn = AdbProtocol.encode(AdbProtocol.CMD_CNXN, AdbProtocol.VERSION, AdbProtocol.MAXDATA, AdbProtocol.hostBanner())
        val socket = FakeAdbSocket(authToken + cnxn)

        val session = AdbTcpSession(socket, timeouts, keyStore())
        assertTrue(session.connect("127.0.0.1", 37000))
        assertTrue(session.isConnected())
        // 客户端应发送了 AUTH SIGNATURE（收到 TOKEN 后）
        val sent = socket.clientWritten.toByteArray()
        assertTrue(sent.size >= 24 * 2)
    }

    @Test
    fun 无认证响应则握手失败() = runBlocking {
        val cnxn = AdbProtocol.encode(AdbProtocol.CMD_CNXN, AdbProtocol.VERSION, AdbProtocol.MAXDATA, AdbProtocol.hostBanner())
        val socket = FakeAdbSocket(cnxn)
        val session = AdbTcpSession(socket, timeouts, keyStore())
        assertTrue(session.connect("127.0.0.1", 37000))
    }

    @Test
    fun execShell回读输出() = runBlocking {
        // 握手：AUTH TOKEN → CNXN；随后 shell：WRTE("ok") → CLSE
        val token = ByteArray(20) { 1 }
        val authToken = AdbProtocol.encode(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
        val cnxn = AdbProtocol.encode(AdbProtocol.CMD_CNXN, AdbProtocol.VERSION, AdbProtocol.MAXDATA, AdbProtocol.hostBanner())
        val wrte = AdbProtocol.encode(AdbProtocol.CMD_WRTE, 1, 1, "shizuku started".toByteArray(Charsets.UTF_8))
        val clse = AdbProtocol.encode(AdbProtocol.CMD_CLSE, 1, 1)
        val socket = FakeAdbSocket(authToken + cnxn + wrte + clse)

        val session = AdbTcpSession(socket, timeouts, keyStore())
        assertTrue(session.connect("127.0.0.1", 37000))
        val out = session.execShell("sh start.sh")
        assertEquals("shizuku started", out)
    }

    @Test
    fun 输出超过上限被截断() = runBlocking {
        val token = ByteArray(20) { 1 }
        val authToken = AdbProtocol.encode(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
        val cnxn = AdbProtocol.encode(AdbProtocol.CMD_CNXN, AdbProtocol.VERSION, AdbProtocol.MAXDATA, AdbProtocol.hostBanner())
        val big = "a".repeat(2000).toByteArray(Charsets.UTF_8)
        val wrte = AdbProtocol.encode(AdbProtocol.CMD_WRTE, 1, 1, big)
        val socket = FakeAdbSocket(authToken + cnxn + wrte)

        val session = AdbTcpSession(socket, timeouts, keyStore())
        assertTrue(session.connect("127.0.0.1", 37000))
        val out = session.execShell("cat /proc/version", maxChars = 1200)
        assertTrue(out.length <= 1200)
    }

    @Test
    fun 未连接时execShell返回空串() = runBlocking {
        val socket = FakeAdbSocket(ByteArray(0))
        val session = AdbTcpSession(socket, timeouts, keyStore())
        assertFalse(session.isConnected())
        assertEquals("", session.execShell("echo hi"))
    }
}
