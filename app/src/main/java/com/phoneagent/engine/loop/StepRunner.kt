package com.phoneagent.engine.loop

/**
 * 主循环里的一个阶段（阶段 B2）。
 *
 * 观察 / 决策 / 执行各实现一次 [run]；阶段之间只通过 [StepSession] 交接产物，
 * 不共享局部变量。返回值统一用 [StepOutcome] 表达「继续 / 返回 / 重试 / 失败」。
 */
internal fun interface StepPhase {
    suspend fun run(session: StepSession): StepOutcome
}

/**
 * 单步编排器：把一个循环轮次按「观察 → 决策 → 执行」串起来。
 *
 * 短路规则与迁移前的 `continue` 完全一致：任一阶段返回非 [StepOutcome.Continue]，
 * 后续阶段不再执行，结果原样交给循环壳 [TaskRunner]。
 */
internal class StepRunner(
    private val observe: StepPhase,
    private val decide: StepPhase,
    private val execute: StepPhase,
) {
    /**
     * 上一步动作后抓到的快照，只交给紧邻的下一步观察；消费一次即清空。
     *
     * 放在编排器内部而不是引擎侧，是因为「动作即观察」的收益必须由相邻两轮直接兑现：
     * 上一步执行段写入 [StepSession.afterObservation]，本步观察段读取
     * [StepSession.pendingAfterObservation]，中间不经过任何任务级状态，
     * 任务级状态一旦残留就会被下一轮任务误复用。
     */
    private var pendingAfter: AfterObservation? = null

    suspend fun run(session: StepSession): StepOutcome {
        session.pendingAfterObservation = pendingAfter
        pendingAfter = null
        return try {
            val observation = observe.run(session)
            if (observation != StepOutcome.Continue) return observation
            val decision = decide.run(session)
            if (decision != StepOutcome.Continue) return decision
            execute.run(session)
        } finally {
            // 执行段没抓到（动作未验证/不改变页面）时保持 null，下一步照旧完整观察
            pendingAfter = session.afterObservation
        }
    }
}
