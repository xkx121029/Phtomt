package com.phoneagent.device.shell

import android.content.Context
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
import org.json.JSONObject

/**
 * 内置 Shizuku 的执行通路启动器（执行层统一封装，主应用侧门面）。
 *
 * 启动器本体（无线 ADB 传输 + 状态机 + 可选拉起内置 Shizuku server）已迁入 Provider：
 * 无线 ADB 仍只是**启动引导机制**，不是独立执行通道。
 * - Provider 端 AUTO 通道决策：Shizuku binder 可用直接用；否则 ADB 会话可用即边引导边用。
 * - 本类只保留 UI/执行层需要的接口：[status] 轮询快照、[ensureReady]、[pairAndEnsureReady]，
 *   以及为兼容旧 AdbBootstrapTransport 调用点保留的 [isConnected] / [isConnectedNow] / [executeShell]。
 *
 * 错误语义与迁移前一致：任一环节失败 → 返回带用户可读错误的 [ReadyResult.Error]，绝不静默。
 */
class ShizukuBootstrap(private val context: Context) {

    sealed class ReadyResult {
        object Ready : ReadyResult()
        data class Error(val message: String, val error: AdbError) : ReadyResult()
    }

    private companion object {
        /** 状态轮询间隔：Provider 内状态机推进后由此同步到 UI */
        const val STATUS_POLL_MS = 3_000L
        private const val STATUS_TIMEOUT_MS = 2_500L
        /** 确保就绪：Provider 内可能需要拉起 server 并等待（15s）+ binder 往返余量 */
        const val READY_TIMEOUT_MS = 30_000L
        /** 引导式配对：mDNS 发现（20s）+ 配对 + 连接 + 可选拉起 server，给足余量 */
        const val PAIR_TIMEOUT_MS = 90_000L
        /** shell 执行：Provider 内 30s 强杀，这里留 binder 余量 */
        const val SHELL_TIMEOUT_MS = 35_000L
        /**
         * 视为"shell 真能执行"的状态机阶段：**只有 READY**。
         *
         * READY 由 Provider 在「内置 Shizuku server 已就绪」时给出。PAIRED / BOOTING 只是无线调试
         * 会话在手、正在拉起 server——会话本身不能执行命令（shell 只经 Shizuku binder 下发，
         * 见 Provider 的 ShellBridge.execShizuku）。此前把这两档也算进来，于是"刚配对成功、
         * Shizuku 根本没起来"也被报成已连接：能力快照给 SHELL_ADB=AVAILABLE、授权模式升到 SHIZUKU、
         * 命令派进 execShizuku 后再失败，用户看到的是"已就绪"和一串执行失败。
         */
        val connectedPhases = setOf(AdbPhase.READY)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(AdbStatus())
    val status: StateFlow<AdbStatus> = _status.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                pollStatus()
                delay(STATUS_POLL_MS)
            }
        }
    }

    /** 立即拉一次状态快照（不等下一轮轮询） */
    fun refreshStatus() {
        scope.launch { pollStatus() }
    }

    /**
     * 确保底层可执行通路就绪。返回 [ReadyResult]；结果由调用方展示给用户。
     * Provider 未安装/不可用时返回带引导语的失败，绝不静默。
     */
    suspend fun ensureReady(): ReadyResult {
        val result = ProviderManager.call(appContext, timeoutMs = READY_TIMEOUT_MS) { it.adbEnsureReady() }
        pollStatus()
        return result.toReadyResult()
    }

    /**
     * 引导式配对入口：发现设备 → 输入配对码 → 配对 → 连接 → Ready。
     * UI 调用本方法传入用户在界面输入的 6 位配对码，落地执行在 Provider。
     */
    suspend fun pairAndEnsureReady(code: String): ReadyResult {
        if (code.length != 6 || !code.all(Char::isDigit)) {
            return ReadyResult.Error("配对码须为 6 位数字", AdbError.PAIRING_FAILED)
        }
        val result = ProviderManager.call(appContext, timeoutMs = PAIR_TIMEOUT_MS) {
            it.adbPairAndEnsureReady(code)
        }
        pollStatus()
        return result.toReadyResult()
    }

    /** 判定无线 ADB 是否已连接可用（先拉一次最新状态，供协程调用方使用） */
    suspend fun isConnected(): Boolean {
        pollStatus()
        return isConnectedNow()
    }

    /**
     * 同步判定"ADB 引导通路是否**已经给出可执行的 shell**"（返回缓存状态，供无协程的同步调用方使用）。
     *
     * 语义就是 [connectedPhases]：只有 READY（内置 Shizuku server 已就绪）为 true。
     * 会话连上但 server 没起来时返回 false —— 这条通路上没有 shell 可执行。
     */
    fun isConnectedNow(): Boolean = _status.value.phase in connectedPhases

    /**
     * 经 Provider 执行一条命令并回读输出。
     *
     * 注意它走的是**内置 Shizuku 的 binder 通路**（AIDL `execShizuku`），不是无线 ADB 会话：
     * 会话本身没有实现命令执行。名字保留是为了兼容旧调用点，不要据此认为"ADB 会话能执行 shell"。
     */
    suspend fun executeShell(command: String): String? {
        val parcel = ProviderManager.call(appContext, timeoutMs = SHELL_TIMEOUT_MS) { it.execShizuku(command) }
        return if (parcel != null && parcel.success) parcel.output else null
    }

    fun destroy() {
        scope.cancel()
    }

    private suspend fun pollStatus() {
        _status.value = parseStatus(ProviderManager.call(appContext, timeoutMs = STATUS_TIMEOUT_MS) { it.adbStatus() })
    }

    /** Provider 返回的 JSON 与 [AdbStatus] 字段一一对应；解析失败按未配对处理 */
    private fun parseStatus(raw: String?): AdbStatus {
        if (raw.isNullOrBlank()) {
            return AdbStatus(AdbPhase.UNPAIRED, "未连接到权限服务组件，无法获取引导状态", false)
        }
        return runCatching {
            val json = JSONObject(raw)
            AdbStatus(
                phase = runCatching { AdbPhase.valueOf(json.optString("phase")) }.getOrDefault(AdbPhase.UNPAIRED),
                message = json.optString("message"),
                isUserActionRequired = json.optBoolean("isUserActionRequired"),
            )
        }.getOrDefault(AdbStatus())
    }

    /**
     * AIDL 回执 → 启动结果。
     * 失败原因直接取 Provider 的人话消息（配对码错误/无线调试未开等）；
     * 错误枚举只用于旧测试口径，迁移后细分类别由 [status] 的 phase 承载。
     */
    private fun ShellResultParcel?.toReadyResult(): ReadyResult {
        val parcel = this
        val unavailable = "执行通道不可用：请先安装并授权服务组件（Provider）"
        return when {
            parcel == null -> ReadyResult.Error(unavailable, AdbError.UNKNOWN)
            parcel.success -> ReadyResult.Ready
            else -> ReadyResult.Error(parcel.reason.ifBlank { unavailable }, AdbError.UNKNOWN)
        }
    }
}
