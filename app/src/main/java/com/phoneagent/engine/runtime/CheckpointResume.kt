package com.phoneagent.engine.runtime

/**
 * 检查点续传的起始步数（纯逻辑，可单测）。
 *
 * 为什么要把这件小事单独收一格：检查点里记着「已完成的步数」，而主循环的步数是每轮从 0 自己数上去的。
 * 续传时若照旧从 0 起算，已执行过的步骤会被整条重放——对发送/删除/下单这类不可逆动作就是第二次副作用。
 * 幂等保护在这里指望不上：它要求「页面元素 ≤6 且含确认语」，而真实发生过副作用之后，页面
 * 通常是结果页/列表页（元素远超 6 个），判据直接返回 false。
 *
 * 所以续传必须把已完成的步数带进主循环：步数从这里起算，AI 也会在任务记忆里看到
 * 「已完成 N 步 / 勿重复执行已完成步骤」（见 [StepTexts.taskMemory]，该机制本来就有，只是此前没人喂它）。
 */
internal object CheckpointResume {

    /**
     * 续传时的起始步数。
     *
     * @param completedSteps 检查点里记录的已完成步数（旧版本存档或异常中断可能为负数/超出计划长度）
     * @param plannedSteps 本次计划的总步数（0 = 无计划，不做上限收敛）
     */
    fun startStep(completedSteps: Int, plannedSteps: Int): Int {
        if (completedSteps <= 0) return 0
        // 计划长度是上限：脏检查点（存档被改短、或计划被重新规划成更短的版本）不能让续传
        // 一进来就把步数顶到上限之外，否则第一轮就直接走「达到最大步数」收尾
        val cap = if (plannedSteps > 0) plannedSteps else completedSteps
        return completedSteps.coerceAtMost(cap)
    }
}
