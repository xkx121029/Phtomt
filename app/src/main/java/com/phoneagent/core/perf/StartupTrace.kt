package com.phoneagent.core.perf

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * 冷启动分段打点（只写 logcat，不进应用内日志缓冲）。
 *
 * 用途：把"点图标 → 首屏可输入"拆成 Application、DI 单例、磁盘回载、首帧几段，
 * 避免凭感觉优化。tag 固定为 [TAG]，抓取方式：
 *
 * ```
 * adb logcat -s HPA_STARTUP:I
 * ```
 *
 * 计时基准是进程启动时刻（[Process.getStartUptimeMillis]），所以各点之间可直接相减，
 * 也能和 `am start -W` 的 TotalTime 对照（后者从点击到首帧）。
 */
object StartupTrace {

    const val TAG = "HPA_STARTUP"

    /** 进程启动到现在的毫秒数；取不到进程启动时间时退化为本次调用起点 */
    private val fallbackStart = SystemClock.uptimeMillis()

    internal fun elapsed(): Long =
        SystemClock.uptimeMillis() - runCatching { Process.getStartUptimeMillis() }.getOrDefault(fallbackStart)

    /** 打一个点：输出「+123ms [main] 名称」 */
    fun mark(name: String) {
        Log.i(TAG, "+${elapsed()}ms [${threadName()}] $name")
    }

    /** 包一段同步代码并打点（用于量化单个阻塞段，如磁盘回载） */
    internal inline fun <T> span(name: String, block: () -> T): T {
        val t0 = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            Log.i(TAG, "+${elapsed()}ms [${threadName()}] $name 耗时 ${SystemClock.uptimeMillis() - t0}ms")
        }
    }

    /** 线程名：判断这段工作是否压在首帧上（main vs DefaultDispatcher/IO） */
    internal fun threadName(): String = Thread.currentThread().name
}
