package com.phoneagent.core.log

import android.util.Log

/**
 * 应用统一日志门面（core 层，零上层依赖）。
 *
 * 设计意图：修复"日志双轨"问题 —— engine 包的日志走 TelemetryRecorder（应用内环形缓冲，
 * 调试页可见），而 core/device/feature 等包此前直接写 android.util.Log，只进 logcat、
 * 应用内完全看不到。本门面提供与 android.util.Log 相同的 d/i/w/e 签名，调用方无感切换：
 * 每条日志照常写入 logcat，同时通过 [sink] 钩子交给上层挂接的收集器，使这些日志也进入
 * 应用内的环形缓冲（调试页可见）。
 *
 * 分层约束：core 层不得 import 任何上层包，因此这里只定义 [sink] 钩子、不引用
 * TelemetryRecorder；挂接动作由 engine 层在自己的初始化（TelemetryRecorder 构造）中完成。
 * sink 为 null（引擎尚未构造）时行为与直接调 android.util.Log 完全一致。
 */
object AppLog {

    /**
     * 日志收集钩子：由上层（TelemetryRecorder）挂接，把日志收进应用内缓冲。
     * level 取 android.util.Log 的 Log.DEBUG/INFO/WARN/ERROR 常量；
     * 多次挂接以后一次为准（直接覆盖，天然去重）。
     */
    @Volatile
    var sink: ((level: Int, tag: String, msg: String, tr: Throwable?) -> Unit)? = null

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        Log.d(tag, msg, tr)
        sink?.invoke(Log.DEBUG, tag, msg, tr)
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        Log.i(tag, msg, tr)
        sink?.invoke(Log.INFO, tag, msg, tr)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        sink?.invoke(Log.WARN, tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        sink?.invoke(Log.ERROR, tag, msg, tr)
    }
}
