package com.phoneagent.device.shell

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.phoneagent.device.shell.ShizukuManager.ShellResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Termux 桥：把 shell 命令交给 Termux 执行，并回读 stdout / stderr / exit_code。
 *
 * 与无线 ADB / Shizuku 通道的**本质区别**（决定提示词里怎么用）：
 * - ADB / Shizuku 是 **shell（adb）权限**，能执行 `am` / `pm` / `settings` 等系统命令；
 * - Termux 是**普通应用权限**的 Linux 环境，能跑 curl / wget / python / 文本处理 / 沙箱内文件操作，
 *   **不能**执行系统命令。因此本通道定位为"Linux 工具链"，不是系统级执行通道。
 *
 * 调用链（依据 Termux 官方 RunCommandService 源码）：
 * 1. 目标组件 `com.termux/.app.RunCommandService`，action `com.termux.RUN_COMMAND`；
 * 2. 调用方必须持有 `com.termux.permission.RUN_COMMAND`（Termux 声明的 dangerous 权限，需运行时授予）；
 * 3. Termux 侧需在 `~/.termux/termux.properties` 里设置 `allow-external-apps=true`；
 * 4. 结果回传走 **PendingIntent**（官方源码注释指出：文件方式在 `allow-external-apps` 未开时会永久挂起，
 *    PendingIntent 方式则一定会回传，故只用 PendingIntent）。
 */
class TermuxBridge(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

        /** 结果回传广播：由本 App 的 PendingIntent 发出，因此接收器无需 exported */
        const val ACTION_RESULT = "com.phoneagent.action.TERMUX_RESULT"
        const val EXTRA_REQUEST_ID = "com.phoneagent.extra.TERMUX_REQUEST_ID"

        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT_BUNDLE"

        /** Termux 固定安装路径（包名与数据目录由 Termux 自身决定，不随安装方式变化） */
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"

        /** Runner.APP_SHELL：后台执行、不占用终端会话 */
        private const val RUNNER_APP_SHELL = "app-shell"

        /** 单条命令默认超时：Termux 冷启动 + bash 加载可能较慢 */
        const val DEFAULT_TIMEOUT_MS = 25_000L
    }

    private val seq = AtomicLong(0)

    /** Termux 是否已安装（需 manifest 里声明 queries，否则 Android 11+ 恒为 false） */
    @Suppress("DEPRECATION")
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 是否已获得 Termux 的 RUN_COMMAND 授权（Termux 未安装时必然未授权） */
    fun hasRunCommandPermission(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 通道是否可用：已安装且已授权 */
    fun isAvailable(): Boolean = isInstalled() && hasRunCommandPermission()

    /**
     * 探测 Termux 是否真的能跑通（覆盖 allow-external-apps 未开、bash 路径异常等情况）。
     * 用于能力页展示与"配置向导"，不参与决策链路。
     */
    suspend fun probe(): ShellResult = executeShell("echo __HPA_TERMUX_OK__", timeoutMs = 15_000L)

    /**
     * 通过 Termux 执行一条 shell 命令。
     * @param command 交给 Termux 侧 `bash -c` 执行的命令
     * @return 成功时 output 为合并后的 stdout/stderr；失败时 reason 为可读原因
     */
    suspend fun executeShell(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        if (command.isBlank()) return ShellResult.Failure("命令为空")
        if (!isInstalled()) return ShellResult.Failure("Termux 未安装")
        if (!hasRunCommandPermission()) {
            return ShellResult.Failure("未授予 Termux 的 RUN_COMMAND 权限，请在能力页授权")
        }

        val requestId = "hpa-${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        val deferred = TermuxResultRelay.register(requestId)
        return try {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_WORKDIR, TERMUX_HOME)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
                putExtra(EXTRA_COMMAND_LABEL, "HappyPhoneAgent")
                putExtra(EXTRA_PENDING_INTENT, buildResultPendingIntent(requestId))
            }
            // 本 App 运行时持有前台服务（悬浮窗/通知），满足 Android 8+ 前台 startService 限制
            context.startService(intent)
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            ShellResult.Failure("Termux 执行超时（${timeoutMs / 1000}s）：请确认 Termux 已开启 allow-external-apps=true")
        } catch (e: IllegalStateException) {
            ShellResult.Failure("Termux 调用被系统拒绝（后台服务限制）：${e.message}")
        } catch (e: Exception) {
            ShellResult.Failure("Termux 调用失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            TermuxResultRelay.unregister(requestId)
        }
    }

    /**
     * 结果回传用的 PendingIntent。
     * Termux 需要往其中填充结果 extras，故 API 31+ 必须声明 FLAG_MUTABLE。
     */
    private fun buildResultPendingIntent(requestId: String): PendingIntent {
        val intent = Intent(context, TermuxResultReceiver::class.java).apply {
            action = ACTION_RESULT
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, requestId.hashCode(), intent, flags)
    }
}

