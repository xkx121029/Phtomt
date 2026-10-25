package com.phoneagent.device.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.FileProvider
import com.phoneagent.provider.IDeviceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 权限 Provider 的主应用侧门户：安装引导 + 版本比对 + AIDL 绑定维持。
 *
 * 无障碍 / 内置 Shizuku / Termux 三条通道的实现都在独立应用
 * `com.phoneagent.provider` 内（Provider 构建产物作为静态资产提交在本应用
 * assets/provider/ 下，经 :app:syncProviderAssets 同步）。本类负责：
 * 1. 首次打开或版本落后时，把内置 APK 交给系统安装器引导安装；
 * 2. bindService 进 Provider 的 IDeviceProvider，3 秒超时 + 断连自动重绑；
 * 3. 给上层提供带超时兜底的 [call]（Provider 被杀时 binder 调用可能抛
 *    DeadObjectException 或永久挂起，必须收口成 null）。
 *
 * 绑定权限为 signature 级（com.phoneagent.permission.BIND_PROVIDER），
 * 只有同签名的本应用能拿到 binder。
 */
object ProviderManager {

    private const val TAG = "ProviderManager"

    /** Provider 应用包名（与 :provider 模块 applicationId 一致） */
    const val PACKAGE = "com.phoneagent.provider"

    /** AIDL 服务 action（与 Provider Manifest 中 ProviderService 的 intent-filter 一致） */
    const val ACTION = "com.phoneagent.provider.IDeviceProvider"

    /** 内置 APK 与版本文件在 assets 里的位置（经 :app:syncProviderAssets 同步进仓库） */
    private const val APK_ASSET = "provider/provider.apk"
    private const val VERSION_ASSET = "provider/version.txt"

    /** 默认绑定超时：3 秒足够系统拉起 Provider 进程，超时视为不可用而不是无限等 */
    private const val BIND_TIMEOUT_MS = 3_000L

    private val bindMutex = Mutex()

    @Volatile
    private var provider: IDeviceProvider? = null

    @Volatile
    private var connecting = false

    private var appContext: Context? = null

    private val _connected = MutableStateFlow(false)

    /** Provider 是否已连上（UI 可观察） */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** binder 死亡回调：Provider 进程被杀时清空代理，下次 [call] 触发重绑 */
    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Provider binder 已断开，等待重连")
        provider = null
        _connected.value = false
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = IDeviceProvider.Stub.asInterface(service)
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            provider = remote
            _connected.value = true
            Log.i(TAG, "已绑定 Provider（apiVersion=${runCatching { remote?.apiVersion() }.getOrNull()}）")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            provider = null
            _connected.value = false
        }

        override fun onBindingDied(name: ComponentName?) {
            provider = null
            _connected.value = false
        }

        override fun onNullBinding(name: ComponentName?) {
            provider = null
            _connected.value = false
        }
    }

    // ==================== 安装 / 版本 ====================

    /** Provider 是否已安装 */
    fun isInstalled(context: Context): Boolean = installedVersionName(context) != null

    /** 已安装 Provider 的 versionName；未安装返回 null */
    fun installedVersionName(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0).versionName
    }.getOrNull()

    /** 随主应用打包的 Provider 版本（assets/provider/version.txt） */
    fun bundledVersion(context: Context): String? = runCatching {
        context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText() }.trim()
    }.getOrNull()

    /**
     * 是否需要（重新）安装：未安装，或已安装版本低于随包版本。
     * 版本比较按点分段数字比（"0.2.10" &gt; "0.2.9"）；无法解析时保守返回 true。
     */
    fun needsInstall(context: Context): Boolean {
        val installed = installedVersionName(context) ?: return true
        val bundled = bundledVersion(context) ?: return false
        return compareVersion(installed, bundled) < 0
    }

    /** 安装结果：已是最新 / 已拉起系统安装器 / 失败 */
    sealed class InstallLaunch {
        data object UpToDate : InstallLaunch()
        data object Started : InstallLaunch()
        data class Failed(val reason: String) : InstallLaunch()
    }

    /**
     * 引导安装内置 Provider：解出 assets 里的 APK 到 cache，经 FileProvider 授权给系统安装器。
     * 返回 Started 后用户仍需在系统安装界面确认；安装完成回到前台时调用方应 refresh 重新检测。
     */
    fun install(context: Context): InstallLaunch = runCatching {
        if (!needsInstall(context)) return InstallLaunch.UpToDate

        val apkDir = File(context.cacheDir, "provider").apply { mkdirs() }
        val apkFile = File(apkDir, "provider.apk")
        context.assets.open(APK_ASSET).use { input ->
            apkFile.outputStream().use { output -> input.copyTo(output) }
        }

        // targetSdk 24 起禁止 file:// 外传，必须走 FileProvider 并临时授权读
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        InstallLaunch.Started
    }.getOrElse { e ->
        Log.e(TAG, "拉起 Provider 安装失败", e)
        InstallLaunch.Failed(e.message ?: "无法拉起安装器")
    }

    // ==================== 绑定 / 调用 ====================

    /**
     * 确保已绑定（并发安全）：已连接直接返回，否则在超时内尝试 bind。
     * Provider 未安装时返回 null，不抛异常。
     */
    suspend fun ensureBound(context: Context = requireNotNull(appContext) { "ProviderManager 未初始化" }): IDeviceProvider? {
        provider?.let { return it }
        if (!isInstalled(context)) return null

        return bindMutex.withLock {
            provider?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(BIND_TIMEOUT_MS) { bindAwait(context.applicationContext) }
            }
        }
    }

    /** 绑定并挂起等待回调；超时由 [ensureBound] 的 withTimeoutOrNull 兜底 */
    private suspend fun bindAwait(ctx: Context): IDeviceProvider? = suspendCancellableCoroutine { cont ->
        appContext = ctx
        connecting = true
        val intent = Intent(ACTION).setPackage(PACKAGE)
        val ok = runCatching {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        if (!ok) {
            connecting = false
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // 轮询等待 onServiceConnected 写入代理（ServiceConnection 回调在 binder 线程，
        // 这里用轻量轮询而非再挂一个回调列表，避免连接状态多份来源）
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < BIND_TIMEOUT_MS) {
            val p = provider
            if (p != null) {
                connecting = false
                cont.resume(p)
                return@suspendCancellableCoroutine
            }
            if (!connecting) break // onNullBinding 等异常路径
            Thread.sleep(50)
        }
        connecting = false
        cont.resume(null)
    }

    /**
     * 带超时与断连兜底的远程调用：Provider 不可用、超时、DeadObject 一律返回 null。
     * 调用方按“不可用”处理即可（例如回落为「请先安装/授权服务组件」提示）。
     */
    suspend fun <T> call(
        context: Context = requireNotNull(appContext) { "ProviderManager 未初始化" },
        timeoutMs: Long = 5_000L,
        block: suspend (IDeviceProvider) -> T,
    ): T? {
        val remote = ensureBound(context) ?: return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                runCatching { block(remote) }.getOrElse { e ->
                    // DeadObjectException / RemoteException：失效代理并等下次重绑
                    Log.w(TAG, "Provider 调用失败：${e.message}")
                    provider = null
                    _connected.value = false
                    null
                }
            }
        }
    }

    /** 主动解绑并清空代理（进程退出或 Provider 被卸载时调用） */
    fun release() {
        val ctx = appContext ?: return
        runCatching { ctx.unbindService(connection) }
        provider = null
        _connected.value = false
    }

    /** 版本比较：按 '.' 分段，缺省段补 0；非数字段按 0 处理 */
    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
