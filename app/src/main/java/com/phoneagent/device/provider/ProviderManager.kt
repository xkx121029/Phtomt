// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
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

    /** Provider 服务的完整类名（与 :provider 清单里的 `.ProviderService` 一致） */
    private const val SERVICE_CLASS = "com.phoneagent.provider.ProviderService"

    /** 内置 APK 与版本文件在 assets 里的位置（经 :app:syncProviderAssets 同步进仓库） */
    private const val APK_ASSET = "provider/provider.apk"
    private const val VERSION_ASSET = "provider/version.txt"

    /** 默认绑定超时：3 秒足够系统拉起 Provider 进程，超时视为不可用而不是无限等 */
    private const val BIND_TIMEOUT_MS = 3_000L

    /**
     * 单种绑定方式的等待时长。多种方式串行尝试，因此每次不能等满全局超时，
     * 否则总耗时会随方式数翻倍，把调用方（引擎每步取能力快照）拖住。
     */
    private const val ATTEMPT_TIMEOUT_MS = 1_200L

    private val bindMutex = Mutex()

    @Volatile
    private var provider: IDeviceProvider? = null

    @Volatile
    private var connecting = false

    /**
     * 最近一次绑定的拒绝原因（[ServiceConnection.onNullBinding] 写入）。
     * 主要覆盖「签名级权限不匹配」这类**不抛异常、只是不回调**的失败：
     * 此时 bindService 返回 true，但 onServiceConnected 永远不来，
     * 光看返回值会误判成"正在连接中"。
     */
    @Volatile
    private var bindRejectReason: String? = null

    private var appContext: Context? = null

    private val _connected = MutableStateFlow(false)

    /** Provider 是否已连上（UI 可观察） */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** 最近一次通道异常的中文说明（UI 直接展示，避免「显示已连接却不能用」） */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _apiVersion = MutableStateFlow<Int?>(null)

    /**
     * 已绑定组件报告的 AIDL 契约版本（未绑定为 null）。
     *
     * 兼容判定只看它，不看 versionName（见 [ProviderContract]）：绑定回调里刷新，断开即作废。
     */
    val apiVersion: StateFlow<Int?> = _apiVersion.asStateFlow()

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
            val api = runCatching { remote?.apiVersion() }.getOrNull()
            _apiVersion.value = api
            Log.i(TAG, "已绑定 Provider（apiVersion=$api，本应用需要 ${ProviderContract.REQUIRED_API_VERSION}）")
            // 契约不匹配在这里就说清楚：缺方法的调用只会被 binder 吞成 null，
            // 让用户对着"已连接"和一串没有原因的空结果干瞪眼
            ProviderContract.mismatchReason(api)?.let { _lastError.value = it }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            provider = null
            _connected.value = false
            _apiVersion.value = null
            _lastError.value = "服务组件进程已断开"
        }

        override fun onBindingDied(name: ComponentName?) {
            invalidate("服务组件绑定已失效")
        }

        override fun onNullBinding(name: ComponentName?) {
            provider = null
            _connected.value = false
            _apiVersion.value = null
            connecting = false
            bindRejectReason = "服务返回空 binder（签名或版本不匹配）"
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
     * 是否需要（重新）安装随包组件。
     *
     * 判据优先级（详见 [ProviderContract]）：
     * 1. 未安装 → true；
     * 2. **接口版本已知**（已绑上）→ 只有组件 apiVersion 低于本应用才需要安装。
     *    比本应用新时装回去只会被系统拒降级，正确出路是升级主应用，所以这里返回 false；
     * 3. 接口版本未知（还没绑上）→ 退回 versionName **相等**判定：与随包不一致就提示安装。
     *
     * 刻意不再按 versionName 大小排序：号段被回拨过（曾是 200 万段，后回落 9xxx 段），
     * 排序会把"装着更新的组件"判成"已是最新"，把"装着旧组件"判成"需更新却装不上"。
     */
    fun needsInstall(context: Context): Boolean {
        if (installedVersionName(context) == null) return true
        val live = _apiVersion.value
        if (live != null) return ProviderContract.judge(live) is ProviderContract.Verdict.ProviderOutdated
        val bundled = bundledVersion(context) ?: return false
        return installedVersionName(context) != bundled
    }

    /** 安装结果：已是最新 / 已拉起系统安装器 / 失败 */
    sealed class InstallLaunch {
        data object UpToDate : InstallLaunch()
        data object Started : InstallLaunch()
        data class Failed(val reason: String) : InstallLaunch()
    }

    /** 是否已获得「安装未知应用」权限（Android 8.0 起按来源应用单独授权，minSdk 26 起恒定可用） */
    fun canInstallPackages(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** 跳「安装未知应用」授权页（按本应用单独授权） */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 引导安装内置 Provider：解出 assets 里的 APK 到 cache，经 FileProvider 授权给系统安装器。
     * 返回 Started 后用户仍需在系统安装界面确认；安装完成回到前台时调用方应 refresh 重新检测。
     */
    fun install(context: Context): InstallLaunch = runCatching {
        if (!needsInstall(context)) return InstallLaunch.UpToDate
        // 装着的组件比本应用新：装回随包组件在系统侧就是降级，安装器会静默拒绝。
        // 与其让用户对着"没反应"点第二次，不如直接说清出路是升级本应用。
        if (ProviderContract.judge(_apiVersion.value) is ProviderContract.Verdict.AppOutdated) {
            return InstallLaunch.Failed(ProviderContract.mismatchReason(_apiVersion.value).orEmpty())
        }
        // 没有「安装未知应用」授权时安装器会静默拒绝，先跳授权页，别让用户对着黑屏猜。
        // 文案不写「再点一次」：引导页会在返回时自动续装，其余入口各有自己的续装逻辑
        if (!canInstallPackages(context)) {
            openInstallPermissionSettings(context)
            return InstallLaunch.Failed("需要先允许本应用安装未知应用，已为你打开系统设置页")
        }

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
        // 别让「打开方式」弹出来：设备上常装有同样声明能打开 APK 的应用（文件管理器、浏览器），
        // 实测模拟器上就会弹「打开方式：Happy Agent / 悦览 / 应用安装器」，选错等于白跑一趟。
        // 能解析到系统安装器就锁定它，解析不到才交回系统自选。
        val installerPkg = context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { pkg -> pkg.contains("packageinstaller", ignoreCase = true) }
        if (installerPkg != null) intent.setPackage(installerPkg)
        context.startActivity(intent)
        InstallLaunch.Started
    }.getOrElse { e ->
        Log.e(TAG, "拉起 Provider 安装失败", e)
        InstallLaunch.Failed(e.message ?: "无法拉起安装器")
    }

    // ==================== 通知权限 ====================

    /**
     * 服务组件是否已获得通知权限。
     *
     * Provider 是无 UI 应用，自身弹不出 Android 13+ 的运行时授权框，而它的两条通知
     * （无线调试配对码输入、看门狗常驻）都依赖该权限；未授权时 Provider 侧静默跳过，
     * 表现为「配对流程没有任何提示」。授权入口只能由主应用提供。
     */
    fun providerNotificationGranted(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            runCatching {
                context.packageManager.checkPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    PACKAGE,
                ) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

    /** 跳服务组件的应用通知设置页（没有该页的 ROM 回退到应用信息页） */
    fun openNotificationSettings(context: Context) {
        val direct = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$PACKAGE"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(fallback) }
    }

    /**
     * 借内置 Shizuku 的 shell uid 静默授予 Provider 通知权限（等价 `pm grant`）。
     * 通道不可用或命令失败返回 false，调用方应回落到 [openNotificationSettings] 让用户手动开。
     */
    suspend fun grantNotificationViaShizuku(context: Context): Boolean {
        val executed = call(context, timeoutMs = 8_000L) { remote ->
            remote.execShizuku("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        }?.success == true
        // 命令成功不等于权限生效：回读一次真实状态作为唯一凭据
        return executed && providerNotificationGranted(context)
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
        val attempts = listOf(
            BindAttempt("隐式action", Intent(ACTION).setPackage(PACKAGE), Context.BIND_AUTO_CREATE),
            BindAttempt(
                "显式组件",
                Intent().setComponent(ComponentName(PACKAGE, SERVICE_CLASS)),
                Context.BIND_AUTO_CREATE,
            ),
            BindAttempt(
                "显式组件+IMPORTANT",
                Intent().setComponent(ComponentName(PACKAGE, SERVICE_CLASS)),
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            ),
        )
        val failures = mutableListOf<String>()
        for (attempt in attempts) {
            // 清掉可能残留的旧绑定：bindService 是引用计数，同一 connection 重复绑定
            // 不会再回调 onServiceConnected
            runCatching { ctx.unbindService(connection) }
            provider = null
            bindRejectReason = null
            connecting = true

            val error = runCatching {
                ctx.bindService(attempt.intent, connection, attempt.flags)
            }.exceptionOrNull()
            if (error != null) {
                failures += "${attempt.label} 抛异常（${error.javaClass.simpleName}）：${error.message}"
                continue
            }

            // 轮询等待 onServiceConnected 写入代理（回调在 binder 线程，
            // 这里用轻量轮询而非再挂一个回调列表，避免连接状态多份来源）
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < ATTEMPT_TIMEOUT_MS) {
                val p = provider
                if (p != null) {
                    connecting = false
                    cont.resume(p)
                    return@suspendCancellableCoroutine
                }
                // onNullBinding 会写 bindRejectReason 并把 connecting 置 false
                if (!connecting) break
                Thread.sleep(50)
            }
            failures += "${attempt.label} 未回调${bindRejectReason?.let { "（$it）" }.orEmpty()}"
        }

        connecting = false
        // 全部失败：逐条原因写进 lastError，设置页自检可直接展示，
        // 不再只有一句「绑定超时」而无从定位
        _lastError.value = "无法绑定服务组件：" + failures.joinToString("；")
        cont.resume(null)
    }

    /** 一种绑定方式（标签用于失败原因，便于在设置页定位是哪条路径不通） */
    private data class BindAttempt(val label: String, val intent: Intent, val flags: Int)

    /**
     * 带超时与断连兜底的远程调用：Provider 不可用、超时、DeadObject 一律返回 null。
     * 调用方按“不可用”处理即可（例如回落为「请先安装/授权服务组件」提示）。
     *
     * 关键区分：**只有通道真的死了才清代理**。业务层异常（尤其是回包过大导致的
     * TransactionTooLargeException）说明 Provider 活得好好的，若也清代理并保留旧绑定，
     * 之后既拿不到回调也发不出请求 —— 这正是「显示已连接但命令全废」的成因。
     */
    // 绑定不上、契约版本不匹配、调用抛错是三种互不相干的失败，各自早退比串成 else-if 更好读
    @Suppress("ReturnCount")
    suspend fun <T> call(
        context: Context = requireNotNull(appContext) { "ProviderManager 未初始化" },
        timeoutMs: Long = 5_000L,
        block: suspend (IDeviceProvider) -> T,
    ): T? {
        val remote = ensureBound(context) ?: return null
        // 契约版本不匹配时明确拒绝：缺方法的调用在 binder 里只会变成 null，
        // 那样调用方看到的是"通道在、结果永远空"，无从判断是组件版本问题。
        ProviderContract.mismatchReason(_apiVersion.value)?.let { reason ->
            Log.w(TAG, "拒绝调用：$reason")
            _lastError.value = reason
            return null
        }
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
        _apiVersion.value = null
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
        _apiVersion.value = null
    }

    /**
     * 彻底重置连接状态，供设置页「重新连接」按钮调用。
     *
     * 与 [invalidate] 的区别：这里连 [lastError] 与 [bindRejectReason] 一起清空，
     * 把所有残留状态归零。用户在「改了签名 / 重装服务组件 / 从系统设置里重新启用」之后
     * 需要一个干净起点，否则旧的错误信息会一直挂在那里，看起来像"修了也没用"。
     *
     * 不重建 [connection] 对象：Android 的 unbindService 会把旧连接彻底摘掉，
     * 复用同一个 connection 实例重新 bindService 是合法且更省事的做法。
     */
    fun resetConnection() {
        val ctx = appContext
        provider = null
        connecting = false
        bindRejectReason = null
        _connected.value = false
        _lastError.value = null
        if (ctx != null) runCatching { ctx.unbindService(connection) }
    }

    // ==================== 绑定诊断 ====================

    /**
     * 绑定前的环境诊断：把「为什么绑不上」拆成可判定的事实。
     *
     * 覆盖实测中最常见的四类失败，且都不依赖 logcat：
     * 1. 组件根本没装 / 装了但不是我们的包（山寨同名应用）；
     * 2. 签名不一致 —— signature 级 BIND_PROVIDER 会直接拒绝，且**不抛异常**，
     *    bindService 返回 true 却永远收不到 onServiceConnected，最容易被误判为"卡住"；
     * 3. 服务组件被系统停用（disable）、或用户手动停用；
     * 4. 服务类名解析不到（Provider 换了包名/类名，主应用还在用旧常量）。
     */
    data class BindDiagnosis(
        val installed: Boolean,
        val installedVersion: String?,
        val bundledVersion: String?,
        val sameSignature: Boolean,
        val componentEnabled: Boolean,
        val serviceResolvable: Boolean,
        val problems: List<String>,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * 逐项检查绑定前置条件。返回的诊断可直接渲染在设置页，
     * [problems] 为空才说明「绑定该成功」，否则就是绑定失败的确切原因。
     */
    // 逐个前置条件串成一条诊断链；拆成多个函数会把「同一份问题清单」撕成多段，留待绑定流程统一重构
    @Suppress("CyclomaticComplexMethod")
    fun diagnose(context: Context): BindDiagnosis {
        val pm = context.packageManager
        val problems = mutableListOf<String>()

        val pkgInfo = runCatching { pm.getPackageInfo(PACKAGE, 0) }.getOrNull()
        val installed = pkgInfo != null
        if (!installed) {
            problems += "服务组件未安装（随包 v${bundledVersion(context) ?: "未知"}）"
        }

        // 签名比对：API 28+ 用 GET_SIGNING_CERTIFICATES（GET_SIGNATURES 已废弃），
        // API 26/27 只能用旧的 GET_SIGNATURES —— 直接用新常量会在 26/27 上抛 NoSuchFieldError
        val sameSignature = installed && runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val self = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ).signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                val other = pm.getPackageInfo(
                    PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ).signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                self != null && other != null && self.contentEquals(other)
            } else {
                @Suppress("DEPRECATION")
                val self = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()?.toByteArray()
                @Suppress("DEPRECATION")
                val other = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()?.toByteArray()
                self != null && other != null && self.contentEquals(other)
            }
        }.getOrDefault(false)
        if (installed && !sameSignature) {
            problems += "签名不一致：服务组件与主应用不是同一个证书签的，" +
                "signature 级绑定权限会直接拒绝（用同一次构建产物安装，或先用同证书重装服务组件）"
        }

        // 组件是否被停用（含用户在设置里手动停用）
        val component = ComponentName(PACKAGE, SERVICE_CLASS)
        val componentEnabled = runCatching {
            val info = pm.getServiceInfo(component, 0)
            info.enabled && info.applicationInfo?.enabled == true
        }.getOrDefault(false)
        if (installed && !componentEnabled) {
            problems += "服务组件被停用（应用或服务在系统设置里处于禁用状态）"
        }

        // 服务类名能否解析：Provider 换了类名/包名时这里是 false
        val serviceResolvable = runCatching {
            pm.queryIntentServices(Intent(ACTION).setPackage(PACKAGE), 0).isNotEmpty()
        }.getOrDefault(false)
        if (installed && !serviceResolvable) {
            problems += "服务组件里找不到 $ACTION（版本过旧或安装包损坏，请更新服务组件）"
        }

        return BindDiagnosis(
            installed = installed,
            installedVersion = pkgInfo?.versionName,
            bundledVersion = bundledVersion(context),
            sameSignature = sameSignature,
            componentEnabled = componentEnabled,
            serviceResolvable = serviceResolvable,
            problems = problems,
        )
    }

    // ==================== 自检 ====================

    /** 自检单步结果：UI 逐行展示，[ok]=false 时 [detail] 就是原因本身 */
    data class SelfTestStep(val title: String, val ok: Boolean, val detail: String)

    /**
     * 执行通道自检：把「装没装 → 开没开 → 绑没绑 → 注入命令能不能跑 → 树能不能读 → 图能不能截」
     * 拆成逐步可观察的结果。
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
            ok = installed != null,
            detail = when {
                installed == null -> "未安装（随包 v${bundled ?: "未知"}）"
                needsInstall(context) -> "已安装 v$installed，随包 v${bundled ?: "未知"}：接口版本不兼容，需更新组件"
                else -> "已安装 v$installed（随包 v${bundled ?: "未知"}）"
            },
        )
        steps += SelfTestStep(
            title = "无障碍服务开关",
            ok = a11yEnabled,
            detail = if (a11yEnabled) "系统设置中已开启" else "未开启：读不到元素树，只能靠截图",
        )

        // 绑定前置诊断：签名/停用/类名解析这些「不抛异常但绑不上」的原因都在这里
        val diagnosis = diagnose(context)
        steps += SelfTestStep(
            title = "绑定前置条件",
            ok = diagnosis.ok,
            detail = if (diagnosis.ok) {
                "签名一致 · 组件启用 · 服务可解析"
            } else {
                diagnosis.problems.joinToString("；")
            },
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
        // 契约版本是"能不能调通"的唯一判据（版本号只用于展示）：不相等就是缺接口或多接口
        val contract = ProviderContract.judge(api)
        steps += SelfTestStep(
            title = "接口契约版本",
            ok = contract is ProviderContract.Verdict.Compatible,
            detail = if (api == null) {
                "读取失败：${lastError.value ?: "无响应"}"
            } else {
                "组件 apiVersion=$api · 本应用需要 ${ProviderContract.REQUIRED_API_VERSION}" +
                    (ProviderContract.mismatchReason(api)?.let { "；$it" } ?: "（一致）")
            },
        )
        val remoteEnabled = runCatching { remote.isA11yEnabled() }.getOrNull()
        val remoteConnected = runCatching { remote.isA11yConnected() }.getOrNull()
        steps += SelfTestStep(
            title = "服务侧无障碍实例",
            ok = remoteEnabled == true && remoteConnected == true,
            detail = "开关=${remoteEnabled ?: "读取失败"} · 实例已连接=${remoteConnected ?: "读取失败"}",
        )

        // ---- 注入通道（系统直连真正依赖的一环）----
        // 之前自检只测「装/开/绑/读树/截图」，恰好漏掉了 shell —— 而闹钟/日历/应用管控/
        // 命令行/个人数据全部走它，坏了在自检里完全看不出来。
        val shizukuState = runCatching { remote.shizukuState() }.getOrNull()
        val shizukuText = when (shizukuState) {
            2 -> "READY（已授权）"
            1 -> "PERMISSION_DENIED（已运行但未授权：点卡片里的「请求授权」）"
            0 -> "UNAVAILABLE（未运行：点卡片里的「一键启动」经无线 ADB 拉起）"
            else -> "读取失败：${lastError.value ?: "无响应"}"
        }
        steps += SelfTestStep(
            title = "内置 Shizuku 状态",
            ok = shizukuState == 2,
            detail = shizukuText,
        )
        val shellProbe = call(context, 35_000L) { r -> r.execShizuku("id -u") }
        steps += SelfTestStep(
            title = "注入命令实跑",
            ok = shellProbe?.success == true,
            detail = when {
                shellProbe == null -> "调用超时或通道异常：${lastError.value ?: "无响应"}"
                !shellProbe.success -> "失败：${shellProbe.reason.ifBlank { "无原因" }}"
                else -> "id -u → ${shellProbe.output.trim().ifBlank { "无输出" }}（exit=${shellProbe.exitCode}）"
            },
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

        // ---- Termux 通道：装没装 → 授权落在谁身上 → 能否实跑 ----
        val termuxInstalled = runCatching { remote.termuxInstalled() }.getOrNull() == true
        val termuxGranted = runCatching { remote.termuxPermissionGranted() }.getOrNull() == true
        steps += SelfTestStep(
            title = "Termux 安装",
            ok = termuxInstalled,
            detail = if (termuxInstalled) "已安装 com.termux" else "未安装：Termux 通道需要先安装 Termux 应用",
        )
        steps += SelfTestStep(
            title = "Termux 执行权限",
            ok = termuxGranted,
            detail = if (termuxGranted) {
                "服务组件已持有 com.termux.permission.RUN_COMMAND"
            } else {
                // 授权对象是 Provider 而非主应用：主应用里申请不到这个权限
                "服务组件未持有 RUN_COMMAND：点卡片里的「授予执行权限」（经 Shizuku 静默授予）"
            },
        )
        if (termuxInstalled && termuxGranted) {
            val probe = call(context, 20_000L) { r -> r.termuxProbe() }
            steps += SelfTestStep(
                title = "Termux 实跑探测",
                ok = probe?.success == true,
                detail = when {
                    probe == null -> "调用超时或通道异常：${lastError.value ?: "无响应"}"
                    probe.success -> probe.output.ifBlank { "探测通过" }
                    else -> probe.reason
                },
            )
        } else {
            steps += SelfTestStep("Termux 实跑探测", false, "前置条件未满足，跳过")
        }
        return steps
    }

}
