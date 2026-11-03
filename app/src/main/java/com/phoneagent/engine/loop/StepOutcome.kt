package com.phoneagent.engine.loop

import com.phoneagent.domain.model.AgentState

/**
 * 单步主循环的退出契约（阶段 B2）。
 *
 * 迁移前主循环用裸 `continue` / `return` 表达控制流，散落在 700 余行里：
 * - `continue` 有时表示「本步结束、进入下一轮」，有时表示「本步不计步、重试」；
 * - `return` 有时表示「任务正常完成/放弃」，有时表示「任务失败收尾」。
 * 两者在源码上只差一个关键字，含义却完全不同，是重构时最容易改错行为的地方。
 *
 * 因此把「这一步之后循环怎么走」收成一个显式类型：
 *
 * | 值 | 循环动作 | 语义 |
 * |---|---|---|
 * | [Continue] | 进入下一轮 | 本步已完整处理（含正常跳过） |
 * | [Return] | 退出循环 | 任务已有明确终态（完成 / 放弃 / 已由阶段自己收尾） |
 * | [Retry] | 回退步数后进入下一轮 | 决策链路异常等「本步不计步」的等待重试 |
 * | [Fatal] | 退出循环 + 失败收尾 | 护栏触发（连续决策异常/技能被拒/模式拒绝/用户离开等） |
 *
 * 约定：**阶段自己负责状态文案、悬浮窗与日志**（这些文案各有语境，收进循环壳只会制造分支），
 * 循环壳只负责「回退步数 / 延时 / 任务记忆置失败 / 停止引擎」这类机械动作。
 * 这样 [Fatal] 不携带 UI 文案，阶段在返回前已经把界面写成它想要的样子。
 */
sealed interface StepOutcome {

    /** 本步处理完毕，进入下一轮（步数已由循环壳递增，阶段不得自行改步数）。 */
    data object Continue : StepOutcome

    /** 退出主循环：任务终态已由阶段落定（成功 / 放弃 / 用户停止）。 */
    data object Return : StepOutcome

    /**
     * 本步不计步的重试：循环壳把步数回退一格并按 [delayMs] 等待后进入下一轮。
     *
     * 唯一来源是云端决策链路异常（网络/API 抖动）。原先该分支在循环里手写 `step--; delay(1500); continue`，
     * 收进契约后「不计步」这层含义不再依赖读者去注意 `step--`。
     */
    data class Retry(val delayMs: Long = 0) : StepOutcome

    /**
     * 失败收尾：循环壳把任务记忆置为 FAILED 并停止引擎。
     *
     * [failureKind] 仅用于诊断与测试断言；界面上的 phase/message 由触发阶段自己写，
     * 因为不同护栏的文案与是否需要悬浮窗提示并不一致。
     */
    data class Fatal(
        val reason: String,
        val failureKind: AgentState.FailureKind = AgentState.FailureKind.UNKNOWN,
        /** 写入任务记忆终态的结论（部分分支需要带原因，如「已放弃：…」） */
        val taskMemoryNote: String = "",
    ) : StepOutcome
}
