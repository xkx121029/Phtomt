package com.phoneagent.engine

/**
 * 任务路由决策（Superagent 融合 Phase 4）：把用户任务分流为两种形态。
 *
 * 由 AgentEngine 组装的 router 产出（合并形态下由云端意图判定，见 [IntentRouter]）；
 * [com.phoneagent.engine.runtime.TaskLifecycle] 只按决策分发执行体，不感知形态细节。
 * Chat 决策自带执行体（[run]）：在 TaskLifecycle 的队列 worker 协程内挂起执行，
 * 保持"同一时刻只允许一个任务"的 worker 语义。
 *
 * 编码不在其中：Coding 页有自己的执行体，不经这条链路。
 */
sealed class RouteDecision {

    /** 普通对话：主模型直答一步完成（跳过感知与规划），[run] 负责把回答写进时间线 */
    data class Chat(val run: suspend () -> Unit) : RouteDecision()

    /** 操控手机：原有 ReAct 链路，行为不变 */
    object Agent : RouteDecision()
}
