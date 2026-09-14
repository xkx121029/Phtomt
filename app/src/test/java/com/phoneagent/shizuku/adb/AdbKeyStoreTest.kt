package com.phoneagent.shizuku.adb

import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbKeyStoreTest {

    @Test
    fun 签名可用公钥验签() {
        val kp = AdbKeyStore.generateKeyPair()
        val store = AdbKeyStore.fromKeyPair(kp)
        val token = ByteArray(20) { it.toByte() }
        val signature = store.signToken(token)

        val ver = Signature.getInstance("SHA256withRSA")
        ver.initVerify(kp.public)
        ver.update(token)
        assertTrue("签名应能被同一公钥验证", ver.verify(signature))
    }

    @Test
    fun 篡改token后验签失败() {
        val kp = AdbKeyStore.generateKeyPair()
        val store = AdbKeyStore.fromKeyPair(kp)
        val token = ByteArray(20) { 1 }
        val signature = store.signToken(token)

        val ver = Signature.getInstance("SHA256withRSA")
        ver.initVerify(kp.public)
        ver.update(ByteArray(20) { 2 })
        assertFalse(ver.verify(signature))
    }

    @Test
    fun 公钥DER非空且稳定() {
        val kp = AdbKeyStore.generateKeyPair()
        val store = AdbKeyStore.fromKeyPair(kp)
        val der = store.publicKeyDer()
        assertTrue(der.isNotEmpty())
        // 同一密钥对多次导出应一致
        assertArrayEquals(der, store.publicKeyDer())
    }
}
