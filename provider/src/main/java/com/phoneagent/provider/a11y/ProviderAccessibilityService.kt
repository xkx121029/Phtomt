package com.phoneagent.provider.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍服务：读取屏幕可交互元素，并执行点击/滑动等手势。
 *
 * 由 [ProviderAccessibilityService.instance] 供 `com.phoneagent.provider.A11yBridge` 访问，再经 IDeviceProvider 暴露给主应用。
 * 未连接时调用 [isServiceEnabled] 检查系统设置中的开关状态。
 * 与主应用旧版 AgentAccessibilityService 的差异：跳广告事件处理已移除（no-op），
 * 「服务被杀」提示改由主应用检测绑定断开负责，本进程不再发通知。
 */
class ProviderAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ProviderA11yService"

        @Volatile
        var instance: ProviderAccessibilityService? = null
            private set

        /** 无障碍截图最长等待时间：系统限流/内部错误时不回调，超时即放弃，避免远端调用卡在“观察屏幕” */
        private const val SCREENSHOT_TIMEOUT_MS = 2500L

        /**
         * 非可交互纯文字节点的收录上限。
         * 收它们是为了让 AI 能"读"页面（正文、列表项文字），但正文页动辄上百条，
         * 全塞进决策上下文会明显推高成本，这里只保留遍历顺序靠前的部分。
         */
        private const val MAX_PLAIN_NODES = 80

        /** 无障碍服务是否已在系统设置中开启 */
        fun isServiceEnabled(context: Context): Boolean {
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
        // 框架回调里抛异常会被 system_server 视为服务崩溃，直接从启用列表摘除
        // （表现即"无障碍开关开了又自己关"），所有回调一律 runCatching 兜底。
        // 与 Operit 授权器一致：这里只登记实例，【不】运行时改写 serviceInfo——
        // 所有能力/flag 已在 accessibility_service_config.xml 声明，连接后再
        // setServiceInfo 会触发 AMS 的 info 更新周期，部分 ROM（HyperOS 等）
        // 在该竞态下会直接停用服务，这正是"开了又自动关"的一个来源
        runCatching { instance = this }
            .onFailure { Log.e(TAG, "onServiceConnected 异常已拦截", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no-op：无障碍通道只承载读屏与执行，事件驱动的自动行为（如跳广告）已随主应用移除
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        // 返回 false：告知 AMS 服务不需要重连。返回 true 的语义是"有未投递的事件
        // 想绑定重连"（配合 onRebind），我们用不到，乱返回 true 会干扰 AMS 的重绑决策
        return false
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        instance = null
        // 「服务被杀」不再由本进程发通知提示用户：主应用检测到绑定断开（DeadObjectException）
        // 后自行负责提示与引导重开，Provider 保持无 UI 纯通道形态
        super.onDestroy()
    }

    /**
     * 抓取当前屏幕元素树，序列化为与主应用 ScreenSnapshot/UiElement 字段一一对应的 JSON。
     * 若 rootInActiveWindow 为 null（如屏幕锁定），返回空快照并标记缺少无障碍。
     */
    fun captureScreenJson(): String? {
        val root = rootInActiveWindow ?: return JSONObject().apply {
            put("stats", lastTreeStats)
            put("missingAccessibility", true)
            put("screenWidth", 0)
            put("screenHeight", 0)
            put("elements", JSONArray())
        }.toString()
        val elements = JSONArray()
        val scan = TreeScan()
        collectElements(root, true, 0, elements, scan)
        lastTreeStats = scan.describe(root)
        if (elements.length() == 0) {
            // 空树必须留下证据：否则只能看到「未检测到可交互元素」，分不清是系统不给节点还是被筛选条件挡掉
            Log.w(TAG, "元素树为空：$lastTreeStats")
        }
        val (w, h) = screenSize()
        return JSONObject().apply {
            put("stats", lastTreeStats)
            put("missingAccessibility", false)
            root.packageName?.toString()?.let { put("packageName", it) }
            put("screenWidth", w)
            put("screenHeight", h)
            put("elements", elements)
        }.toString()
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
     * 最近一次抓取的统计摘要，元素为空时写进 JSON 的 stats 字段（排查"读不到控件"用）。
     */
    @Volatile
    var lastTreeStats: String = ""
        private set

    private fun collectElements(
        node: AccessibilityNodeInfo,
        isRoot: Boolean,
        counterHolder: Int,
        out: JSONArray,
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
        // 收录条件：① 可交互的控件（点击目标）；② 自带文字的可见节点（页面内容，供 AI 阅读与按文字定位）。
        // 已在可交互容器内的普通子节点不再重复收录——它的文字已经补到容器标签上，
        // 重复收录只会让 AI 在同一位置看到两个目标（微信聊天列表就是这种结构）。
        val plainText = !isInteractive && (ownText != null || ownDesc != null)
        val keep = node.isVisibleToUser && (isInteractive ||
            (plainText && !insideInteractive && scan.plainCollected < MAX_PLAIN_NODES))
        // 可点击容器常常自身没有文字（文字挂在不可点击的子控件上，微信就是这么做的）：
        // 这类容器用后代文字补一个标签，否则 AI 只拿到一堆无名方框，按 by=text 定位必然失败。
        // 拼接规则与执行层的 [DerivedLabel] 同源（元素树里看得见的标签，执行层也必须匹配得到）；
        // 放在 keep 之后算，被筛掉的节点不必再花时间翻它的后代
        val derivedLabel = if (keep) DerivedLabel.of(node, recycleChildren = true) else null
        if (!isRoot && keep) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                if (plainText) scan.plainCollected++
                val cls = node.className?.toString() ?: "Unknown"
                // 标签落在 contentDescription：text 保持节点真实文字，派生标签不污染它，
                // 而主应用的 effectiveLabel() = text ?: contentDescription 仍能被 AI 与定位层看到
                out.put(elementJson(counter, cls, node, bounds, ownText, ownDesc, derivedLabel))
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
     * 单个元素的 JSON 序列化，字段与主应用 com.phoneagent.domain.model.UiElement 完全同名同义。
     * 可空字段（text/currentValue/viewId/semanticId/ratioX 等）在无值时直接省键——
     * 主应用解析端按缺省处理，避免写入 JSON null 后被 optString 读成 "null" 字符串。
     * semanticId / ratioX / ratioY / priority 由主应用侧标注器补齐，Provider 不产出。
     */
    private fun elementJson(
        index: Int,
        cls: String,
        node: AccessibilityNodeInfo,
        bounds: Rect,
        ownText: String?,
        ownDesc: String?,
        derivedLabel: String?,
    ): JSONObject = JSONObject().apply {
        put("index", index)
        put("className", cls)
        put("type", classify(cls))
        ownText?.let { put("text", it) }
        (ownDesc ?: derivedLabel)?.let { put("contentDescription", it) }
        put("isSelected", node.isSelected)
        if (node.isEditable) node.text?.toString()?.let { put("currentValue", it) }
        put("x", bounds.centerX())
        put("y", bounds.centerY())
        put("left", bounds.left)
        put("top", bounds.top)
        put("right", bounds.right)
        put("bottom", bounds.bottom)
        put("clickable", node.isClickable)
        put("longClickable", node.isLongClickable)
        put("scrollable", node.isScrollable)
        put("editable", node.isEditable)
        node.viewIdResourceName?.substringAfterLast('/')?.let { put("viewId", it) }
        node.packageName?.toString()?.let { put("packageName", it) }
        put("isEnabled", node.isEnabled)
        put("isVisibleToUser", node.isVisibleToUser)
        put("childCount", node.childCount)
    }

    /**
     * 每步自动截图（不依赖 MediaProjection 屏幕共享）：改用无障碍服务的 takeScreenshot（API 30+）。
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
        // 协程会永久挂起（跨进程调用表现为 Binder 侧一直等），故加超时兜底
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

/** "这一屏上压在某个点上的节点"的唯一判定点（位置兜底定位与落点审计共用） */
internal object NodesAtPoint {
    fun of(nodes: List<AccessibilityNodeInfo>, x: Int, y: Int): List<AccessibilityNodeInfo> {
        val rect = Rect()
        return nodes.filter { node ->
            node.getBoundsInScreen(rect)
            rect.contains(x, y)
        }
    }
}

/**
 * 派生标签（容器标签）：可点击容器自身没有文字时，用后代文字拼一个标签
 * （如微信聊天列表行 →「末影箱 / 测试消息 / 昨天」）。
 *
 * 感知层（[ProviderAccessibilityService] 收录元素）与执行层（[ProviderActionExecutor] 回到活节点树定位）
 * **共用这一份定义**：两边各写一份的后果很实际——拼接规则稍有出入，元素树里 AI 看得见的标签
 * 在执行层就永远匹配不到，点击只能退化成按坐标猜。
 */
internal object DerivedLabel {

    /** 最多拼接的后代文字段数 / 总字数 / 递归深度：防止列表容器把整页文字拼成一大串 */
    const val MAX_PARTS = 3
    const val MAX_CHARS = 60
    const val MAX_DEPTH = 3
    const val SEPARATOR = " / "

    /**
     * 取容器的派生标签；自身已有文字、或不是可点击/可长按/可编辑的容器则返回 null
     * （前者标签就是它自己，后者不是点击目标、不需要名字）。
     *
     * @param recycleChildren 取完是否回收子节点（API 33+ 框架自动回收）。感知层只取文字、之后
     *   不再持有节点，传 true 避免 API<33 上的泄漏；执行层要把命中的**节点句柄**交给上层
     *   performAction，一旦回收就会点到已失效的节点上，故必须传 false。
     */
    fun of(node: AccessibilityNodeInfo, recycleChildren: Boolean = false): String? {
        val ownText = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val ownDesc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (ownText != null || ownDesc != null) return null
        if (!node.isClickable && !node.isLongClickable && !node.isEditable) return null
        val parts = ArrayList<String>(MAX_PARTS)
        collect(node, 0, parts, recycleChildren)
        if (parts.isEmpty()) return null
        val joined = parts.joinToString(SEPARATOR)
        return if (joined.length > MAX_CHARS) joined.take(MAX_CHARS) else joined
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<String>,
        recycleChildren: Boolean,
    ) {
        if (depth >= MAX_DEPTH || out.size >= MAX_PARTS) return
        for (i in 0 until node.childCount) {
            if (out.size >= MAX_PARTS) return
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: child.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            text?.let { out += it }
            collect(child, depth + 1, out, recycleChildren)
            if (recycleChildren && android.os.Build.VERSION.SDK_INT < 33) child.recycle()
        }
    }
}
