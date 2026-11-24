package com.phoneagent.engine

import com.phoneagent.domain.model.TaskPlan

/**
 * 一次任务会话的归档（Agent 页「任务」侧边栏的数据源）。
 *
 * 只存**任务级**的轻量元数据：标题 / 起止时间 / 终态 / 摘要 / 已批准计划 / 本次写入的记忆。
 * 逐步的执行明细（StepTrace / StepRecord）不在这里复制一份——它们本就按 taskId
 * 长期留存在引擎的 traces / executionHistory 中，再存一遍等于让带截图的轨迹成倍驻留内存。
 *
 * [taskId] 由任务开始时刻生成，因此也直接当作 [startedAt] 使用。
 */
data class TaskSession(
    val taskId: Long,
    val title: String,
    val startedAt: Long = taskId,
    val endedAt: Long = 0L,
    val status: Status = Status.RUNNING,
    /** 终态说明：完成摘要 / 停止或失败原因 */
    val summary: String = "",
    /** 本次任务已批准的计划（排队入口直接执行的任务为 null） */
    val plan: TaskPlan? = null,
    /** 本次任务写入/更新的记忆事件 */
    val memoryEvents: List<MemoryEvent> = emptyList(),
    /** 已产生的步数（沉底计数，明细被环形缓冲淘汰后仍能显示规模） */
    val steps: Int = 0,
    /** 已验证生效的步数 */
    val okSteps: Int = 0,
) {
    enum class Status { RUNNING, DONE, ABORTED, FAILED }
}