/**
 * 挂起中的 Termux 请求登记表。
 *
 * 广播接收器是系统实例化的（不持有 [TermuxBridge] 引用），故用进程内单例把结果投回等待中的协程。
 */
internal object TermuxResultRelay {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<ShellResult>>()

    fun register(requestId: String): CompletableDeferred<ShellResult> =
        CompletableDeferred<ShellResult>().also { pending[requestId] = it }

    fun unregister(requestId: String) {
        pending.remove(requestId)
    }

    fun deliver(requestId: String, result: ShellResult) {
        pending.remove(requestId)?.complete(result)
    }
}

/**
 * Termux 结果回传接收器。
 *
 * 由本 App 自己创建的 PendingIntent 触发，属于进程内自收自发，故 `exported=false` 即可。
 */
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(TermuxBridge.EXTRA_REQUEST_ID) ?: return
        TermuxResultRelay.deliver(requestId, parseResult(intent))
    }

    /**
     * 防御性解析回传结果。
     *
     * Termux 把结果放在 `EXTRA_RESULT_BUNDLE` 里（不同版本 key 命名略有差异），
     * 因此按键名子串匹配取值，避免版本差异导致读不到输出。
     */
    private fun parseResult(intent: Intent): ShellResult {
        val bundle = intent.getBundleExtra("com.termux.RUN_COMMAND_RESULT_BUNDLE")
            ?: intent.extras
            ?: return ShellResult.Success("")

        val values = HashMap<String, String>()
        for (key in bundle.keySet()) {
            val v = bundle.get(key)
            values[key.lowercase()] = when (v) {
                null -> ""
                is String -> v
                is Int -> v.toString()
                is Array<*> -> v.filterIsInstance<String>().joinToString(" ")
                else -> v.toString()
            }
        }

        val errmsg = values.entries.firstOrNull { it.key.contains("errmsg") }?.value
        val exitCode = values.entries.firstOrNull { it.key.contains("exit") }?.value?.toIntOrNull() ?: 0
        val stdout = values.entries.firstOrNull { it.key.contains("stdout") }?.value.orEmpty()
        val stderr = values.entries.firstOrNull { it.key.contains("stderr") }?.value.orEmpty()

        // 策略/权限拒绝（如 allow-external-apps 未开）会带 errmsg 且无任何输出
        if (exitCode != 0 || (!errmsg.isNullOrBlank() && stdout.isBlank() && stderr.isBlank())) {
            val reason = errmsg?.takeIf { it.isNotBlank() }
                ?: stderr.takeIf { it.isNotBlank() }
                ?: "Termux 执行失败（exit=$exitCode）"
            return ShellResult.Failure(reason, exitCode)
        }

        val merged = listOf(stdout, stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        return ShellResult.Success(merged, exitCode)
    }
}

/** Termux 通道可用性快照（供能力页展示与引导，不参与决策链路） */
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