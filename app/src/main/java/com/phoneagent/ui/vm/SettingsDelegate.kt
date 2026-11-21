package com.phoneagent.ui.vm

import com.phoneagent.data.prefs.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置与新手引导域。
 *
 * 只搬运 [AppSettings] 的两个对外状态与三个写入动作，不碰引擎，
 * 因此可以被安全地拆出 [com.phoneagent.ui.MainViewModel]——写入的取消语义仍跟随
 * 传进来的 [scope]（即 `viewModelScope`），拆分不改任何生命周期。
 */
class SettingsDelegate(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {

    val settingsFlow: StateFlow<AppSettings.Settings> = settings.settings
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), AppSettings.Settings())

    /**
     * 新手引导状态：null = 既没有门控镜像、也还没从 DataStore 读到真实值。
     *
     * 先用 [AppSettings.onboardingGate] 的 SharedPreferences 镜像回答门控（同步读、毫秒级），
     * 随后仍以 DataStore 的值为准覆盖它——镜像只是加速，不改权威源。
     * 不能直接用 [settingsFlow] 判定——它的初始值是默认 Settings（onboardingDone=false），
     * 老用户冷启动会先闪一帧引导页再跳回主界面。
     */
    val onboardingState: StateFlow<Boolean?> = flow<Boolean?> {
        settings.onboardingGate()?.let { emit(it) }
        emitAll(settings.settings.map { it.onboardingDone as Boolean? })
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** 结束新手引导：把完成标记落盘，下次启动直接进主界面 */
    fun completeOnboarding() {
        scope.launch { settings.markOnboardingDone() }
    }

    fun saveSettings(value: AppSettings.Settings) {
        scope.launch { settings.update(value) }
    }

    /**
     * 保存并等落盘完成。
     *
     * 只有"保存完立刻要用新配置"的流程才需要它：快速开始里填完 Key 直接发任务，
     * 若走 [saveSettings] 的异步写入，引擎可能在写入完成前就按旧配置起跑。
     */
    suspend fun saveSettingsNow(value: AppSettings.Settings) {
        settings.update(value)
    }
}
