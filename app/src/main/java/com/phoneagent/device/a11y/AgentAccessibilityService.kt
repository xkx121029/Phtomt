package com.phoneagent.device.a11y

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import com.phoneagent.core.log.AppLog
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.provider.ProviderApi
import com.phoneagent.domain.model.ScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程无障碍会话（主应用侧 facade）。
 *
 * 无障碍能力已整体迁入独立 Provider 应用（`com.phoneagent.provider`），本类不再继承
 * AccessibilityService，只保留原有公开入口：上层（AgentEngine、execution 下的执行器、CapabilityManager 等）
 * 继续以 `AgentAccessibilityService.instance` 的形态访问，内部一律经 [ProviderManager] 调 AIDL。
 *
 * [instance] 的语义：Provider 已绑定且其无障碍服务已连接时为非 null（惰性构造），否则 null。
 * 实例由 [install] 启动的观察协程维持：应用启动时由 Application 注入 appContext，
 * 之后按秒级节奏查询 Provider 的 isA11yConnected，连接/断开即时反映到 [instance]。
 */
class AgentAccessibilityService private constructor(
    /** 应用上下文：每次远程调用都显式传给 ProviderManager，不依赖 ProviderManager 的默认初始化 */
    val applicationContext: Context,
) {

    companion object {
        private const val TAG = "AgentAccessibilityService"

        /** Provider 无障碍服务的完整类名（与 provider 模块里 ProviderAccessibilityService 一致） */
        private const val PROVIDER_SERVICE_CLASS = "com.phoneagent.provider.a11y.ProviderAccessibilityService"

        /** Provider 已连接时的状态轮询间隔：服务被系统杀掉后要尽快把 instance 置空，避免上层继续空转 */
        private const val ACTIVE_POLL_MS = 1_000L

        /** Provider 未安装时的重试间隔：不反复做包查询与绑定尝试 */
        private const val IDLE_POLL_MS = 3_000L

        /** 轻量状态查询的超时：超时视为「暂时查不到」，保持现状而不是误判断开 */
        private const val PROBE_TIMEOUT_MS = 2_000L

        /** 读屏超时：元素树遍历在 Provider 侧完成，分片回传要多次往返；超时按「无障碍不可用」降级 */
        private const val CAPTURE_TIMEOUT_MS = 10_000L

        /** 截图超时：Provider 自身 2.5s 超时 + 分片回传（40 次往返），留足余量 */
        private const val SCREENSHOT_TIMEOUT_MS = 12_000L

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /** Agent 是否正在执行任务：Provider 已不消费该标志，保留仅为源码兼容 */
        @Volatile
        var agentRunning = false

        private val watcherStarted = AtomicBoolean(false)

        private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * 应用级注入：记下 appContext 并启动 Provider 状态观察（幂等）。
         * 由 Application.onCreate 调用；不在此处绑定，观察协程会按需触发 ProviderManager 绑定。
         */
        fun install(context: Context) {
            val ctx = context.applicationContext
            if (!watcherStarted.compareAndSet(false, true)) return
            watcherScope.launch { watch(ctx) }
        }

        /** 观察 Provider 绑定与无障碍连接状态，维护 [instance]（[lastTreeStats] 随各实例的读屏更新） */
        private suspend fun watch(context: Context) {
            while (true) {
                if (!ProviderManager.isInstalled(context)) {
                    instance = null
                    delay(IDLE_POLL_MS)
                    continue
                }
                val connected = ProviderManager.call(context, PROBE_TIMEOUT_MS) { it.isA11yConnected() }
                when (connected) {
                    true -> if (instance == null) {
                        instance = AgentAccessibilityService(context)
                        AppLog.i(TAG, "Provider 无障碍通道已连接")
                    }

                    false -> instance = null
                    // binder 暂时不可用：只有确认 Provider 代理已失效才置空，避免瞬断误伤
                    null -> if (!ProviderManager.connected.value) instance = null
                }
                delay(ACTIVE_POLL_MS)
            }
        }

        /** 无障碍服务是否已在系统设置中开启（同步读系统设置，不能走 AIDL） */
        fun isServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = ComponentName(ProviderManager.PACKAGE, PROVIDER_SERVICE_CLASS)
            // 系统设置里手动开启通常写入全量形式（pkg/pkg.cls），但部分 ROM、备份恢复或
            // 第三方工具会写短形式（pkg/.cls）或大小写不一致——逐条反解成 ComponentName
            // 再比对，避免「开关明明开着却检测为关」
            return enabled.split(':').any { entry ->
                val cn = ComponentName.unflattenFromString(entry.trim()) ?: return@any false
                cn.packageName.equals(expected.packageName, ignoreCase = true) &&
                    cn.className.equals(expected.className, ignoreCase = true)
            }
        }

        /** 跳转无障碍设置页 */
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

    }

    /**
     * 最近一次抓取的统计摘要（由 Provider 的 captureScreen JSON 带回），
     * 元素树为空时由引擎写进任务日志（排查"读不到控件"用）；取不到时为「无统计」。
     */
    @Volatile
    var lastTreeStats: String = "无统计"
        private set

    /**
     * 抓取当前屏幕元素树，生成 [ScreenSnapshot]（语义与原本地实现一致）。
     *
     * 保持同步签名以兼容既有调用点：内部用 runBlocking 等 Provider 回包，
     * 读屏本身是毫秒级操作；协程内调用时优先走 [captureScreenAsync]。
     */
    fun captureScreen(): ScreenSnapshot = runBlocking { captureScreenAsync() }

    /** [captureScreen] 的挂起版本：协程内调用不阻塞所在线程 */
    internal suspend fun captureScreenAsync(): ScreenSnapshot {
        // 元素树同样可能超 Binder 事务上限（长列表 / WebView 页面的 JSON 可达数百 KB 到数 MB），
        // 与截图一样优先走分片拉取；旧版服务组件不支持分片时才回退一次性回包
        val raw = ProviderManager.call(applicationContext, CAPTURE_TIMEOUT_MS) { remote ->
            ProviderManager.readPayload(remote, ProviderApi.KIND_TREE)?.toString(Charsets.UTF_8)
                ?: remote.captureScreen()
        }
        val decoded = SnapshotJsonCodec.decode(raw)
        if (decoded == null) {
            // 空树必须留下证据：否则只能看到「未检测到可交互元素」，分不清是系统不给节点还是被筛选条件挡掉
            if (raw != null) AppLog.w(TAG, "captureScreen JSON 解析失败（${raw.length} 字节）")
            else AppLog.w(
                TAG,
                "元素树读取失败：${ProviderManager.lastError.value ?: "Provider 无回包"}" +
                    if (instance == null) "（无障碍会话未连接）" else "",
            )
            lastTreeStats = "无统计"
            return ScreenSnapshot(packageName = null, missingAccessibility = true)
        }
        lastTreeStats = decoded.stats
        if (decoded.snapshot.elements.isEmpty()) {
            AppLog.w(TAG, "元素树为空：$lastTreeStats")
        }
        return decoded.snapshot
    }

    /**
     * 每步自动截图（不依赖 MediaProjection 屏幕共享）：由 Provider 的无障碍服务执行 takeScreenshot（API 30+）。
     * 结果以 PNG 字节跨进程回传，这里解成 Bitmap。
     */
    fun canScreenshot(): Boolean = android.os.Build.VERSION.SDK_INT >= 30

    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (!canScreenshot()) return null
        // 全屏 PNG 有 2~6MB，直接当 AIDL 回包会超 Binder 事务上限；优先走分片拉取，
        // 只有旧版服务组件（不支持分片）才回退到一次性回包
        val png = ProviderManager.call(applicationContext, SCREENSHOT_TIMEOUT_MS) { remote ->
            ProviderManager.readPayload(remote, ProviderApi.KIND_SCREENSHOT)
                ?: remote.takeScreenshot()
        }
        if (png == null || png.isEmpty()) return null
        return runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()
    }
}
