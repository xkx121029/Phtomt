package com.phoneagent.shizuku.adb

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无线 ADB「开始配对」编排流程（模拟 Shizuku 的通知栏向导）。
 *
 * 时序：
 * 1. [startDiscovery]：发"正在搜索无线调试服务"通知，在同一 Looper 线程协程上跑 Nsd 发现
 * 2. 搜索到 → 发"已发现"通知 + 弹出带内联输入框的通知请求 6 位配对码
 * 3. 用户在通知栏输入提交 → [AdbPairingReceiver] 回调 [onPairingCode]
 * 4. 校验码 → 发"正在配对"通知 → 配对并拉起 Shizuku（阻塞线程内 runBlocking 跑 suspend）
 * 5. 就绪/失败 → 通知栏反馈结果
 *
 * 作为 [active] 注册，供 [AdbPairingReceiver] 路由；同一时刻仅允许一条配对流程。
 */
class WirelessAdbPairingFlow(
    private val context: Context,
    private val transport: AdbWirelessTransport,
    private val bootstrap: ShizukuBootstrap,
    private val timeouts: AdbTimeouts = AdbTimeouts(),
) {
    companion object {
        @Volatile
        var active: WirelessAdbPairingFlow? = null
    }

    private var discoverJob: Job? = null

    @Volatile
    private var discovering = false

    private var lastMessage: String = ""
    val message: String get() = _message.value

    /** 供 UI 观察到的人话进度 */
    private val _message = MutableStateFlow("")
    val messageFlow: StateFlow<String> = _message.asStateFlow()

    private fun setMsg(msg: String) {
        _message.value = msg
    }

    /** 开始搜索并走通知栏向导。callerScope 为主线程协程作用域（Nsd 需在带 Looper 线程）。 */
    fun startDiscovery(callerScope: CoroutineScope) {
        if (discovering) return
        if (bootstrap.status.value.phase == AdbPhase.READY) {
            post("Shizuku 已可用，无需无线对接")
            return
        }
        discovering = true
        active = this
        setMsg("正在搜索无线调试服务…")
        com.phoneagent.notify.AdbPairingNotifier.notifyDiscovering(context)

        discoverJob = callerScope.launch {
            // 在 callerScope（主线程）执行，NsdManager 才算合法
            val svc = withTimeoutOrNull(timeouts.discoverMs) { transport.discoverService() }
            if (svc == null) {
                discovering = false
                setMsg("未找到无线调试服务，请保持「使用配对码配对设备」界面")
                com.phoneagent.notify.AdbPairingNotifier.notifyError(context, _message.value)
                return@launch
            }
            setMsg("已发现设备 ${svc.host}:${svc.port}，请在通知中输入配对码")
            com.phoneagent.notify.AdbPairingNotifier.notifyFound(context)
            com.phoneagent.notify.AdbPairingNotifier.notifyRequestCode(context)
            // 通知输入走回调；此处仅保留发现标记超时复位
            delay(60_000)
            discovering = false
        }
    }

    /** 收到通知栏提交的配对码后继续流程（配对为网络+等待 binder，放后台线程执行） */
    fun onPairingCode(code: String) {
        discoverJob?.cancel()
        discovering = false
        com.phoneagent.notify.AdbPairingNotifier.notifyPairing(context, "已收到配对码，正在配对并拉起 Shizuku…")
        kotlin.concurrent.thread {
            val r = runCatching {
                kotlinx.coroutines.runBlocking { bootstrap.pairAndEnsureReady(code) }
            }.getOrElse {
                ShizukuBootstrap.ReadyResult.Error("配对流程异常：${it.message}", AdbError.UNKNOWN)
            }
            setMsg(
                when (r) {
                    is ShizukuBootstrap.ReadyResult.Ready -> "Shizuku 已通过无线 ADB 就绪"
                    is ShizukuBootstrap.ReadyResult.Error -> r.message
                }
            )
            when (r) {
                is ShizukuBootstrap.ReadyResult.Ready ->
                    com.phoneagent.notify.AdbPairingNotifier.notifySuccess(context, _message.value)
                is ShizukuBootstrap.ReadyResult.Error ->
                    com.phoneagent.notify.AdbPairingNotifier.notifyError(context, _message.value)
            }
        }
    }

    fun cancel() {
        discoverJob?.cancel()
        discovering = false
        com.phoneagent.notify.AdbPairingNotifier.cancel(context)
    }

    private fun post(msg: String) {
        lastMessage = msg
        com.phoneagent.notify.AdbPairingNotifier.notifySuccess(context, msg)
    }
}