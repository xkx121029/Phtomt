package com.phoneagent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用清单文案测试。
 *
 * 这两段文本是"本地事实 → AI 读得懂的话"的唯一边界，错了不会崩、只会让 AI 判错，
 * 所以重点锁"截断必须自曝"与"没有匹配"这两种容易被静默处理的情况。
 */
class DeviceFactsTextTest {

    private val apps = listOf("微信(com.tencent.mm)", "设置(com.android.settings)", "地图(com.x.map)")

    // ---- 规划提示词清单 ----

    @Test
    fun appListPromptText_未超限_只给清单不带附加说明() {
        val text = DeviceFacts.appListPromptText(apps, max = 10)

        assertEquals("微信(com.tencent.mm)、设置(com.android.settings)、地图(com.x.map)", text)
    }

    @Test
    fun appListPromptText_超限_必须写明被截断并给出补救用法() {
        // 截断一旦静默，被截掉的应用在 AI 眼里就等同于"没装"，会误判为需要澄清或直接放弃任务
        val text = DeviceFacts.appListPromptText(apps, max = 2)

        assertTrue(text.startsWith("微信(com.tencent.mm)、设置(com.android.settings)"))
        assertFalse(text.contains("地图"))
        assertTrue(text.contains("仅列出前 2 个"))
        assertTrue(text.contains("本机共 3 个"))
        assertTrue(text.contains("device_query kind=apps"))
        assertTrue(text.contains("filter"))
    }

    @Test
    fun appListPromptText_空清单_给出空串而不是说明() {
        assertEquals("", DeviceFacts.appListPromptText(emptyList(), max = 10))
    }

    // ---- device_query kind=apps 的答复 ----

    @Test
    fun appQueryAnswerText_读不到清单_直说读不到() {
        assertEquals("未能读取到已安装应用清单", DeviceFacts.appQueryAnswerText(emptyList(), filter = "微信"))
    }

    @Test
    fun appQueryAnswerText_无匹配_说明总数而不要谎报没装() {
        val text = DeviceFacts.appQueryAnswerText(apps, filter = "抖音", max = 10)

        assertEquals("已安装应用里没有匹配「抖音」的（共 3 个可启动应用）", text)
    }

    @Test
    fun appQueryAnswerText_有匹配未超限_给出总数与命中数() {
        val text = DeviceFacts.appQueryAnswerText(apps, filter = "微", max = 10)

        assertEquals(
            "已安装可启动应用共 3 个，匹配「微」的 1 个：\n微信(com.tencent.mm)",
            text,
        )
    }

    @Test
    fun appQueryAnswerText_空过滤词_按全部口径表述() {
        val text = DeviceFacts.appQueryAnswerText(apps, filter = "", max = 10)

        assertTrue(text.contains("匹配「全部」的 3 个"))
    }

    @Test
    fun appQueryAnswerText_过滤词忽略大小写() {
        val text = DeviceFacts.appQueryAnswerText(apps, filter = "TENCENT", max = 10)

        assertTrue(text.contains("匹配「TENCENT」的 1 个"))
    }

    @Test
    fun appQueryAnswerText_命中超限_写明剩余条数与缩小范围的用法() {
        val text = DeviceFacts.appQueryAnswerText(apps, filter = "", max = 2)

        assertTrue(text.contains("匹配「全部」的 3 个"))
        assertTrue(text.contains("仅列出前 2 个"))
        assertTrue(text.contains("剩下 1 个"))
        assertTrue(text.contains("filter"))
    }
}
