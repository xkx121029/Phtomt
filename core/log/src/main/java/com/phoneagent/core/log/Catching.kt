package com.phoneagent.core.log

import kotlinx.coroutines.CancellationException

/**
 * 与 [runCatching] 行为完全一致（异常仍被转成失败结果，调用方的 getOrNull/getOrDefault 照常可用），
 * 但失败时额外记一条 WARN 日志（带堆栈）。
 *
 * 为什么需要它：执行链路上的裸 `runCatching` 一旦失败就是「功能静默失效」——
 * 用户只看到动作没生效，日志里却什么都没有，只能靠复现猜原因。
 *
 * 与 android.util.Log 的差异：日志经 [AppLog] 同时进 logcat 和应用内调试页，
 * 因此这些失败在调试页上直接可见。
 *
 * @param tag 日志标签（通常传类名，便于过滤）
 * @param action 动作描述，如「截图」「执行 shell」；为空时只记异常本身
 */
inline fun <T> runCatchingLogged(tag: String, action: String = "", block: () -> T): Result<T> =
    runCatching(block).onFailure { tr ->
        // 协程取消不是故障：用户主动停止任务不该在日志里变成一条错误
        if (tr !is CancellationException) {
            val reason = tr.message ?: tr.javaClass.simpleName
            AppLog.w(tag, if (action.isEmpty()) "操作失败：$reason" else "$action 失败：$reason", tr)
        }
    }
