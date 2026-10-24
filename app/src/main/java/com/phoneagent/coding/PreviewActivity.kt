package com.phoneagent.coding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.phoneagent.coding.device.DeviceWorkspace

/**
 * 代码预览（#30）：把工作区里的 HTML 页面直接渲染出来。
 *
 * 与内置浏览器刻意分成两个 WebView：浏览器那份是「agent 访问互联网」的长期会话
 * （在 HPA 里由主工程的浏览器体系承担），预览这份是一次性的本地渲染。混在一起
 * 会让退出一页就把另一页的状态一起带走。这里的页面只在打开预览期间存在，退出即销毁。
 *
 * 目标既可以是工作区相对路径（渲染本地页面），也可以是 http(s) 链接
 * （渲染 agent 起的预览服务器）。
 */
class PreviewActivity : Activity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()

        val webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#111214"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // allowFileAccess 是 file:// 基础页本身加载所需（API 30+ 默认已关）。
            //
            // 安全修复（2026-10-21）：原来这里还开了
            //   allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs
            // 两者一开，预览页里的 JS 就能 XHR 读 file:/// 下的**应用私有文件**
            // （shared_prefs 里的 API Key/token 是明文）再 fetch 外网——
            // 而预览的 HTML 恰恰是 agent 写进工作区的不可信内容。
            // 关掉后：同源 file 页面仍可加载同目录 css/js（script/link 标签不受影响），
            // 代价是页面内 fetch('/data.json') 这类同目录 XHR 不再可用——
            // 相对于密钥外泄，这个代价必须付。
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            // 预览页只该访问它自己（本地文件或 agent 起的本地预览服务）。
            // mixedContent 保持默认（never），不主动放行 http 子资源。
        }
        webView.loadUrl(urlFor(target))
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
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /** 工作区相对路径 -> file:// URL；http(s) 原样透传。越界的路径会被拒绝（落到空白页）。 */
    private fun urlFor(target: String): String {
        val t = target.trim()
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:")) return t
        return try {
            "file://" + Uri.encode(DeviceWorkspace.resolveWithin(this, t).absolutePath)
        } catch (_: Exception) {
            "about:blank"
        }
    }

    companion object {
        private const val EXTRA_TARGET = "target"

        /** 打开预览；[target] 是工作区相对路径或 http(s) 链接。 */
        fun open(context: Context, target: String) {
            if (target.isBlank()) return
            val intent = Intent(context, PreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TARGET, target)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                /* 后台里拉不起界面就静默失败 */
            }
        }
    }
}
