package com.phoneagent.coding.env

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.phoneagent.coding.agent.Workspace
import com.phoneagent.env.EnvApi
import com.phoneagent.env.IEnvCallback
import com.phoneagent.env.IEnvProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * 编码环境组件（com.phoneagent.env）的主应用侧门户。
 *
 * 分工与 [com.phoneagent.device.provider.ProviderManager] 一致：组件是独立应用，
 * 装不上/没绑上/签名不一致都要给出明确原因，因此这里把「安装引导 → 版本比对 →
 * AIDL 绑定 → 进度聚合」收在一个类里，UI 只观察 [state]。
 *
 * 与 Provider 的差别是它有一项跨 uid 的硬约束：环境组件读不到本应用的 filesDir，
 * 构建必须在共享存储上跑。私有工作区在发起请求前会先镜像到
 * `/storage/emulated/0/happyac-build/<工程名>`，[ProjectToolchain.projectDir] 只决定源目录。
 */
// 该类按能力分片组织，方法数量多但职责清晰，此处按规则豁免
@Suppress("TooManyFunctions")
class CodingEnvClient(private val context: Context) {

    private companion object {
        const val TAG = "CodingEnvClient"

        private const val APK_ASSET = "env/env.apk"

        /** 进度日志只留最近 300 行：构建日志动辄上万行，全留着没有意义 */
        private const val LOG_LIMIT = 300
        private const val LOG_LINE_LIMIT = 400

        /** 共享存储根目录（与容器里绑定的路径一致） */
        private const val SHARED_ROOT = "/storage/emulated/0"

        /** 镜像目录：HPA 私有工作区复制到这里，环境组件才读得到 */
        private const val SHARED_BUILD_DIR = "happyac-build"
    }

    /** 一条安装/构建步骤：[index] 从 0 开始，[state] 取 [EnvApi.STEP_START] 等三态 */
    data class Step(val index: Int, val total: Int, val title: String, val state: String)

