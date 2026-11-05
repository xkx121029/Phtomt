package com.phoneagent.coding

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import com.phoneagent.coding.device.DeviceWorkspace
import com.phoneagent.ui.agent.MarkdownReaderScreen
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.PhoneAgentTheme

/**
 * 代码预览（#30）：把工作区里的页面渲染出来。
 *
 * 与内置浏览器刻意分成两个 WebView：浏览器那份是「agent 访问互联网」的长期会话
 * （在 HPA 里由主工程的浏览器体系承担），预览这份是一次性的本地渲染。混在一起
 * 会让退出一页就把另一页的状态一起带走。这里的页面只在打开预览期间存在，退出即销毁。
 *
 * 目标既可以是工作区相对路径（渲染本地页面），也可以是 http(s) 链接
 * （渲染 agent 起的预览服务器）。
 *
 * Markdown 文件（.md/.markdown）不进 WebView：源码里的 `#`、`*`、`|` 原样显示出来
 * 等于没排版，转而交给编码页同款的 [MarkdownReaderScreen] 整屏阅读。
 */
class PreviewActivity : ComponentActivity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        if (isMarkdown(target)) showMarkdown(target) else showWeb(target)
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /** 只有工作区里的 .md/.markdown 走阅读器；http(s)/about: 与其它扩展名仍交给 WebView。 */
    private fun isMarkdown(target: String): Boolean {
        val t = target.trim()
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:")) return false
        // 去掉查询串与锚点再判扩展名：editor 传过来的路径可能带这些尾巴
        val path = t.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".md") || path.endsWith(".markdown")
    }

    private fun showMarkdown(target: String) {
        enableEdgeToEdge()
        val loaded = runCatching { DeviceWorkspace.read(this, target) }
        val name = target.trim().replace('\\', '/').substringAfterLast('/')
        setContent {
            PhoneAgentTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    MarkdownReaderScreen(
                        title = name,
                        content = loaded.fold(
                            onSuccess = { json ->
                                val text = json.optString("content")
                                if (json.optBoolean("truncated")) {
                                    // 截断必须明说：少一段正文却读起来"到头了"，比报错更难排查
                                    text + "\n\n---\n\n> 文件超过 512 KB，后面的内容没有读出来。"
                                } else {
                                    text
                                }
                            },
                            onFailure = { "读取失败：${it.message ?: "未知原因"}" },
                        ),
                        headline = "工作区 Markdown",
                        icon = AppIcons.Description,
                        backLabel = "返回编辑",
                        backIcon = AppIcons.ArrowBack,
                        onClose = { finish() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** HTML 等其它目标：一次性 WebView 渲染（原有行为不变）。 */
    private fun showWeb(target: String) {
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
