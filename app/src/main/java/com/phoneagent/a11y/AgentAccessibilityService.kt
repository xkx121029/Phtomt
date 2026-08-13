package com.phoneagent.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.UiElement

/**
 * 无障碍服务：读取屏幕可交互元素，并执行点击/滑动等手势。
 *
 * 通过 [AgentAccessibilityService.instance] 供 Agent 引擎访问。
 * 未连接时调用 [isServiceEnabled] 检查系统设置中的开关状态。
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

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
        // 框架阶段：引擎按需主动抓取，暂不依赖事件流做状态管理
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
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

    private fun collectElements(node: AccessibilityNodeInfo, isRoot: Boolean, counterHolder: Int, out: MutableList<UiElement>): Int {
        var counter = counterHolder
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
                counter = collectElements(child, false, counter, out)
            }
        }
        return counter
    }

    /** 依据控件类名归纳为语义化类型 */
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