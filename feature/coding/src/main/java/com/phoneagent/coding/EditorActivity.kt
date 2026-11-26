package com.phoneagent.coding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject
import com.phoneagent.coding.device.DeviceWorkspace

/**
 * WebView 代码编辑器（#29）。
 *
 * 编辑区是随包发布的一份静态页面（`assets/editor/index.html`：文件树 + 行号 +
 * 语法高亮 + 按扩展名的关键字补全）。它不直接碰文件系统，而是通过 [Bridge] 这个
 * JS 接口让 Kotlin 侧读写设备工作区——页面只管画和编辑，文件 IO 只有一个入口，
 * 权限边界也就只有一道。
 *
 * 这些文件离线也要能打开：编辑器不依赖 agent 在线，本地模式下工作区就是 agent
 * 的工作目录，两边看的是同一份文件。
 */
class EditorActivity : Activity() {

    private var web: WebView? = null
    private var initial: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initial = intent.getStringExtra(EXTRA_PATH).orEmpty()

        val webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // assets/editor/index.html 是 file:// 页面，需要读同源的资源。
            allowFileAccess = true
            allowContentAccess = true
            // 纵深防御（2026-10-21）：页面是随包资产、桥接口也只碰工作区内路径，
            // 但 file→file / file→任意 origin 的 XHR 一律不给开——编辑器一旦
            // 引入任何远程/不可信内容，这两个开关就是全线失守的那道门。
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        webView.addJavascriptInterface(Bridge(), "ACAEditor")
        webView.loadUrl(ASSET_URL)
        web = webView

        setContentView(
            FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )
    }

    override fun onDestroy() {
        web?.let {
            it.removeJavascriptInterface("ACAEditor")
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    /**
     * 暴露给页面的全部能力。@JavascriptInterface 的方法跑在 WebView 的 JavaBridge
     * 线程上（不是主线程），所以里面的文件 IO 不会卡住界面——这正是我们要的。
     */
    private inner class Bridge {
        @JavascriptInterface
        fun root(): String = DeviceWorkspace.root(this@EditorActivity).absolutePath

        @JavascriptInterface
        fun tree(): String = DeviceWorkspace.tree(this@EditorActivity).toString()

        @JavascriptInterface
        fun initialPath(): String = initial

        @JavascriptInterface
        fun read(path: String): String = json { DeviceWorkspace.read(this@EditorActivity, path) }

        @JavascriptInterface
        fun write(path: String, content: String): String =
            json { DeviceWorkspace.write(this@EditorActivity, path, content) }

        @JavascriptInterface
        fun create(path: String): String = json { DeviceWorkspace.create(this@EditorActivity, path) }

        /** 用别的窗口看这一页（#30）。HTML 才有意义，但入口不设限，由预览页自己判断。 */
        @JavascriptInterface
        fun preview(path: String) {
            runOnUiThread { PreviewActivity.open(this@EditorActivity, path) }
        }
    }

    /** 统一的返回信封：成功 `{ok:true,...}`，失败 `{ok:false,error:"中文原因"}`。 */
    private fun json(block: () -> JSONObject): String = try {
        block().put("ok", true).toString()
    } catch (e: Throwable) {
        JSONObject().put("ok", false).put("error", e.message ?: e.toString()).toString()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val ASSET_URL = "file:///android_asset/editor/index.html"

        /** 打开编辑器；[path] 是工作区相对路径，给了就直接定位到那个文件。 */
        fun open(context: Context, path: String? = null) {
            val intent = Intent(context, EditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!path.isNullOrBlank()) intent.putExtra(EXTRA_PATH, path)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                /* 后台里拉不起界面就静默失败，不做无谓的兜底 */
            }
        }
    }
}
