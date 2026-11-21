package com.phoneagent.coding.device

import java.security.SecureRandom

/**
 * 宿主桥共享密钥的生成与归一判据（纯逻辑，JVM 可测）。
 *
 * 历史包袱：[LEGACY_COMPROMISED] 是这套代码曾经硬编码的密钥。常量一旦打进 APK，
 * 任何能读源码或反编译安装包的本地进程就等于拿到了它——鉴权退化成"有没有带上
 * 这个公开字符串"。HAPP-36 堵的是"空密钥 = 无鉴权"，常量本身是更大的洞（XKX-46）。
 *
 * 现在的规则：每装机首启生成一次 256 bit 随机密钥，落盘后跨重启复用；读不到就重新生成，
 * **任何情况下都不回退到历史常量**。
 */
internal object HostBridgeToken {

    /** 已作废的历史常量。存储里读到它一律按"没有密钥"处理，重新生成。 */
    const val LEGACY_COMPROMISED = "phoneagent-bridge"

    /** 随机字节数：32 字节 = 256 bit，hex 展开 64 字符。 */
    private const val BYTES = 32

    /** 可接受的最小长度（hex 字符）：低于此长度不可能出自 [generate]，按损坏处理。 */
    private const val MIN_LENGTH = 32

    private const val HEX_RADIX = 16
    private const val BYTE_MASK = 0xFF
    private const val HEX_PAD = 2
    private const val PAD_CHAR = '0'

    /** 生成新密钥（小写 hex）。随机源可注入，单测据此钉住字节→hex 的展开规则。 */
    fun generate(random: SecureRandom): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return buildString(BYTES * HEX_PAD) {
            bytes.forEach { byte ->
                val value = byte.toInt() and BYTE_MASK
                append(value.toString(HEX_RADIX).padStart(HEX_PAD, PAD_CHAR))
            }
        }
    }

    fun generate(): String = generate(SecureRandom())

    /**
     * 把存储里读到的原值归一成「可用密钥 / 空串」。
     *
     * 返回空串的三种情况都必须重新生成：
     * 1. 从来没写过（首次安装）；
     * 2. Keystore 被系统清掉后密文解不出来（[com.phoneagent.core.security.SecretCodec]
     *    解密失败返回空串）；
     * 3. **历史常量**——升级路径的关键：老版本装过的设备上旧值必须立刻失效，
     *    否则这次修复等于没做。
     */
    fun usable(stored: String): String {
        val value = stored.trim()
        if (value == LEGACY_COMPROMISED) return ""
        return if (value.length >= MIN_LENGTH) value else ""
    }
}
