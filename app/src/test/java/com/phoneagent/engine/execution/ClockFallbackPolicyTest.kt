package com.phoneagent.engine.execution

import com.phoneagent.device.clock.ClockRequest
import com.phoneagent.device.clock.ClockRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时钟降级链的纯策略测试：候选顺序、各级生效条件、失败判据文案、用户可见说明。
 *
 * 执行部分（Intent/shell/无障碍）需要真机，单测只锁「表本身」——表错了，真机再怎么跑都是错的。
 */
class ClockFallbackPolicyTest {

    private val fullCaps = ClockCapabilities(accessibility = true, shell = true)
    private val alarm = ClockRequests.alarm(7, 30, null, "起床")

    @Test
    fun 全通道可用_顺序为直达_写库_无障碍_手动() {
        val kinds = ClockFallbackPolicy.levels(alarm, fullCaps).map { it.kind }
        assertEquals(
            listOf(
                ClockFallbackKind.DIRECT_SILENT,
                ClockFallbackKind.SHELL_WRITE,
                ClockFallbackKind.UI_CONFIRM,
                ClockFallbackKind.OPEN_MANUAL,
            ),
            kinds,
        )
    }

    @Test
    fun 无shell无无障碍_仍保留直达与手动兜底() {
        val kinds = ClockFallbackPolicy.levels(alarm, ClockCapabilities(accessibility = false, shell = false))
            .map { it.kind }
        assertEquals(listOf(ClockFallbackKind.DIRECT_SILENT, ClockFallbackKind.OPEN_MANUAL), kinds)
    }

    @Test
    fun 倒计时_即使有shell也不进写库级() {
        val timer = ClockRequests.timer(1800, "煮面")
        val kinds = ClockFallbackPolicy.levels(timer, fullCaps).map { it.kind }
        assertFalse(ClockFallbackKind.SHELL_WRITE in kinds)
        // 跳过原因必须说清楚是「没有可写入口」，不能静默消失
        assertTrue(ClockFallbackPolicy.skipReason(ClockFallbackKind.SHELL_WRITE, timer, fullCaps)!!.contains("倒计时"))
    }

    @Test
    fun 重复闹钟_不进写库级且说明星期字段不一致() {
        val repeating = ClockRequests.alarm(7, 30, listOf(2, 3, 4, 5, 6), null)
        val kinds = ClockFallbackPolicy.levels(repeating, fullCaps).map { it.kind }
        assertFalse(ClockFallbackKind.SHELL_WRITE in kinds)
        assertTrue(ClockFallbackPolicy.skipReason(ClockFallbackKind.SHELL_WRITE, repeating, fullCaps)!!.contains("重复"))
    }

    @Test
    fun 无shell时_写库级标为跳过而不是失败() {
        val caps = ClockCapabilities(accessibility = true, shell = false)
        assertNull(ClockFallbackPolicy.skipReason(ClockFallbackKind.DIRECT_SILENT, alarm, caps))
        val reason = ClockFallbackPolicy.skipReason(ClockFallbackKind.SHELL_WRITE, alarm, caps)!!
        assertTrue(reason.contains("跳过"))
        assertTrue(reason.contains("不记为失败"))
    }

    @Test
    fun 每一级都有生效条件与失败判据() {
        ClockFallbackPolicy.levels(alarm, fullCaps).forEach { level ->
            assertTrue("${level.kind} 缺少生效条件", level.condition.isNotBlank())
            assertTrue("${level.kind} 缺少失败判据", level.failureCriterion.isNotBlank())
        }
    }

    @Test
    fun 成功文案必须点名当前方式与时间() {
        val direct = ClockFallbackPolicy.successMessage(ClockFallbackKind.DIRECT_SILENT, alarm)
        assertTrue(direct.contains("系统时钟"))
        assertTrue(direct.contains("07:30"))
        assertTrue(direct.contains("未回执"))

        val shell = ClockFallbackPolicy.successMessage(ClockFallbackKind.SHELL_WRITE, alarm)
        assertTrue(shell.contains("Shizuku"))
        assertTrue(shell.contains("回查确认"))

        val a11y = ClockFallbackPolicy.successMessage(ClockFallbackKind.UI_CONFIRM, alarm, clickedLabel = "保存")
        assertTrue(a11y.contains("无障碍"))
        assertTrue(a11y.contains("保存"))
    }

    @Test
    fun 手动兜底文案_包含失败链原因与当前方式() {
        val text = ClockFallbackPolicy.manualMessage(
            alarm,
            listOf("静默直达：启动时钟页失败", "无障碍确认：无障碍服务未开启，跳过页面确认"),
            opened = true,
        )
        assertTrue(text.contains("未完成"))
        assertTrue(text.contains("当前方式：打开时钟页手动设置"))
        assertTrue(text.contains("无障碍服务未开启"))
        assertTrue(text.contains("07:30"))
    }

    @Test
    fun 连时钟页都打不开_文案指出没有可用时钟应用() {
        val text = ClockFallbackPolicy.manualMessage(alarm, listOf("静默直达：没有可用的时钟应用"), opened = false)
        assertTrue(text.contains("没有可用的时钟应用"))
        assertTrue(text.contains("失败"))
    }

    @Test
    fun 时间描述_支持每天与倒计时格式() {
        assertEquals("闹钟 07:30（每天）", ClockFallbackPolicy.describe(ClockRequests.alarm(7, 30, (1..7).toList(), null)))
        assertEquals("闹钟 23:05（工作日）", ClockFallbackPolicy.describe(ClockRequests.alarm(23, 5, listOf(2, 3, 4, 5, 6),
            null)))
        assertEquals("倒计时 30 分钟", ClockFallbackPolicy.describe(ClockRequests.timer(1800, null)))
        assertEquals("倒计时 1 小时 2 分钟", ClockFallbackPolicy.describe(ClockRequests.timer(3720, null)))
    }

    @Test
    fun 厂商私有action候选_包内去重且按顺序挑选() {
        assertTrue(ClockVendorRoutes.alarm.isNotEmpty())
        assertEquals(ClockVendorRoutes.alarm.size, ClockVendorRoutes.alarm.toSet().size)
        assertTrue(ClockVendorRoutes.alarm.all { it.action.startsWith(it.packageName) })

        val picked = ClockVendorRoutes.pick(ClockVendorRoutes.alarm) { it.packageName == "com.miui.clock" }
        assertEquals("com.miui.clock", picked!!.packageName)

        // 全不可解析 → 不选任何私有 action（随后会落回标准 AlarmClock 意图）
        assertNull(ClockVendorRoutes.pick(ClockVendorRoutes.alarm) { false })
    }
}
