package com.phoneagent.engine.perf

import com.phoneagent.core.log.AppLog

/**
 * 引擎阶段耗时聚合（性能改造的第一步：先量后改）。
 *
 * 目的：把"任务提交 → 首次决策返回"的等待，以及每轮"感知 → 决策 → 归一化 → 转译 → 执行"的往返，
 * 拆到具体阶段上。没有这一步，所有优化都只是猜。
 *
 * 设计约束：
 * - **只包一层已有调用点**，不改任何业务逻辑；[measure] 为 inline，不产生额外 lambda 对象。
 * - **按任务隔离**：任务切换时把上一任务的汇总（各阶段次数/总耗时/均值）写进应用内日志
 *   （调试页可见，与 TelemetryRecorder 同一缓冲），然后清零——跨任务混算会把均值搅乱。
 * - 阶段名用中文，便于直接在调试页读；不进入发给模型的提示词，不影响提示词契约。
 */
internal object StageTiming {
    private const val TAG = "StageTiming"

    /** 单个阶段在本任务内的聚合样本 */
    data class Sample(val stage: String, val count: Int, val totalMs: Long) {
        val avgMs: Long get() = if (count <= 0) 0 else totalMs / count
    }

    private val lock = Any()
    private var activeTask = Long.MIN_VALUE
    private val buckets = LinkedHashMap<String, LongArray>()

    /**
     * 计时执行 [block]。阶段耗时无论成功与否都会记账：
     * 失败路径的"等了多久才失败"同样是决策延迟的一部分。
     */
    internal inline fun <T> measure(taskId: Long, stage: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            record(taskId, stage, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    /* internal 而非 private：inline 函数只能访问可内联的可见性成员 */
    internal fun record(taskId: Long, stage: String, elapsedMs: Long) {
        val flushed = synchronized(lock) {
            var out: Pair<Long, String>? = null
            if (taskId != activeTask) {
                if (activeTask != Long.MIN_VALUE && buckets.isNotEmpty()) {
                    out = activeTask to summaryTextInternal()
                }
                activeTask = taskId
                buckets.clear()
            }
            val cell = buckets.getOrPut(stage) { longArrayOf(0L, 0L) }
            cell[0] += elapsedMs
            cell[1] += 1
            out
        }
        // 日志在锁外写：AppLog 的 sink 会碰 UI 状态流，别在锁里做
        flushed?.let { (id, text) -> AppLog.i(TAG, "任务 $id 阶段耗时：$text") }
    }

    /** 当前任务各阶段样本（副本） */
    fun samples(): List<Sample> = synchronized(lock) {
        buckets.entries.map { (stage, cell) -> Sample(stage, cell[1].toInt(), cell[0]) }
    }

    /** 当前任务汇总文本，例如："环境事实 8 次 24ms（均 3ms）；记忆与规则 8 次 88ms（均 11ms）" */
    fun summaryText(): String = synchronized(lock) { summaryTextInternal() }

    /** 清空（引擎重建/测试用） */
    fun reset() = synchronized(lock) {
        activeTask = Long.MIN_VALUE
        buckets.clear()
    }

    private fun summaryTextInternal(): String =
        buckets.entries.joinToString("；") { (stage, cell) ->
            val count = cell[1]
            val avg = if (count > 0) cell[0] / count else 0
            "$stage ${count} 次 ${cell[0]}ms（均 ${avg}ms）"
        }
}
