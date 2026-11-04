package com.phoneagent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 降级提示展示规则的单元测试：方式标签、需操作徽章、停留时长。
 *
 * 这些是引擎契约（docs/task-notice-contract.md）落到界面上的判定点，
 * 改动它们等于改用户看到的语义，所以用测试钉住。
 */
class TaskNoticePresentationTest {

    @Test
    fun `method 非空时加当前前缀`() {
        assertEquals("当前：内置 Shizuku", TaskNoticePresentation.methodLabel("内置 Shizuku"))
    }

    @Test
    fun `method 空白视为没有自动方式`() {
        assertNull(TaskNoticePresentation.methodLabel(null))
        assertNull(TaskNoticePresentation.methodLabel("   "))
        assertNull(TaskNoticePresentation.methodLabel(""))
    }

    @Test
    fun `method 首尾空白被裁掉`() {
        assertEquals("当前：无障碍服务", TaskNoticePresentation.methodLabel("  无障碍服务 "))
    }

    @Test
    fun `只有需操作才挂徽章`() {
        assertEquals("需操作", TaskNoticePresentation.actionBadge(true))
        assertNull(TaskNoticePresentation.actionBadge(false))
    }

    @Test
    fun `停留时长不低于契约要求的三秒`() {
        assertEquals(4000L, TaskNoticePresentation.HoldMs)
        assert(TaskNoticePresentation.HoldMs >= 3000L)
    }
}
