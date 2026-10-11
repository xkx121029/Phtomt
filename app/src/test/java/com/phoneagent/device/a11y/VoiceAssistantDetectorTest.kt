package com.phoneagent.device.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音助手探测顺序纯函数测试：不依赖 PackageManager，
 * 只验证「系统入口优先、厂商兜底按序、全未命中返回 null」的挑选逻辑。
 */
class VoiceAssistantDetectorTest {

    // ---- pickHandler：action 候选挑选（系统应用优先）----

    @Test
    fun 挑选_系统应用优先于第三方() {
        val picked = VoiceAssistantDetector.pickHandler(
            listOf("com.third.assist" to false, "com.android.assist" to true),
        )
        assertEquals("com.android.assist", picked)
    }

    @Test
    fun 挑选_无系统应用时取第一个候选() {
        val picked = VoiceAssistantDetector.pickHandler(
            listOf("com.a.assist" to false, "com.b.assist" to false),
        )
        assertEquals("com.a.assist", picked)
    }

    @Test
    fun 挑选_空候选返回null() {
        assertNull(VoiceAssistantDetector.pickHandler(emptyList()))
    }

    // ---- vendorFallback：厂商包名兜底顺序 ----

    @Test
    fun 厂商兜底_按预置顺序命中第一个已安装的() {
        val installed = setOf(
            "com.vivo.voiceassist", "com.heytap.speechassist",
        )
        // 预置顺序 miui → heytap → vivo → ...，heytap 排在 vivo 前
        assertEquals("com.heytap.speechassist", VoiceAssistantDetector.vendorFallback(installed))
    }

    @Test
    fun 厂商兜底_全未安装返回null() {
        assertNull(VoiceAssistantDetector.vendorFallback(emptySet()))
        assertNull(VoiceAssistantDetector.vendorFallback(setOf("com.other.app")))
    }

    @Test
    fun 厂商兜底_预置清单覆盖主流厂商() {
        val expected = setOf(
            "com.miui.voiceassist",
            "com.heytap.speechassist",
            "com.vivo.voiceassist",
            "com.huawei.vassistant",
            "com.hihonor.vassistant",
            "com.google.android.googlequicksearchbox",
        )
        assertEquals(expected, VoiceAssistantDetector.VENDOR_ASSISTANTS.toSet())
    }
}
