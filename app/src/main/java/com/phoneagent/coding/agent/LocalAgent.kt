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
    private const val TAG = "LocalAgent"

    @Volatile
    var process: AgentProcess? = null

    @Volatile
    var lastError: String? = null

    /** 最近一次成功发起启动时使用的配置；前台服务被系统重启后靠它把宿主拉回来。 */
    @Volatile
    var lastConfig: CodingAgentConfig? = null

    private val exitHooks = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    /**
     * 启动串行化。
     *
     * 前台服务的 onStartCommand 会在宿主进程里再调一次 [start]，而两次调用都发生在
     * `process` 被赋值之前：单靠 `process?.isAlive` 判断会让同一份宿主被启动两次，
     * 前一个进程无人持有、无法停止。这里用 `starting` 挡住重入，把重入方的回调排队，
     * 等这次启动真正有结果后统一回报。
     */
    private val startLock = Any()
    private var starting = false
    private val pendingStarts = mutableListOf<Pair<() -> Unit, (String) -> Unit>>()

    /** 启动代次：stop() 会自增，用于识别"启动到一半用户已停止"。 */
    private var generation = 0

    /**
     * 启动相关的重活（首启解压 assets、定位 libnode.so、spawn 进程）都放这里，
     * 不占调用线程：connectLocal 由 UI 线程触发、重连由主线程 postDelayed 触发，
     * 同步跑的话首次本地连接会直接 ANR。
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "coding-local-start").apply { isDaemon = true }
    }

    // 此处刻意兜底捕获系统服务/协议异常，避免单个失败打断整体流程，故按规则豁免
    @Suppress("TooGenericExceptionCaught")
    fun start(
        context: Context,
        config: CodingAgentConfig,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        lastConfig = config
        var gen = 0
        // 引擎循环刻意兜底捕获，避免单个异常打断整个任务，此处按规则豁免
        @Suppress("TooGenericExceptionCaught")
        var alreadyRunning = false
        synchronized(startLock) {
            if (process?.isAlive == true) {
                alreadyRunning = true
            } else if (starting) {
                // 同一时刻只允许一次真实启动，重入方排队等结果。
                pendingStarts.add(onReady to onFailure)
                return
            } else {
                starting = true
                gen = generation
            }
        }
        if (alreadyRunning) {
            onReady()
            return
        }
        io.execute {
            // 此处刻意兜底捕获系统服务/协议异常，避免单个失败打断整体流程，故按规则豁免
            @Suppress("TooGenericExceptionCaught")
            var error: String? = null
            try {
                // Foreground service keeps the host alive when the UI is backgrounded.
                // Android 12+ 在应用退到后台时抛 ForegroundServiceStartNotAllowedException——
                // 自动重连恰恰容易在后台触发，所以这里必须自己兜住：FGS 起不来不影响
                // agent 进程本身跑起来（只是保活等级降级），不能把主线程带崩。
                try {
                    ContextCompat.startForegroundService(context, Intent(context, com.phoneagent.coding.CodingAgentService::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "前台服务启动被拒绝: ${e.message}")
                }
                // 二次检查：排队/调度期间可能已有别的启动先落地，避免重复 spawn。
                if (process?.isAlive != true) {
                    error = startHost(context, config, gen)
                }
            } catch (t: Throwable) {
                error = "编码 Agent 启动失败：${t.message ?: t.toString()}"
                Log.e(TAG, "start failed", t)
            } finally {
                finishStart(gen, error, onReady, onFailure)
            }
        }
    }

    /** 启动收尾：把本次结果回报给首个调用方，并把排队中的重入方一并回报。 */
    private fun finishStart(
        gen: Int,
        error: String?,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val queued: List<Pair<() -> Unit, (String) -> Unit>>
        var cancelled = false
        synchronized(startLock) {
            cancelled = generation != gen
            if (!cancelled) {
                starting = false
                queued = pendingStarts.toList()
                pendingStarts.clear()
            } else {
                // 期间被 stop() 打断：不要清掉新一轮启动的 in-flight 标记。
                queued = emptyList()
            }
        }
        val result = if (cancelled) (error ?: "编码 Agent 已停止") else error
        runCatching { if (result == null) onReady() else onFailure(result) }
        queued.forEach { (ready, fail) ->
            runCatching { if (result == null) ready() else fail(result) }
        }
    }

    /**
     * 真正 spawn 宿主进程。成功返回 null，失败返回中文原因——
     * 由调用方统一回报，避免多个回调分支各自写一套失败文案。
     */
    // 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun startHost(
        context: Context,
        config: CodingAgentConfig,
        gen: Int,
    ): String? {
        try {
            val runtime = RuntimeBootstrap.runtime(context)
            val node = runtime.nodePath
            val entry = RuntimeBootstrap.ensureHost(context)
            val workspace = Workspace.resolve(context)
            // 状态目录跟随工作区：会话/记忆/用量/模型档位都落在 <工作区>/.happyac/agent，
            // 切工作区就是切一整套记忆，互不串线（以前固定 filesDir/state，所有工作区共用一份）。
            val stateDir = File(workspace, CodingWorkspaces.META_DIR).resolve("agent").apply { mkdirs() }
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
            // 随包 skill（编码环境说明文档）先落到 skills 目录再交给 node：
            // 落盘失败不影响启动，大不了 agent 少一份说明。
            val skillsDir = com.phoneagent.coding.skill.AcaSkillCatalog
                .apply { ensureBundled(context) }
                .skillsDir(context).apply { mkdirs() }
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
                "HAPPY_ACA_STATE_DIR" to stateDir.absolutePath,
                // Skill 包根目录（agent-host/skills.js 的 SkillManager）：不传的话
                // skillsRoot 为 null，list_skills/activate_skill 等元工具根本不会
                // 注册，编码模式的 skill 机制等于不存在。目录空也没关系——
                // agent 会拿到 "(no skills installed)"，用户往里放包即可生效。
                // 路径统一取 AcaSkillCatalog.skillsDir：主页的技能扫描读的是
                // 同一个目录，两处各写一份迟早失配。
                "HAPPY_ACA_SKILLS_DIR" to skillsDir.absolutePath,
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
            var created: AgentProcess? = null
            val proc = AgentProcess(
                nodePath = node,
                scriptPath = entry.absolutePath,
                workDir = workspace,
                env = env,
                stderrLog = File(context.filesDir, "agent-host/stderr.log"),
                onExit = { code ->
                    // 只清理"当前这一份"：旧进程晚到的退出回调不能把新进程的引用抹掉，
                    // 否则 UI 以为宿主没了，实际新宿主还活着，再点一次又拉第三份。
                    if (process === created) process = null
                    exitHooks.forEach { hook -> runCatching { hook(code) } }
                },
            )
            created = proc
            proc.start()
            process = proc
            // Short grace period: a hard exec failure shows up within ~100ms.
            Thread.sleep(800)
            if (synchronized(startLock) { generation != gen }) {
                // 启动过程中用户点了停止：别把这个进程留下当孤儿。
                proc.stop()
                if (process === proc) process = null
                return "编码 Agent 启动已取消"
            }
            if (!proc.isAlive) {
                lastError = "编码 Agent 启动后立即退出，请查看 files/agent-host/stderr.log"
                Log.e(TAG, lastError!!)
                return lastError
            }
            lastError = null
            return null
        } catch (e: Exception) {
            lastError = "编码 Agent 启动失败：${e.message ?: e.toString()}"
            Log.e(TAG, "编码 Agent 启动失败", e)
            // Surface the Java-side failure (e.g. libnode.so missing) in the
            // same file the host stderr lands in, so it is inspectable without
            // logcat: run-as com.phoneagent cat files/agent-host/stderr.log
            try {
                File(context.filesDir, "agent-host/stderr.log").apply {
                    parentFile?.mkdirs()
                    appendText("编码 Agent 启动失败：${e.message}\n")
                }
            } catch (_: Exception) {
            }
            return lastError
        }
    }

    fun stop() {
        val queued: List<Pair<() -> Unit, (String) -> Unit>>
        synchronized(startLock) {
            generation += 1
            starting = false
            queued = pendingStarts.toList()
            pendingStarts.clear()
        }
        // 排队等结果的重入方必须先收到失败，否则界面会一直停在"连接中"。
        queued.forEach { (_, fail) -> runCatching { fail("编码 Agent 已停止") } }
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
