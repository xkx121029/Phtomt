package com.phoneagent.debug.logtransfer

import java.io.File

/**
 * 追加式日志流水（debug 专有）。
 *
 * 为什么需要它：应用内日志只有 800 条环形缓冲，磁盘存档只是任务结束时的快照，
 * 都撑不起「设备本地存储的全部历史日志」。这里按行追加、立即 flush，跨重启保留；
 * 刻意不裁剪、不轮转——需求要求历史完整，宁可占用磁盘也不丢条目（目录仅供 debug 包使用，
 * 用户清空应用数据即回收）。
 */
class LogJournal(private val file: File) {

    private val lock = Any()

    init {
        runCatching { file.parentFile?.mkdirs() }
    }

    fun append(record: LogRecord) {
        synchronized(lock) {
            runCatching { file.appendText(LogWire.encodeRecord(record) + "\n", Charsets.UTF_8) }
        }
    }

    fun readAll(): List<LogRecord> = synchronized(lock) {
        runCatching {
            if (!file.exists()) return@synchronized emptyList()
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { LogWire.decodeRecord(it) }.toList()
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)
}
