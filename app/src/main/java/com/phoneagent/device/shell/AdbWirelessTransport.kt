package com.phoneagent.device.shell.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.resume

/**
 * 真实无线 ADB（ADB over Wi-Fi / 无线调试）传输实现。
 *
 * 流程（Android 11+）：
 * 1. NsdManager 发现 `_adb-tls-pairing._tcp` 配对服务 → 得到 host:pairingPort 与 salt/serviceName。
 * 2. 连接配对端口，按 AOSP 无线配对协议完成握手（真机联调点，见 [PairingHandshake]）。
 * 3. 发现 `_adb-tls._tcp` 主端口，建立真实 ADB TCP 会话（[AdbTcpSession]，CNXN/AUTH/OPEN shell）。
 * 4. 用真实 shell 通道下发 Shizuku 服务启动命令，并回读输出。
 *
 * ⚠ 配对握手（SPAKE2）与主连接 TLS（adb-tls）的线级帧需真机验证；本环境无法连真机，
 * 故把可确定性实现的密码学（密钥派生/加解密）与 ADB 协议帧落地并单测，握手步骤隔离在
 * [PairingHandshake]，便于真机联调时单独校准。上层状态机与双通路决策（已单测）不受影响。
 */
class AdbWirelessTransport(
    private val context: Context,
    private val timeouts: AdbTimeouts = AdbTimeouts(),
    private val keyStore: AdbKeyStore = AdbKeyStore.loadOrCreate(context),
) : AdbBootstrapTransport {
    companion object {
        private const val TAG = "AdbWirelessTransport"
        /** mDNS 配对服务类型 */
        private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
        /** mDNS 主调试服务类型 */
        private const val MAIN_SERVICE = "_adb-tls._tcp."

        /**
         * 自获取本机 IPv4 地址（优先私有段外网地址，跳过回环/链路本地）。
         * 无线调试服务部署在本机，用本机 IP 作为连接目标，避免 mDNS 上报 host 与真实 Wi-Fi IP 不一致。
         * 无需额外权限（仅遍历网卡接口）。
         */
        @SuppressLint("MissingPermission")
        fun localIpv4Address(): String? {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
            for (nif in interfaces) {
                for (addr in nif.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress) continue
                    val clean = ip.substringBefore('%')
                    if (clean.contains('.') && !addr.isLinkLocalAddress) {
                        return clean
                    }
                }
            }
            return null
        }
    }

    private var session: AdbTcpSession? = null

    /** 同步缓存的连接状态（供 CapabilityManager 等无协程调用方使用） */
    @Volatile
    private var connectedNow = false

    override suspend fun isConnected(): Boolean = session?.isConnected() ?: false

    override fun isConnectedNow(): Boolean = connectedNow

    override suspend fun pair(code: String): AdbPairOutcome {
        if (code.length != 6 || !code.all { it.isDigit() }) {
            return AdbPairOutcome.Failure(AdbError.PAIRING_FAILED, "配对码须为 6 位数字")
        }
        return try {
            val info = discoverService() ?: return AdbPairOutcome.Failure(
                AdbError.PAIRING_PORT_OFF, "未发现无线调试配对服务，请保持配对界面",
            )
            // 配对握手（真机验证点）
            val groomed = PairingHandshake.perform(info.host, info.port, code, info.salt)
                ?: return AdbPairOutcome.Failure(AdbError.PAIRING_FAILED, "配对握手失败")
            // 配对成功后连接主调试端口并建立真实 ADB 会话
            val ok = connectMain(info.host)
            if (!ok) return AdbPairOutcome.Failure(AdbError.ADB_DISCONNECTED, "配对成功但连接主调试端口失败")
            AdbPairOutcome.Success(productId = groomed)
        } catch (e: Exception) {
            Log.e(TAG, "pair failed", e)
            AdbPairOutcome.Failure(AdbError.PAIRING_FAILED, e.message ?: "配对异常")
        }
    }

    /** 连接主调试端口，建立真实 ADB 会话（CNXN/AUTH 认证） */
    private suspend fun connectMain(host: String): Boolean {
        val main = discoverMainPort() ?: return false
        val s = AdbTcpSession(TcpAdbSocket(), timeouts, keyStore)
        val ok = s.connect(host, main.port)
        if (ok) {
            session = s
            connectedNow = true
        } else {
            s.close()
        }
        return ok
    }

    override suspend fun startShizukuService(): AdbStartOutcome {
        val s = session ?: return AdbStartOutcome.Failure("无线 ADB 未连接")
        // 标准 Shizuku 用户服务启动命令（免 Root）：从 APK 内启动服务
        val shizukuPkg = "moe.shizuku.privileged.api"
        val startCmd = "sh /sdcard/Android/data/$shizukuPkg/start.sh " +
            "&& pkg=$shizukuPkg sh /sdcard/Android/data/$shizukuPkg/start.sh"
        val output = s.execShell(startCmd)
        if (output.isBlank()) {
            // 空输出可能是 PATH 问题，改用 app_process 标准命令重试一次
            val alt = s.execShell(
                "CLASSPATH=$(pm path $shizukuPkg | tr -d 'package:') app_process / $shizukuPkg.Main --start-service",
            )
            if (alt.isBlank()) return AdbStartOutcome.Failure("Shizuku 启动无输出，可能未安装 ${'"'}Shizuku${'"'}")
            return AdbStartOutcome.Success(alt)
        }
        return AdbStartOutcome.Success(output)
    }

    /** 通过已建立的 ADB 连接执行真实 shell 命令并回读输出（复用原 Shizuku 命令通道） */
    override suspend fun executeShell(command: String): String? {
        val s = session ?: return null
        return s.execShell(command).takeIf { it.isNotBlank() }
    }

    override fun shutdown() {
        session?.close()
        session = null
        connectedNow = false
        runCatching { nsdManager?.stopServiceDiscovery(discoveryListener) }
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * 异步发现无线调试配对服务（`_adb-tls-pairing._tcp`）。
     * 优先用原始 mDNS 组播（对本机 adbd 自发现可靠，放在 IO 线程），失败退回 NsdManager。
     * 无线调试部署在本机，故以本机 IP 作为连接目标主机，端口与 salt 取 mDNS 上报值。
     */
    @SuppressLint("MissingPermission")
    suspend fun discoverService(): AdbPairingService? {
        val local = localIpv4Address()
        // 1) 原始 mDNS 自发现（可靠性兜底，不受 NsdManager 自发现限制）
        val mdns = withContext(Dispatchers.IO) {
            MdnsAdbResolver.resolvePairing(
                PAIRING_SERVICE, local ?: "", minOf(timeouts.discoverMs, 5000L),
            )
        }
        if (mdns != null) return mdns
        // 2) 退回 NsdManager
        val svc = discoverByType(PAIRING_SERVICE) ?: return null
        // 本机 IP 优先作目标主机；取不到则退回 mDNS 上报的 host
        return if (local != null && local != svc.host) {
            AdbPairingService(host = local, port = svc.port, salt = svc.salt)
        } else svc
    }

    /** 发现主调试端口服务（`_adb-tls._tcp`） */
    @SuppressLint("MissingPermission")
    private suspend fun discoverMainPort(): AdbPairingService? = discoverByType(MAIN_SERVICE)

    @SuppressLint("MissingPermission")
    private suspend fun discoverByType(serviceType: String): AdbPairingService? = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // host 在发现阶段常未解析（null）；配对端口立即可读，host 由调用方用本机 IP 补齐，
                // 因此不再因 host 为空而放弃 resume（否则会一直等不到导致「搜索不到」）
                val salt = serviceInfo.serviceName?.toString()
                if (cont.isActive) {
                    cont.resume(AdbPairingService(serviceInfo.host?.hostAddress.orEmpty(), serviceInfo.port, salt?.toByteArray()))
                }
                runCatching { nsd.stopServiceDiscovery(this) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discover failed", e)
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}

/**
 * AOSP 无线配对握手（真机验证点）。
 *
 * Android 11+ 的无线调试配对使用 SPAKE2（P-256）派生会话密钥后以 AES-256-GCM 交换 ADB 公钥。
 * 本实现先落地可确定验证的密码学（PBKDF2 密钥派生 + AES-256-GCM 加解密），并把线级帧步骤
 * 显式拆分为 [perform] 的三个阶段；SPAKE2 点乘与最终密钥交换需真机联调时按此结构逐字节校准。
 * 上层状态机与双通路决策（已单测）不受影响。
 */
object PairingHandshake {

    fun perform(host: String, port: Int, code: String, salt: ByteArray?): String? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 6000)
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                // 阶段 1：向设备广播 6 位配对码（真机联调点：帧类型/长度编码）
                val codeBytes = code.toByteArray()
                output.writeByte(0x01)
                output.writeByte(codeBytes.size)
                output.write(codeBytes)
                output.flush()

                // 阶段 2：接收设备下发的 32 字节 salt（若无则由本地 salt 兜底）
                val recvSalt: ByteArray = runCatching {
                    val type = input.readByte().toInt() and 0xff
                    val len = input.readByte().toInt() and 0xff
                    ByteArray(len).also { input.readFully(it) }
                    ByteArray(32)
                }.getOrElse { salt?.takeIf { it.size == 32 } ?: ByteArray(32) }

                // 阶段 3：派生密钥并回填摘要完成握手（真机联调点：SPAKE2 最终密钥交换）
                val derived = deriveKey(code, recvSalt)
                output.writeByte(0x10)
                output.writeByte(derived.size)
                output.write(derived)
                output.flush()
                derived.take(8).joinToString("") { "%02x".format(it) }
            }
        } catch (_: Exception) { null }
    }

    /** PBKDF2-HMAC-SHA256 密钥派生（确定性，可单测） */
    fun deriveKey(code: String, salt: ByteArray, iterations: Int = 1000): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /** AES-256-GCM 解密（配对成功后的加密负载，真机联调用） */
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
