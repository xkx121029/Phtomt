package com.phoneagent.coding.api

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** 编码会话的连接阶段。 */
enum class CodingConnection { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * 编码会话的对外快照。
 *
 * 后台引擎与设置页只关心「连上没有 / 忙不忙 / 出没出错 / 有没有在等审批」，
 * 消息明细（工具卡、审批卡、子 agent 进度）留在切片内部给编码页自己渲染。
 */
data class CodingSessionStatus(
    val connection: CodingConnection = CodingConnection.DISCONNECTED,
    val busy: Boolean = false,
    val error: String? = null,
    /** 有工具调用在等用户审批（后台进度据此显示「等待审批」） */
    val awaitingApproval: Boolean = false,
    /** 最新一条非空 assistant 文本（后台进度显示用；没有就是 null） */
    val lastAssistantText: String? = null,
)

/**
 * 把切片内部的状态流投影成 [CodingSessionStatus]。
 *
 * 不新开协程、不加 mirror 缓冲：`value` 与 `collect` 都在读取时换算，
 * 订阅者拿到的进度与内部流逐条同步（投影是纯函数，不产生延迟也不丢事件）。
 */
internal class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {

    override val value: R get() = transform(source.value)

    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).collect { collector.emit(it) }
        error("StateFlow 的 collect 不会正常返回")
    }
}
