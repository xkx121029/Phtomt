package com.phoneagent.shizuku.adb

import com.phoneagent.device.shell.AdbProtocol
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdbProtocolTest {

    @Test
    fun 帧编码解码roundtrip() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val bytes = AdbProtocol.encode(AdbProtocol.CMD_CNXN, AdbProtocol.VERSION, 4096, payload)
        val frame = AdbProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes)))
        assertNotNull(frame)
        assertEquals(AdbProtocol.CMD_CNXN, frame!!.command)
        assertEquals(AdbProtocol.VERSION, frame.arg0)
        assertEquals(4096, frame.arg1)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun crc32与JDK一致() {
        val data = "adb-over-wifi".toByteArray(Charsets.UTF_8)
        val c = CRC32()
        c.update(data)
        assertEquals(c.value.toInt(), AdbProtocol.crc32(data))
    }

    @Test
    fun 空payload也可编码解码() {
        val bytes = AdbProtocol.encode(AdbProtocol.CMD_OKAY, 1, 2)
        val frame = AdbProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes)))
        assertNotNull(frame)
        assertEquals(0, frame!!.payload.size)
        assertEquals(1, frame.arg0)
        assertEquals(2, frame.arg1)
    }

    @Test
    fun EOF返回null() {
        // 不足 24 字节的流视为关闭
        val truncated = ByteArray(10)
        assertNull(AdbProtocol.readFrame(DataInputStream(ByteArrayInputStream(truncated))))
    }
}
