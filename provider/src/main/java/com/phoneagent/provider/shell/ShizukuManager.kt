package com.phoneagent.provider.shell

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku 管理器：封装生命周期、权限检查、shell 命令执行。
 *
 * State 三态：
 * - UNAVAILABLE: Shizuku 未安装或未运行
 * - PERMISSION_DENIED: Shizuku 运行中但未授权
 * - READY: 可用
 */
class ShizukuManager {

    enum class State {
        UNAVAILABLE,
        PERMISSION_DENIED,
        READY,
    }

    sealed class ShellResult {
        data class Success(val output: String, val exitCode: Int = 0) : ShellResult()
        data class Failure(val reason: String, val exitCode: Int = -1) : ShellResult()
    }

    private val _state = MutableStateFlow(State.UNAVAILABLE)
    val state: StateFlow<State> get() = _state.asStateFlow()

    /** shell 命令最长等待（毫秒），超时强杀进程并按失败返回 */
    private companion object {
        const val SHELL_TIMEOUT_MS = 30_000L

        /** 权限请求超时（毫秒）：Shizuku 无响应时 onRequestPermissionResult 可能永不回调，超时按未授权收口 */
        const val REQUEST_PERMISSION_TIMEOUT_MS = 10_000L
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { _state.value = State.UNAVAILABLE }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        refreshState()
    }

    /**
     * 刷新 Shizuku 可用状态。
     *
     * 全程 runCatching 收口：vendored Shizuku API 的约定是「binder 未收到时调用会抛
     * IllegalStateException」（见 Shizuku.requireService），且静态 binder 字段与
     * binder 死亡回调存在竞态窗口——pingBinder 为 true 的瞬间 binder 死亡，紧随的
     * checkSelfPermission 就会抛。曾经该异常从设置页的 LaunchedEffect 直接炸穿进程
     * （设置 → 权限与执行通道一打开就闪退），这里必须兜住，任何异常一律降级为不可用。
     */
    fun refreshState() {
        _state.value = runCatching {
            when {
                !Shizuku.pingBinder() -> State.UNAVAILABLE
                !isPermissionGranted() -> State.PERMISSION_DENIED
                else -> State.READY
            }
        }.getOrElse { State.UNAVAILABLE }
    }

    fun isAvailable(): Boolean = _state.value == State.READY

    /** binder 收发竞态下 vendored API 可能抛 IllegalStateException，统一兜住按未授权处理 */
    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrElse { false }

    /** 请求 Shizuku 权限，通过回调返回结果 */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (isPermissionGranted()) {
            _state.value = State.READY
            onResult(true)
            return
        }
        // 回调统一切主线程：onRequestPermissionResult 跑在 Binder 线程，
        // 调用方（MainViewModel）多是 UI 状态更新，直接回传有跨线程更新风险（2026-08-15 B11）
        val mainHandler = Handler(Looper.getMainLooper())
        var settled = false
        val token = Any()
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (settled) return
                settled = true
                mainHandler.removeCallbacksAndMessages(token)
                Shizuku.removeRequestPermissionResultListener(this)
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                _state.value = if (granted) State.READY else State.PERMISSION_DENIED
                mainHandler.post { onResult(granted) }
            }
        }
        // 超时兜底：Shizuku 进程被杀/无响应时 onRequestPermissionResult 可能永不回调，
        // 调用方（配对流程）会永久挂起。超时按未授权收口，让流程能继续往下走
        mainHandler.postAtTime({
            if (settled) return@postAtTime
            settled = true
            Shizuku.removeRequestPermissionResultListener(listener)
            _state.value = State.UNAVAILABLE
            onResult(false)
        }, token, SystemClock.uptimeMillis() + REQUEST_PERMISSION_TIMEOUT_MS)
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(0)
    }

    /** 通过 Shizuku 执行 shell 命令（反射调用受限 API） */
    @Suppress("UNCHECKED_CAST")
    fun executeShell(command: String): ShellResult {
        if (!isAvailable()) {
            return ShellResult.Failure("Shizuku 不可用: ${_state.value}")
        }
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? java.lang.Process
                ?: return ShellResult.Failure("newProcess 返回 null")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            // stderr 由独立线程持续读入缓冲：主线程若先读 stderr 再读 stdout，
            // 两条管道任一写满都会让子进程写阻塞，主线程又等不到退出 → 死锁
            val errorBuilder = StringBuilder()
            val errorThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).use { er ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = er.read(buf)
                            if (n < 0) break
                            errorBuilder.append(buf, 0, n)
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val output = reader.readText()
            errorThread.join(5_000)
            // 带超时等待退出：命令卡死时强杀并按失败返回，而不是永久挂死调用线程
            val finished = process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                runCatching { reader.close() }
                return ShellResult.Failure(
                    reason = "命令执行超时（${SHELL_TIMEOUT_MS / 1000} 秒），已强制终止",
                    exitCode = -1,
                )
            }
            val exitCode = process.exitValue()
            runCatching { reader.close() }
            process.destroy()
            if (exitCode == 0) {
                ShellResult.Success(output = output)
            } else {
                ShellResult.Failure(
                    reason = errorBuilder.toString().ifBlank { "命令退出码: $exitCode" },
                    exitCode = exitCode,
                )
            }
        } catch (e: Exception) {
            ShellResult.Failure(reason = "shell 执行异常: ${e.message}")
        }
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}
