package com.phoneagent.engine

/**
 * 一次记忆写入事件（引擎内存态），供 Agent 页在任务流里实时插入记忆卡片。
 * [updated] 为 true 表示这条与已有记忆合并更新，而不是新增。
 *
 * 住在 `:data`：它是 [com.phoneagent.data.store.DebugRecordsStore] 直接持久化的记录类型
 * （调试记录导出要把它写盘）。包名仍是 `com.phoneagent.engine`，所以 `:app` 里 9 处
 * 引用一行都没改——代价是这个包被 `:app` 与 `:data` 各持一半，见报告「split package」一节。
 */
data class MemoryEvent(
    val id: Long,
    val content: String,
    val category: String,
    val updated: Boolean,
    val runKey: String,
    val step: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
