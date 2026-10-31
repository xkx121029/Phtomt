package com.phoneagent.core.text

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 时间格式化统一入口（纯 Kotlin，无 Android 依赖，任意层可用）。
 *
 * 原先 UI 面板 / 日志导出 / 设备事实各自 `SimpleDateFormat(...)` 现场构造：
 * 列表行每次重组都会新建一个格式化器，而 SimpleDateFormat 的构造（含模式解析）远贵于一次 format。
 *
 * 这里按「模式 + Locale」缓存到线程级实例：SimpleDateFormat 非线程安全，不能跨线程共享；
 * ThreadLocal 既避免同步开销，也避免并发格式化产生错乱输出。
 */
object TimeFormats {

    private val cache = ConcurrentHashMap<String, ThreadLocal<SimpleDateFormat>>()

    /**
     * 按 [pattern] 格式化时间戳。
     * @param locale 参与缓存键，运行时切换语言（如应用内语言设置）不会拿到旧 Locale 的实例
     */
    fun format(
        pattern: String,
        millis: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val key = pattern + '@' + locale.toLanguageTag()
        val holder = cache.getOrPut(key) {
            ThreadLocal.withInitial { SimpleDateFormat(pattern, locale) }
        }
        return holder.get()!!.format(Date(millis))
    }
}
