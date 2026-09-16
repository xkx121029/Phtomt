package com.phoneagent.device.shell.MdnsAdbResolver

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * 原始 mDNS（DNS-SD）解析器：直接向 224.0.0.251:5353 发送 PTR 查询并解析响应。
 *
 * NsdManager 主要面向发现「其他设备」广播的服务，对**本机（同一设备）adbd** 广播的
 * 无线调试服务自发现常不返回，导致「搜索不到」。原始组播查询关闭 loopback 过滤
 * （IP_MULTICAST_LOOP）后可收到本机 adbd 的 mDNS 响应，作为配对服务发现的可靠兜底。
 *
 * 仅提取 SRV 端口（配对端口为动态值，是搜索的核心目标）；TXT 中的 salt 尽力解析，
 * 缺失时由 [PairingHandshake] 兜底（真机联调点）。host 由调用方用本机 IP 补齐。
 */
@SuppressLint("MissingPermission")
object MdnsAdbResolver {

    private const val MDNS_ADDR = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val TYPE_PTR = 12
    private const val TYPE_SRV = 33
    private const val TYPE_TXT = 16

    /**
     * 解析无线调试配对服务：返回端口与（尽力而为的）TXT salt；找不到返回 null。
     * [serviceType] 形如 `_adb-tls-pairing._tcp.`，[localHost] 为本机 IP（连接目标）。
     * 组播绑 5353 端口失败（被系统 mdnsd 占用）或超时均返回 null，由调用方退回 NsdManager。
     */
    fun resolvePairing(serviceType: String, localHost: String, timeoutMs: Long = 4000): AdbPairingService? {
        var socket: MulticastSocket? = null
        return try {
            socket = MulticastSocket(MDNS_PORT).apply {
                reuseAddress = true
                joinGroup(InetAddress.getByName(MDNS_ADDR))
                soTimeout = 2000
                // 允许接收本机 adbd 的组播响应
                setLoopbackMode(false)
            }
            val query = buildQuery(serviceType)
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(MDNS_ADDR), MDNS_PORT))
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt) // 超时抛 SocketTimeoutException 跳出
                val port = parseSrvPort(buf, pkt.length) ?: continue
                val salt = parseTxtFirst(buf, pkt.length)
                return AdbPairingService(host = localHost, port = port, salt = salt)
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.leaveGroup(InetAddress.getByName(MDNS_ADDR)) }
            runCatching { socket?.close() }
        }
    }

    /** 构造标准 DNS PTR 查询：header + QNAME + QTYPE(PTR)/QCLASS(IN)。 */
    private fun buildQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        // ID=0, Flags=0, QD=1, AN=0, NS=0, AR=0
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeName(out, name)
        out.write(byteArrayOf(0, TYPE_PTR.toByte(), 0, 1))
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
    }

    /** 解析一条 mDNS 响应报文，提取 SRV 端口与 TXT salt（供单测与组播收包共用）。 */
    internal fun parseResponse(data: ByteArray, len: Int): AdbPairingService? {
        val port = parseSrvPort(data, len) ?: return null
        val salt = parseTxtFirst(data, len)
        return AdbPairingService(host = "", port = port, salt = salt)
    }

    /** 在响应 ANSWER 区查找 SRV 记录并返回端口；失败/无则 null。 */
    private fun parseSrvPort(d: ByteArray, len: Int): Int? {
        return try {
            // header: ID(0) Flags(2) QD(4) AN(6) NS(8) AR(10)
            val qd = readU16(d, 4)
            val an = readU16(d, 6)
            var p = 12
            repeat(qd) { p = skipName(d, p); p += 4 }
            repeat(an) {
                p = skipName(d, p)
                val type = readU16(d, p)
                val rdlen = readU16(d, p + 8)
                val rdata = p + 10
                if (type == TYPE_SRV && rdlen >= 6) {
                    return readU16(d, rdata + 4)
                }
                p = rdata + rdlen
            }
            null
        } catch (_: Exception) { null }
    }

    /** 在响应 ANSWER 区查找 TXT 记录并返回第一条字符串（best-effort）。 */
    private fun parseTxtFirst(d: ByteArray, len: Int): ByteArray? {
        return try {
            // header: ID(0) Flags(2) QD(4) AN(6) NS(8) AR(10)
            val qd = readU16(d, 4)
            val an = readU16(d, 6)
            var p = 12
            repeat(qd) { p = skipName(d, p); p += 4 }
            repeat(an) {
                p = skipName(d, p)
                val type = readU16(d, p)
                val rdlen = readU16(d, p + 8)
                val rdata = p + 10
                if (type == TYPE_TXT && rdlen > 0) {
                    val sLen = d[rdata].toInt() and 0xff
                    if (sLen > 0) return d.copyOfRange(rdata + 1, rdata + 1 + sLen)
                }
                p = rdata + rdlen
            }
            null
        } catch (_: Exception) { null }
    }

    private fun readU16(d: ByteArray, p: Int): Int =
        ((d[p].toInt() and 0xff) shl 8) or (d[p + 1].toInt() and 0xff)

    /** 跳过 DNS 名称（含压缩指针 0xC0），返回结束位置。 */
    private fun skipName(d: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            val b = d[p].toInt() and 0xff
            if (b == 0) return p + 1
            if ((b and 0xC0) == 0xC0) return p + 2
            p += 1 + b
        }
    }
}
