package com.phoneagent.device.shell.adb

import com.phoneagent.device.shell.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无线 ADB 为主、Shizuku 可选增强的执行通路启动器（执行层统一封装）。
 *
 * 上层业务只调 [ensureReady()]，无需关心底层是无线 ADB 还是 Shizuku：
 * - 无线 ADB 连接即具备真实 shell 能力（主通道），Shizuku 仅作可选增强。
 * - 执行通道三态：
 *   - `AUTO`（默认）：无线 ADB 优先，其次 Shizuku 直连。
 *   - `ADB`：仅用无线 ADB；未连接则无真实 shell（走无障碍）。
 *   - `SHIZUKU`：仅用 Shizuku；Shizuku 不可用则用无线 ADB 拉起（仍是 Shizuku 通路）。
 * - 任一环节失败 → 返回带用户可读错误的失败，绝不静默；但 ADB 已连接时，Shizuku 可选启动失败不阻塞 Ready。
 */
class ShizukuBootstrap(
    private val shizukuManager: ShizukuManager,
    private val transport: AdbBootstrapTransport,
    private val waitShizukuDelayMs: Long = 1200,
    private val shizukuReadyTimeoutMs: Long = 15000,
    /** 当前执行通道偏好（AUTO / ADB / SHIZUKU）。测试可注入固定值控制三态决策。 */
    private val channelProvider: suspend () -> String = { "AUTO" },
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
     * 同步阻塞于协程内判定通道与（SHIZUKU 模式下）拉取 Shizuku 就绪（带超时）。
     */
    suspend fun ensureReady(): ReadyResult {
        val channel = channelProvider()
        val shizukuOk = shizukuManager.isAvailable()
        val adbOk = transport.isConnected()

        return when (channel) {
            "ADB" -> {
                if (adbOk) {
                    adbReadyThenOptionalEnhance()
                    ReadyResult.Ready
                } else {
                    guidePairing()
                }
            }
            "SHIZUKU" -> {
                if (shizukuOk) {
                    machine.onShizukuDirectReady(); emit()
                    ReadyResult.Ready
                } else if (adbOk) {
                    // 用无线 ADB 拉起 Shizuku（仍是 Shizuku 通路，需就绪才算 Ready）
                    bootViaAdb()
                } else {
                    guidePairing()
                }
            }
            else -> { // AUTO：无线 ADB 优先，其次 Shizuku 直连
                if (adbOk) {
                    adbReadyThenOptionalEnhance()
                    ReadyResult.Ready
                } else if (shizukuOk) {
                    machine.onShizukuDirectReady(); emit()
                    ReadyResult.Ready
                } else {
                    guidePairing()
                }
            }
        }
    }

    /**
     * 引导式配对入口：发现设备 → 输入配对码 → 配对 → 连接 → Ready。
     * 配对成功后无线 ADB 即为主通道；SHIZUKU 模式下进一步拉起 Shizuku 并等待就绪。
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
                machine.onAdbConnected(); emit()
                if (channelProvider() == "SHIZUKU") {
                    return bootViaAdb()
                }
                machine.onAdbReady(); emit()
                // 可选增强拉起 Shizuku（失败不阻塞 Ready）
                bootViaAdbEnhancement()
                return ReadyResult.Ready
            }
        }
    }

    /** 无线 ADB 连接即就绪，随后可选尝试拉起 Shizuku 作增强（不阻塞 Ready）。 */
    private suspend fun adbReadyThenOptionalEnhance() {
        machine.onAdbConnected(); emit()
        machine.onAdbReady(); emit()
        bootViaAdbEnhancement()
    }

    /** SHIZUKU 模式下，经无线 ADB 拉起 Shizuku 并等待就绪；失败/超时返回错误（Shizuku 是唯一 shell 通路）。 */
    private suspend fun bootViaAdb(): ReadyResult {
        machine.onBootStart(); emit()
        val started = runCatching { transport.startShizukuService() }.getOrElse {
            AdbStartOutcome.Failure(it.message ?: "异常")
        }
        when (started) {
            is AdbStartOutcome.Failure -> {
                machine.onBootFailed(started.detail); emit()
                return ReadyResult.Error(AdbFailure.message(AdbError.SHIZUKU_START_FAILED), AdbError.SHIZUKU_START_FAILED)
            }
            is AdbStartOutcome.Success -> {
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

    /** 可选增强：ADB 已连接时尝试拉起 Shizuku。无论成败都不影响已就绪的 ADB 通道，始终不抛错。 */
    private suspend fun bootViaAdbEnhancement() {
        machine.onBootStart(); emit()
        val started = runCatching { transport.startShizukuService() }.getOrElse {
            AdbStartOutcome.Failure(it.message ?: "异常")
        }
        when (started) {
            is AdbStartOutcome.Failure -> {
                machine.onBootFailed(started.detail); emit()
            }
            is AdbStartOutcome.Success -> {
                val deadline = System.currentTimeMillis() + shizukuReadyTimeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (shizukuManager.isAvailable()) {
                        machine.onBootSuccess(); emit()
                        return
                    }
                    delay(waitShizukuDelayMs)
                }
                machine.onBootFailed("Shizuku 已下发启动，但等待就绪超时"); emit()
            }
        }
    }

    private fun guidePairing(): ReadyResult {
        machine.onWirelessDebugDisabled()
        emit()
        return ReadyResult.Error(
            "未获得执行权限：请开启无障碍服务；或在本页完成无线 ADB 配对以启用 shell。",
            AdbError.WIRELESS_DEBUG_DISABLED,
        )
    }
}
