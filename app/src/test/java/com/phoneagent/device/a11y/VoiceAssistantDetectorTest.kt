package com.phoneagent.device.a11y

import com.phoneagent.provider.a11y.VoiceAssistantDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音助手探测顺序纯函数测试：不依赖 PackageManager，
 * 只验证「系统入口优先、厂商兜底按序、全未命中返回 null」的挑选逻辑。
 * 被测实现位于 provider-api（主应用与 Provider 共用），测试留在调用方一侧。
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

    // ---- orderedTargets：多入口唤醒顺序（去重 + 上限）----

    @Test
    fun 唤醒顺序_系统入口在前_厂商兜底在后() {
        val targets = VoiceAssistantDetector.orderedTargets(
            voiceCommand = VoiceAssistantDetector.Target(VoiceAssistantDetector.Via.VOICE_COMMAND, "com.miui.voiceassist", "VoiceCommandActivity"),
            assist = VoiceAssistantDetector.Target(VoiceAssistantDetector.Via.ASSIST, "com.miui.voiceassist", "AssistActivity"),
            installedVendors = listOf("com.miui.voiceassist", "com.heytap.speechassist"),
        )
        // 同包名去重：语音指令入口最先命中 miui，后续 ASSIST 与厂商兜底里同包名的都去掉
        assertEquals(
            listOf(
                VoiceAssistantDetector.Target(VoiceAssistantDetector.Via.VOICE_COMMAND, "com.miui.voiceassist", "VoiceCommandActivity"),
                VoiceAssistantDetector.Target(VoiceAssistantDetector.Via.VENDOR, "com.heytap.speechassist", null),
            ),
            targets,
        )
    }

    @Test
    fun 唤醒顺序_系统入口包名未知时不被厂商兜底顶掉() {
        val targets = VoiceAssistantDetector.orderedTargets(
            voiceCommand = VoiceAssistantDetector.Target(VoiceAssistantDetector.Via.VOICE_COMMAND, null, null),
            assist = null,
            installedVendors = listOf("com.miui.voiceassist"),
        )
        // 包名为空的系统入口（交给系统默认）与厂商兜底不冲突，两条都保留
        assertEquals(2, targets.size)
        assertEquals(VoiceAssistantDetector.Via.VOICE_COMMAND, targets[0].via)
        assertEquals(VoiceAssistantDetector.Via.VENDOR, targets[1].via)
    }

    @Test
    fun 唤醒顺序_全未命中返回空表() {
        val targets = VoiceAssistantDetector.orderedTargets(null, null, emptyList())
        assertEquals(emptyList<VoiceAssistantDetector.Target>(), targets)
    }

    @Test
    fun 唤醒顺序_候选数有上限_失败要快() {
        val vendors = listOf("a", "b", "c", "d", "e")
        val targets = VoiceAssistantDetector.orderedTargets(null, null, vendors)
        assertEquals(VoiceAssistantDetector.MAX_WAKE_CANDIDATES, targets.size)
    }
}
