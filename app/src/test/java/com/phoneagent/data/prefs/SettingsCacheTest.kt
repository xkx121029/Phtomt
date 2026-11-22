package com.phoneagent.data.prefs

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phoneagent.data.prefs.AppSettings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置解码缓存的行为验证。
 *
 * 要钉住的是**解码次数**，不是「有没有加个 StateFlow」：
 * 真实 decode 体里有 6 次 `SecretCodec` 解密与两次 JSON 解码，引擎每步会读 2~4 次配置，
 * 所以「读 N 次解码 1 次」与「读 N 次解码 N 次」是每步固定开销的差别。
 *
 * 缓存作用域用 `UnconfinedTestDispatcher`：上游一发射，缓存立刻在调用栈内更新，
 * 断言因此不依赖调度推进的顺序（用标准测试调度器时，"发射→推进→再读" 的时序很容易写出假失败）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCacheTest {

    private val modelKey = stringPreferencesKey("model")

    private fun prefs(model: String): Preferences = mutablePreferencesOf(modelKey to model)

    /** 解码次数计数的假解码器：只做「取一个字段」，把昂贵部分换成计数 */
    private class CountingDecode {
        var calls = 0
            private set
        val decode: (Preferences) -> Settings = { p ->
            calls++
            Settings(model = p[stringPreferencesKey("model")] ?: "")
        }
    }

    @Test
    fun `连续读 50 次配置只解码一次（同一份冷 map 是 50 次）`() = runTest {
        val source = MutableStateFlow(emptyPreferences())
        val counting = CountingDecode()
        val cache = SettingsCache(source, counting.decode, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        repeat(50) { cache.settings.first() }
        assertEquals("缓存后：50 次读取只解码一次", 1, counting.calls)

        // 对照：同样 50 次读取走原来的冷 Flow（每次收集重跑整段 map），解码 50 次
        val cold = CountingDecode()
        val coldFlow = source.map(cold.decode)
        repeat(50) { coldFlow.first() }
        assertEquals("冷 Flow 对照：50 次读取解码 50 次", 50, cold.calls)
    }

    @Test
    fun `上游发射新配置后缓存跟着更新（失效机制未丢）`() = runTest {
        val source = MutableStateFlow(prefs("旧的"))
        val cache = SettingsCache(source, CountingDecode().decode, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals("旧的", cache.settings.first().model)

        source.value = prefs("新的")
        assertEquals("新的", cache.settings.first().model)
    }

    @Test
    fun `写入后同步刷新，紧接着读取即拿到新值`() = runTest {
        val source = MutableStateFlow(prefs("旧的"))
        val cache = SettingsCache(source, CountingDecode().decode, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals("旧的", cache.settings.first().model)

        // 模拟 update()：edit() 返回写完后的整份快照，刷新缓存不等上游协程被调度
        cache.refreshFrom(prefs("新的"))
        assertEquals("新的", cache.settings.first().model)
    }

    @Test
    fun `第一份配置到达前读取会等待，不会返回默认配置`() = runTest {
        val source = MutableSharedFlow<Preferences>()
        val cache = SettingsCache(source, CountingDecode().decode, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        var read: Settings? = null
        val job = launch { read = cache.settings.first() }
        runCurrent()
        assertFalse("上游还没发射时不应返回默认配置", job.isCompleted)

        source.emit(prefs("迟到的配置"))
        runCurrent()
        assertTrue("上游发射后应完成", job.isCompleted)
        assertEquals("迟到的配置", read?.model)
    }

    @Test
    fun `解码失败不会中止刷新链路`() = runTest {
        val source = MutableStateFlow(prefs("坏的一份"))
        var attempts = 0
        val decode: (Preferences) -> Settings = { p ->
            attempts++
            if (attempts == 1) error("模拟解码异常")
            Settings(model = p[modelKey] ?: "")
        }
        val cache = SettingsCache(source, decode, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // 第一次解码抛异常：收集协程必须活下来，后续发射仍能刷进缓存（否则配置永久失效）
        source.value = prefs("好的一份")
        assertEquals("好的一份", cache.settings.first().model)
    }
}
