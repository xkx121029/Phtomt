package com.phoneagent.device.shell.AdbSocket

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ADB TCP 传输抽象。抽成接口以便单元测试用内存实现回放协议帧。
 */
interface AdbSocket {
    /** 连接指定 host:port，超时毫秒 */
    fun connect(host: String, port: Int, timeoutMs: Int)

    fun input(): DataInputStream
    fun output(): DataOutputStream

    /** 是否已连接 */
    fun isConnected(): Boolean

    fun close()
}

/** 基于 [Socket] 的真实 ADB 传输 */
class TcpAdbSocket : AdbSocket {
    private var socket: Socket? = null

    override fun connect(host: String, port: Int, timeoutMs: Int) {
        if (socket?.isConnected == true) return
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
    }

    override fun input(): DataInputStream = DataInputStream(
        socket?.getInputStream() ?: throw IOException("ADB socket 未连接"),
    )

    override fun output(): DataOutputStream = DataOutputStream(
        socket?.getOutputStream() ?: throw IOException("ADB socket 未连接"),
    )

    override fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
