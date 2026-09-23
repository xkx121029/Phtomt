package com.phoneagent.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力探测判定测试。两条口径必须守住：
 * 1. [AiClient.probeVerdict] 只看状态码 —— 5xx 的错误正文常含 "image" 字样，做关键词启发式会把服务端故障标成"不支持"。
 * 2. [AiClient.isModelNameError] 只认 404 与「400 + 模型名线索」—— 否则纯文字模型因 400「不支持图片」被整体判失败，永远拿不到 vision=false。
 */
class ModelProbeVerdictTest {

    @Test
    fun successMeansSupported() {
        assertEquals(true, AiClient.probeVerdict(200))
        assertEquals(true, AiClient.probeVerdict(201))
        assertEquals(true, AiClient.probeVerdict(204))
    }

    @Test
    fun explicitRejectionMeansNotSupported() {
        assertEquals(false, AiClient.probeVerdict(415))
        assertEquals(false, AiClient.probeVerdict(422))
    }

    @Test
    fun everythingElseIsUnknownNotFalse() {
        assertNull(AiClient.probeVerdict(400))
        assertNull(AiClient.probeVerdict(429))
        assertNull(AiClient.probeVerdict(500))
        assertNull(AiClient.probeVerdict(503))
    }

    @Test
    fun notFoundIsAlwaysModelNameError() {
        assertTrue(AiClient.isModelNameError(404, ""))
        assertTrue(AiClient.isModelNameError(404, """{"error":{"message":"whatever"}}"""))
    }

    @Test
    fun fourHundredWithoutModelHintIsNotModelNameError() {
        assertFalse(AiClient.isModelNameError(400, """{"error":{"message":"image_url is not supported"}}"""))
        assertFalse(AiClient.isModelNameError(400, """{"error":{"message":"messages[0].content: invalid type"}}"""))
    }

    @Test
    fun fourHundredWithModelHintIsModelNameError() {
        assertTrue(AiClient.isModelNameError(400, """{"error":{"message":"Invalid model: glm-4.5-nonexist"}}"""))
        assertTrue(AiClient.isModelNameError(400, """{"error":{"message":"模型不存在，请检查模型名"}}"""))
    }

    @Test
    fun serverErrorsAreNeverModelNameError() {
        assertFalse(AiClient.isModelNameError(500, "model not found"))
        assertFalse(AiClient.isModelNameError(503, "unknown model"))
    }
}