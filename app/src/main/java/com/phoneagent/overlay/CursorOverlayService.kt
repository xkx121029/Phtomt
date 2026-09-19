package com.phoneagent.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 点击光标覆盖层：任务执行时在全屏透明覆盖层上显示一个圆润指针，
 * 平滑移动到 AI 即将点击的位置，按下时切换颜色并缩小。
 *
 * 结构对齐 [com.phoneagent.feature.edge.EdgeLightingService]：
 * TYPE_APPLICATION_OVERLAY + 全屏透明 + 不拦截触摸 + 自绘 View + companion 静态门面。
 *
 * 服务在任务期间常驻（[show]/[hide]），点击时只更新 View 内部状态，
 * 不反复 startService；无悬浮窗权限时静默失败，绝不影响任务执行。
 */
class CursorOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var pointerView: CursorPointerView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 首次点击已落位（此后才需要飞行动画） */
    private var placedOnce = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                syncMode = intent?.getBooleanExtra("sync", false) ?: false
                showOverlay()
            }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (pointerView != null) return
        // 每次挂载都从"未落位"开始，首个点击点直接定位而不是从上一任务的位置飞过来
        placedOnce = false
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = CursorPointerView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            alpha = 0f
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不拦截触摸（否则会吃掉用户与 AI 的点击）、不抢焦点、允许绘制到系统栏区域
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager?.addView(view, params)
            pointerView = view
            // 首次出现淡入，避免生硬闪现
            view.animate()?.alpha(1f)?.setDuration(160)?.start()
        } catch (_: WindowManager.BadTokenException) {
            // 没有悬浮窗权限时静默失败
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        pointerView?.animate()?.cancel()
        pointerView?.let { runCatching { windowManager?.removeView(it) } }
        pointerView = null
        params = null
    }

    override fun onDestroy() {
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 把指针移到 (x, y)，移动段结束时回调；视图已移除时立即回调，避免调用方挂起 */
    private fun moveTo(x: Int, y: Int, onArrived: (() -> Unit)?) {
        val view = pointerView
        if (view == null) {
            onArrived?.invoke()
            return
        }
        view.animateTo(x.toFloat(), y.toFloat(), onArrived)
    }

    /** 首次出现时直接定位，不做移动动画 */
    private fun placeAt(x: Int, y: Int) {
        pointerView?.setPosition(x.toFloat(), y.toFloat())
    }

    companion object {
        private const val ACTION_HIDE = "com.phoneagent.cursor.HIDE"

        @Volatile
        var instance: CursorOverlayService? = null
            private set

        /** 同步模式：等光标到位再执行点击（由设置项驱动，默认关闭） */
        @Volatile
        private var syncMode = false

        /** 同步模式等待移动段的上限：正常 260ms，留足余量 */
        private const val MOVE_WAIT_TIMEOUT_MS = 700L

        /** 任务开始：挂上覆盖层（幂等）。[sync] = 光标先到位再点击 */
        fun show(context: Context, sync: Boolean) {
            syncMode = sync
            val intent = Intent(context, CursorOverlayService::class.java).apply {
                putExtra("sync", sync)
            }
            runCatching { context.startService(intent) }
        }

        /** 任务结束：移除覆盖层 */
        fun hide(context: Context) {
            val intent = Intent(context, CursorOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            runCatching { context.startService(intent) }
        }

        /**
         * 指针移动到 (x, y) 并做一次按压动效。
         * - 默认并行：立即返回，不阻塞点击（任务速度不变）
         * - 同步模式：等光标飞到位再返回，点击随后发生
         *
         * 服务未挂上（无权限/未启动）时直接返回，调用方无需判空。
         */
        suspend fun point(x: Int, y: Int) {
            val service = instance ?: return
            if (!service.placedOnce) {
                // 首个点击点：直接落位，避免从屏幕左上角飞过来，也无需等待
                service.placedOnce = true
                service.mainHandler.post { service.placeAt(x, y) }
                return
            }
            if (!syncMode) {
                service.mainHandler.post { service.moveTo(x, y, null) }
                return
            }
            // 同步模式：等移动段结束再放行点击。
            // 超时兜底——服务被销毁/动画异常时也必须放行，绝不能让光标卡住任务。
            withTimeoutOrNull(MOVE_WAIT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    service.mainHandler.post {
                        service.moveTo(x, y) { if (cont.isActive) cont.resume(Unit) }
                    }
                }
            }
        }

        /** 截图前隐藏 / 截图后恢复（与悬浮窗同进同出，避免光标被截进画面污染 AI 读屏）。 */
        fun setVisible(visible: Boolean): Boolean {
            val service = instance ?: return false
            service.mainHandler.post {
                val view = service.pointerView ?: return@post
                view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
            return true
        }
    }
}
