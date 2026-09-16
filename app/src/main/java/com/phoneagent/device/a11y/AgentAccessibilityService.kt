package com.phoneagent.device.a11y.AgentAccessibilityService

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneagent.feature.adskip.AdSkipperCore
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * 无障碍服务：读取屏幕可交互元素，并执行点击/滑动等手势。
 *
 * 通过 [AgentAccessibilityService.instance] 供 Agent 引擎访问。
 * 未连接时调用 [isServiceEnabled] 检查系统设置中的开关状态。
 * 同时承载内置跳广告功能：在 Agent 空闲且开关开启时，事件驱动地自动点击广告跳过/关闭按钮。
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /** Agent 是否正在执行任务：为 true 时内置跳广告暂停，避免与 Agent 的广告处理冲突 */
        @Volatile
        var agentRunning = false

        /** 无障碍服务是否已在系统设置中开启 */
        fun isServiceEnabled(context: Context): Boolean {
            val expected = context.packageName + "/" + AgentAccessibilityService::class.java.name
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /** 跳转无障碍设置页 */
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsJob: Job? = null

    /** 内置跳广告开关（从 DataStore 读取） */
    @Volatile
    private var autoSkipEnabled = false

    /** 广告扫描节流：避免在内容频繁变化时重复扫描 */
    @Volatile
    private var lastAdScanAt = 0L
    private val adScanInterval = 450L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // 订阅跳广告开关，后台也随设置实时生效
        settingsJob = scope.launch {
            AppSettings(applicationContext).settings.collect { s ->
                autoSkipEnabled = s.autoSkipAds
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 内置跳广告：仅当 Agent 空闲、开关开启，且是窗口/内容变化事件时触发
        if (event == null || agentRunning || !autoSkipEnabled) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val now = System.currentTimeMillis()
        if (now - lastAdScanAt < adScanInterval) return
        lastAdScanAt = now
        // 在后台协程扫描并点击，避免在无障碍事件线程（主线程）做节点树遍历
        scope.launch { maybeSkipAd() }
    }

    /** 扫描当前屏幕，若发现广告目标则在协程中点击，并带冷却去重 */
    private fun maybeSkipAd() {
        val snapshot = captureAdSnapshot()
        val target = AdSkipperCore.find(snapshot) ?: return
        if (AdSkipperCore.isCooldown(target)) return
        AdSkipperCore.recordClick(target)
        scope.launch {
            val result = ActionExecutor(this@AgentAccessibilityService).click(target.x, target.y)
            if (result is ActionExecutor.Result.Success) {
                AdSkipperCore.skippedCount.incrementAndGet()
            }
        }
    }

    /**
     * 供跳广告使用的宽松屏幕快照：遍历所有窗口（可覆盖独立广告/弹窗窗口），
     * 收集所有含可见文本的节点（含不可点击的倒计时角标、弹窗标题），
     * 并排除本应用窗口（悬浮窗），避免误点自身控件。
     */
    fun captureAdSnapshot(): ScreenSnapshot {
        val elements = mutableListOf<UiElement>()
        var counter = 0
        val ownPkg = packageName
        val (w, h) = screenSize()
        try {
            for (win in windows) {
                val root = win.root ?: continue
                // 排除本应用窗口（悬浮窗等）
                if (root.packageName?.toString() == ownPkg) continue
                counter = collectTextNodes(root, elements, counter)
            }
        } catch (_: Exception) {
            // 个别窗口访问失败时忽略，继续用已收集的节点
        }
        return ScreenSnapshot(
            packageName = null,
            screenWidth = w,
            screenHeight = h,
            elements = elements,
        )
    }

    /** 收集所有有可见文本的节点（可点击节点或含文本的叶子节点），供广告 / 青少年模式识别 */
    private fun collectTextNodes(node: AccessibilityNodeInfo, out: MutableList<UiElement>, counter: Int, depth: Int = 0): Int {
        var c = counter
        // 限制递归深度（40 层）和节点总数（800 个），防止 StackOverflow 和性能问题
        if (depth > 40 || c > 800) return c
        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val text = node.text?.toString()
                val cd = node.contentDescription?.toString()
                val clickable = node.isClickable || node.isLongClickable
                // 收集：可点击节点（按钮等），或含文本的叶子节点（倒计时角标、弹窗标题等）
                if ((!text.isNullOrBlank() || !cd.isNullOrBlank()) && (clickable || node.childCount == 0)) {
                    val cls = node.className?.toString() ?: "Unknown"
                    out += UiElement(
                        index = c,
                        className = cls,
                        type = classify(cls),
                        text = text,
                        contentDescription = cd,
                        x = bounds.centerX(),
                        y = bounds.centerY(),
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        clickable = clickable,
                        longClickable = node.isLongClickable,
                        scrollable = node.isScrollable,
                        editable = node.isEditable,
                        viewId = node.viewIdResourceName?.substringAfterLast('/'),
                        packageName = node.packageName?.toString(),
                        isEnabled = node.isEnabled,
                        isVisibleToUser = node.isVisibleToUser,
                        childCount = node.childCount,
                    )
                    c++
                }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                c = collectTextNodes(child, out, c, depth + 1)
                // 每个 getChild 获取的节点只由其调用方回收一次，避免双重 recycle（API 33+ 框架自动回收）
                if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
            }
        }
        return c
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        settingsJob?.cancel()
        scope.cancel()
        instance = null
        // 主动反馈（v2.2.1 八）：无障碍服务被系统杀掉时，提示用户重新开启
        runCatching {
            com.phoneagent.core.notify.ActiveNotifier.notify(
                applicationContext, com.phoneagent.core.notify.ActiveNotifier.ID_A11Y_KILLED,
                "无障碍服务已断开",
                "AI 控制手机的通道被系统关闭了，请重新开启无障碍服务以继续自动操作。",
            )
        }
        super.onDestroy()
    }

    /**
     * 抓取当前屏幕元素树，生成 [ScreenSnapshot]。
     * 若 rootInActiveWindow 为 null（如屏幕锁定），返回空快照并标记缺少无障碍。
     */
    fun captureScreen(): ScreenSnapshot {
        val root = rootInActiveWindow ?: return ScreenSnapshot(
            packageName = null,
            missingAccessibility = true,
        )
        val elements = mutableListOf<UiElement>()
        var counter = 0
        counter = collectElements(root, true, counter, elements)
        val (w, h) = screenSize()
        return ScreenSnapshot(
            packageName = root.packageName?.toString(),
            screenWidth = w,
            screenHeight = h,
            elements = elements,
        )
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val point = android.graphics.Point()
        wm.defaultDisplay.getRealSize(point)
        return point.x to point.y
    }

    private fun collectElements(node: AccessibilityNodeInfo, isRoot: Boolean, counterHolder: Int, out: MutableList<UiElement>, depth: Int = 0): Int {
        var counter = counterHolder
        // 限制递归深度（80 层）和节点总数（500 个），防止 StackOverflow 和性能问题
        if (depth > 80 || counter > 500) return counter
        val isInteractive = node.isClickable ||
            node.isScrollable ||
            node.isEditable ||
            node.isLongClickable
        if (!isRoot && isInteractive && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val cls = node.className?.toString() ?: "Unknown"
                out += UiElement(
                    index = counter,
                    className = cls,
                    type = classify(cls),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    isSelected = node.isSelected,
                    currentValue = if (node.isEditable) node.text?.toString() else null,
                    x = bounds.centerX(),
                    y = bounds.centerY(),
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    viewId = node.viewIdResourceName?.substringAfterLast('/'),
                    packageName = node.packageName?.toString(),
                    isEnabled = node.isEnabled,
                    isVisibleToUser = node.isVisibleToUser,
                    childCount = node.childCount,
                )
                counter++
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                counter = collectElements(child, false, counter, out, depth + 1)
                // 每个 getChild 获取的节点只由其调用方回收一次，避免双重 recycle（API 33+ 框架自动回收）
                if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
            }
        }
        return counter
    }

    /** 每步自动截图（不依赖 MediaProjection 屏幕共享）：改用无障碍服务的 takeScreenshot（API 30+）。
     *  无需额外权限，复用已开启的无障碍通道；结果经 HardwareBuffer → Bitmap 拷贝，可安全复用。 */
    fun canScreenshot(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val cap = serviceInfo?.capabilities ?: return false
        return cap and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
    }

    @SuppressLint("NewApi") // takeScreenshot 系列 API30+，由 canScreenshot() 运行时守卫；此处抑制静态误报
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (!canScreenshot()) return null
        return try {
            suspendCancellableCoroutine { cont ->
                val executor: Executor = Dispatchers.Main.asExecutor()
                try {
                    takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        executor,
                        object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bmp = hardwareToBitmap(screenshot)
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 将无障碍截图结果（HardwareBuffer）转为可复用的 ARGB Bitmap 拷贝 */
    @SuppressLint("NewApi")
    private fun hardwareToBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val hb = runCatching { result.hardwareBuffer }.getOrNull() ?: return null
        val wrapped = runCatching { Bitmap.wrapHardwareBuffer(hb, result.colorSpace) }.getOrNull()
        hb.close()
        val copied = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        if (wrapped != null && wrapped !== copied) runCatching { wrapped.recycle() }
        return copied
    }
    private fun classify(className: String): String = when {
        className.contains("Button") -> "Button"
        className.contains("ImageButton") -> "ImageButton"
        className.contains("EditText") -> "EditText"
        className.contains("CheckBox") -> "CheckBox"
        className.contains("RadioButton") -> "RadioButton"
        className.contains("Switch") -> "Switch"
        className.contains("SeekBar") || className.contains("Slider") -> "Slider"
        className.contains("Tab") -> "Tab"
        className.contains("MenuItem") -> "MenuItem"
        className.contains("RecyclerView") || className.contains("ListView") ||
            className.contains("ScrollView") -> "Scrollable"
        className.contains("ImageView") -> "ImageView"
        className.contains("TextView") -> "TextView"
        className.contains("Image") -> "Image"
        else -> "View"
    }
}