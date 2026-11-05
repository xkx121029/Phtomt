package com.phoneagent.debug.logtransfer

/**
 * 跨来源去重：同一批日志会同时从「AppLog 挂接」和「引擎日志流」出来
 * （TelemetryRecorder 会把 AppLog 的日志镜像进引擎缓冲，正文带 `[tag] ` 前缀）。
 *
 * 规则：同一 key 只在**不同来源**之间去重，同一来源内的重复（用户真的连续打两条一样
 * 的日志）照常保留；窗口外的同 key 视为新日志，避免长时间运行后误杀。
 */
class CrossSourceDeduper(
    private val clock: () -> Long,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxTracked: Int = DEFAULT_MAX_TRACKED,
) {

    private data class Seen(val source: String, val at: Long)

    private val recent = LinkedHashMap<String, Seen>(maxTracked, 0.75f, true)

    /** @return true 表示这条日志应被接受（未被另一来源刚刚发过） */
    @Synchronized
    fun accept(key: String, source: String): Boolean {
        val now = clock()
        val seen = recent[key]
        if (seen != null && seen.source != source && now - seen.at <= windowMs) {
            // 另一来源的镜像：丢掉这一份，并移除记录，避免第三次同 key 再被误判
            recent.remove(key)
            return false
        }
        recent[key] = Seen(source, now)
        if (recent.size > maxTracked) {
            val it = recent.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 3_000L
        const val DEFAULT_MAX_TRACKED = 512
    }
}
