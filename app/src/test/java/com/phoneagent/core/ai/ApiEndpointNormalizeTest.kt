package com.phoneagent.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * API 地址规范化测试：内网 / localhost / http 协议与未写协议的补全规则。
 * 覆盖点：
 * - 已写 http:// 的地址原样保留（不能升级成 https）
 * - 未写协议时按主机推断：本机与内网补 http://，公网域名补 https://
 * - 末尾斜杠统一去掉
 */
class ApiEndpointNormalizeTest {

    @Test
    fun keepsHttpAndHttpsScheme() {
        assertEquals("http://192.168.1.5:8000/v1", AiClient.normalizeBaseUrl("http://192.168.1.5:8000/v1"))
        assertEquals("http://localhost:11434/v1", AiClient.normalizeBaseUrl("  http://localhost:11434/v1/  "))
        assertEquals("https://api.deepseek.com/v1", AiClient.normalizeBaseUrl("https://api.deepseek.com/v1/"))
    }

    @Test
    fun fillsHttpForLocalAndPrivateHosts() {
        assertEquals("http://192.168.1.5:8000/v1", AiClient.normalizeBaseUrl("192.168.1.5:8000/v1"))
        assertEquals("http://10.0.0.7/v1", AiClient.normalizeBaseUrl("10.0.0.7/v1"))
        assertEquals("http://172.20.5.9:8000/v1", AiClient.normalizeBaseUrl("172.20.5.9:8000/v1"))
        assertEquals("http://127.0.0.1:8080", AiClient.normalizeBaseUrl("127.0.0.1:8080"))
        assertEquals("http://localhost:11434/v1", AiClient.normalizeBaseUrl("localhost:11434/v1"))
        assertEquals("http://[::1]:8080/v1", AiClient.normalizeBaseUrl("[::1]:8080/v1"))
    }

    @Test
    fun fillsHttpForIntranetHosts() {
        assertEquals("http://nas.local:8080/v1", AiClient.normalizeBaseUrl("nas.local:8080/v1"))
        assertEquals("http://llm.internal/v1", AiClient.normalizeBaseUrl("llm.internal/v1"))
        assertEquals("http://myserver:8000/v1", AiClient.normalizeBaseUrl("myserver:8000/v1"))
    }

    @Test
    fun fillsHttpsForPublicHosts() {
        assertEquals("https://api.deepseek.com/v1", AiClient.normalizeBaseUrl("api.deepseek.com/v1"))
        assertEquals("https://8.8.8.8/v1", AiClient.normalizeBaseUrl("8.8.8.8/v1"))
        // 172.32 已不在 172.16~172.31 私有段内
        assertEquals("https://172.32.0.1/v1", AiClient.normalizeBaseUrl("172.32.0.1/v1"))
    }

    @Test
    fun handlesBlankInput() {
        assertEquals("", AiClient.normalizeBaseUrl(""))
        assertEquals("", AiClient.normalizeBaseUrl("   "))
    }
}