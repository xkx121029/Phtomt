package com.phoneagent.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * 新手引导完成标记的启动门控镜像。
 *
 * `agent_settings`（DataStore）仍是**唯一权威源**；这里只镜像 `onboarding_done` 一个布尔，
 * 专供首帧门控同步读取：DataStore 首读要解 protobuf，再跑整段 [AppSettings.settings] 的解码
 * （6 次解密 + 两次 JSON 解码），门控等它的时候屏幕上没有任何内容；这个小文件只有几 KB，
 * 读一次是毫秒级。
 *
 * 只存这一个键 —— 其他设置一律不许写进来，否则就成了第二套配置源。
 * 两边不一致时以 DataStore 为准，由 [AppSettings.syncOnboardingMirror] 在启动期对账修复。
 */
class OnboardingMirror(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 镜像值；从未写过（首次安装，或第一次升级到带镜像的版本）返回 null，门控照旧等 DataStore */
    fun read(): Boolean? = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null

    /** DataStore 写成功后的同步回写（`apply` 异步落盘，不改写调用方时序）；值没变就不碰磁盘 */
    fun write(done: Boolean) {
        if (read() == done) return
        prefs.edit().putBoolean(KEY, done).apply()
    }

    /**
     * 与权威值对账：不一致时以 DataStore 的值改写镜像。
     *
     * @return true 表示镜像与权威值不一致、已被修复
     */
    fun reconcile(done: Boolean): Boolean {
        val mirror = read()
        if (mirror == done) return false
        write(done)
        return true
    }

    private companion object {
        /** 与 `agent_settings`（DataStore）分开的文件：只此一用，不做成第二个设置存储 */
        const val FILE = "startup_gate"
        const val KEY = "onboarding_done"
    }
}
