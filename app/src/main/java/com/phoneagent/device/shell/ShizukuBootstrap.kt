package com.phoneagent.device.shell

import com.phoneagent.device.shell.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内置 Shizuku 的执行通路启动器（执行层统一封装）。
 *
 * 上层业务只调 [ensureReady()]，无需关心底层实现：内置 Shizuku server 随本 APK 分发，
 * 经无线 ADB（shell uid + app_process）拉起——无线 ADB 只是**启动引导机制**，不是独立通道。
 * - 执行通道三态（旧 "ADB" 偏好已砍，读取时并入 AUTO）：
 *   - `AUTO`（默认）：Shizuku binder 可用直接用；否则 ADB 会话可用即边引导边用（连接即具备 shell 能力）。
 *   - `SHIZUKU`：仅认 Shizuku binder 就绪；未就绪则尝试经 ADB 拉起并等待。
 *   - `TERMUX`：仅用 Termux。
 * - 任一环节失败 → 返回带用户可读错误的失败，绝不静默；但 ADB 已连接时，server 拉起失败不阻塞 Ready。
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
     * 旧 "ADB" 偏好已并入 AUTO（内置 Shizuku 的引导机制不再是独立通道）。
     */
    suspend fun ensureReady(): ReadyResult {
        val channel = channelProvider()
        val shizukuOk = shizukuManager.isAvailable()
        val adbOk = transport.isConnected()

        return when (channel) {
            "SHIZUKU" -> {
                if (shizukuOk) {
                    machine.onShizukuDirectReady(); emit()
                    ReadyResult.Ready
                } else if (adbOk) {
                    // 经 ADB 引导拉起内置 Shizuku server，需就绪才算 Ready
                    bootViaAdb()
                } else {
                    guidePairing()
                }
            }
            else -> { // AUTO（含旧 "ADB" 值归一）：Shizuku binder 优先，其次 ADB 会话（连接即具备 shell 能力）
                if (shizukuOk) {
                    machine.onShizukuDirectReady(); emit()
                    ReadyResult.Ready
                } else if (adbOk) {
                    adbReadyThenOptionalEnhance()
                    ReadyResult.Ready
                } else {
                    guidePairing()
                }
            }
        }
    }

    /**
     * 引导式配对入口：发现设备 → 输入配对码 → 配对 → 连接 → Ready。
     * 配对成功后引导通路即就绪；SHIZUKU 模式下进一步拉起内置 server 并等待就绪。
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
                // 可选增强：拉起内置 Shizuku server（失败不阻塞 Ready）
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
            "未获得执行权限：请开启无障碍服务；或在本页完成「内置 Shizuku」的首次启动引导。",
            AdbError.WIRELESS_DEBUG_DISABLED,
        )
    }
}
