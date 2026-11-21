package com.phoneagent.ui.vm

import com.phoneagent.device.shell.AdbStatus
import com.phoneagent.device.shell.ShizukuBootstrap
import com.phoneagent.device.shell.WirelessAdbPairingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 内置 Shizuku 启动域：启动/检测通路、6 位配对码引导，以及通知栏配对向导的消息。
 *
 * 引导会话复用无线 ADB（[WirelessAdbPairingFlow]），仅作启动机制，不再作为独立通道暴露。
 */
class ShizukuBootstrapDelegate(
    private val shizukuBootstrap: ShizukuBootstrap,
    private val adbPairingFlow: WirelessAdbPairingFlow,
    private val scope: CoroutineScope,
) {

    val adbStatus: StateFlow<AdbStatus> = shizukuBootstrap.status

    /** 一键启动/检测内置 Shizuku 通路：binder 可用直接就绪，否则经引导会话拉起内置 server */
    fun ensureShizukuReady(onResult: (String) -> Unit) {
        scope.launch {
            onResult(
                when (val r = shizukuBootstrap.ensureReady()) {
                    is ShizukuBootstrap.ReadyResult.Ready -> "内置 Shizuku 已就绪，可执行 shell"
                    is ShizukuBootstrap.ReadyResult.Error -> r.message
                }
            )
        }
    }

    /** 用界面输入的 6 位配对码完成首次启动引导，并就绪内置 Shizuku 通路 */
    fun pairShizuku(code: String, onResult: (String) -> Unit) {
        scope.launch {
            if (code.length != 6 || !code.all { it.isDigit() }) {
                onResult("配对码须为 6 位数字")
                return@launch
            }
            onResult(
                when (val r = shizukuBootstrap.pairAndEnsureReady(code)) {
                    is ShizukuBootstrap.ReadyResult.Ready -> "启动成功，内置 Shizuku 已就绪"
                    is ShizukuBootstrap.ReadyResult.Error -> r.message
                }
            )
        }
    }

    // ---- 首次启动引导的通知栏向导（沿用无线 ADB 配对流程，仅作为内置 Shizuku 的引导机制） ----
    val adbPairingMessage: String
        get() = adbPairingFlow.message

    val adbPairingMessageFlow: StateFlow<String> get() = adbPairingFlow.messageFlow

    /** 点击「开始引导」：后台搜索系统无线调试服务，搜到后通过通知栏请求配对码 */
    fun startAdbDiscovery() = adbPairingFlow.startDiscovery(scope)

    fun cancelAdbPairing() = adbPairingFlow.cancel()
}
