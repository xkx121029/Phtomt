package com.phoneagent.engine

/**
 * 任务路由决策（Superagent 融合 Phase 4）：把用户任务分流为三种形态。
 *
 * 由 AgentEngine 组装的 router 产出（判定唯一走 [TaskKindDetector]，与提示词裁块同源）；
 * [com.phoneagent.engine.runtime.TaskLifecycle] 只按决策分发执行体，不感知三形态的细节。
 * Coding / Chat 两个决策自带执行体（[run]）：在 TaskLifecycle 的队列 worker 协程内挂起执行，
 * 保持"同一时刻只允许一个任务"的 worker 语义——coding 任务执行期间，队列里后续任务照常排队等待。
 */
sealed class RouteDecision {

    /** vibecoding：委托设备端编码 agent（不进 ReAct 感知循环），[run] 挂起直到编码会话结束 */
    data class Coding(val run: suspend () -> Unit) : RouteDecision()

    /** 普通对话：主模型直答一步完成（跳过感知与规划），[run] 负责把回答写进时间线 */
    data class Chat(val run: suspend () -> Unit) : RouteDecision()

    /** 操控手机：原有 ReAct 链路，行为不变 */
    object Agent : RouteDecision()
}
