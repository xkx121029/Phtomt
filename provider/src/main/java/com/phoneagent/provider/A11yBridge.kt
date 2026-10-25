package com.phoneagent.provider

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import com.phoneagent.provider.a11y.ProviderAccessibilityService
import com.phoneagent.provider.a11y.ProviderActionExecutor
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * 无障碍通道 Bridge：IDeviceProvider 中无障碍部分的实现载体。
 *
 * 内部持有 ProviderAccessibilityService（无障碍服务实例，由系统绑定回调注入）与
 * 绑定在该实例上的 ProviderActionExecutor，对外提供
 * 读屏（元素树 JSON / 截图 PNG）与执行（手势 / 节点操作 / 全局键）能力。
 * 由 [ProviderService.onCreate] 初始化；所有方法在 Default 调度器上执行。
 *
 * 服务实例的生命周期由系统管理（无障碍开关切换/服务被杀都会置空 [ProviderAccessibilityService.instance]），
 * 因此每个入口都按「实例为 null → 通道未连接」兜底，绝不缓存过期实例。
 */
object A11yBridge {

    private val lock = Any()

    /** 与当前服务实例绑定的执行器；服务重连（新实例）时自动重建 */
    private var cachedExecutor: ProviderActionExecutor? = null

    fun init(appContext: Context) {
        // 无初始化依赖：服务实例由系统绑定 ProviderAccessibilityService 后在 onServiceConnected 注入
    }

    /** 无障碍服务是否已在系统设置中开启（开关开着但实例可能尚未连接，两态分开报） */
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val pkg = context.packageName
        val cls = ProviderAccessibilityService::class.java.name
        // 系统设置里手动开启通常写入全量形式（pkg/pkg.cls），但部分 ROM、备份恢复或
        // 第三方工具会写短形式（pkg/.cls）或大小写不一致——逐条反解成 ComponentName
        // 再比对，避免「开关明明开着却检测为关」
        return enabled.split(':').any { entry ->
            val cn = ComponentName.unflattenFromString(entry.trim()) ?: return@any false
            cn.packageName.equals(pkg, ignoreCase = true) &&
                cn.className.equals(cls, ignoreCase = true)
        }
    }

    /** 无障碍服务实例是否已连接（可执行） */
    fun isConnected(): Boolean = ProviderAccessibilityService.instance != null

    private fun service(): ProviderAccessibilityService? = ProviderAccessibilityService.instance

    private fun executor(): ProviderActionExecutor? {
        val svc = service() ?: return null
        synchronized(lock) {
            cachedExecutor?.takeIf { it.service === svc }?.let { return it }
            return ProviderActionExecutor(svc).also { cachedExecutor = it }
        }
    }

    /** 服务未连接时的统一失败回执 */
    private fun notConnected() = ActionResult(false, "无障碍服务未连接")

    // ==================== 感知 ====================

    suspend fun captureScreenJson(): String? = service()?.captureScreenJson()

    /** 无障碍截图压成 PNG 字节（Bitmap 由本处持有，用完即回收）；不支持或失败返回 null */
    suspend fun takeScreenshotPng(): ByteArray? {
        val bitmap = service()?.takeScreenshotBitmap() ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)) return null
            bos.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    /** 落点审计 JSON：{label,viewId,className,clickable}，label/viewId 缺省时省键；无法审计返回 null */
    suspend fun hitTestJson(x: Int, y: Int): String? {
        val hit = executor()?.hitTest(x, y) ?: return null
        return JSONObject().apply {
            hit.label?.let { put("label", it) }
            hit.viewId?.let { put("viewId", it) }
            put("className", hit.className)
            put("clickable", hit.clickable)
        }.toString()
    }

    /** 目标控件此刻的中心点；找不到返回 null */
    suspend fun liveCenter(selector: A11ySelector): IntArray? {
        val center = executor()?.liveCenter(selector) ?: return null
        return intArrayOf(center.first, center.second)
    }

    // ==================== 执行 ====================

    suspend fun click(x: Int, y: Int): ActionResult = executor()?.click(x, y) ?: notConnected()

    suspend fun longClick(x: Int, y: Int, durationMs: Int): ActionResult =
        executor()?.longClick(x, y, durationMs) ?: notConnected()

    suspend fun clickNode(selector: A11ySelector, longClick: Boolean, durationMs: Int): ActionResult =
        executor()?.clickNode(selector, longClick, durationMs) ?: notConnected()

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ActionResult =
        executor()?.swipe(x1, y1, x2, y2, durationMs) ?: notConnected()

    suspend fun scrollContainer(direction: String): ActionResult =
        executor()?.scrollContainer(direction) ?: notConnected()

    /** 全局按键：action 常量（GLOBAL_ACTION_*）由主应用经 AIDL 传入，直接透传执行 */
    fun globalAction(action: Int): Boolean = executor()?.globalAction(action) ?: false

    /** x/y < 0 表示无坐标线索，仅靠元素树定位 */
    suspend fun typeText(text: String, x: Int, y: Int): ActionResult =
        executor()?.typeText(text, x, y) ?: notConnected()

    suspend fun launchApp(packageName: String): ActionResult =
        executor()?.launchApp(packageName) ?: notConnected()

    suspend fun openUri(uri: String, pkg: String?): ActionResult =
        executor()?.openUri(uri, pkg) ?: notConnected()

    suspend fun openSettingsAction(action: String): ActionResult =
        executor()?.openSettingsAction(action) ?: notConnected()

    suspend fun launchAssistant(text: String?): ActionResult =
        executor()?.launchAssistant(text) ?: notConnected()

    suspend fun handoffToAssistant(text: String?): String =
        executor()?.handoffToAssistant(text) ?: "无障碍服务未连接，无法传话"
}
