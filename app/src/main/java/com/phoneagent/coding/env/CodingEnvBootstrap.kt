package com.phoneagent.coding.env

import android.content.Context
import com.phoneagent.env.EnvApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 把「装组件 → 授权 → 装工具链 → 构建」编排成一个可重复执行的流程。
 *
 * 设备内 Agent 只能看到一句「环境没配好」，而真实断点分散在几处：内置 Node 运行时随主包、
 * PRoot 容器在独立组件、构建需要共享存储镜像与两项授权。逐个步骤教 Agent 判断顺序既耗时
 * 又容易漏，所以把顺序固化在这里：每次调用都先探测，停在第一个需要用户操作的环节并明确
 * 说明，办完后重跑即可继续——已完成的步骤不会重复执行。
 *
 * [status] 只读；[setup] 推进安装；[build] 在工具链就绪后跑一次 Gradle。
 */
// 该类按能力分片组织，方法数量多但职责清晰，此处按规则豁免
@Suppress("TooManyFunctions")
class CodingEnvBootstrap(
    private val context: Context,
    private val client: CodingEnvClient,
) {

    private companion object {
        /** 等待任务结束的默认窗口：JDK / Gradle / SDK 的下载体积大，短窗口只会误判失败 */
        const val DEFAULT_TIMEOUT_MS = 1_800_000L
        const val MAX_TIMEOUT_MS = 3_600_000L
        const val MIN_TIMEOUT_MS = 30_000L

        /** 共享存储前缀：命中这些前缀的工程本身就是组件可见的路径，不需要镜像 */
        val SHARED_PREFIXES = listOf("/storage/", "/sdcard/")

        /** 组件 stateJson 里代表"已完成"的取值（见 env 模块 ToolchainInstaller.state） */
        const val STATE_READY_LINUX = "就绪"
        const val STATE_READY_AAPT2 = "已配置"
        const val STATE_MISSING = "未安装"
        const val STATE_NEVER = "从未安装"

        /** 返回给 Agent 的日志尾巴：全量日志留在设置页，这里只给最近的上下文 */
        const val LOG_TAIL = 25

        /** 找产物时只下探到 build/outputs/apk 的量级，避免遍历整个镜像目录 */
        const val APK_SCAN_DEPTH = 10
    }

    /** 内置 Node 运行时是否随主包落地（Android 10+ 只认 nativeLibraryDir 里的真实文件） */
    fun runtimeReady(): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libnode.so").isFile

    /**
     * 环境总览。[refresh] 为 true 时先重读安装状态、工程识别与组件侧进度，
     * 得到的是真实快照而不是上一次探测的残影。
     */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod")
    suspend fun status(refresh: Boolean = false): JSONObject {
        if (refresh) client.refresh()
        val ui = client.snapshot()
        val toolchain = ui.toolchain
        val staging = toolchain?.projectDir?.absolutePath
            ?.let { path -> SHARED_PREFIXES.none { path.startsWith(it) } } == true
        return JSONObject()
            .put(
                "ready",
                runtimeReady() && ui.storageGranted && envInstalled(ui.envState) &&
                    toolchain?.ready == true && (!staging || ui.sharedWritable),
            )
            .put("runtimeReady", runtimeReady())
            .put("componentInstalled", ui.installedVersion != null)
            .put("componentVersion", ui.installedVersion ?: JSONObject.NULL)
            .put("bundledVersion", ui.bundledVersion ?: JSONObject.NULL)
            .put("componentNeedsInstall", ui.needsInstall)
            .put("componentBound", ui.bound)
            .put("envStorageGranted", ui.storageGranted)
            .put("allFilesAccess", ui.sharedWritable)
            .put("needsStaging", staging)
            .put("toolchainReady", toolchain?.ready == true)
            .put("projectDir", (ui.lastProjectPath ?: toolchain?.projectDir?.absolutePath) ?: JSONObject.NULL)
            .put(
                "toolchain",
                JSONObject()
                    .put("gradle", toolchain?.gradleVersion ?: JSONObject.NULL)
                    .put("gradleSource", toolchain?.gradleSource ?: JSONObject.NULL)
                    .put("agp", toolchain?.agpVersion ?: JSONObject.NULL)
                    .put("compileSdk", toolchain?.compileSdk ?: JSONObject.NULL)
                    .put("buildTools", toolchain?.buildTools ?: JSONObject.NULL),
            )
            .put("environmentInstalled", envInstalled(ui.envState))
            .put("environment", JSONObject(ui.envState))
            .put(
                "task",
                JSONObject()
                    .put("running", ui.running)
                    .put("finished", ui.finished)
                    .put("ok", ui.ok)
                    .put("message", ui.message ?: JSONObject.NULL)
                    .put(
                        "steps",
                        ui.steps.fold(JSONArray()) { array, step ->
                            array.put(
                                JSONObject()
                                    .put("index", step.index)
                                    .put("title", step.title)
                                    .put("state", step.state),
                            )
                        },
                    )
                    .put("logs", JSONArray(ui.logs.takeLast(LOG_TAIL))),
            )
    }

    /**
     * 一键推进环境。可重复执行：每次只走到第一个未完成的环节；
     * 需要用户动手时（装组件、两项授权）会拉起对应系统页面并返回 `needsUserAction=true`。
     */
    suspend fun setup(timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        val wait = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        client.refresh()
        val ui = client.snapshot()
        val blocker = blocker(ui)
        if (blocker != null) return blocker
        return when {
            client.snapshot().running -> awaitRunning(wait)
            envInstalled(client.snapshot().envState) ->
                frame("ready", true, false, "编码环境已就绪，可以直接执行 coding_env build。")
            else -> startInstall(wait)
        }
    }

    /** 在容器内构建 APK；未就绪时明确告诉 Agent 先跑 setup，而不是抛一句莫名的 Gradle 报错 */
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    suspend fun build(task: String = "assembleDebug", timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        val wait = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        client.refresh()
        val ui = client.snapshot()
        val reason = notReadyReason(ui)
        return when {
            reason != null ->
                frame("not-ready", false, false, "$reason。先执行 coding_env setup（可重复执行，它会补齐缺少的部分）。")
            ui.running ->
                frame("busy", false, false, "已有安装或构建任务在执行；等它结束再构建，进度可用 coding_env status 查看。")
            else -> {
                val err = client.buildApk(task)
                val finished = if (err == null) client.awaitFinished(wait) else null
                val root = finished?.lastProjectPath ?: ui.lastProjectPath
                    ?: ui.toolchain?.projectDir?.absolutePath
                // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
                @Suppress("MaxLineLength")
                val apk = root?.let(::latestApk)
                when {
                    err != null -> frame("build-failed", false, false, err)
                    finished == null ->
                        frame("building", false, false, "构建仍在进行（等待窗口已到）。稍后用 coding_env status 查询，日志在「设置 → 编码环境」页。")
                    !finished.ok ->
                        frame(
                            "build-failed", false, false,
                            "构建失败：${finished.message.orEmpty().ifBlank { "组件未给出原因，请到「设置 → 编码环境」页看日志" }}",
                        )
                    else ->
                        frame(
                            "built", true, false,
                            "构建完成：${finished.message.orEmpty().ifBlank { "$task 成功" }}${apk?.let { "；产物 ${it.absolutePath}" }.orEmpty()}",
                        )
                }
            }
        }
    }

    /** 请求取消当前任务（组件在下一次检查点停下） */
    suspend fun cancel(): JSONObject {
        client.cancel()
        return frame("cancelled", true, false, "已请求取消当前任务；组件会在下一个检查点停下。")
    }

    /** 统一包装：status 给全量事实，stage / ok / needsUserAction / message 描述这一次调用的结论 */
    private suspend fun frame(
        stage: String,
        ok: Boolean,
        needsUserAction: Boolean,
        message: String,
    ): JSONObject = status()
        .put("stage", stage)
        .put("ok", ok)
        .put("needsUserAction", needsUserAction)
        .put("message", message)

    /**
     * 第一个需要用户操作（或根本无法继续）的环节；全部通过时返回 null。
     * 顺序与真实依赖一致：运行时 → 组件安装 → 镜像所需的全文件访问 → 组件存储授权。
     */
    private suspend fun blocker(ui: CodingEnvClient.UiState): JSONObject? {
        val staging = ui.toolchain?.projectDir?.absolutePath
            ?.let { path -> SHARED_PREFIXES.none { path.startsWith(it) } } == true
        return when {
            !runtimeReady() -> frame(
                "runtime-missing", false, false,
                "内置 Node 运行时缺失：安装包里没有 libnode.so，当前 APK 不完整，请重新安装完整版 HPA。",
            )
            ui.needsInstall -> client.installComponent().let { error ->
                if (error == null) {
                    frame(
                        "component-install", false, true,
                        "已拉起编码环境组件的安装器：请在系统弹窗点「安装」，完成后重新执行 coding_env setup，" +
                            "流程会跳过已完成的步骤。",
                    )
                } else {
                    frame("component-install-failed", false, false, error)
                }
            }
            staging && !ui.sharedWritable -> client.openAllFilesAccessSettings().let { error ->
                if (error == null) {
                    frame(
                        "all-files-access", false, true,
                        "已打开本应用的「所有文件访问」授权页：请打开开关后重跑 coding_env setup。" +
                            "私有工作区必须先镜像到共享存储，环境组件（另一个 uid）才读得到。",
                    )
                } else {
                    frame("all-files-access-failed", false, false, error)
                }
            }
            !ui.storageGranted -> client.openStorageSetup().let { error ->
                if (error == null) {
                    frame(
                        "env-storage", false, true,
                        "已打开环境组件的存储授权页：请点「允许」后重跑 coding_env setup。",
                    )
                } else {
                    frame("env-storage-failed", false, false, error)
                }
            }
            else -> null
        }
    }

    /** 已有任务在跑：等它结束再看结果，绝不并发发第二个请求（组件一次只跑一个任务） */
    private suspend fun awaitRunning(wait: Long): JSONObject {
        val finished = client.awaitFinished(wait)
        client.refresh()
        return when {
            finished == null ->
                frame("installing", false, false, "已有任务仍在执行（等待窗口已到），稍后用 coding_env status 查询进度。")
            envInstalled(client.snapshot().envState) ->
                frame("ready", true, false, "上一个任务已结束，编码环境已就绪，可以开始构建。")
            else ->
                frame(
                    "previous-task-finished", false, false,
                    "上一个任务已结束但工具链仍未就绪：${finished.message.orEmpty().ifBlank { "没有更多信息" }}。" +
                        "请重新执行 coding_env setup。",
                )
        }
    }

    /** 发起工具链安装并等结果；成功时刷新一次，让随后的 status 能读到组件侧清单 */
    private suspend fun startInstall(wait: Long): JSONObject {
        val err = client.installEnvironment()
        val finished = if (err == null) client.awaitFinished(wait) else null
        if (finished?.ok == true) client.refresh()
        return when {
            err != null -> frame("install-failed", false, false, err)
            finished == null ->
                frame("installing", false, false, "工具链安装仍在进行（等待窗口已到）。稍后用 coding_env status 查询，日志在「设置 → 编码环境」页。")
            finished.ok ->
                frame(
                    "installed", true, false,
                    "安装完成：${finished.message.orEmpty().ifBlank { "Linux / JDK / Gradle / SDK / aapt2 已就绪" }}。" +
                        "现在可以执行 coding_env build。",
                )
            else ->
                frame(
                    "install-failed", false, false,
                    "安装失败：${finished.message.orEmpty().ifBlank { "组件未给出原因，请到「设置 → 编码环境」页看日志" }}",
                )
        }
    }

    /** 构建前置条件的第一处缺口；null 表示可构建 */
    private fun notReadyReason(ui: CodingEnvClient.UiState): String? {
        val staging = ui.toolchain?.projectDir?.absolutePath
            ?.let { path -> SHARED_PREFIXES.none { path.startsWith(it) } } == true
        return when {
            !runtimeReady() -> "内置 Node 运行时缺失（nativeLibraryDir 下没有 libnode.so）"
            ui.needsInstall -> "编码环境组件未安装到随包版本"
            staging && !ui.sharedWritable -> "本应用还没有「所有文件访问」，私有工作区无法镜像到共享存储"
            !ui.storageGranted -> "环境组件还没有共享存储权限"
            ui.toolchain?.ready != true -> "当前工作区没有识别到可构建的 Android 工程"
            !envInstalled(ui.envState) -> "Linux / JDK / Gradle / Android SDK / aapt2 尚未安装完成"
            else -> null
        }
    }

    /** 工具链是否装齐：以组件自报的状态为准，缺任何一项都算未就绪 */
    private fun envInstalled(env: Map<String, String>): Boolean {
        val gradle = env[EnvApi.STATE_GRADLE].orEmpty()
        val sdk = env[EnvApi.STATE_SDK].orEmpty()
        return env[EnvApi.STATE_LINUX] == STATE_READY_LINUX &&
            env[EnvApi.STATE_JDK].orEmpty().startsWith("JDK") &&
            gradle.isNotBlank() && gradle != STATE_MISSING && gradle != STATE_NEVER &&
            sdk.isNotBlank() && sdk != STATE_MISSING &&
            env[EnvApi.STATE_AAPT2] == STATE_READY_AAPT2
    }

    /** 最近产出的 APK：只看 build/outputs/apk 下的文件，按修改时间取最新 */
    private fun latestApk(rootPath: String): File? = runCatching {
        File(rootPath).walkTopDown().maxDepth(APK_SCAN_DEPTH)
            .filter { it.isFile && it.extension.equals("apk", true) }
            .filter { it.absolutePath.contains("/build/outputs/apk/") }
            .maxByOrNull { it.lastModified() }
    }.getOrNull()
}
