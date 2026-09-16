package com.phoneagent.device.shell.AdbProtocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * ADB（Android Debug Bridge）协议帧编解码与常量。
 *
 * 帧格式（ADB 官方协议，公开稳定）：
 * - 24 字节头 + payload，所有字段为小端（LE）uint32：
 *   command / arg0 / arg1 / length / check / magic
 * - check = CRC32(payload)；magic = command ^ 0xFFFFFFFF
 * - command 常量：CNXN(连接) / AUTH(认证) / OPEN(打开服务) / OKAY / WRTE(写) / CLSE(关闭)
 * - AUTH 类型：1=TOKEN 2=SIGNATURE 3=RSAPUBLICKEY
 *
 * 纯函数、无 IO，便于单元测试帧字节。
 */
object AdbProtocol {
    const val CMD_CNXN = 0x4e58434e
    const val CMD_AUTH = 0x48545541
    const val CMD_OPEN = 0x4e45504f
    const val CMD_OKAY = 0x59414b4f
    const val CMD_CLSE = 0x45534c43
    const val CMD_WRTE = 0x45545257

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** 当前 ADB 协议版本 */
    const val VERSION = 0x01000000

    /** 默认最大数据段大小（256KB） */
    const val MAXDATA = 256 * 1024

    /** 一帧解析结果 */
    data class Frame(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray = ByteArray(0),
    )

    /**
     * 组装一帧字节。
     * @param hostBanner 是否为 CNXN 的 host banner（含特征串）
     */
    fun encode(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val check = crc32(payload)
        val magic = command xor -1
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(check)
        buf.putInt(magic)
        buf.put(payload)
        return buf.array()
    }

    /**
     * 从流中读取一帧；连接关闭（EOF）时返回 null。
     * 非法长度抛 [IOException]（用于错误分类）。
     */
    fun readFrame(input: DataInputStream): Frame? {
        val head = ByteArray(24)
        try {
            input.readFully(head)
        } catch (e: EOFException) {
            return null
        }
        val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val command = hb.int
        val arg0 = hb.int
        val arg1 = hb.int
        val length = hb.int
        val check = hb.int
        val magic = hb.int
        if (length < 0 || length > MAXDATA) throw IOException("非法 ADB payload 长度: $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(command, arg0, arg1, payload)
    }

    /** CRC32 校验值（ADB check 字段） */
    fun crc32(data: ByteArray): Int {
        val c = CRC32()
        c.update(data)
        return c.value.toInt()
    }

    /** 构造 CNXN 的 host banner 字节（真机联调点：特征串随系统版本微调） */
    fun hostBanner(): ByteArray = "host::features=shell_v2,cmd,stat_v2,apex,abb".toByteArray(Charsets.UTF_8)
}
