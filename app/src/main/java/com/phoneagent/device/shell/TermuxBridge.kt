package com.phoneagent.device.shell

import android.content.Context
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.device.shell.ShizukuManager.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Termux 桥（主应用侧门面）。
 *
 * Termux 的调用链——RUN_COMMAND 权限、`com.termux.RUN_COMMAND` 服务、PendingIntent 结果回传、
 * allow-external-apps 判定——已整体迁入独立 Provider 应用
 * （com.phoneagent.provider.shell.TermuxBridge），本类只转发 AIDL 并通过缓存暴露同步状态。
 *
 * 与无线 ADB / Shizuku 通道的**本质区别**不变（决定提示词里怎么用）：
 * - ADB / Shizuku 是 **shell（adb）权限**，能执行 `am` / `pm` / `settings` 等系统命令；
 * - Termux 是**普通应用权限**的 Linux 环境，能跑 curl / wget / python / 文本处理 / 沙箱内文件操作，
 *   **不能**执行系统命令。因此本通道定位为"Linux 工具链"，不是系统级执行通道。
 *
 * 状态读取说明：AIDL 调用都是挂起的，同步 getter 只能读缓存；
 * [refresh] 会把 Provider 的 termuxInstalled / termuxPermissionGranted 拉回本地缓存。
 */
class TermuxBridge(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

        /** 单条命令默认超时：Termux 冷启动 + bash 加载可能较慢（与 Provider 内默认值一致） */
        const val DEFAULT_TIMEOUT_MS = 25_000L

        private const val STATUS_TIMEOUT_MS = 3_000L
        /** 执行/探测的 binder 往返余量：Provider 内已有自己的超时兜底 */
        private const val BINDER_MARGIN_MS = 5_000L
        /** 授权经 Shizuku 执行 pm grant，冷启动 binder 往返较慢，留足余量 */
        private const val GRANT_TIMEOUT_MS = 8_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var installed = false

    @Volatile
    private var permissionGranted = false

    init {
        // 预取一次，避免首次进设置页时缓存还是默认值
        scope.launch { refresh() }
    }

    /** Termux 是否已安装（读 Provider 同步过来的缓存） */
    fun isInstalled(): Boolean = installed

    /** 是否已获得 Termux 的 RUN_COMMAND 授权（Termux 未安装时必然未授权） */
    fun hasRunCommandPermission(): Boolean = permissionGranted

    /** 通道是否可用：已安装且已授权 */
    fun isAvailable(): Boolean = installed && permissionGranted

    /** 从 Provider 拉取安装/授权状态并刷新缓存 */
    suspend fun refresh() {
        val snapshot = ProviderManager.call(appContext, timeoutMs = STATUS_TIMEOUT_MS) { remote ->
            remote.termuxInstalled() to remote.termuxPermissionGranted()
        }
        installed = snapshot?.first == true
        permissionGranted = snapshot?.second == true
    }

    /**
     * 探测 Termux 是否真的能跑通（覆盖 allow-external-apps 未开、bash 路径异常等情况）。
     * 用于能力页展示与"配置向导"，不参与决策链路。
     */
    suspend fun probe(): ShellResult {
        val result = ProviderManager.call(appContext, timeoutMs = DEFAULT_TIMEOUT_MS + BINDER_MARGIN_MS) {
            it.termuxProbe()
        }.toShellResult()
        refresh()
        return result
    }

    /**
     * 通过 Termux 执行一条 shell 命令。
     * @param command 交给 Provider 侧 Termux 的 `bash -c` 执行的命令
     * @return 成功时 output 为合并后的 stdout/stderr；失败时 reason 为可读原因
     */
    suspend fun executeShell(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        if (command.isBlank()) return ShellResult.Failure("命令为空")
        val result = ProviderManager.call(appContext, timeoutMs = timeoutMs + BINDER_MARGIN_MS) {
            it.execTermux(command, timeoutMs.toInt())
        }.toShellResult()
        refresh()
        return result
    }

    /**
     * 静默授予**服务组件**的 Termux 执行权限（RUN_COMMAND）。
     *
     * 权限必须落在 Provider 上：真正向 Termux 的 RunCommandService 发命令的是它。
     * 主应用已不再声明该权限（见 app/AndroidManifest.xml），在主应用里弹运行时授权框
     * 只会被判为拒绝——这就是「点授予执行权限没反应、永远停在未授权」的成因。
     * 唯一无需用户翻系统设置的办法：借内置 Shizuku 的 shell uid 执行 pm grant。
     */
    suspend fun grantRunCommandPermission(): ShellResult {
        val result = ProviderManager.call(appContext, timeoutMs = GRANT_TIMEOUT_MS) { remote ->
            remote.execShizuku("pm grant ${ProviderManager.PACKAGE} $RUN_COMMAND_PERMISSION")
        }.toShellResult()
        refresh()
        return result
    }

    fun destroy() {
        scope.cancel()
    }
}

/**
 * Termux 通道可用性快照（供能力页展示与引导，不参与决策链路）。
 *
 * 字段语义与原实现一致；数据来源于 [TermuxBridge] 的缓存与一次实跑探测。
 */
data class TermuxStatus(
    /** Termux 是否已安装 */
    val installed: Boolean = false,
    /** 是否已授予 RUN_COMMAND 权限 */
    val permissionGranted: Boolean = false,
    /** 是否已做过一次实跑探测 */
    val probed: Boolean = false,
    /** 探测是否跑通（涵盖 allow-external-apps 未开、bash 路径异常等） */
    val ready: Boolean = false,
    /** 探测消息：失败原因，或成功时的命令回显 */
    val message: String = "",
)
