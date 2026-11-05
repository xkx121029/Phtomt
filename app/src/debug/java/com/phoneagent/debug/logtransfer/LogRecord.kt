package com.phoneagent.debug.logtransfer

/**
 * 传输用的单条日志记录（debug 专有，release 不参与编译）。
 *
 * 与 `domain.model.AgentLog` 刻意解耦：本类不引用任何 Android 类型，因此纯逻辑
 * （历史合并、序号、重传、延迟断言）可以在 JVM 单测里直接跑。
 */
data class LogRecord(
    val timestamp: Long,
    val level: String,
    val message: String,
    val detail: String? = null,
    val taskId: Long = -1L,
    val taskName: String? = null,
) {

    /** 去重键：同一条日志在「内存缓冲 / 磁盘存档 / 追加日志」三处都会出现 */
    fun key(): String = "$timestamp|$level|$message"
}
