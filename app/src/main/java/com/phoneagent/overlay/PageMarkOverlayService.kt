package com.phoneagent.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.phoneagent.data.store.PageMemoryHotspot

/**
 * 页面记忆标记覆盖层：当前页面命中已记忆页面时，在该页已记忆的可点击控件位置
 * 画一圈淡色圆环，让「AI 记得这一页」对用户可见。
 *
 * 结构对齐 [CursorOverlayService]：TYPE_APPLICATION_OVERLAY + 全屏透明 + 不拦截触摸 + 自绘 View。
 * 关键差异：这里没有动画时间线，只有静态圆环——标记只是"记忆热区"的提示，
 * 绝不移动/遮挡任何控件（FLAG_NOT_TOUCHABLE，触摸全部穿透到下面的真实控件）。
 *
 * 坐标用 ratio（0~1 相对屏幕比例）存储、绘制时才换算成像素：
 * 旋转或换分辨率后标记仍落在原控件附近，不需要重新记忆。
 * 无悬浮窗权限时静默失败，绝不影响任务执行。
 */
class PageMarkOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var markView: PageMarkView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showOverlay()
        applyPending()
        // 不用 STICKY：服务被杀后没有任务支撑的标记不该被系统重建挂回屏幕
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (markView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = PageMarkView(this)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不拦截触摸（标记绝不挡住用户与真实控件的交互）、不抢焦点、允许绘制到系统栏区域
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 与 CursorOverlayService 同源修复：API 30+ 清空 fitInsetsTypes，
            // 坐标系才从物理屏顶开始，与元素树/手势使用的物理坐标一致
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        try {
            windowManager?.addView(view, params)
            markView = view
        } catch (_: WindowManager.BadTokenException) {
            // 没有悬浮窗权限时静默失败
        } catch (_: Exception) {
        }
    }

    private fun applyPending() {
        val view = markView ?: return
        view.marks = pendingMarks
        view.alpha = 0f
        view.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun removeOverlay() {
        markView?.animate()?.cancel()
        markView?.let { runCatching { windowManager?.removeView(it) } }
        markView = null
        params = null
    }

    override fun onDestroy() {
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private var params: WindowManager.LayoutParams? = null

    companion object {
        /** 待绘制的标记：挂载前暂存（服务还没起时先记下），挂载后直接刷到 View */
        @Volatile
        private var pendingMarks: List<PageMemoryHotspot> = emptyList()

        @Volatile
        var instance: PageMarkOverlayService? = null
            private set

        /**
         * 刷新屏幕标记：命中已记忆页面时传入该页热点；未命中传空列表（等价 hide）。
         * 幂等，可在任务每步反复调用。
         */
        fun update(context: Context, marks: List<PageMemoryHotspot>) {
            pendingMarks = marks
            if (marks.isEmpty()) {
                hide()
                return
            }
            val service = instance
            if (service != null) {
                service.mainHandler.post { service.applyPending() }
            } else {
                runCatching { context.startService(Intent(context, PageMarkOverlayService::class.java)) }
            }
        }

        /**
         * 撤下标记（幂等）。与 CursorOverlayService.hide 同思路：直接在主线程撤视图并停服务，
         * 不走 startService(ACTION_HIDE)——后台启动服务可能被系统拒绝，标记会赖在屏幕上。
         */
        fun hide() {
            pendingMarks = emptyList()
            val service = instance ?: return
            service.mainHandler.post {
                if (instance === service) {
                    service.removeOverlay()
                    service.stopSelf()
                }
            }
        }

        /** 截图前隐藏 / 截图后恢复：标记只是给用户看的，不该被截进画面污染 AI 读屏 */
        fun setVisible(visible: Boolean): Boolean {
            val service = instance ?: return false
            service.mainHandler.post {
                val view = service.markView ?: return@post
                view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
            return true
        }
    }
}

/**
 * 标记绘制视图：每个已记忆热点画一圈淡色圆环 + 中心小点。
 * 低调为上：细描边、低不透明度，看得见但抢不过真实控件。
 */
private class PageMarkView(context: Context) : View(context) {

    var marks: List<PageMemoryHotspot> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    /** 主题青色（与应用品牌一致），整体压到 28% 不透明度 */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x480E7C66.toInt()
        strokeWidth = 2f * density
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x480E7C66.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = 11f * density
        for (mark in marks) {
            val cx = mark.ratioX.coerceIn(0f, 1f) * w
            val cy = mark.ratioY.coerceIn(0f, 1f) * h
            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawCircle(cx, cy, 2f * density, dotPaint)
        }
    }
}
