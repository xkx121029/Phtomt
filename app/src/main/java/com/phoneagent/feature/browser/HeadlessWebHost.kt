package com.phoneagent.feature.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.phoneagent.feature.browser.script.NavScripts

private const val TAG = "HeadlessWebHost"

/**
 * 静默浏览器宿主：让 AI 的 browse_* 在**后台**跑完，不再把 App 界面切到「浏览器」二级页。
 *
 * 做法：把给 AI 用的那个 WebView 挂进一个**全透明、不吃触摸、不抢焦点**的悬浮窗
 * （[WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]，与悬浮窗服务同一类型）。
 * 窗口尺寸取真实屏幕尺寸，所以网页拿到的是与可见页一致的视口，布局和 DOM 脚本都能正常跑；
 * `alpha = 0` 保证屏幕上看不到任何东西 —— 用户正在用的那个 App 不会被切走、也不会被盖住。
 *
 * 与旧路径（引擎起 Activity 切到浏览器页）的区别只在"看得见"：静默模式下 AI 不靠截图看网页，
 * 一律用 browse_read 拿 Markdown 正文与可操作元素清单（见 [BrowserBridge] 的说明）。
 * 用户自己点开「浏览器」页时仍然照常可见，那条路径优先级更高（[BrowserBridge] 会先用它）。
 *
 * 建不起来（系统不给悬浮窗权限）时不硬撑：返回 null，调用方回退到旧的"切到可见页"路径。
 * 建好后一直留着复用（省掉每次任务重建 WebView 的开销），随进程结束一起回收。
 */
internal object HeadlessWebHost {

    /** 悬浮窗类型：与 `overlay/FloatingWindowService` 一致，需要"显示在其他应用之上"权限 */
    private const val WINDOW_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var windowManager: WindowManager? = null
    private var hostView: WebView? = null

    /**
     * 取得静默 WebView（首次调用时建好宿主窗口）。
     * **必须在主线程调用**：WebView 只能在带 Looper 的线程上创建。
     * 建不起来返回 null。
     */
    fun ensure(context: Context): WebView? {
        hostView?.let { return it }
        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val size = realSize(ctx)
        val web = runCatching { createBrowserWebView(ctx) }.getOrElse {
            Log.w(TAG, "静默 WebView 创建失败：${it.message}")
            return null
        }
        // 不参与无障碍：AI 的元素树来自"当前前台窗口"，网页节点混进去只会污染感知
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web.setBackgroundColor(0)
        val lp = WindowManager.LayoutParams(
            size.x,
            size.y,
            WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 全透明：窗口照常参与合成（页面照常布局、脚本照常执行），但屏幕上一个像素都看不到。
            // 不用"把窗口挪到屏幕外"：部分 ROM 会把越界窗口直接判成不可见而停掉渲染。
            alpha = 0f
        }
        try {
            wm.addView(web, lp)
        } catch (e: Throwable) {
            Log.w(TAG, "静默浏览器宿主窗口创建失败（可能缺少悬浮窗权限）：${e.message}")
            runCatching { web.destroy() }
            return null
        }
        windowManager = wm
        hostView = web
        return web
    }

    /** 从系统 WindowManager 取真实屏幕尺寸（失败才退回资源尺寸） */
    private fun realSize(ctx: Context): Point {
        val p = Point()
        runCatching {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(p)
        }
        if (p.x <= 0 || p.y <= 0) {
            val dm = ctx.resources.displayMetrics
            p.set(dm.widthPixels, dm.heightPixels)
        }
        return p
    }
}

/**
 * 构造"给 AI 用"的 WebView。
 *
 * **可见页（`ui/browser/BrowserScreen`）与静默宿主共用这一份**：设置与两个客户端只写一遍，
 * 两侧就不会出现"可见页能点、静默页点不动"这种漂移（以前只有 BrowserScreen 一份内联实现）。
 * 加载状态、进度、标题照旧回传给 [BrowserBridge]，桥那边不关心 WebView 挂在哪。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun createBrowserWebView(
    context: Context,
    onStarted: (String) -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onTitle: (String) -> Unit = {},
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    // 不开多窗口：target=_blank 的链接由页面钩子改成同窗打开（见 NavScripts.UNBLANK），
    // 否则 WebView 会静默吞掉这类点击，AI 看起来就是"点了没反应"
    settings.setSupportMultipleWindows(false)
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
            BrowserBridge.onPageStarted()
            onStarted(u.orEmpty())
        }

        override fun onPageFinished(view: WebView?, u: String?) {
            BrowserBridge.onPageFinished(u.orEmpty())
            view?.evaluateJavascript(NavScripts.UNBLANK, null)
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView?, t: String?) {
            BrowserBridge.onTitle(t.orEmpty())
            onTitle(t.orEmpty())
        }
    }
}