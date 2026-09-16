package com.phoneagent.device.shell

import com.phoneagent.device.shell.AdbProtocol.CMD_AUTH
import com.phoneagent.device.shell.AdbProtocol.CMD_CLSE
import com.phoneagent.device.shell.AdbProtocol.CMD_CNXN
import com.phoneagent.device.shell.AdbProtocol.CMD_OKAY
import com.phoneagent.device.shell.AdbProtocol.CMD_OPEN
import com.phoneagent.device.shell.AdbProtocol.CMD_WRTE
import com.phoneagent.device.shell.AdbProtocol.MAXDATA
import com.phoneagent.device.shell.AdbProtocol.VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真实 ADB TCP 会话（CNXN → AUTH → OPEN shell: → 读输出 → CLSE）。
 *
 * 基于公开稳定的 ADB 协议，帧编解码可单测（用内存 [AdbSocket] 回放帧字节）。
 *
 * ⚠ 真机联调点：
 * - 无线调试主连接（`_adb-tls._tcp`）在 TLS 内进行，明文通路用于传统 USB/网络 ADB；
 *   此处先落地标准 ADB 明文会话结构，TLS 包装（adb-tls）需真机联调时接入。
 * - AUTH 签名算法以 [AdbKeyStore.signToken] 为准（默认 SHA256withRSA，联调校准）。
 */
class AdbTcpSession(
    private val socket: AdbSocket,
    private val timeouts: AdbTimeouts,
    private val keys: AdbKeyStore,
) {
    private var nextLocalId = 1
    @Volatile private var connected = false

    fun isConnected(): Boolean = connected

    /** 连接并对端完成 CNXN/AUTH 认证后返回 true */
    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            socket.connect(host, port, timeouts.connectMs.toInt())
            val input = socket.input()
            val output = socket.output()

            // 1. 发起 CNXN（版本 / 最大数据段 / host banner）
            output.write(AdbProtocol.encode(CMD_CNXN, VERSION, MAXDATA, AdbProtocol.hostBanner()))
            output.flush()

            // 2. 循环收 CNXN 或 AUTH 帧，处理认证
            val deadline = System.currentTimeMillis() + timeouts.authMs
            while (System.currentTimeMillis() < deadline) {
                val frame = AdbProtocol.readFrame(input) ?: return@withContext false
                when (frame.command) {
                    CMD_CNXN -> {
                        connected = true
                        return@withContext true
                    }
                    CMD_AUTH -> when (frame.arg0) {
                        AdbProtocol.AUTH_TOKEN -> {
                            // 设备下发 TOKEN → 用私钥签名回 AUTH SIGNATURE
                            val sig = keys.signToken(frame.payload)
                            output.write(AdbProtocol.encode(CMD_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, sig))
                            output.flush()
                        }
                        AdbProtocol.AUTH_RSAPUBLICKEY -> {
                            // 设备请求公钥 → 下发 ADB 公钥
                            output.write(AdbProtocol.encode(CMD_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, keys.publicKeyDer()))
                            output.flush()
                        }
                        else -> return@withContext false
                    }
                    else -> return@withContext false
                }
            }
            false
        } catch (e: Exception) {
            connected = false
            false
        }
    }

    /**
     * 在已认证会话上执行 shell 命令并回读输出（截断至 [maxChars]）。
     * 返回空串表示失败或超时；输出上限默认 1200 字符（注入决策上下文）。
     */
    suspend fun execShell(cmd: String, maxChars: Int = 1200): String = withContext(Dispatchers.IO) {
        if (!connected) return@withContext ""
        try {
            val input = socket.input()
            val output = socket.output()
            val localId = nextLocalId++

            // OPEN shell:<cmd>
            output.write(AdbProtocol.encode(CMD_OPEN, localId, 0, "shell:$cmd".toByteArray(Charsets.UTF_8)))
            output.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeouts.shellReadMs
            while (System.currentTimeMillis() < deadline) {
                val frame = AdbProtocol.readFrame(input) ?: break
                when (frame.command) {
                    CMD_WRTE -> {
                        sb.append(String(frame.payload, Charsets.UTF_8))
                        // 回 OKAY 确认对端可继续写
                        output.write(AdbProtocol.encode(CMD_OKAY, frame.arg1, frame.arg0))
                        output.flush()
                    }
                    CMD_CLSE -> break
                    CMD_OKAY, CMD_CNXN -> Unit
                    else -> Unit
                }
                if (sb.length >= maxChars) break
            }

            // 关闭服务
            runCatching {
                output.write(AdbProtocol.encode(CMD_CLSE, localId, 0))
                output.flush()
            }
            val text = sb.toString().trim()
            if (text.length > maxChars) text.take(maxChars) else text
        } catch (e: Exception) {
            ""
        }
    }

    fun close() {
        socket.close()
        connected = false
    }
}
