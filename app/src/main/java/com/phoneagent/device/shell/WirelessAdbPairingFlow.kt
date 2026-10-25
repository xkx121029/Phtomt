package com.phoneagent.device.shell

import android.content.Context
import com.phoneagent.core.notify.AdbPairingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无线 ADB「开始配对」编排流程（主应用侧门面，通知栏向导）。
 *
 * 时序（迁移后）：
 * 1. [startDiscovery]：发"正在搜索无线调试服务"通知，并让 Provider 检查底层通路是否已就绪
 *    （已配对过的设备可直接复用密钥连回来）
 * 2. 未就绪 → 发"已发现"提示，用户在设置页下方输入 6 位配对码
 *    （配对/mDNS 发现/拉起 Shizuku server 全部在 Provider 内执行）
 * 3. `MainViewModel.pairShizuku` → [ShizukuBootstrap.pairAndEnsureReady] → 结果经 [messageFlow] 与通知栏反馈
 *
 * 通知栏内联输入配对码的静态接收器已整体迁入 Provider（Provider Manifest 已声明同名 receiver），
 * 主应用不再注册该接收器，配对码统一在设置页输入。
 */
class WirelessAdbPairingFlow(
    private val context: Context,
    private val bootstrap: ShizukuBootstrap,
) {
    private companion object {
        /** 发现标记自动复位时间：避免流程长期占用，期间用户仍可在界面输入配对码 */
        const val RESET_AFTER_MS = 60_000L
        /** 就绪探测上限：Provider 内若需连接已配对设备，通常几秒内返回 */
        const val CHECK_READY_TIMEOUT_MS = 12_000L
    }

    private var discoverJob: Job? = null

    @Volatile
    private var discovering = false

    val message: String get() = _message.value

    /** 供 UI 观察到的人话进度 */
    private val _message = MutableStateFlow("")
    val messageFlow: StateFlow<String> = _message.asStateFlow()

    private fun setMsg(msg: String) {
        _message.value = msg
    }

    /** 开始搜索并走通知栏向导。callerScope 为主线程协程作用域（通知与 UI 更新都在其中）。 */
    fun startDiscovery(callerScope: CoroutineScope) {
        if (discovering) return
        if (bootstrap.status.value.phase == AdbPhase.READY) {
            post("Shizuku 已可用，无需无线对接")
            return
        }
        discovering = true
        setMsg("正在检查执行通道…请保持设备「无线调试 · 使用配对码配对设备」界面")
        AdbPairingNotifier.notifyDiscovering(context)

        discoverJob = callerScope.launch {
            val ready = withTimeoutOrNull(CHECK_READY_TIMEOUT_MS) { bootstrap.ensureReady() }
            when {
                ready is ShizukuBootstrap.ReadyResult.Ready -> {
                    discovering = false
                    setMsg("内置 Shizuku 已就绪，无需配对")
                    AdbPairingNotifier.notifySuccess(context, _message.value)
                }
                ready is ShizukuBootstrap.ReadyResult.Error && ready.message.contains("Provider") -> {
                    // Provider 未安装/不可用：配对无从谈起，直接给安装引导而不是假的"已发现"
                    discovering = false
                    setMsg(ready.message)
                    AdbPairingNotifier.notifyError(context, ready.message)
                }
                ready == null -> {
                    // 探测超时：多为无线调试未开或 Provider 无响应，不能谎报"已发现"
                    discovering = false
                    setMsg("检查执行通道超时：请确认已在开发者选项开启「无线调试」并保持配对界面")
                    AdbPairingNotifier.notifyError(context, _message.value)
                }
                else -> {
                    // 进入"等待配对码"阶段：保留发现标记一段时间，用户可随时在界面输入 6 位码
                    setMsg("已发现无线调试设备，请在下方输入 6 位配对码")
                    AdbPairingNotifier.notifyFound(context)
                    delay(RESET_AFTER_MS)
                    discovering = false
                }
            }
        }
    }

    fun cancel() {
        discoverJob?.cancel()
        discovering = false
        AdbPairingNotifier.cancel(context)
    }

    private fun post(msg: String) {
        AdbPairingNotifier.notifySuccess(context, msg)
    }
}
