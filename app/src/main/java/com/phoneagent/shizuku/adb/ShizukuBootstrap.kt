package com.phoneagent.shizuku.adb

import com.phoneagent.shizuku.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shizuku 双通路启动器（执行层统一封装）。
 *
 * 上层业务只调 [ensureReady()]，无需关心底层是 Shizuku 直连还是无线 ADB 拉起：
 * 1. Shizuku 已 READY → [Path.SHIZUKU_DIRECT]
 * 2. Shizuku 不可用 → 走无线 ADB：
 *    - 已连接则直接拉起 Shizuku 服务
 *    - 未连接则引导用户输入配对码完成配对，再拉起
 *    - 拉起成功后等待 Shizuku 就绪 → [Path.ADB_BOOTSTRAP]
 * 3. 任一环节失败 → 返回带用户可读错误的失败，绝不静默。
 */
class ShizukuBootstrap(
    private val shizukuManager: ShizukuManager,
    private val transport: AdbBootstrapTransport,
    private val waitShizukuDelayMs: Long = 1200,
    private val shizukuReadyTimeoutMs: Long = 15000,
) {
    sealed class ReadyResult {
        object Ready : ReadyResult()
        data class Error(val message: String, val error: AdbError) : ReadyResult()
    }

    private val machine = WirelessAdbStateMachine()
    private val _status = MutableStateFlow(machine.current())
    val status: StateFlow<AdbStatus> = _status.asStateFlow()

    private fun emit() { _status.value = machine.current() }

    /**
     * 确保底层可执行通路就绪。返回 [ReadyResult]；结果由调用方展示给用户。
     * 同步阻塞于协程内拉取 Shizuku 就绪（带超时）。
     */
    suspend fun ensureReady(): ReadyResult {
        if (shizukuManager.isAvailable()) {
            machine.onShizukuDirectReady(); emit()
            return ReadyResult.Ready
        }
        // 无线 ADB 通路
        if (transport.isConnected()) {
            return bootViaAdb()
        }
        return ReadyResult.Error(
            "未获得执行权限：请开启无障碍服务；或在本页完成无线 ADB 配对以启用 Shizuku。",
            AdbError.WIRELESS_DEBUG_DISABLED,
        )
    }

    /**
     * 引导式配对入口：发现设备 → 输入配对码 → 配对 → 连接 → 拉起 Shizuku。
     * UI 调用本方法传入用户在界面输入的 6 位配对码。
     */
    suspend fun pairAndEnsureReady(code: String): ReadyResult {
        when (val pair = transport.pair(code)) {
            is AdbPairOutcome.Failure -> {
                machine.onPairingFailed(pair.detail); emit()
                return ReadyResult.Error(AdbFailure.message(pair.error), pair.error)
            }
            is AdbPairOutcome.Success -> {
                machine.onPairingSuccess(); emit()
                return bootViaAdb()
            }
        }
    }

    private suspend fun bootViaAdb(): ReadyResult {
        machine.onAdbConnected(); emit()
        machine.onBootStart(); emit()
        val started = runCatching { transport.startShizukuService() }.getOrElse {
            com.phoneagent.shizuku.adb.AdbStartOutcome.Failure(it.message ?: "异常")
        }
        when (started) {
            is AdbStartOutcome.Failure -> {
                machine.onBootFailed(started.detail); emit()
                return ReadyResult.Error(AdbFailure.message(AdbError.SHIZUKU_START_FAILED), AdbError.SHIZUKU_START_FAILED)
            }
            is AdbStartOutcome.Success -> {
                // 等待 Shizuku binder 就绪
                val deadline = System.currentTimeMillis() + shizukuReadyTimeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (shizukuManager.isAvailable()) {
                        machine.onBootSuccess(); emit()
                        return ReadyResult.Ready
                    }
                    delay(waitShizukuDelayMs)
                }
                machine.onBootFailed("Shizuku 已下发启动，但等待就绪超时"); emit()
                return ReadyResult.Error(AdbFailure.message(AdbError.SHIZUKU_START_FAILED), AdbError.SHIZUKU_START_FAILED)
            }
        }
    }
}