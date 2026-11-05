package com.phoneagent.debug.logtransfer

import java.io.Closeable

/**
 * 日志源抽象：历史快照 + 实时订阅。
 *
 * 服务端对每个连接各取一次历史快照、各订阅一次实时流，因此"多连接/重连"天然是
 * 各自独立的全量历史 + 各自从连接时刻起的实时流，互不干扰。
 */
interface LogFeed {

    /** 历史日志：时间戳正序、已去重的完整集合 */
    fun historySnapshot(): List<LogRecord>

    /** 订阅实时日志；返回的句柄关闭后不再回调 */
    fun addListener(listener: (LogRecord) -> Unit): Closeable
}
