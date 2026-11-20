package com.phoneagent.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 检查点续传的起始步数。
 *
 * 回归目标（真机可复现）：任务停在第 6 步，用户点「续传」——旧实现只把任务原文与计划带回去，
 * 步数仍从 0 起算，于是计划第 1~6 步整条重放：已发送的消息再发一遍、已下的单再下一单。
 * 幂等保护挡不住（它要求页面元素 ≤6 且含确认语，副作用发生之后基本不成立）。
 */
class CheckpointResumeTest {

    @Test
    fun `已完成N步时从第N步之后继续`() {
        assertEquals(5, CheckpointResume.startStep(completedSteps = 5, plannedSteps = 10))
    }

    @Test
    fun `没有已完成步数时从头开始`() {
        assertEquals(0, CheckpointResume.startStep(completedSteps = 0, plannedSteps = 10))
    }

    @Test
    fun `旧存档的负数按从头处理`() {
        assertEquals(0, CheckpointResume.startStep(completedSteps = -3, plannedSteps = 10))
    }

    @Test
    fun `已完成步数超出计划长度时收敛到计划长度`() {
        // 脏检查点（存档被改短 / 计划被重新规划成更短版本）不能让续传一进来就撞上「达到最大步数」
        assertEquals(10, CheckpointResume.startStep(completedSteps = 12, plannedSteps = 10))
    }

    @Test
    fun `没有计划时不做上限收敛`() {
        assertEquals(7, CheckpointResume.startStep(completedSteps = 7, plannedSteps = 0))
    }
}
