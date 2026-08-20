package com.phoneagent.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

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

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { _state.value = State.UNAVAILABLE }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        refreshState()
    }

    fun refreshState() {
        _state.value = when {
            !Shizuku.pingBinder() -> State.UNAVAILABLE
            !isPermissionGranted() -> State.PERMISSION_DENIED
            else -> State.READY
        }
    }

    fun isAvailable(): Boolean = _state.value == State.READY

    fun isPermissionGranted(): Boolean =
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** 请求 Shizuku 权限，通过回调返回结果 */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (isPermissionGranted()) {
            _state.value = State.READY
            onResult(true)
            return
        }
        Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                _state.value = if (granted) State.READY else State.PERMISSION_DENIED
                onResult(granted)
            }
        })
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
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = reader.readText()
            val error = errorReader.readText()
            val exitCode = process.waitFor()
            reader.close()
            errorReader.close()
            process.destroy()
            if (exitCode == 0) {
                ShellResult.Success(output = output)
            } else {
                ShellResult.Failure(
                    reason = error.ifBlank { "命令退出码: $exitCode" },
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