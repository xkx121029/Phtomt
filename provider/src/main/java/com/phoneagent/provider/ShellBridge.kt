package com.phoneagent.provider

import android.content.Context
import com.phoneagent.provider.shell.AdbWirelessTransport
import com.phoneagent.provider.shell.ShellResult
import com.phoneagent.provider.shell.ShizukuBootstrap
import com.phoneagent.provider.shell.ShizukuManager
import com.phoneagent.provider.shell.TermuxBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * shell 通道 Bridge：IDeviceProvider 中 Shizuku / Termux / 无线 ADB 引导部分的实现载体。
 *
 * 内置 Shizuku server、无线 ADB 配对状态机、Termux 桥整体迁入 Provider 进程；
 * 主应用经 [ProviderService] 的 AIDL 方法间接使用，授权状态经轮询同步。
 *
 * 实现：各通道组件（ShizukuManager / AdbWirelessTransport + ShizukuBootstrap / TermuxBridge）
 * 均为本模块 com.phoneagent.provider.shell 下的迁移副本，生命周期跟随 [ProviderService]
 * 的 onCreate/onDestroy（init 创建并注册 binder 监听，destroy 注销并关闭连接）。
 */
object ShellBridge {

    /** 权限请求等待上限：与 ShizukuManager 内部的 10s 兜底对齐，留 1s 余量 */
    private const val REQUEST_PERMISSION_TIMEOUT_MS = 11_000L

    private var shizukuManager: ShizukuManager? = null
    private var adbTransport: AdbWirelessTransport? = null
    private var bootstrap: ShizukuBootstrap? = null
    private var termuxBridge: TermuxBridge? = null

    fun init(appContext: Context) {
        // ShizukuManager 构造时即注册 binder 收发监听并做一次状态刷新
        val manager = ShizukuManager()
        shizukuManager = manager
        // 无线 ADB 传输（密钥存 Provider 私有目录）+ 引导启动器（通道决策固定 AUTO）
        val transport = AdbWirelessTransport(appContext)
        adbTransport = transport
        bootstrap = ShizukuBootstrap(manager, transport)
        termuxBridge = TermuxBridge(appContext)
    }

    fun destroy() {
        // 先注销 binder 监听再关连接，避免关闭期间状态回调空转
        shizukuManager?.destroy()
        adbTransport?.shutdown()
        shizukuManager = null
        adbTransport = null
        bootstrap = null
        termuxBridge = null
    }

    /** Shizuku 三态：0=UNAVAILABLE 1=PERMISSION_DENIED 2=READY */
    fun shizukuState(): Int = when (shizukuManager?.state?.value) {
        ShizukuManager.State.PERMISSION_DENIED -> 1
        ShizukuManager.State.READY -> 2
        else -> 0
    }

    fun termuxInstalled(): Boolean = termuxBridge?.isInstalled() ?: false

    fun termuxPermissionGranted(): Boolean = termuxBridge?.hasRunCommandPermission() ?: false

    /**
     * 请求 Shizuku 权限：授权确认页由本 Provider 内的 server 拉起（主应用拿不到该 Activity）。
     * 已授权直接返回 true；Provider 未初始化返回 false；等待超时按未授权收口，
     * 避免 Shizuku 无响应时调用方（主应用设置页）永久挂起。
     */
    suspend fun shizukuRequestPermission(): Boolean {
        val manager = shizukuManager
        // Shizuku binder 未就绪时 requestPermission 会抛 IllegalStateException，
        // 这里必须收口成 false，异常不能炸穿 Binder 线程；已授权直接返回 true
        return runCatching {
            when {
                manager == null -> false
                manager.isPermissionGranted() -> true
                else -> withTimeoutOrNull(REQUEST_PERMISSION_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        manager.requestPermission { granted -> if (cont.isActive) cont.resume(granted) }
                    }
                } ?: false
            }
        }.getOrDefault(false)
    }

    suspend fun execShizuku(command: String): ShellResultParcel =
        mapShizukuResult(shizukuManager?.executeShell(command))

    suspend fun execTermux(command: String, timeoutMs: Int): ShellResultParcel {
        // AIDL 约定：timeoutMs <= 0 用 Provider 内默认 25s（TermuxBridge.DEFAULT_TIMEOUT_MS）
        val timeout = if (timeoutMs > 0) timeoutMs.toLong() else TermuxBridge.DEFAULT_TIMEOUT_MS
        return mapTermuxResult(termuxBridge?.executeShell(command, timeout))
    }

    suspend fun termuxProbe(): ShellResultParcel =
        mapTermuxResult(termuxBridge?.probe())

    /**
     * 引导状态机快照 JSON（与主应用 AdbStatus 属性一一对应，主应用按同名字段反序列化）：
     * {"phase":"UNPAIRED|...|READY|FAILED","message":"...","isUserActionRequired":false}
     */
    fun adbStatusJson(): String {
        val s = bootstrap?.status?.value
        return JSONObject().apply {
            put("phase", s?.phase?.name ?: "UNPAIRED")
            put("message", s?.message ?: "")
            put("isUserActionRequired", s?.isUserActionRequired ?: false)
        }.toString()
    }

    suspend fun adbEnsureReady(): ShellResultParcel =
        mapReadyResult(bootstrap?.ensureReady())

    suspend fun adbPairAndEnsureReady(code: String): ShellResultParcel =
        mapReadyResult(bootstrap?.pairAndEnsureReady(code))

    // ==================== 内部映射 ====================

    /**
     * ShizukuManager.ShellResult → AIDL ShellResultParcel。
     * （ShizukuManager 保留类内嵌套类型是迁移原样；Termux 侧已统一用包顶层 ShellResult。）
     */
    private fun mapShizukuResult(result: ShizukuManager.ShellResult?): ShellResultParcel = when (result) {
        is ShizukuManager.ShellResult.Success ->
            ShellResultParcel(success = true, output = result.output, reason = "", exitCode = result.exitCode)
        is ShizukuManager.ShellResult.Failure ->
            ShellResultParcel.failure(result.reason, result.exitCode)
        null -> ShellResultParcel.failure("shell 通道未初始化（ProviderService 未完成 init）")
    }

    /** 包顶层 ShellResult（TermuxBridge.probe/executeShell 返回类型）→ AIDL ShellResultParcel */
    private fun mapTermuxResult(result: ShellResult?): ShellResultParcel = when (result) {
        is ShellResult.Success ->
            ShellResultParcel(success = true, output = result.output, reason = "", exitCode = result.exitCode)
        is ShellResult.Failure ->
            ShellResultParcel.failure(result.reason, result.exitCode)
        null -> ShellResultParcel.failure("shell 通道未初始化（ProviderService 未完成 init）")
    }

    /** ShizukuBootstrap.ReadyResult → AIDL 回执；Ready 附带状态机里的中文结果描述 */
    private fun mapReadyResult(result: ShizukuBootstrap.ReadyResult?): ShellResultParcel = when (result) {
        is ShizukuBootstrap.ReadyResult.Ready -> ShellResultParcel.success(readyDescription())
        is ShizukuBootstrap.ReadyResult.Error -> ShellResultParcel.failure(result.message)
        null -> ShellResultParcel.failure("引导通道未初始化（ProviderService 未完成 init）")
    }

    /** Ready 时的中文结果描述：取状态机最新人话快照（如「Shizuku 直连可用」/「内置 Shizuku 已启动就绪」） */
    private fun readyDescription(): String =
        bootstrap?.status?.value?.message?.takeIf { it.isNotBlank() } ?: "执行通道已就绪"
}
