package com.phoneagent.device.a11y

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
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val TAG = "AgentA11y"

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /** 无障碍截图最长等待时间：系统限流/内部错误时不回调，超时即放弃，避免 Agent 卡在“观察屏幕” */
        private const val SCREENSHOT_TIMEOUT_MS = 2500L

        /**
         * 非可交互纯文字节点的收录上限。
         * 收它们是为了让 AI 能"读"页面（正文、列表项文字），但正文页动辄上百条，
         * 全塞进决策上下文会明显推高成本，这里只保留遍历顺序靠前的部分。
         */
        private const val MAX_PLAIN_NODES = 80

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no-op：Agent 任务内的广告处理由引擎侧 AdContentFilter 完成；
        // 「Agent 空闲时自动跳广告」是面向日常使用的独立能力，已移除
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
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
        val scan = TreeScan()
        collectElements(root, true, 0, elements, scan)
        lastTreeStats = scan.describe(root)
        if (elements.isEmpty()) {
            // 空树必须留下证据：否则只能看到「未检测到可交互元素」，分不清是系统不给节点还是被筛选条件挡掉
            android.util.Log.w(TAG, "元素树为空：$lastTreeStats")
        }
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

    /**
     * 一次抓取的统计：元素树为空时用它判断"是系统不给节点"还是"被我们的筛选条件挡掉"。
     * 计数只服务诊断，不参与决策。
     */
    private class TreeScan {
        var visited = 0
        var invisible = 0
        var zeroSize = 0
        var noLabel = 0
        var deduped = 0

        /** 已收录的"非可交互纯文字"节点数：上限 [MAX_PLAIN_NODES]，防止正文页把整页文字灌进 AI 上下文 */
        var plainCollected = 0

        fun describe(root: AccessibilityNodeInfo?): String =
            "访问=$visited 不可见=$invisible 无标签=$noLabel 尺寸为0=$zeroSize 容器内重复=$deduped" +
                " 纯文字收录=$plainCollected 根节点子数=${root?.childCount ?: -1}"
    }

    /**
     * 最近一次抓取的统计摘要，元素为空时由引擎写进任务日志（排查"读不到控件"用）。
     */
    @Volatile
    var lastTreeStats: String = ""
        private set

    /** 容器标签最多拼接的后代文字段数 / 总字数：防止列表容器把整页文字拼成一大串 */
    private val LABEL_MAX_PARTS = 3
    private val LABEL_MAX_CHARS = 60

    /** 后代文字的最大递归深度（只找浅层文字，深了就是另一条内容） */
    private val LABEL_MAX_DEPTH = 3

    private fun collectElements(
        node: AccessibilityNodeInfo,
        isRoot: Boolean,
        counterHolder: Int,
        out: MutableList<UiElement>,
        scan: TreeScan,
        depth: Int = 0,
        insideInteractive: Boolean = false,
    ): Int {
        var counter = counterHolder
        // 限制递归深度（80 层）和节点总数（500 个），防止 StackOverflow 和性能问题
        if (depth > 80 || counter > 500) return counter
        scan.visited++
        val isInteractive = node.isClickable ||
            node.isScrollable ||
            node.isEditable ||
            node.isLongClickable
        val ownText = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val ownDesc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        // 可点击容器常常自身没有文字（文字挂在不可点击的子控件上，微信就是这么做的）：
        // 这类容器用后代文字补一个标签，否则 AI 只拿到一堆无名方框，按 by=text 定位必然失败。
        // 只补"可点击/可长按/可编辑"的容器（列表容器本身不需要名字）。
        val derivedLabel = if (ownText == null && ownDesc == null &&
            (node.isClickable || node.isLongClickable || node.isEditable)
        ) descendantLabel(node) else null
        // 收录条件：① 可交互的控件（点击目标）；② 自带文字的可见节点（页面内容，供 AI 阅读与按文字定位）。
        // 已在可交互容器内的普通子节点不再重复收录——它的文字已经补到容器标签上，
        // 重复收录只会让 AI 在同一位置看到两个目标（微信聊天列表就是这种结构）。
        val plainText = !isInteractive && (ownText != null || ownDesc != null)
        val keep = node.isVisibleToUser && (isInteractive ||
            (plainText && !insideInteractive && scan.plainCollected < MAX_PLAIN_NODES))
        if (!isRoot && keep) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                if (plainText) scan.plainCollected++
                val cls = node.className?.toString() ?: "Unknown"
                // 标签落在 contentDescription：text 保持节点真实文字，派生标签不污染它，
                // 而 effectiveLabel() = text ?: contentDescription 仍能被 AI 与定位层看到
                out += UiElement(
                    index = counter,
                    className = cls,
                    type = classify(cls),
                    text = ownText,
                    contentDescription = ownDesc ?: derivedLabel,
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
            } else {
                scan.zeroSize++
            }
        } else if (!isRoot) {
            if (!node.isVisibleToUser) scan.invisible++
            else if (!isInteractive && (ownText != null || ownDesc != null)) scan.deduped++
            else scan.noLabel++
        }
        val childInside = insideInteractive || (!isRoot && isInteractive)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                counter = collectElements(child, false, counter, out, scan, depth + 1, childInside)
                // 每个 getChild 获取的节点只由其调用方回收一次，避免双重 recycle（API 33+ 框架自动回收）
                if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
            }
        }
        return counter
    }

    /**
     * 取后代文字作为容器标签：最多 [LABEL_MAX_PARTS] 段、总长 [LABEL_MAX_CHARS] 字、深度不超过 [LABEL_MAX_DEPTH]。
     * 多段用 " / " 连接（如「末影箱 / 测试消息 / 昨天」），让 AI 一眼认出这一行是什么。
     */
    private fun descendantLabel(node: AccessibilityNodeInfo): String? {
        val parts = ArrayList<String>(LABEL_MAX_PARTS)
        collectDescendantTexts(node, 0, parts)
        if (parts.isEmpty()) return null
        val joined = parts.joinToString(" / ")
        return if (joined.length > LABEL_MAX_CHARS) joined.take(LABEL_MAX_CHARS) else joined
    }

    private fun collectDescendantTexts(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>) {
        if (depth >= LABEL_MAX_DEPTH || out.size >= LABEL_MAX_PARTS) return
        for (i in 0 until node.childCount) {
            if (out.size >= LABEL_MAX_PARTS) return
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: child.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            text?.let { out += it }
            collectDescendantTexts(child, depth + 1, out)
            if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
        }
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
        // 必须限时：系统限流（间隔过短）/内部错误/个别机型回调丢失时，回调可能不来，
        // 协程会永久挂起（此时悬浮窗已隐藏），表现为 Agent 一直卡在“观察屏幕”这一步，故加超时兜底
        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            try {
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