package com.phoneagent.device.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.provider.ShellResultParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shizuku 管理器（主应用侧门面）。
 *
 * 真正的 Shizuku 客户端、内置 server 与授权交互都已迁入独立 Provider 应用
 * （com.phoneagent.provider.shell.ShizukuManager），本类只做三件事：
 * 1. 把 Provider 的 shizukuState() 轮询成 [State] 三态（替换原来的 binder 收发监听）；
 * 2. 把 [executeShell] 转发成 AIDL 的 execShizuku（Provider 内 30s 强杀超时不变）；
 * 3. 把 [requestPermission] 转发成 AIDL 的 shizukuRequestPermission（授权页在 Provider 内唤起）。
 *
 * State 三态语义与迁移前一致：
 * - UNAVAILABLE: Shizuku 未安装或未运行（含 Provider 不可用/未连接）
 * - PERMISSION_DENIED: Shizuku 运行中但未授权
 * - READY: 可用
 */
class ShizukuManager(private val context: Context) {

    enum class State {
        UNAVAILABLE,
        PERMISSION_DENIED,
        READY,
    }

    sealed class ShellResult {
        data class Success(val output: String, val exitCode: Int = 0) : ShellResult()
        data class Failure(val reason: String, val exitCode: Int = -1) : ShellResult()
    }

    private companion object {
        /** 状态轮询间隔：替代原 binder 监听，保证引擎侧读到的可用性不会长期陈旧 */
        const val STATE_POLL_MS = 3_000L

        /**
         * 界面无人观察时的轮询间隔。
         *
         * 原先固定 3 秒轮询在后台常驻时毫无意义：每次都是一次跨进程 AIDL 往返，
         * 只是把一个不会变的状态再读一遍。降频后后台几乎不唤醒，界面一订阅又回到 3 秒。
         */
        const val IDLE_POLL_MS = 30_000L

        /** 单次状态查询的超时（Provider 未安装时 3s 绑定超时内直接返回） */
        const val STATE_TIMEOUT_MS = 2_500L

        /** shell 命令等待上限：Provider 内 30s 强杀，这里留出 binder 往返余量 */
        const val SHELL_TIMEOUT_MS = 35_000L

        /** 权限请求等待上限：Provider 内 10s 超时兜底，这里不再无限挂起 */
        const val REQUEST_PERMISSION_TIMEOUT_MS = 15_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(State.UNAVAILABLE)
    val state: StateFlow<State> get() = _state.asStateFlow()

    init {
        scope.launch {
            // 界面可见时有人订阅 state，按 3 秒细粒度刷新；无人观察时降到 30 秒。
            // 用轮询间隔做自适应，而不是彻底停掉：引擎在执行任务时读的是 isAvailable()，
            // 那条路径不经过 collect，完全停掉会让它读到过期状态
            while (isActive) {
                applyRemoteState()
                delay(if (_state.subscriptionCount.value > 0) STATE_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    /**
     * 刷新 Shizuku 可用状态（异步）。
     *
     * 迁移后状态唯一来源是 Provider 的 shizukuState()，旧实现里
     * "pingBinder 与 checkSelfPermission 之间的竞态异常"由 Provider 进程内兜住，
     * 这里只需把 AIDL 结果映射成三态；调用失败一律降级为不可用。
     */
    fun refreshState() {
        scope.launch { applyRemoteState() }
    }

    /** 与 [refreshState] 等价（保持旧调用点可读性） */
    fun refresh() = refreshState()

    fun isAvailable(): Boolean = _state.value == State.READY

    /** 权限是否已授予（binder 场景下等价于 READY） */
    fun isPermissionGranted(): Boolean = _state.value == State.READY

    /**
     * 请求 Shizuku 权限。
     *
     * 迁入 Provider 后，授权页由 Provider 内的 server 拉起；这里仅转发 AIDL 调用，
     * 回调统一切主线程（与旧实现一致，调用方多是 UI 状态更新）。
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        scope.launch {
            val granted = ProviderManager.call(appContext, timeoutMs = REQUEST_PERMISSION_TIMEOUT_MS) {
                it.shizukuRequestPermission()
            } ?: false
            applyRemoteState()
            mainHandler.post { onResult(granted) }
        }
    }

    /** 通过 Provider 的内置 Shizuku 通道执行 shell 命令 */
    suspend fun executeShell(command: String): ShellResult {
        if (command.isBlank()) return ShellResult.Failure("命令为空")
        val parcel = ProviderManager.call(appContext, timeoutMs = SHELL_TIMEOUT_MS) {
            it.execShizuku(command)
        }
        return parcel.toShellResult()
    }

    fun destroy() {
        scope.cancel()
    }

    private suspend fun applyRemoteState() {
        // AIDL 约定：0=UNAVAILABLE 1=PERMISSION_DENIED 2=READY；调用失败（Provider 未装/被杀）按不可用处理
        _state.value = when (
            ProviderManager.call(appContext, timeoutMs = STATE_TIMEOUT_MS) { it.shizukuState() }
        ) {
            1 -> State.PERMISSION_DENIED
            2 -> State.READY
            else -> State.UNAVAILABLE
        }
    }
}

/**
 * AIDL shell 回执 → 主应用内的 Shizuku 结果类型。
 *
 * Termux / 无线 ADB 引导共用同一份回执，统一在这里收口，避免各 facade 各写一套映射。
 */
internal fun ShellResultParcel?.toShellResult(): ShizukuManager.ShellResult {
    val parcel = this
    val unavailable = "执行通道不可用：请先安装并授权服务组件（Provider）"
    return when {
        parcel == null -> ShizukuManager.ShellResult.Failure(unavailable)
        parcel.success ->
            ShizukuManager.ShellResult.Success(output = parcel.output, exitCode = parcel.exitCode)
        else -> ShizukuManager.ShellResult.Failure(
            reason = parcel.reason.ifBlank { "命令执行失败（退出码 ${parcel.exitCode}）" },
            exitCode = parcel.exitCode,
        )
    }
}
