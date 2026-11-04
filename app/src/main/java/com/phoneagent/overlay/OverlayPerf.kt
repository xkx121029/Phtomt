package com.phoneagent.overlay

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import com.phoneagent.BuildConfig

/**
 * 浮层关键路径埋点（仅 debug 包生效，release 里所有方法都是空实现）。
 *
 * 为什么要把「代码成本」和「帧等待」拆开报：
 * 本机模拟器是软件渲染，一帧合成就要 0.5–2s；此时 `addView → 首帧` 的 1.6s 里
 * 真正属于我们代码的只有几十毫秒，其余全是等待合成。两个数字混在一起既不能指导优化，
 * 也会被误读成真机指标——所以 [firstFrame] 会分别打出 code / wait / total。
 *
 * tag 统一是 `HPA_PERF`，取值方式：
 * `adb logcat -s HPA_PERF:D`
 */
internal object OverlayPerf {

    const val TAG = "HPA_PERF"

    /** 纯代码段耗时（建视图 / addView / 落位），不含任何帧等待 */
    fun code(tag: String, sinceUptime: Long) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "$tag=${SystemClock.uptimeMillis() - sinceUptime}ms")
    }

    /** 一般日志：只在 debug 包输出 */
    fun log(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "$tag $message")
    }

    /**
     * 「调用 → 首帧真正绘制」的完整耗时，并拆出代码成本与帧等待。
     *
     * @param codeUntilAdded 从起点到视图已加入窗口（代码部分）的毫秒数；未知时传 null
     */
    fun firstFrame(tag: String, view: View, sinceUptime: Long, codeUntilAdded: Long? = null) {
        if (!BuildConfig.DEBUG) return
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val observer = view.viewTreeObserver
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                val total = SystemClock.uptimeMillis() - sinceUptime
                if (codeUntilAdded == null) {
                    Log.d(TAG, "$tag=${total}ms")
                } else {
                    val wait = (total - codeUntilAdded).coerceAtLeast(0)
                    Log.d(TAG, "$tag-total=${total}ms code=${codeUntilAdded}ms wait=${wait}ms")
                }
                return true
            }
        })
    }
}
