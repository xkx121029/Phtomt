package com.phoneagent.provider

import android.app.Application
import android.content.Context
import android.util.Log
import com.phoneagent.provider.watchdog.WatchdogConfig

/**
 * Provider 进程级初始化：注册全局异常兜底。
 *
 * 为什么必须吞异常而不是像 Operit 主应用那样"崩溃报告页 + exitProcess"：
 * 无障碍服务进程一旦死亡，多数 ROM（MIUI/HyperOS/ColorOS 等）会把无障碍开关
 * 一并关掉（system_server 检测到服务崩溃即从 ENABLED_ACCESSIBILITY_SERVICES
 * 摘除），用户看到的就是"开了没多久自己关了"。Provider 是无 UI 的通道进程，
 * 没有 UI 可以展示崩溃报告，唯一正确姿势是拦在默认 handler 之前把异常吃掉、
 * 记录现场，让进程活下来——后台线程异常吞掉即无感；主线程异常吞掉后框架
 * 可能残留半初始化状态，但远好于开关被关（看门狗还会兜底重绑）。
 */
class ProviderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 进程一旦起来就把保活锚点拉起来：此前只有 ProviderService.onCreate 会启动看门狗，
        // 而「AMS 被系统重新 bind 无障碍服务」这条最常见的复活路径根本不经过 AIDL 服务——
        // 结果是无障碍把进程拉活、进程却没有任何前台服务兜底，几秒后又被 ROM 冻结杀掉，
        // 系统侧就循环停留在「此服务出现故障无法运行」（小米 HyperOS 上尤其明显）。
        // 失败已被 startIfEnabled 内部吞掉（后台启动 FGS 受限时不致命）
        runCatching { WatchdogConfig.startIfEnabled(this) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(thread, throwable) }
            Log.e(TAG, "已拦截未捕获异常（线程=${thread.name}），保持进程存活以免无障碍开关被系统关闭", throwable)
            // 不调用 previous、不 exitProcess：吞掉即保住开关
        }
    }

    /** 崩溃现场写入 prefs：watchdogStatus 经 AIDL 透出，主应用设置面板可见根因 */
    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val stack = throwable.stackTraceToString().take(1500)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_CRASH_MSG, "${throwable.javaClass.simpleName}: ${throwable.message ?: ""}\n$stack")
            .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
            .putInt(KEY_CRASH_COUNT, prefs().getInt(KEY_CRASH_COUNT, 0) + 1)
            .putString(KEY_LAST_CRASH_THREAD, thread.name)
            .apply()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "ProviderApp"
        const val PREFS = "watchdog"
        const val KEY_LAST_CRASH_MSG = "last_crash_message"
        const val KEY_LAST_CRASH_AT = "last_crash_at"
        const val KEY_CRASH_COUNT = "crash_count"
        const val KEY_LAST_CRASH_THREAD = "last_crash_thread"
    }
}
