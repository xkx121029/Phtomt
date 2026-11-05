package com.phoneagent.debug.logtransfer

/**
 * 历史日志合并：磁盘追加日志 + 引擎内存环形缓冲 + 调试记录磁盘存档。
 *
 * 三者都是"设备本地存储"的一部分，但覆盖面不同：
 * - 追加日志：本功能启用后写入的完整流水（跨重启保留，不裁剪）；
 * - 内存缓冲：最近 800 条（`MAX_LOGS` 环形）；
 * - 调试存档：任务结束时落盘的快照（功能启用前的历史只能从这里拿）。
 *
 * 要求是「按时间戳正序完整传输」，所以：去重（三处会出现同一条）后按时间戳升序排列，
 * 不设数量上限，也不做截断。排序使用稳定排序，时间戳相同的条目保持来源内原顺序。
 */
object LogHistoryAssembler {

    fun assemble(vararg sources: List<LogRecord>): List<LogRecord> {
        val total = sources.sumOf { it.size }
        val seen = HashSet<String>(total / 2 + 8)
        val out = ArrayList<LogRecord>(total)
        sources.forEach { list ->
            list.forEach { rec ->
                if (seen.add(rec.key())) out += rec
            }
        }
        out.sortWith(STABLE_BY_TIMESTAMP)
        return out
    }

    private val STABLE_BY_TIMESTAMP = Comparator<LogRecord> { a, b -> a.timestamp.compareTo(b.timestamp) }
}
