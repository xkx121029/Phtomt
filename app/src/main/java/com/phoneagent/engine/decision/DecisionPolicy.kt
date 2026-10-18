package com.phoneagent.engine.decision

import com.phoneagent.engine.runtime.EngineLimits

/**
 * 决策护栏策略（决策逻辑改进，阶段 B3）：把散落在决策循环里的硬编码护栏阈值收口为纯函数，
 * 可单测、口径单一。只做判定，不持有状态；计数器本身仍存 EngineStore。
 */
internal object DecisionPolicy {
    /** 连续端侧决策达到该次数即判定"疑似死循环" */
    internal const val LOCAL_LOOP_REVIEW_THRESHOLD = 5

    /** 疑似死循环时是否强制一次云端复核：达到阈值即强制（云端复核一轮后 streak 清零，自然恢复端侧节流） */
    fun shouldForceCloudReview(localStreak: Int): Boolean = localStreak >= LOCAL_LOOP_REVIEW_THRESHOLD

    /** 连续决策链路异常是否达到收尾阈值 */
    fun isDecisionFailureFatal(failureStreak: Int): Boolean = failureStreak >= EngineLimits.MAX_DECISION_FAILURE_STREAK
}
