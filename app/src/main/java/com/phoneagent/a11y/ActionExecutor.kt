package com.phoneagent.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.UiElement
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
     * 输入文本：在可编辑元素上执行 ACTION_SET_TEXT。
     * 若未指定元素，则在整个窗口树中查找第一个可编辑节点。
     */
    fun typeText(text: String, target: UiElement?): Result {
        val node = findEditableNode(target) ?: return Result.Failure("未找到可输入文本的输入框")
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            Result.Success("已输入文本")
        } else {
            Result.Failure("文本输入失败")
        }
    }

    private fun findEditableNode(target: UiElement?): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (target != null) {
            // 按坐标匹配可编辑节点
            findNodeAt(root, target.centerX, target.centerY)?.let { return it }
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

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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