package com.phoneagent.coding.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-wide holder for the on-device agent host, started by AgentService.
 * A singleton is fine: service, activity and transports live in one app process.
 */
object LocalAgent {
    private const val TAG = "coding-local"

    @Volatile
    var process: AgentProcess? = null

    @Volatile
    var lastError: String? = null

    /** 最近一次成功发起启动时使用的配置；前台服务被系统重启后靠它把宿主拉回来。 */
    @Volatile
    var lastConfig: CodingAgentConfig? = null

    private val exitHooks = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    /**
     * 启动相关的重活（首启解压 assets、定位 libnode.so、spawn 进程）都放这里，
     * 不占调用线程：connectLocal 由 UI 线程触发、重连由主线程 postDelayed 触发，
     * 同步跑的话首次本地连接会直接 ANR。
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "coding-local-start").apply { isDaemon = true }
    }

    fun start(
        context: Context,
        config: CodingAgentConfig,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        lastConfig = config
        if (process?.isAlive == true) {
            onReady()
            return
        }
        io.execute {
            // Foreground service keeps the host alive when the UI is backgrounded.
            // Android 12+ 在应用退到后台时抛 ForegroundServiceStartNotAllowedException——
            // 自动重连恰恰容易在后台触发，所以这里必须自己兜住：FGS 起不来不影响
            // agent 进程本身跑起来（只是保活等级降级），不能把主线程带崩。
            try {
                ContextCompat.startForegroundService(context, Intent(context, com.phoneagent.coding.CodingAgentService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "foreground service start refused: ${e.message}")
            }
            startHost(context, config, onReady, onFailure)
        }
    }

    private fun startHost(
        context: Context,
        config: CodingAgentConfig,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        try {
            val runtime = RuntimeBootstrap.runtime(context)
            val node = runtime.nodePath
            val entry = RuntimeBootstrap.ensureHost(context)
            val workspace = Workspace.resolve(context)
            // node 自带的 Mozilla CA 包缺部分国内网关的根证书（实测 model.iepose.cn 的
            // Xcc Trust 直接 fetch failed），而主 app 的 OkHttp 走 Android 系统信任库。
            // 把系统 CA 拼成 PEM 传给 NODE_EXTRA_CA_CERTS（在自带根上"叠加"，不替换），
            // 让 node 与 OkHttp 的信任面完全对齐。目录缺失/拼接失败只降级不阻断。
            var caBundlePath: String? = null
            try {
                File(context.filesDir, "agent-host/cacerts.pem").apply {
                    parentFile?.mkdirs()
                    val pems = File("/system/etc/security/cacerts").listFiles()
                        ?.filter { it.isFile }?.map { it.readText() }
                    if (!pems.isNullOrEmpty()) writeText(pems.joinToString("\n"))
                    if (length() > 0) caBundlePath = absolutePath
                }
            } catch (e: Exception) {
                Log.w(TAG, "system CA bundle unavailable: ${e.message}")
            }
            // 环境变量保持 HAPPY_ACA_* 前缀不变：这是与 agent-core 零改动同步的契约，
            // 上游 host-stdio.js 只认这些名字，改名就得动两边的代码。
            val env = mutableMapOf(
                "HAPPY_ACA_PROVIDER" to config.provider,
                "HAPPY_ACA_API_KEY" to config.apiKey,
                "HAPPY_ACA_BASE_URL" to config.baseUrl,
                "HAPPY_ACA_MODEL" to config.model,
                "HAPPY_ACA_WORKSPACE" to workspace.absolutePath,
                "HAPPY_ACA_APPROVAL" to config.approval,
                "HAPPY_ACA_TOKEN" to config.token,
                // host-stdio.js 读的是 HAPPY_ACA_STATE_DIR；以前这里写成
                // HAPPY_ACA_SESSIONS_DIR，变量名对不上 → 本地模式其实什么都没落盘
                // （会话、记忆、用量账本、模型档位重启全丢）。
                "HAPPY_ACA_STATE_DIR" to File(context.filesDir, "state").apply { mkdirs() }.absolutePath,
                "HOME" to context.filesDir.absolutePath,
                "TMPDIR" to context.cacheDir.absolutePath,
                // Tells the agent it is on a phone, so it plans accordingly
                // (mksh, no compiler toolchain) instead of promising npm builds.
                "HAPPY_ACA_ANDROID" to "1",
                // Optional scripted scenario for offline tool-loop demos.
                "HAPPY_ACA_MOCK_SCRIPT" to RuntimeBootstrap.scenarioPath(context, config.mockScript),
                // bionic does not search the app's files dir by default; the
                // extracted libs (libz.so.1, libcrypto.so.3, ...) live there.
                "LD_LIBRARY_PATH" to runtime.libDir,
            )
            caBundlePath?.let { env["NODE_EXTRA_CA_CERTS"] = it }
            val proc = AgentProcess(
                nodePath = node,
                scriptPath = entry.absolutePath,
                workDir = workspace,
                env = env,
                stderrLog = File(context.filesDir, "agent-host/stderr.log"),
                onExit = { code ->
                    process = null
                    exitHooks.forEach { it(code) }
                },
            )
            proc.start()
            process = proc
            // Short grace period: a hard exec failure shows up within ~100ms.
            Thread {
                Thread.sleep(800)
                if (proc.isAlive) {
                    lastError = null
                    onReady()
                } else {
                    lastError = "agent process exited at startup — see files/agent-host/stderr.log"
                    Log.e(TAG, lastError!!)
                    onFailure(lastError!!)
                }
            }.start()
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            Log.e(TAG, "start failed", e)
            // Surface the Java-side failure (e.g. libnode.so missing) in the
            // same file the host stderr lands in, so it is inspectable without
            // logcat: run-as com.phoneagent cat files/agent-host/stderr.log
            try {
                File(context.filesDir, "agent-host/stderr.log").apply {
                    parentFile?.mkdirs()
                    appendText("coding-agent start failed: ${e.message}\n")
                }
            } catch (_: Exception) {
            }
            onFailure(lastError!!)
        }
    }

    fun stop() {
        process?.stop()
        process = null
    }

    fun addExitHook(hook: (Int) -> Unit) {
        exitHooks.add(hook)
    }

    fun removeExitHook(hook: (Int) -> Unit) {
        exitHooks.remove(hook)
    }
}
