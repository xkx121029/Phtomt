package com.phoneagent.data.prefs

import androidx.datastore.preferences.core.Preferences
import com.phoneagent.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * 「DataStore 快照 → [AppSettings.Settings]」解码结果的共享缓存。
 *
 * 为什么需要：解码不是取字段那么便宜——`map` 体里要做 6 次 [com.phoneagent.core.security.SecretCodec]
 * 解密加两次 `ModelCatalogCodec` 的 JSON 解码。而引擎每步有 2~4 处 `settings.first()`，
 * 冷 Flow 意味着**每走一步都把整份配置重新解密、重新解析一遍**。
 *
 * 口径：
 * 1. 只有一条上游收集在算解码结果，其余订阅者直接拿缓存值（`.first()` 命中即返回，零解码）；
 * 2. 缓存值仍来自 DataStore 的真实发射，所以「改了配置要生效」这条不变——任何写入都会让 DataStore 发射新快照；
 * 3. 写入路径额外调 [refreshFrom] 用 `edit()` 返回的新快照**同步**刷新，消除「刚保存设置、紧接着读到旧值」
 *    这一段窗口。缓存化最危险的失效方式就是它，所以写路径不等上游协程调度。
 *
 * 初始值用 `null` 而不是一份默认配置：[settings] 会 suspend 到第一份真实配置为止。
 * 若先给默认值，构造后立刻 `first()` 的调用方会拿到「没有 API Key」的假配置。
 */
internal class SettingsCache(
    source: Flow<Preferences>,
    private val decode: (Preferences) -> AppSettings.Settings,
    scope: CoroutineScope,
) {
    private val cached = MutableStateFlow<AppSettings.Settings?>(null)

    /** 对外暴露的共享流：有缓存值时立即返回，不再解码 */
    val settings: Flow<AppSettings.Settings> = cached.filterNotNull()

    init {
        scope.launch {
            source.collect { prefs ->
                // 解码异常只丢这一次更新，不能让收集协程死掉：
                // 否则后续所有写入都刷不进缓存，表现为「改了配置不生效」
                val decoded = runCatching { decode(prefs) }.getOrElse { e ->
                    AppLog.w(TAG, "设置解码失败，沿用上一份缓存：${e.javaClass.simpleName}")
                    null
                }
                if (decoded != null) cached.value = decoded
            }
        }
    }

    /** 写入后同步刷新（`DataStore.edit()` 的返回值就是写完后的整份快照） */
    fun refreshFrom(prefs: Preferences) {
        val decoded = runCatching { decode(prefs) }.getOrNull() ?: return
        cached.value = decoded
    }

    private companion object {
        const val TAG = "SettingsCache"
    }
}
