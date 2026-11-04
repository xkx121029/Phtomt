package com.phoneagent.engine.runtime

import com.phoneagent.engine.execution.ChannelAttempt
import com.phoneagent.engine.execution.ChannelKind
import com.phoneagent.engine.execution.ChannelOutcome
import com.phoneagent.engine.execution.ClockFallbackKind
import com.phoneagent.engine.execution.ClockOutcome
import com.phoneagent.engine.execution.DegradationTrigger
import com.phoneagent.engine.execution.LadderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 降级说明状态出口测试。
 *
 * 重点不是"有没有存下来"，而是三件事：
 * 1. 文案必须来自 [com.phoneagent.engine.execution.DegradationPolicy]（不在出口里另写一套）；
 * 2. 重复发布同一条不会刷屏，文案变化时才替换；
 * 3. 通道阶梯/时钟降级链发生降级时才发布，没降级不打扰用户。
 */
class TaskNoticeStoreTest {

    @Before
    fun reset() {
        TaskNoticeCenter.clear()
    }

    @Test
    fun 降级说明发布后_所有线都能读到同一条() {
        val notice = TaskNoticeCenter.publishDegradation(
            trigger = DegradationTrigger.CHANNEL_UNAVAILABLE,
            detail = "无障碍服务不可用",
            method = "内置 Shizuku",
            source = "action",
        )
        assertSame(notice, TaskNoticeCenter.notice.value)
        assertEquals(TaskNoticeKind.DEGRADATION, notice.kind)
        assertEquals("channel_unavailable", notice.trigger)
        assertEquals("action", notice.source)
        assertEquals("内置 Shizuku", notice.method)
        assertFalse(notice.requiresAction)
        assertTrue(notice.atMillis > 0)
        // 文案由策略表生成：点名原通道与新方式，不是出口里另写的
        assertTrue(notice.text.contains("无障碍服务不可用"))
        assertTrue(notice.text.contains("已改用内置 Shizuku"))
    }

    @Test
    fun 没有替代通道时_标记为用户需要动手() {
        val notice = TaskNoticeCenter.publishDegradation(
            trigger = DegradationTrigger.PERMISSION_MISSING,
            detail = "通知使用权未开启",
            method = null,
            source = "permission",
        )
        assertTrue(notice.requiresAction)
        assertNull(notice.method)
        assertTrue(notice.text.contains("需要你授权"))
        assertTrue(notice.text.contains("没有可替代"))
    }

    @Test
    fun 相同提示重复发布不会刷屏_文案变化才替换() {
        val first = TaskNoticeCenter.publishDegradation(
            DegradationTrigger.CHANNEL_UNAVAILABLE, "无障碍服务不可用", "内置 Shizuku", "action",
        )
        val again = TaskNoticeCenter.publishDegradation(
            DegradationTrigger.CHANNEL_UNAVAILABLE, "无障碍服务不可用", "内置 Shizuku", "action",
        )
        assertSame("完全相同的提示应保留原对象，不重复发送", first, again)
        assertSame(first, TaskNoticeCenter.notice.value)

        val changed = TaskNoticeCenter.publishDegradation(
            DegradationTrigger.CHANNEL_UNAVAILABLE, "无线调试未连接", "内置 Shizuku", "action",
        )
        assertNotSame("文案变化时应替换为最新状态", first, changed)
        assertSame(changed, TaskNoticeCenter.notice.value)
        assertTrue(changed.text.contains("无线调试未连接"))
    }

    @Test
    fun 清除说明_可按id只清匹配的那条() {
        val first = TaskNoticeCenter.publishDegradation(
            DegradationTrigger.CHANNEL_UNAVAILABLE, "无障碍服务不可用", "内置 Shizuku", "action",
        )
        TaskNoticeCenter.clear(expectedId = "不存在的键")
        assertSame("不匹配的清除不应误删", first, TaskNoticeCenter.notice.value)

        TaskNoticeCenter.clear(expectedId = first.id)
        assertNull(TaskNoticeCenter.notice.value)

        TaskNoticeCenter.clear()
        assertNull(TaskNoticeCenter.notice.value)
    }

