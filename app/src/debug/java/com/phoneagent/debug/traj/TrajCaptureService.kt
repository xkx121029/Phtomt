package com.phoneagent.debug.traj

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * debug-only 轨迹采集无障碍服务（`app/src/debug`，release 变体不含本类与任何采集权限）。
 *
 * 职责边界：只做「读当前屏幕 + 派发一个动作 + 截图」，不做任何决策/判分；
 * 采集策略与落盘在 [TrajRecorder]，外部驱动入口在 [TrajCommandReceiver]。
 *
 * 为什么自带一个无障碍服务，而不是复用 Provider 的 AIDL 通道：
 * 轨迹数据要的是**完整节点树**（含 level/visible/checked）与**动作前后同一时刻**的截图，
 * 经一次跨进程 JSON 过滤后字段会丢；这里直接读本地 NodeInfo 树，字段与训练契约一一对应。
 */
class TrajCaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "TrajCapture"

        /** 服务连接后才可采集；进程内单实例 */
        @Volatile
        var instance: TrajCaptureService? = null
            private set

        /** 最近一次窗口切换事件的包名：rootInActiveWindow 偶发为 null 时用它兜底 */
        @Volatile
        private var lastEventPackage: String? = null

        fun isConnected(): Boolean = instance != null

        private const val CLICK_HOLD_MS = 60L
        private const val LONG_PRESS_MS = 700L
        private const val DOUBLE_TAP_GAP_MS = 120L
        private const val SWIPE_MS = 320L
        private const val GESTURE_TIMEOUT_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍采集服务已连接：api=${Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { lastEventPackage = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.w(TAG, "无障碍采集服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 屏幕物理尺寸（含状态栏/导航栏），元素坐标与之同一坐标系 */
    fun screenSize(): Pair<Int, Int> {
        // 优先 maximumWindowMetrics：它给的是整块屏幕，不受状态栏/导航栏 inset 影响；
        // getRealSize 在部分镜像上会退化成 app 尺寸（高度少掉系统栏），导致元数据与截图比例不一致
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val wm = getSystemService(WindowManager::class.java)
                val bounds = wm?.maximumWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            }
        }
        val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
        val point = Point()
        runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(point) }
        if (point.x > 0 && point.y > 0) return point.x to point.y
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /** 抓一帧节点树（同步，毫秒级） */
    internal fun captureTree(): TrajElementTree.Snapshot {
        val (w, h) = screenSize()
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val snapshot = TrajElementTree.capture(root, w, h)
        if (snapshot.packageName == null) {
            // root 读不到的瞬间（窗口切换中）用最近一次窗口事件兜底，落盘的前台包名才不会空
            return snapshot.copy(packageName = lastEventPackage)
        }
        return snapshot
    }

    /**
     * 等树稳定后再返回（给驱动脚本用）：连续两帧元素序列一致才算稳定。
     *
     * 滑动惯性/键盘弹出动画期间抓到的坐标会偏移，脚本拿这种坐标去点必然点空。
     */
    internal fun captureStableTree(maxWaitMs: Long = 4_000): TrajElementTree.Snapshot {
        var last = captureTree()
        var lastKey = last.elements.toString()
        var waited = 0L
        while (waited < maxWaitMs) {
            Thread.sleep(220)
            waited += 220
            val next = captureTree()
            val key = next.elements.toString()
            // 窗口切换瞬间 rootInActiveWindow 会返回 null（空树却"稳定"），这类帧不能当完整树返回
            val nextEmpty = next.truncationReasons.contains("no_root")
            if (key == lastKey && !nextEmpty) return next
            last = next
            lastKey = key
        }
        return last
    }

    /**
     * 截一帧屏幕（API 30+ 的 takeScreenshot）。
     *
     * @return 失败（安全窗口 / 系统忙 / 低版本）时 null，调用方按「无截图」降级而不是让整步失败。
     */
    fun takeScreenshotBlocking(timeoutMs: Long = 8_000): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                { runnable -> Thread(runnable, "traj-shot-cb").start() },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        // 硬件位图不能直接 compress：拷成软件位图再落 JPEG
                        result.set(hardware?.copy(Bitmap.Config.ARGB_8888, false))
                        runCatching { screenshot.hardwareBuffer.close() }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "截图失败 errorCode=$errorCode")
                        latch.countDown()
                    }
                },
            )
        }.onFailure {
            Log.w(TAG, "截图调用异常：${it.message}")
            return null
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }

    /** 当前输入焦点的中心点：type/enter 没有显式坐标时用它补一个点击目标 */
    internal fun focusedInputPoint(): Pair<Int, Int>? {
        val focused = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull() ?: return null
        val bounds = android.graphics.Rect()
        focused.getBoundsInScreen(bounds)
        return if (bounds.width() > 0 && bounds.height() > 0) bounds.centerX() to bounds.centerY() else null
    }

    // ==================== 动作派发 ====================

    /** 动作参数：由广播命令解析而来，字段与 traj 的 action JSON 同义 */
    data class ActionSpec(
        val action: String,
        val x: Int = -1,
        val y: Int = -1,
        val x2: Int = -1,
        val y2: Int = -1,
        val text: String? = null,
        val packageName: String? = null,
        val durationMs: Long = 0,
        val waitMs: Long = 0,
    )

    data class ActionOutcome(val ok: Boolean, val detail: String)

    /**
     * 执行一个动作。所有动作都只是"派发"，不等界面变化；界面稳定时间由 [TrajRecorder] 的 settle 控制。
     */
    fun perform(spec: ActionSpec): ActionOutcome {
        return when (spec.action) {
            "click" -> gesture(singleStroke(spec.x, spec.y, spec.x, spec.y, CLICK_HOLD_MS), "click")
            "long_press" -> gesture(
                singleStroke(spec.x, spec.y, spec.x, spec.y, (spec.durationMs.takeIf { it > 0 } ?: LONG_PRESS_MS)),
                "long_press",
            )

            "double_tap" -> doubleTap(spec.x, spec.y)
            "swipe", "drag" -> gesture(
                singleStroke(
                    spec.x, spec.y, spec.x2, spec.y2,
                    spec.durationMs.takeIf { it > 0 } ?: SWIPE_MS,
                ),
                spec.action,
            )

            "type" -> typeText(spec)
            "enter" -> pressEnter()
            "back" -> global(GLOBAL_ACTION_BACK, "back")
            "home" -> global(GLOBAL_ACTION_HOME, "home")
            "recent" -> global(GLOBAL_ACTION_RECENTS, "recent")
            "open_app" -> openApp(spec.packageName)
            "wait" -> {
                Thread.sleep(spec.waitMs.takeIf { it > 0 } ?: 1_000)
                ActionOutcome(true, "wait ${spec.waitMs.takeIf { it > 0 } ?: 1_000}ms")
            }

            // 非界面动作（complete/abort/answer/info/noop）：只记录，不派发
            else -> ActionOutcome(true, "无界面动作：${spec.action}")
        }
    }

    private fun gesture(description: GestureDescription?, label: String): ActionOutcome {
        if (description == null) return ActionOutcome(false, "$label 参数不合法（坐标缺失）")
        val latch = CountDownLatch(1)
        var completed = false
        val dispatched = runCatching {
            dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed = true
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                },
                mainHandler,
            )
        }.getOrDefault(false)
        if (!dispatched) return ActionOutcome(false, "$label 手势派发被拒")
        latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return if (completed) ActionOutcome(true, "$label 已执行") else ActionOutcome(false, "$label 手势未完成")
    }

    private fun singleStroke(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): GestureDescription? {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return null
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val builder = GestureDescription.Builder()
        val strokeMs = durationMs.coerceAtLeast(1)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, strokeMs, false))
        return builder.build()
    }

    private fun doubleTap(x: Int, y: Int): ActionOutcome {
        if (x < 0 || y < 0) return ActionOutcome(false, "double_tap 参数不合法")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, CLICK_HOLD_MS, false))
        builder.addStroke(GestureDescription.StrokeDescription(path, DOUBLE_TAP_GAP_MS, CLICK_HOLD_MS, true))
        return gesture(builder.build(), "double_tap")
    }

    private fun global(action: Int, label: String): ActionOutcome {
        val ok = runCatching { performGlobalAction(action) }.getOrDefault(false)
        return ActionOutcome(ok, if (ok) "$label 已执行" else "$label 执行失败")
    }

    private fun typeText(spec: ActionSpec): ActionOutcome {
        val text = spec.text ?: return ActionOutcome(false, "type 缺少 text")
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return ActionOutcome(false, "读不到窗口，无法输入")
        var target = findFocusedInput(root)
        if (target == null && spec.x >= 0 && spec.y >= 0) {
            // 没有焦点时先点一下目标坐标，等焦点落到输入框再填
            gesture(singleStroke(spec.x, spec.y, spec.x, spec.y, CLICK_HOLD_MS), "focus")
            Thread.sleep(400)
            target = findFocusedInput(rootInActiveWindow)
        }
        if (target == null) {
            // 仍无焦点：退回第一个可编辑节点（很多自绘输入框不申请输入焦点）
            target = findFirstEditable(runCatching { rootInActiveWindow }.getOrNull())
        }
        if (target == null) return ActionOutcome(false, "找不到可编辑节点")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        return ActionOutcome(ok, if (ok) "已填入文本" else "ACTION_SET_TEXT 被拒")
    }

    private fun findFocusedInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val focused = runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && (focused.isEditable || focused.className?.contains("EditText") == true)) {
            return focused
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findFirstEditable(child)?.let { return it }
            if (Build.VERSION.SDK_INT < 33) child.recycle()
        }
        return null
    }

    private fun pressEnter(): ActionOutcome {
        if (Build.VERSION.SDK_INT < 30) return ActionOutcome(false, "ACTION_IME_ENTER 需要 API 30+")
        val focused = runCatching {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull() ?: return ActionOutcome(false, "没有输入焦点，无法回车")
        val ok = runCatching {
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }.getOrDefault(false)
        return ActionOutcome(ok, if (ok) "已回车" else "ACTION_IME_ENTER 被拒")
    }

    private fun openApp(pkg: String?): ActionOutcome {
        if (pkg.isNullOrBlank()) return ActionOutcome(false, "open_app 缺少 package")
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            ActionOutcome(true, "已拉起 $pkg")
        }.getOrElse { ActionOutcome(false, "拉起 $pkg 失败：${it.message}") }
    }
}