    /** 「环境」页观察的全部状态 */
    data class UiState(
        val installedVersion: String? = null,
        val bundledVersion: String? = null,
        val bound: Boolean = false,
        /** 环境组件是否拿到了共享存储的运行时权限 */
        val storageGranted: Boolean = false,
        /** 本应用能否写共享存储（Android 11+ 看「所有文件访问」） */
        val sharedWritable: Boolean = false,
        val toolchain: ProjectToolchain? = null,
        /** 组件侧的安装清单（stateJson），键见 [EnvApi.STATE_LINUX] 等 */
        val envState: Map<String, String> = emptyMap(),
        /** 实际传给组件的工程路径（可能是共享存储上的镜像） */
        val lastProjectPath: String? = null,
        val running: Boolean = false,
        val steps: List<Step> = emptyList(),
        val logs: List<String> = emptyList(),
        val finished: Boolean = false,
        val ok: Boolean = false,
        val message: String? = null,
    ) {
        val needsInstall: Boolean
            get() = installedVersion == null ||
                (bundledVersion != null && compareVersions(installedVersion, bundledVersion) < 0)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 同步快照：设备内 Agent 的 host 工具跑在阻塞的桥线程里，拿不到 suspend 语境 */
    fun snapshot(): UiState = _state.value

    /** 等当前任务结束；超时返回 null，调用方据此报「仍在执行」而不是判失败 */
    suspend fun awaitFinished(timeoutMs: Long): UiState? =
        withTimeoutOrNull(timeoutMs) { state.first { !it.running } }

    /** 绑定、代理与生命周期全部交给 [EnvBinder]，这里只关心它给出的代理 */
    private val binder = EnvBinder(context) { bound ->
        _state.update { it.copy(bound = bound) }
    }

    // ==================== 状态读取 ====================

    /** 重新探测安装状态、存储权限、工程版本与组件侧进度快照 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val installed = installedVersion(context)
        val bundled = bundledVersion(context)
        val toolchain = ProjectToolchainProbe.probe(Workspace.resolve(context))
        _state.update {
            it.copy(
                installedVersion = installed,
                bundledVersion = bundled,
                storageGranted = storageGranted(context),
                sharedWritable = canWriteSharedStorage(context),
                toolchain = toolchain,
            )
        }
        queryEnvState()
    }

    private suspend fun queryEnvState() {
        val proxy = binder.ensure() ?: return
        val parsed = runCatching { parseState(proxy.stateJson()) }.getOrNull() ?: return
        _state.update { it.copy(envState = parsed) }
    }

    // ==================== 组件安装 ====================

    /**
     * 把随包 APK 交给系统安装器；返回 null 表示已拉起或已是最新，非空是失败原因。
     * 用户确认安装后回到前台，设置页的 ON_RESUME 会重新探测版本。
     */
    suspend fun installComponent(): String? = withContext(Dispatchers.IO) {
        if (!_state.value.needsInstall) return@withContext null
        runCatching {
            val dir = File(context.cacheDir, "env").apply { mkdirs() }
            val apk = File(dir, "env.apk")
            context.assets.open(APK_ASSET).use { input -> apk.outputStream().use { input.copyTo(it) } }
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.exceptionOrNull()?.let { e ->
            Log.w(TAG, "拉起环境组件安装失败", e)
            "无法拉起安装器：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 打开组件自带的存储授权页；返回非空表示没打开（组件未安装等） */
    fun openStorageSetup(): String? = runCatching {
        context.startActivity(
            Intent().setClassName(EnvApi.PACKAGE, EnvApi.SETUP_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.exceptionOrNull()?.let { "无法打开存储授权页：${it.message ?: it.javaClass.simpleName}" }

    /**
     * 拉起本应用的「所有文件访问」授权页。
     * Android 11+ 才能写共享存储；跳转失败（部分 ROM 精简了这个页面）时回落到权限总表。
     */
    fun openAllFilesAccessSettings(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "当前系统版本不需要该权限"
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess || runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        return if (opened) null else "无法打开设置页，请手动在系统设置中授予「所有文件访问」"
    }

    // ==================== 安装 / 构建 ====================

    /** 一键安装：Linux 根文件系统 + JDK + Gradle + Android SDK + aapt2 兼容层 */
    suspend fun installEnvironment(): String? = launchRequest(EnvApi.OP_INSTALL, "assembleDebug")

    /** 在容器内跑一次 Gradle 构建 */
    suspend fun buildApk(task: String = "assembleDebug"): String? =
        launchRequest(EnvApi.OP_BUILD, task)

    /** 请求取消当前任务（组件在下一次检查点停下） */
    fun cancel() {
        val proxy = binder.current() ?: return
        runCatching { proxy.cancel() }
        _state.update { it.copy(message = "正在取消…") }
    }

    private suspend fun launchRequest(op: String, task: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { startRequest(op, task) }.exceptionOrNull()?.let { e ->
                Log.w(TAG, "启动环境任务失败", e)
                "启动失败：${e.message ?: e.javaClass.simpleName}"
            }
        }

    private suspend fun startRequest(op: String, task: String) {
        val toolchain = ProjectToolchainProbe.probe(Workspace.resolve(context))
        check(toolchain.ready) { "未识别到可构建的工程：${toolchain.projectDir.absolutePath}" }
        val proxy = checkNotNull(binder.ensure()) {
            "环境组件未安装或绑定失败（需要同签名安装）"
        }
        val projectPath = stageProject(toolchain.projectDir)
        val request = JSONObject().apply {
            put(EnvApi.KEY_OP, op)
            put(EnvApi.KEY_PROJECT, projectPath)
            put(EnvApi.KEY_GRADLE, toolchain.gradleVersion)
            put(EnvApi.KEY_AGP, toolchain.agpVersion.orEmpty())
            put(EnvApi.KEY_COMPILE_SDK, toolchain.compileSdk)
            put(EnvApi.KEY_BUILD_TOOLS, toolchain.buildTools)
            put(EnvApi.KEY_TASK, task)
        }
        // 先置运行态再发请求：组件是 oneway 调用，忙时可能立刻回 onFinished，
        // 反过来写会把刚收到的失败结果又盖成"运行中"
        _state.update {
            it.copy(
                running = true,
                steps = emptyList(),
                logs = emptyList(),
                finished = false,
                ok = false,
                message = null,
                lastProjectPath = projectPath,
            )
        }
        proxy.run(request.toString(), Callback())
    }

    /**
     * 工程必须落在共享存储上：环境组件是另一个 uid，读不到本应用 filesDir。
     * 已经是共享存储路径的直接用，否则镜像到 `/storage/emulated/0/happyac-build/<工程名>`。
     */
    private fun stageProject(project: File): String {
        val path = project.absolutePath
        if (path.startsWith(SHARED_ROOT) || path.startsWith("/sdcard")) return path
        check(canWriteSharedStorage(context)) {
            "无法写入共享存储：请在系统设置里给本应用开启「所有文件访问」"
        }
        val target = File(File(SHARED_ROOT, SHARED_BUILD_DIR), project.name)
        copyTree(project, target)
        return target.absolutePath
    }

    // ==================== 回调聚合 ====================

    private inner class Callback : IEnvCallback.Stub() {
        override fun onStep(index: Int, total: Int, title: String, state: String) {
            _state.update { current ->
                val steps = current.steps.filterNot { it.index == index } +
                    Step(index, total, title, state)
                current.copy(steps = steps.sortedBy { it.index })
            }
        }

        override fun onLog(line: String) {
            _state.update { current ->
                current.copy(
                    logs = (current.logs + line.take(LOG_LINE_LIMIT)).takeLast(LOG_LIMIT),
                )
            }
        }

        override fun onFinished(ok: Boolean, message: String) {
            _state.update {
                it.copy(running = false, finished = true, ok = ok, message = message)
            }
        }
    }
}

/**
 * AIDL 绑定状态机：拉起组件进程、等待 onServiceConnected、binder 死亡后真正解绑。
 *
 * 单独成类而不是塞进 [CodingEnvClient]，一是绑定细节和「环境」页的数据无关，
 * 二是里面全是同步/轮询逻辑，分开后两者都更好读。
 */
internal class EnvBinder(
    private val context: Context,
    private val onBoundChanged: (Boolean) -> Unit,
) {
    private companion object {
        const val TAG = "EnvBinder"

        /** 组件进程拉起最多等 3 秒（与 Provider 一致），超时按不可用处理 */
        private const val BIND_TIMEOUT_MS = 3_000L
        private const val BIND_POLL_MS = 50L
    }

    private val mutex = Mutex()

    @Volatile
    private var remote: IEnvProvider? = null

    @Volatile
    private var binding = false

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "环境组件 binder 已断开")
        invalidate()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            remote = IEnvProvider.Stub.asInterface(service)
            binding = false
            onBoundChanged(remote != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            onBoundChanged(false)
        }

        override fun onBindingDied(name: ComponentName?) {
            invalidate()
        }

        override fun onNullBinding(name: ComponentName?) {
            remote = null
            binding = false
            onBoundChanged(false)
        }
    }

    /** 已绑定的代理；调用方要做一次性操作（如取消）时用它，不做绑定尝试 */
    fun current(): IEnvProvider? = remote

    /** 取一个可用的代理：已绑定直接返回，没装返回 null，否则绑定并等待回调 */
    suspend fun ensure(): IEnvProvider? = when {
        isBinderAlive(remote) -> remote
        installedVersion(context) == null -> null
        else -> mutex.withLock {
            if (isBinderAlive(remote)) remote else withTimeoutOrNull(BIND_TIMEOUT_MS) { bindAwait() }
        }
    }

    /** 绑定并挂起等待回调；整体超时由 [ensure] 的 withTimeoutOrNull 兜底 */
    private suspend fun bindAwait(): IEnvProvider? = suspendCancellableCoroutine { cont ->
        binding = true
        runCatching { context.unbindService(connection) }
        val ok = runCatching {
            context.bindService(
                Intent(EnvApi.ACTION).setPackage(EnvApi.PACKAGE),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)

        if (!ok) {
            binding = false
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // 轮询等待 onServiceConnected 写入代理（回调在 binder 线程，不另建回调表）
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < BIND_TIMEOUT_MS) {
            val proxy = remote
            if (proxy != null) {
                binding = false
                cont.resume(proxy)
                return@suspendCancellableCoroutine
            }
            if (!binding) break // onNullBinding 等异常路径
            Thread.sleep(BIND_POLL_MS)
        }
        binding = false
        cont.resume(null)
    }

    /** binder 死亡或绑定失效：清代理并真正解绑，否则系统认为仍连着、不会再回调 */
    private fun invalidate() {
        remote = null
        binding = false
        onBoundChanged(false)
        runCatching { context.unbindService(connection) }
    }
}

// ==================== 无状态辅助（文件级，两个类共用） ====================

/** 版本比较：按 '.' 分段、缺省补 0，"0.2.10" 大于 "0.2.9"。放文件级：UiState 与客户端都要用 */
private fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/** 已安装的环境组件版本；没装返回 null */
private fun installedVersion(context: Context): String? = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(EnvApi.PACKAGE, 0).versionName
}.getOrNull()

/** 随包 APK 的版本（构建时由 :env 的 writeEnvVersion 任务生成） */
private fun bundledVersion(context: Context): String? = runCatching {
    context.assets.open("env/version.txt").bufferedReader().use { it.readText() }.trim()
}.getOrNull()?.takeIf { it.isNotBlank() }

/** 环境组件是否已获得共享存储权限（targetSdk 28 走旧存储模型，需运行时授权） */
private fun storageGranted(context: Context): Boolean = runCatching {
    context.packageManager.checkPermission(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        EnvApi.PACKAGE,
    ) == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)

/** 本应用能否写共享存储：Android 11+ 靠「所有文件访问」，更早的版本靠存储运行时权限 */
private fun canWriteSharedStorage(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        runCatching {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.getOrNull() == PackageManager.PERMISSION_GRANTED
    }

private fun parseState(raw: String): Map<String, String> {
    val json = JSONObject(raw)
    return json.keys().asSequence().associateWith { json.optString(it) }
}

private fun isBinderAlive(proxy: IEnvProvider?): Boolean =
    proxy != null && runCatching { proxy.asBinder().isBinderAlive }.getOrDefault(false)

/** 递归镜像；同名同大小的文件跳过，避免每次构建都全量重写 */
private fun copyTree(source: File, target: File) {
    when {
        source.isDirectory -> {
            target.mkdirs()
            source.listFiles()?.forEach { copyTree(it, File(target, it.name)) }
        }
        target.isFile && target.length() == source.length() &&
            target.lastModified() >= source.lastModified() -> Unit
        else -> {
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            target.setLastModified(source.lastModified())
        }
    }
}
