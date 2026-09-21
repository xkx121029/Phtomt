package com.phoneagent.device.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 通过无障碍服务执行具体的屏幕动作。
 * 点击/滑动使用手势，返回键/主页/最近任务使用全局按键，文本输入使用 ACTION_SET_TEXT。
 */
class ActionExecutor(
    private val service: AgentAccessibilityService,
) {

    sealed class Result {
        data class Success(val description: String = "") : Result()
        data class Failure(val reason: String) : Result()
    }

    /** 点击屏幕坐标 */
    suspend fun click(x: Int, y: Int): Result {
        // 让用户看到光标飞向点击点（并行/同步由设置驱动态控制闭环内）
        com.phoneagent.overlay.CursorOverlayService.point(x, y)
        return dispatchGesture(GestureDescription.Builder().run {
            addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 60))
            build()
        })
    }

    /** 长按 */
    suspend fun longClick(x: Int, y: Int): Result {
        com.phoneagent.overlay.CursorOverlayService.point(x, y)
        return dispatchGesture(GestureDescription.Builder().run {
            addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 800))
            build()
        })
    }

    /** 滑动 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Result =
        dispatchGesture(GestureDescription.Builder().run {
            addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) },
                    0,
                    durationMs,
                ),
            )
            build()
        })

    /** 方向滑动（补齐屏幕中心点） */
    suspend fun swipeDirection(x1: Int, y1: Int, x2: Int, y2: Int): Result = swipe(x1, y1, x2, y2)

    /** 滚动（在元素范围内纵向滑动） */
    suspend fun scroll(elem: UiElement?, direction: String): Result {
        val x1 = elem?.centerX ?: service.resources.displayMetrics.widthPixels / 2
        val y1 = elem?.centerY ?: service.resources.displayMetrics.heightPixels / 2
        val y2 = if (direction == "up") y1 - 600 else y1 + 600
        return swipe(x1, y1, x1, y2, 300)
    }

    /** 全局按键 */
    fun globalAction(action: Int): Result {
        return if (service.performGlobalAction(action)) {
            Result.Success()
        } else {
            Result.Failure("全局按键执行失败")
        }
    }

    fun back() = globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun home() = globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun recents() = globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    /** 启动应用（多种策略兜底，确保兼容不同 Android 版本） */
    fun launchApp(packageName: String): Result {
        return try {
            val pm = service.applicationContext.packageManager
            // 策略1：精确查找 LAUNCHER activity
            val query = pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                },
                0,
            )
            if (query.isNotEmpty()) {
                val ri = query[0]
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.applicationContext.startActivity(intent)
                return Result.Success("已启动 $packageName（activity=${ri.activityInfo.name}）")
            }
            // 策略2：使用 broadcast 启动（某些设备更可靠）
            val broadcastIntent = android.content.Intent("android.intent.action.MAIN").apply {
                setPackage(packageName)
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.applicationContext.sendBroadcast(broadcastIntent)
            return Result.Success("已发送启动广播: $packageName")
        } catch (e: Exception) {
            Result.Failure("启动应用失败：${e.message} (pkg=$packageName)")
        }
    }

    /**
     * 用系统应用打开链接/文件（ACTION_VIEW）——"交给系统软件打开"的统一出口。
     *
     * 三类目标都由 [OpenTarget] 归一化后交给系统：
     * 1. 网址（http/https）→ 系统浏览器；
     * 2. 本地文件（`/sdcard/x.ppt`、`file://…`）→ 转 `content://` + 按扩展名补 MIME，
     *    否则文档软件不会被列为候选（详见 [OpenTarget]）；
     * 3. App 私有 scheme / 系统页 → 原样直发。
     *
     * @param pkg 指定用哪个应用打开（已解析的包名）；为空时**优先系统自带应用**，
     *            没有任何系统应用可处理才交回系统默认/选择器
     */
    fun openUri(uri: String, pkg: String? = null): Result {
        val raw = uri.trim().takeIf { it.isNotBlank() } ?: return Result.Failure("打开目标为空")
        val data = android.net.Uri.parse(OpenTarget.normalize(raw))
        val base = android.content.Intent(android.content.Intent.ACTION_VIEW, data).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            OpenTarget.mimeOf(raw)?.let { setDataAndType(data, it) }
        }
        val target = pkg?.trim()?.takeIf { it.isNotBlank() } ?: systemHandlerOf(base)
        return try {
            service.applicationContext.startActivity(if (target != null) android.content.Intent(base).setPackage(target) else base)
            Result.Success(if (target != null) "已用 $target 打开 $raw" else "已打开 $raw")
        } catch (e: Exception) {
            // 没有任何应用能处理这个链接/文件（如本机没装文档阅读器）——给出可执行的中文原因
            Result.Failure("没有应用能打开 $raw（${e.javaClass.simpleName}）：本机可能缺少能处理该类型的应用")
        }
    }

    /**
     * 未指定应用时优先挑**系统自带**应用（用户偏好：打开软件优先用系统软件）。
     * 查不到系统应用（或 Android 11+ 未授予包可见性）返回 null，交回系统的默认应用/选择器。
     */
    private fun systemHandlerOf(intent: android.content.Intent): String? {
        val pm = service.applicationContext.packageManager
        val hits = runCatching {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        return hits.firstOrNull { ri ->
            val app = ri.activityInfo?.applicationInfo ?: return@firstOrNull false
            app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }?.activityInfo?.packageName
    }

    /** 用系统 Intent Action 直达指定设置页（如 Wi-Fi/蓝牙/显示） */
    fun openSettingsAction(action: String): Result {
        return try {
            val intent = android.content.Intent(action).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.applicationContext.startActivity(intent)
            Result.Success("已直达系统设置页")
        } catch (e: Exception) {
            Result.Failure("打开设置页失败：${e.message}")
        }
    }

    /**
     * 向输入框填充文本（整段覆写，ACTION_SET_TEXT）。
     *
     * 两层策略：
     * 1. 若元素树中能找到目标坐标/控件处的可编辑节点 → 直接对其填充。
     * 2. 若无障碍读不到输入框（如 WebView/自绘控件）：先点击坐标聚焦，
     *    等待聚焦后再查可编辑节点并填充。
     */
    suspend fun typeText(text: String, target: UiElement?, x: Int? = null, y: Int? = null): Result {
        val cx = x ?: target?.centerX
        val cy = y ?: target?.centerY
        // 策略1：直接命中元素树中的可编辑节点
        findEditableNode(target, cx, cy)?.let { node ->
            if (setText(node, text)) return Result.Success("已向输入框填充文本")
        }
        // 策略2：点击坐标聚焦后填充（弥补元素树读不到输入框的场景）
        if (cx != null && cy != null) {
            click(cx, cy)
            delay(350)
            val root = service.rootInActiveWindow
            findFirstEditable(root)?.let { node ->
                if (setText(node, text)) return Result.Success("已聚焦输入框并填充文本")
            }
        }
        return Result.Failure("未找到可输入文本的输入框")
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun findEditableNode(target: UiElement?, x: Int?, y: Int?): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (x != null && y != null) {
            findNodeAt(root, x, y)?.let { return it }
        }
        return findFirstEditable(root)
    }

    private fun findNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node.isEditable) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.contains(x, y)) return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeAt(child, x, y)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findFirstEditable(child)?.let { return it }
            }
        }
        return null
    }

    /** 派发手势并等待结果 */
    private suspend fun dispatchGesture(gesture: GestureDescription): Result =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Result.Success())
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Result.Failure("手势被取消"))
                }
            }
            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                if (cont.isActive) cont.resume(Result.Failure("手势派发失败"))
            }
        }
}