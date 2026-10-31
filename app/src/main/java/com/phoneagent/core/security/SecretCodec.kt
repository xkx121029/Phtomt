package com.phoneagent.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.phoneagent.core.log.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设置里敏感字段的静态加密（模型 API Key / 对外控制口令）。
 *
 * 为什么不用 EncryptedSharedPreferences：本工程没引入 security-crypto，
 * 而需求只是"落盘的凭据不是明文"，Android Keystore 本身就能做到，
 * 没必要为此增加一个依赖与其传递依赖。
 *
 * 方案：AES/GCM/NoPadding，密钥由系统 Keystore 生成并托管（不出安全硬件/不出应用沙箱），
 * 密文格式 `enc:v1:<base64(iv || ciphertext)>`。
 *
 * 兼容：没有前缀的值一律按明文原样返回——老版本写下的 Key 不会因为升级而失效；
 * 下次 [AppSettings.update] 回写时自动转成密文。任何加解密异常都降级为明文，
 * 因为"读不到配置"比"配置以明文落盘"对用户伤害更大（前者等于应用不可用）。
 */
object SecretCodec {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "hpa_settings_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PREFIX = "enc:v1:"
    private const val TAG = "SecretCodec"

    /** 加密：失败时原样返回（宁可明文可用，也不让配置凭空丢失） */
    fun encrypt(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrElse {
            AppLog.w(TAG, "加密失败，按明文存储：${it.javaClass.simpleName}")
            plain
        }
    }

    /** 解密：非密文（老数据/加密失败留下的明文）原样返回 */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val body = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrElse {
            // 密钥被系统清掉（恢复出厂/换机）后密文永久不可解：返回空串让用户重填，
            // 而不是把一串 base64 当成 Key 发出去，导致莫名其妙的 401
            AppLog.w(TAG, "解密失败，按空值处理：${it.javaClass.simpleName}")
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不要求用户认证：任务在后台执行时也要能读 Key
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
