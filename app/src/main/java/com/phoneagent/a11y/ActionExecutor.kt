package com.phoneagent.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.UiElement
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
    suspend fun click(x: Int, y: Int): Result =
        dispatchGesture(GestureDescription.Builder().run {
            addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 60))
            build()
        })

    /** 长按 */
    suspend fun longClick(x: Int, y: Int): Result =
        dispatchGesture(GestureDescription.Builder().run {
            addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 800))
            build()
        })

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

    /** 启动应用（通过桌面 LAUNCHER intent） */
    fun launchApp(packageName: String): Result {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            service.startActivity(intent)
            Result.Success("已启动 $packageName")
        } catch (e: Exception) {
            Result.Failure("启动应用失败：${e.message}")
        }
    }

    /** 深链直达：用 ACTION_VIEW 打开 uri（网页/地图/系统页或应用私有 scheme），直接调出目标页面 */
    fun openUri(uri: String): Result {
        val u = uri.trim().takeIf { it.isNotBlank() } ?: return Result.Failure("深链为空")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            service.startActivity(intent)
            Result.Success("已打开 $u")
        } catch (e: Exception) {
            Result.Failure("深链打开失败：${e.message}")
        }
    }

    /** 用系统 Intent Action 直达指定设置页（如 Wi-Fi/蓝牙/显示） */
    fun openSettingsAction(action: String): Result {
        return try {
            val intent = android.content.Intent(action).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
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