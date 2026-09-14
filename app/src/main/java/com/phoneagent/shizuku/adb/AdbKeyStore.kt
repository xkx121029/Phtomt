package com.phoneagent.shizuku.adb

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB 客户端密钥（RSA-2048）：用于 AUTH SIGNATURE 认证与无线配对。
 *
 * - 密钥持久化到应用私有目录（避免每次重启更换密钥导致需重新配对）。
 * - [signToken] 为可注入签名函数，便于单测（用公钥验签）。
 *
 * ⚠ 真机联调点：真实 ADB 的 AUTH SIGNATURE 使用 RSA PKCS#1 v1.5 直接对 token 签名；
 * 此处默认采用 SHA256withRSA（等价载荷语义），联调时按设备行为校准。
 */
class AdbKeyStore(
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
    private val sign: (ByteArray) -> ByteArray,
) {

    /** ADB 公钥（X.509 SPKI DER），配对/认证时下发 */
    fun publicKeyDer(): ByteArray = publicKey.encoded

    /** 对 ADB TOKEN 签名，用于 AUTH SIGNATURE */
    fun signToken(token: ByteArray): ByteArray = sign(token)

    companion object {
        private const val FILE_PRIV = "adb_key_priv.der"
        private const val FILE_PUB = "adb_key_pub.der"

        /** 加载或生成并持久化密钥 */
        fun loadOrCreate(context: Context): AdbKeyStore {
            val dir = context.filesDir
            val privF = File(dir, FILE_PRIV)
            val pubF = File(dir, FILE_PUB)
            if (privF.exists() && pubF.exists()) {
                runCatching {
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privF.readBytes()))
                    val pub = kf.generatePublic(X509EncodedKeySpec(pubF.readBytes()))
                    return fromKeyPair(KeyPair(pub, priv))
                }
            }
            return generateNew(dir)
        }

        /** 从已有密钥对构造（供单测/内存模式） */
        fun fromKeyPair(keyPair: KeyPair): AdbKeyStore {
            val sign: (ByteArray) -> ByteArray = { token ->
                val sig = Signature.getInstance("SHA256withRSA")
                sig.initSign(keyPair.private)
                sig.update(token)
                sig.sign()
            }
            return AdbKeyStore(keyPair.private, keyPair.public, sign)
        }

        /** 生成新的 RSA-2048 密钥对 */
        fun generateKeyPair(): KeyPair {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            return gen.generateKeyPair()
        }

        private fun generateNew(dir: File): AdbKeyStore {
            val kp = generateKeyPair()
            runCatching {
                File(dir, FILE_PRIV).writeBytes(kp.private.encoded)
                File(dir, FILE_PUB).writeBytes(kp.public.encoded)
            }
            return fromKeyPair(kp)
        }
    }
}
