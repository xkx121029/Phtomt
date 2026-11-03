package com.phoneagent.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * args 的宽松解码回归：模型把设备直连参数写成数字/布尔是常态，
 * 严格 `Map<String, String>` 会让整条意图解析失败（表现为"设备直连完全不工作"）。
 */
class AgentIntentArgsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String): AgentIntent =
        json.decodeFromString(AgentIntent.serializer(), raw)

    @Test
    fun `数字参数按字面量读成字符串`() {
        val intent = decode("""{"intent":"device_control","op":"volume","args":{"level":30,"stream":"music"}}""")
        assertEquals("30", intent.args?.get("level"))
        assertEquals("music", intent.args?.get("stream"))
    }

    @Test
    fun `布尔与小数参数同样宽松`() {
        val intent = decode(
            """{"intent":"calendar_op","op":"create","args":{"title":"周会","duration_min":30,"all_day":true}}""",
        )
        assertEquals("30", intent.args?.get("duration_min"))
        assertEquals("true", intent.args?.get("all_day"))
        assertEquals("周会", intent.args?.get("title"))
    }

    @Test
    fun `args 里的 null 视作没给该参数`() {
        val intent = decode("""{"intent":"device_control","op":"volume","args":{"level":null,"stream":"music"}}""")
        assertFalse(intent.args.orEmpty().containsKey("level"))
        assertEquals("music", intent.args?.get("stream"))
    }

    @Test
    fun `序列化仍写成字符串映射`() {
        val raw = json.encodeToString(
            AgentIntent.serializer(),
            AgentIntent(intent = "device_control", op = "volume", args = mapOf("level" to "30")),
        )
        assertTrue("args 应原样保留数值字符串：$raw", raw.contains("\"level\":\"30\""))
    }
}