    @Test
    fun 通道阶梯降级后_出口有说明且不需要用户动手() {
        val ladder = LadderResult(
            delivered = true,
            confirmed = true,
            channel = ChannelKind.SHELL_SHIZUKU,
            attempts = listOf(
                ChannelAttempt(ChannelKind.ACCESSIBILITY, ChannelOutcome.NOT_DELIVERED, "无障碍服务不可用", 1200),
                ChannelAttempt(ChannelKind.SHELL_SHIZUKU, ChannelOutcome.CONFIRMED, "", 300),
            ),
            detail = "",
        )
        val notice = TaskNoticeCenter.publishLadder(ladder, source = "launch")
        assertNotNull(notice)
        assertEquals("launch", notice!!.source)
        assertEquals("内置 Shizuku", notice.method)
        assertFalse(notice.requiresAction)
        assertTrue(notice.text.contains("已改用内置 Shizuku"))
        assertSame(notice, TaskNoticeCenter.notice.value)
    }

    @Test
    fun 所有候选通道不可用_说明保留现场并要求用户处理() {
        val ladder = LadderResult(
            delivered = false,
            confirmed = false,
            channel = null,
            attempts = listOf(
                ChannelAttempt(ChannelKind.ACCESSIBILITY, ChannelOutcome.NOT_DELIVERED, "节点失效", 500),
                ChannelAttempt(ChannelKind.SHELL_ADB, ChannelOutcome.NOT_DELIVERED, "无线调试未连接", 500),
            ),
            detail = "未交付",
        )
        val notice = TaskNoticeCenter.publishLadder(ladder, source = "action")
        assertNotNull(notice)
        assertEquals("action", notice!!.source)
        assertNull(notice.method)
        assertTrue(notice.requiresAction)
        assertTrue(notice.text.contains("所有候选通道都不可用"))
    }

    @Test
    fun 未发生降级时不打扰用户() {
        val ladder = LadderResult(
            delivered = true,
            confirmed = true,
            channel = ChannelKind.ACCESSIBILITY,
            attempts = listOf(ChannelAttempt(ChannelKind.ACCESSIBILITY, ChannelOutcome.CONFIRMED, "", 200)),
            detail = "",
        )
        assertNull(TaskNoticeCenter.publishLadder(ladder, source = "action"))
        assertNull(TaskNoticeCenter.notice.value)
    }

    @Test
    fun 时钟静默直达成功不打扰_其余三级都要点名当前方式() {
        val direct = ClockOutcome.Done(ClockFallbackKind.DIRECT_SILENT, "已用系统时钟静默直达设定闹钟 07:30", verified = false)
        assertNull(TaskNoticeCenter.publishClock(direct))
        assertNull(TaskNoticeCenter.notice.value)

        val ui = ClockOutcome.Done(
            ClockFallbackKind.UI_CONFIRM,
            "静默直达不可用，已改用时钟页直达 + 无障碍点击「保存」确认：闹钟 07:30",
            verified = true,
        )
        val uiNotice = TaskNoticeCenter.publishClock(ui)!!
        assertEquals("clock", uiNotice.source)
        assertEquals("clock_fallback", uiNotice.trigger)
        assertEquals("时钟页直达 + 无障碍确认", uiNotice.method)
        assertFalse(uiNotice.requiresAction)
        assertTrue(uiNotice.text.contains("无障碍"))

        val manual = ClockOutcome.Manual("当前方式：打开时钟页手动设置。已打开时钟应用，请手动设定闹钟 07:30")
        val manualNotice = TaskNoticeCenter.publishClock(manual)!!
        assertEquals("打开时钟页手动设置", manualNotice.method)
        assertTrue("手动设置需要用户动手", manualNotice.requiresAction)

        val failed = ClockOutcome.Failed("连时钟应用都解析不到/启动失败")
        val failedNotice = TaskNoticeCenter.publishClock(failed)!!
        assertNull(failedNotice.method)
        assertTrue(failedNotice.requiresAction)
        assertEquals("连时钟应用都解析不到/启动失败", failedNotice.text)
    }

    @Test
    fun 通道切换后_静默直达失败也不覆盖成功说明() {
        val written = ClockOutcome.Done(
            ClockFallbackKind.SHELL_WRITE,
            "已用注入通道（Shizuku/无线 ADB）写入时钟数据库并回查确认：闹钟 07:30",
            verified = true,
        )
        val notice = TaskNoticeCenter.publishClock(written)!!
        assertEquals("注入通道写闹钟库", notice.method)
        assertTrue(notice.text.contains("回查确认"))
        assertFalse(notice.requiresAction)
    }
}
