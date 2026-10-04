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
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

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
    private val nextLocalId = AtomicInteger(0)
    @Volatile private var connected = false

    fun isConnected(): Boolean = connected

    /** 连接并对端完成 CNXN/AUTH 认证后返回 true */
    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            socket.connect(host, port, timeouts.connectMs.toInt())
            // 读超时：对端静默时 readFrame 抛 SocketTimeoutException，而不是永久阻塞
            socket.setSoTimeout(timeouts.soTimeoutMs.toInt())
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
     * 返回空串表示失败或超时；输出上限默认 4000 字符（与 ShellRules.SHELL_OUTPUT_BUDGET 对齐）。
     */
    suspend fun execShell(cmd: String, maxChars: Int = DEFAULT_MAX_CHARS): String = withContext(Dispatchers.IO) {
        if (!connected) return@withContext ""
        // 对端流 id：OPEN 响应 OKAY / WRTE / CLSE 帧的 arg0 携带，用于回发 CLSE
        var remoteId = 0
        try {
            val input = socket.input()
            val output = socket.output()
            val localId = nextLocalId.incrementAndGet()

            // OPEN shell:<cmd>
            output.write(AdbProtocol.encode(CMD_OPEN, localId, 0, "shell:$cmd".toByteArray(Charsets.UTF_8)))
            output.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeouts.shellReadMs
            var streamClosed = false
            while (System.currentTimeMillis() < deadline) {
                val frame = try {
                    AdbProtocol.readFrame(input) ?: break
                } catch (e: SocketTimeoutException) {
                    // 对端静默触发读超时：本流收集结束（保留已收到的输出）
                    break
                }
                // 流卫生：只处理寻址到本流（arg1 == localId）的帧，历史流残留帧直接丢弃
                if (frame.arg1 != localId) continue
                when (frame.command) {
                    CMD_WRTE -> {
                        remoteId = frame.arg0
                        sb.append(String(frame.payload, Charsets.UTF_8))
                        // 回 OKAY 确认对端可继续写
                        output.write(AdbProtocol.encode(CMD_OKAY, frame.arg1, frame.arg0))
                        output.flush()
                    }
                    CMD_CLSE -> {
                        remoteId = frame.arg0
                        streamClosed = true
                        break
                    }
                    CMD_OKAY -> remoteId = frame.arg0
                    else -> Unit
                }
                if (sb.length >= maxChars) break
            }

            // 达到输出上限或读超时退出后，排空本流残留帧：
            // 直到收到本流 CLSE / 再次读超时 / 达到帧数上限，避免残留帧污染下一次会话
            if (!streamClosed) {
                var drained = 0
                while (drained < DRAIN_MAX_FRAMES) {
                    val frame = try {
                        AdbProtocol.readFrame(input) ?: break
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    drained++
                    if (frame.arg1 != localId) continue
                    when (frame.command) {
                        CMD_WRTE -> {
                            remoteId = frame.arg0
                            output.write(AdbProtocol.encode(CMD_OKAY, frame.arg1, frame.arg0))
                            output.flush()
                        }
                        CMD_CLSE -> {
                            remoteId = frame.arg0
                            break
                        }
                    }
                }
            }

            // 关闭服务（arg0=本端流 id，arg1=对端流 id）
            runCatching {
                output.write(AdbProtocol.encode(CMD_CLSE, localId, remoteId))
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

    companion object {
        /** shell 输出默认上限（与 ShellRules.SHELL_OUTPUT_BUDGET 对齐） */
        const val DEFAULT_MAX_CHARS = 4000

        /** 排空残留帧的上限，防止对端异常时死循环 */
        private const val DRAIN_MAX_FRAMES = 64
    }
}
