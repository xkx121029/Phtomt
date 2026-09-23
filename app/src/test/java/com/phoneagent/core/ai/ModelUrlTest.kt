package com.phoneagent.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模型列表端点拼装测试：各服务商对「API 地址」的填法不一（裸 base、带 /v1、
 * 甚至整条 /chat/completions），都要能拼出正确的 /models。
 */
class ModelUrlTest {

    @Test
    fun appendsModelsToBareBase() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            AiClient.modelsUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun stripsChatCompletionsSuffix() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            AiClient.modelsUrl("https://api.deepseek.com/v1/chat/completions"),
        )
    }

    @Test
    fun keepsExistingModelsSuffix() {
        assertEquals("http://192.168.1.5:8000/v1/models", AiClient.modelsUrl("http://192.168.1.5:8000/v1/models"))
    }

    @Test
    fun normalizesProtocolAndTrailingSlash() {
        assertEquals("http://192.168.1.5:8000/v1/models", AiClient.modelsUrl("192.168.1.5:8000/v1/"))
        assertEquals("https://api.deepseek.com/v1/models", AiClient.modelsUrl("  api.deepseek.com/v1/  "))
    }
}