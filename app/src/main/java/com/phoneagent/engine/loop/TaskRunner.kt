package com.phoneagent.engine.loop

import kotlinx.coroutines.delay

/**
 * 主循环壳（阶段 B2）：只负责「什么时候跑下一步、这一步之后怎么走」，
 * 不关心观察/决策/执行的具体内容。
 *
 * 迁移前这段是 AgentEngine.runInner 里的一个 `while`，与本步的业务逻辑缠在一起；
 * 拆出来后循环语义集中在一处，且能用一个假 [runStep] 做端到端回归（见 TaskRunnerTest）。
 *
 * 逐行等价点：
 * - 搁置等待与取消检查发生在 `step++` 之前（搁置期间不白耗步数）
 * - [StepOutcome.Retry] 回退步数（原先的 `step--`）后再等待
 * - 达到最大步数才走 [onMaxSteps]；用户取消（[isActive] 为 false）直接返回，不触发最大步数收尾
 * - `maxSteps <= 0` 表示不设上限
 */
internal class TaskRunner(
    private val awaitResume: suspend () -> Unit,
    private val isActive: suspend () -> Boolean,
    /** 每步开始：写观察相位、步数、无障碍状态并推送悬浮窗 */
    private val onStepStart: (Int) -> Unit,
    /** 重试回退：把界面上已写出的步数改回回退后的值 */
    private val onStepRolledBack: (Int) -> Unit,
    /** 失败收尾：任务记忆置失败并停止引擎（界面文案由触发阶段自己写） */
    private val onFatal: (StepOutcome.Fatal) -> Unit,
    /** 达到最大步数收尾 */
    private val onMaxSteps: suspend () -> Unit,
) {

    /**
     * @param startStep 已消耗的步数（迁移前 `step` 从 0 开始）
     * @param runStep 执行一轮；返回本轮之后循环怎么走
     * @return 循环结束时的步数
     */
    suspend fun run(
        maxSteps: Int,
        startStep: Int = 0,
        runStep: suspend (Int) -> StepOutcome,
    ): Int {
        var step = startStep
        while (maxSteps <= 0 || step < maxSteps) {
            awaitResume()
            if (!isActive()) return step
            step++
            onStepStart(step)
            when (val outcome = runStep(step)) {
                StepOutcome.Continue -> Unit
                StepOutcome.Return -> return step
                is StepOutcome.Retry -> {
                    step--
                    onStepRolledBack(step)
                    if (outcome.delayMs > 0) delay(outcome.delayMs)
                }
                is StepOutcome.Fatal -> {
                    onFatal(outcome)
                    return step
                }
            }
        }
        onMaxSteps()
        return step
    }
}
