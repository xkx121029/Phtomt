package com.phoneagent.device.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.TransactionTooLargeException
import android.util.Log
import androidx.core.content.FileProvider
import com.phoneagent.provider.IDeviceProvider
import com.phoneagent.provider.ProviderApi
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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

    private val _lastError = MutableStateFlow<String?>(null)

    /** 最近一次通道异常的中文说明（UI 直接展示，避免「显示已连接却不能用」） */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 分片拉取的单片大小：远低于 Binder 约 1MB 的事务上限 */
    private const val PAYLOAD_CHUNK = 128 * 1024

    /** binder 死亡回调：Provider 进程被杀时清空代理并解绑，下次 [call] 才能重新绑定 */
    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Provider binder 已断开，等待重连")
        invalidate("服务组件连接已断开")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = IDeviceProvider.Stub.asInterface(service)
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            provider = remote
            _connected.value = true
            _lastError.value = null
            Log.i(TAG, "已绑定 Provider（apiVersion=${runCatching { remote?.apiVersion() }.getOrNull()}）")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            provider = null
            _connected.value = false
            _lastError.value = "服务组件进程已断开"
        }

        override fun onBindingDied(name: ComponentName?) {
            invalidate("服务组件绑定已失效")
        }

        override fun onNullBinding(name: ComponentName?) {
            provider = null
            _connected.value = false
            _lastError.value = "服务组件拒绝绑定（签名或版本不匹配）"
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
        // 代理存在但 binder 已死（Provider 进程被回收）：先真正解绑，否则系统认为仍连着，
        // 再次 bindService 不会回调 onServiceConnected，代理会一直是空
        provider?.let { p ->
            if (runCatching { p.asBinder().isBinderAlive }.getOrDefault(false)) return p
            invalidate("服务组件连接已失效")
        }
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
        // 清掉可能残留的旧绑定：Android 的 bindService 是引用计数，
        // 同一 connection 重复绑定不会重新回调 onServiceConnected
        runCatching { ctx.unbindService(connection) }
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
     *
     * 关键区分：**只有通道真的死了才清代理**。业务层异常（尤其是回包过大导致的
     * TransactionTooLargeException）说明 Provider 活得好好的，若也清代理并保留旧绑定，
     * 之后既拿不到回调也发不出请求 —— 这正是「显示已连接但命令全废」的成因。
     */
    suspend fun <T> call(
        context: Context = requireNotNull(appContext) { "ProviderManager 未初始化" },
        timeoutMs: Long = 5_000L,
        block: suspend (IDeviceProvider) -> T,
    ): T? {
        val remote = ensureBound(context) ?: return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                try {
                    val result = block(remote)
                    _lastError.value = null
                    result
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 超时/取消必须原样抛出，否则 withTimeoutOrNull 会失去意义
                    throw e
                } catch (e: Throwable) {
                    if (isTransportDead(e)) {
                        Log.w(TAG, "Provider 通道断开：${e.javaClass.simpleName} ${e.message}")
                        invalidate("服务组件连接已断开")
                    } else {
                        // Provider 仍在，只是这次请求失败（如回包超过 Binder 上限）；
                        // 保留连接，下一次请求照常可用
                        Log.w(TAG, "Provider 调用失败：${e.javaClass.simpleName} ${e.message}")
                        _lastError.value = "调用失败：${e.message ?: e.javaClass.simpleName}"
                    }
                    null
                }
            }
        }
    }

    /**
     * 分片拉取 Provider 暂存的大块数据（截图 / 元素树）。
     * 返回 null 表示 Provider 不支持该接口或数据已失效，调用方自行决定是否回退旧接口。
     *
     * 必须在 [call] 的超时窗口内调用；单片 128KB，40 次往返也远低于默认 5s 预算。
     */
    suspend fun readPayload(remote: IDeviceProvider, kind: Int): ByteArray? {
        val token = runCatching { remote.beginPayload(kind) }.getOrDefault(-1)
        if (token <= 0) return null
        try {
            val length = runCatching { remote.payloadLength(token) }.getOrDefault(-1)
            if (length <= 0) return null
            val out = ByteArrayOutputStream(length)
            var offset = 0
            while (offset < length) {
                val chunk = runCatching { remote.readPayload(token, offset, PAYLOAD_CHUNK) }.getOrNull()
                    ?: return null
                if (chunk.isEmpty()) break
                out.write(chunk)
                offset += chunk.size
            }
            return if (offset >= length) out.toByteArray() else null
        } finally {
            runCatching { remote.endPayload(token) }
        }
    }

    /** 通道是否真的断了：通信异常且不是「请求太大」这类业务性失败 */
    private fun isTransportDead(e: Throwable): Boolean = when (e) {
        is DeadObjectException -> true
        is TransactionTooLargeException -> false
        is RemoteException -> true
        else -> false
    }

    /**
     * 作废当前代理：清空引用 + **真正解绑** + 记录原因。
     * 只清引用而不解绑，系统仍视连接为活动状态，下次 bindService 不会再回调
     * onServiceConnected —— 代理就再也拿不回来了。
     */
    private fun invalidate(reason: String) {
        val ctx = appContext
        provider = null
        _connected.value = false
        _lastError.value = reason
        connecting = false
        if (ctx != null) runCatching { ctx.unbindService(connection) }
    }

    /** 主动解绑并清空代理（进程退出或 Provider 被卸载时调用） */
    fun release() {
        val ctx = appContext ?: return
        runCatching { ctx.unbindService(connection) }
        provider = null
        _connected.value = false
    }

    // ==================== 自检 ====================

    /** 自检单步结果：UI 逐行展示，[ok]=false 时 [detail] 就是原因本身 */
    data class SelfTestStep(val title: String, val ok: Boolean, val detail: String)

    /**
     * 执行通道自检：把「装没装 → 开没开 → 绑没绑 → 树能不能读 → 图能不能截」
     * 拆成五步可观察的结果。
     *
     * 存在的意义是终结「界面说已连接、实际一条命令都发不出去」只能靠猜的排障：
     * 每步给出实际数值（版本号 / apiVersion / 字节数 / 元素个数 / 异常），
     * 结果直接渲染在设置页，不需要连 adb 抓 logcat。
     *
     * [a11yEnabled] 由调用方传入：无障碍开关要从系统设置读，那份实现属于 a11y 包，
     * 本类不反向依赖它。
     */
    suspend fun selfTest(context: Context, a11yEnabled: Boolean): List<SelfTestStep> {
        val steps = mutableListOf<SelfTestStep>()
        val installed = installedVersionName(context)
        val bundled = bundledVersion(context)
        steps += SelfTestStep(
            title = "服务组件安装",
            ok = installed != null && !needsInstall(context),
            detail = when {
                installed == null -> "未安装（随包 v${bundled ?: "未知"}）"
                needsInstall(context) -> "已安装 v$installed，随包 v${bundled ?: "未知"}，需更新"
                else -> "已安装 v$installed（随包同版本）"
            },
        )
        steps += SelfTestStep(
            title = "无障碍服务开关",
            ok = a11yEnabled,
            detail = if (a11yEnabled) "系统设置中已开启" else "未开启：读不到元素树，只能靠截图",
        )

        val remote = ensureBound(context)
        if (remote == null) {
            steps += SelfTestStep(
                title = "AIDL 绑定",
                ok = false,
                detail = lastError.value ?: "绑定超时（服务组件未安装或签名不一致）",
            )
            steps += SelfTestStep("服务侧无障碍实例", false, "未绑定，无法查询")
            steps += SelfTestStep("元素树读取", false, "未绑定，无法读取")
            steps += SelfTestStep("截图读取", false, "未绑定，无法读取")
            return steps
        }

        val api = runCatching { remote.apiVersion() }.getOrNull()
        val alive = runCatching { remote.asBinder().isBinderAlive }.getOrDefault(false)
        steps += SelfTestStep(
            title = "AIDL 绑定",
            ok = api != null && alive,
            detail = "apiVersion=${api ?: "读取失败"} · binderAlive=$alive",
        )
        val remoteEnabled = runCatching { remote.isA11yEnabled() }.getOrNull()
        val remoteConnected = runCatching { remote.isA11yConnected() }.getOrNull()
        steps += SelfTestStep(
            title = "服务侧无障碍实例",
            ok = remoteEnabled == true && remoteConnected == true,
            detail = "开关=${remoteEnabled ?: "读取失败"} · 实例已连接=${remoteConnected ?: "读取失败"}",
        )

        // 元素树：优先分片（与引擎实际路径一致），旧组件才回退一次性接口
        val treeStep = call<SelfTestStep>(context, 20_000L) { r ->
            val sharded = readPayload(r, ProviderApi.KIND_TREE)
            val raw = sharded ?: r.captureScreen()?.toByteArray(Charsets.UTF_8)
            if (raw == null) {
                SelfTestStep("元素树读取", false, "Provider 返回空：无障碍实例未连接")
            } else {
                val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
                val count = json?.optJSONArray("elements")?.length() ?: -1
                val stats = json?.optString("stats").orEmpty()
                SelfTestStep(
                    title = "元素树读取",
                    ok = count > 0,
                    detail = "${if (sharded != null) "分片" else "一次性"} ${raw.size} 字节 · " +
                        "元素 $count" + if (stats.isNotBlank()) " · $stats" else "",
                )
            }
        }
        steps += treeStep ?: SelfTestStep(
            title = "元素树读取",
            ok = false,
            detail = "调用超时或通道异常：${lastError.value ?: "无响应"}",
        )

        val shotStep = call<SelfTestStep>(context, 20_000L) { r ->
            val sharded = readPayload(r, ProviderApi.KIND_SCREENSHOT)
            val size = sharded?.size ?: r.takeScreenshot()?.size ?: -1
            SelfTestStep(
                title = "截图读取",
                ok = size > 0,
                detail = if (size > 0) {
                    "${if (sharded != null) "分片" else "一次性"} $size 字节（${size / 1024} KB）"
                } else {
                    "Provider 返回空：无障碍截图不可用（需 API 30+ 且实例已连接）"
                },
            )
        }
        steps += shotStep ?: SelfTestStep(
            title = "截图读取",
            ok = false,
            detail = "调用超时或通道异常：${lastError.value ?: "无响应"}",
        )
        return steps
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
