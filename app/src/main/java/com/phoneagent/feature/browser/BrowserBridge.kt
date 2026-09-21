package com.phoneagent.feature.browser

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import com.phoneagent.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

private const val TAG = "BrowserBridge"

/**
 * 内置浏览器桥：把 AI 的 browse_* 意图接到 App 自己的 WebView 上。
 *
 * 三个边界（与提示词一一对应）：
 * 1. **可见**：浏览器是本 App 的「浏览器」二级页，需要上网时把 App 切到前台，
 *    于是每步截图里就是真实网页 —— AI 能"亲眼看到"页面，而不是只拿一段文本凭空判断。
 * 2. **端侧自足**：网页读写走 DOM 脚本（[BrowserScripts]），不依赖无障碍、Shizuku、Termux，
 *    这些能力缺失时浏览器照常可用。
 * 3. **只操作浏览器里的页**：所有操作都作用于 WebView 里当前这一页；没有打开过网页时，
 *    一律返回可判定的中文原因（引导 AI 先 browse_open），而不是静默无动作。
 *
 * 线程：WebView 只能在主线程碰，故所有交互统一切到主线程；调用方（AgentEngine）在后台协程里 await。
 * 生命周期：Activity 重建会重新 [attach]，引擎侧只需一次 [open]，等待点由 [attachWaiter]/[loadWaiter] 承接。
 */
object BrowserBridge {

    /** 引擎要求 MainActivity 切到「浏览器」二级页 */
    const val EXTRA_BROWSE = "com.phoneagent.extra.BROWSE"

    /** 界面挂载超时：App 被系统冻结/启动慢时不至于把任务卡死 */
    private const val ATTACH_TIMEOUT_MS = 8_000L
    /** 页面加载超时 */
    private const val LOAD_TIMEOUT_MS = 25_000L
    /** 单条注入脚本的执行超时 */
    private const val JS_TIMEOUT_MS = 8_000L
    /** 回注给 AI 的内容上限（与 shell 输出同一量级：网页正文转 Markdown 后需要更多空间） */
    const val MAX_RESULT_CHARS = 4000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var appContext: Context? = null

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var lastUrl: String = ""

    @Volatile
    private var lastTitle: String = ""

    /** 页面是否已加载完成（onPageFinished 后为真，导航开始即置否） */
    @Volatile
    private var pageReady: Boolean = false

    /** 引擎预置的初始网址：界面挂载后由浏览器页取走并加载 */
    private var pendingUrl: String = ""

    private var attachWaiter: CompletableDeferred<Unit>? = null
    private var loadWaiter: CompletableDeferred<Boolean>? = null

    /** 串行化：引擎一次只派一条 browse 动作，这里再加一道锁，挡住异常并发（如用户同时手动点） */
    private val lock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ==================== 浏览器页回调 ====================

    /** 浏览器页挂载 WebView */
    fun attach(wv: WebView) {
        webView = wv
        attachWaiter?.complete(Unit)
    }

    fun detach(wv: WebView) {
        if (webView === wv) {
            webView = null
            pageReady = false
        }
    }

    fun onPageStarted() {
        pageReady = false
    }

    fun onPageFinished(url: String) {
        lastUrl = url
        pageReady = true
        loadWaiter?.complete(true)
    }

    fun onTitle(title: String) {
        lastTitle = title
    }

    /** 取走引擎预置的初始网址（只取一次） */
    fun takePendingUrl(): String {
        val u = pendingUrl
        pendingUrl = ""
        return u
    }

    /** Activity 重建后用于把上一次的网页重新打开，避免网页凭空消失 */
    fun lastUrl(): String = lastUrl

    fun currentPage(): Pair<String, String> = lastUrl to lastTitle

    fun isAttached(): Boolean = webView != null

    // ==================== AI 可调用的 6 个操作 ====================

    /**
     * browse_open：在内置浏览器打开网址。WebView 不在时先把 App 切到「浏览器」页再加载。
     * 等待页面加载完成，让下一步的截图必然是"已经出来的页面"，而不是白屏。
     */
    suspend fun open(url: String): BrowseResult = lock.withLock {
        val ctx = appContext
            ?: return@withLock BrowseResult(false, "内置浏览器尚未初始化，无法打开网页。")
        val waiter = CompletableDeferred<Boolean>()
        loadWaiter = waiter
        val existing = webView
        if (existing != null) {
            withContext(Dispatchers.Main) { existing.loadUrl(url) }
        } else {
            pendingUrl = url
            attachWaiter = CompletableDeferred()
            val started = runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        )
                        putExtra(EXTRA_BROWSE, true)
                    },
                )
            }.isSuccess
            if (!started) {
                loadWaiter = null
                pendingUrl = ""
                return@withLock BrowseResult(false, "无法切到内置浏览器界面，请检查应用是否被系统限制后台启动。")
            }
            val attached = withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attachWaiter?.await() }
            if (attached == null) {
                loadWaiter = null
                pendingUrl = ""
                return@withLock BrowseResult(false, "内置浏览器界面打开超时（${ATTACH_TIMEOUT_MS / 1000}s），请重试。")
            }
        }
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { waiter.await() } ?: false
        loadWaiter = null
        if (!loaded) {
            return@withLock BrowseResult(
                false,
                "网页加载超时（${LOAD_TIMEOUT_MS / 1000}s）：$url。请检查网址是否正确、网络是否可用，或换一个地址。",
            )
        }
        val title = lastTitle.ifBlank { url }
        BrowseResult(true, "已在内置浏览器打开网页（下一步的截图中就能看到它）：\n标题：$title\n网址：$url")
    }

    /** browse_read：抓取当前网页的结构化内容（标题/正文/链接/输入框/按钮） */
    suspend fun read(): BrowseResult = lock.withLock {
        withPage { wv ->
            val obj = eval(wv, BrowserScripts.READ)
                ?: return@withPage BrowseResult(false, "读取网页内容超时，请重试或先 wait 等待页面渲染。")
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                return@withPage BrowseResult(false, "读取网页内容失败：${obj.str("error").ifBlank { "未知原因" }}")
            }
            BrowseResult(true, formatPage(obj))
        }
    }

    /** browse_click：按元素文字（或 CSS 选择器）点击网页元素 */
    suspend fun click(by: String, value: String): BrowseResult = lock.withLock {
        withPage { wv ->
            val obj = eval(wv, BrowserScripts.click(by, value))
                ?: return@withPage BrowseResult(false, "点击网页元素超时，请重试。")
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                return@withPage BrowseResult(false, "点击「$value」失败：${obj.str("error").ifBlank { "未知原因" }}")
            }
            delay(600)
            BrowseResult(
                true,
                "已点击网页元素「${obj.str("clicked").ifBlank { value }}」" +
                    "（${obj.str("tag")}）；当前网址：${lastUrl}",
            )
        }
    }

    /** browse_input：往网页输入框填字 */
    suspend fun input(by: String, value: String, text: String): BrowseResult = lock.withLock {
        withPage { wv ->
            val obj = eval(wv, BrowserScripts.input(by, value, text))
                ?: return@withPage BrowseResult(false, "写入网页输入框超时，请重试。")
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                return@withPage BrowseResult(false, "往「$value」填字失败：${obj.str("error").ifBlank { "未知原因" }}")
            }
            BrowseResult(true, "已往「${obj.str("into").ifBlank { value }}」填入：${obj.str("filled")}")
        }
    }

    /** browse_scroll：滚动网页 */
    suspend fun scroll(direction: String): BrowseResult = lock.withLock {
        withPage { wv ->
            val obj = eval(wv, BrowserScripts.scroll(direction))
                ?: return@withPage BrowseResult(false, "滚动网页超时，请重试。")
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                return@withPage BrowseResult(false, "滚动网页失败：${obj.str("error").ifBlank { "未知原因" }}")
            }
            val atBottom = obj["at_bottom"]?.jsonPrimitive?.contentOrNull == "true"
            BrowseResult(
                true,
                "已滚动网页（$direction）；当前滚动位置 ${obj.str("scroll_y")} / ${obj.str("scroll_height")}" +
                    if (atBottom) "，已到底部" else "",
            )
        }
    }

    /** browse_back：网页内后退（不是系统返回，不会退出浏览器） */
    suspend fun back(): BrowseResult = lock.withLock {
        withPage { wv ->
            val obj = eval(wv, BrowserScripts.BACK)
                ?: return@withPage BrowseResult(false, "网页后退超时，请重试。")
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                return@withPage BrowseResult(false, "网页后退失败：${obj.str("error").ifBlank { "未知原因" }}")
            }
            delay(800)
            BrowseResult(true, "已后退到上一个网页：${lastUrl}")
        }
    }

    // ==================== 内部 ====================

    /** 统一的"必须先有网页"守卫：没有 WebView / 没打开过网页时给出可判定的中文原因 */
    private inline fun withPage(block: (WebView) -> BrowseResult): BrowseResult {
        val wv = webView
            ?: return BrowseResult(
                false,
                "内置浏览器还没打开，请先 browse_open 打开目标网址，再执行这一步。",
            )
        return block(wv)
    }

    /** 在主线程执行注入脚本并解析返回对象；超时或解析失败返回 null */
    private suspend fun eval(wv: WebView, js: String): JsonObject? {
        val raw = withTimeoutOrNull(JS_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) { wv.evaluate(js) }
        }
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }
            .onFailure { Log.w(TAG, "脚本结果解析失败：${raw.take(200)}") }
            .getOrNull()
    }

    private suspend fun WebView.evaluate(js: String): String? = suspendCancellableCoroutine { cont ->
        try {
            evaluateJavascript(js) { value -> if (cont.isActive) cont.resume(value) }
        } catch (e: Throwable) {
            Log.w(TAG, "注入脚本失败：${e.message}")
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.items(key: String): List<JsonObject> =
        runCatching { this[key]?.jsonArray?.mapNotNull { it as? JsonObject } }.getOrNull().orEmpty()

    /**
     * 把抓到的页面整理成紧凑的中文块，直接作为"上一步结果"喂给 AI。
     *
     * 正文是 **Markdown**（标题层级/列表/表格/代码块/内联链接），链接已内联为 `[文字](网址)`，
     * 因此不再单独给"可点链接"清单——AI 要 `browse_click` 时直接取正文里的链接文字。
     * 输入框与按钮仍单独列出：它们是浏览通道特有的**可操作面**（fetch 链路没有），
     * AI 靠它们决定 `browse_input` 的 target 与 `browse_click` 的文字。
     */
    private fun formatPage(o: JsonObject): String {
        val sb = StringBuilder()
        sb.append("标题：").append(o.str("title").ifBlank { "(无标题)" }).append('\n')
        sb.append("网址：").append(o.str("url")).append('\n')
        sb.append("滚动：").append(o["scroll"]?.jsonObject?.str("y")).append('/')
            .append(o["scroll"]?.jsonObject?.str("height")).append('\n')
        val md = o.str("markdown").trim()
        sb.append("正文（Markdown）").append(if (o.str("truncated") == "true") "（已截断）" else "").append("：\n")
        // 给输入框/按钮留出尾部空间，正文预算 = 总预算 - 已用长度 - 预留
        val bodyBudget = (MAX_RESULT_CHARS - sb.length - TAIL_RESERVE).coerceAtLeast(200)
        sb.append(
            if (md.isBlank()) "(页面没有可读正文)"
            else com.phoneagent.core.text.HtmlToMarkdown.takeBlocks(md, bodyBudget),
        ).append('\n')
        val inputs = o.items("inputs")
        if (inputs.isNotEmpty()) {
            sb.append("输入框：\n")
            inputs.take(10).forEachIndexed { i, it2 ->
                sb.append("  ").append(i + 1).append(") ").append(it2.str("hint").ifBlank { "(无提示文字)" })
                    .append("（").append(it2.str("type")).append("）")
                    .append(if (it2.str("id").isNotBlank()) " id=${it2.str("id")}" else "")
                    .append('\n')
            }
        }
        val buttons = runCatching { o["buttons"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrNull().orEmpty()
        if (buttons.isNotEmpty()) sb.append("按钮：").append(buttons.joinToString(" | ")).append('\n')
        return sb.toString().trimEnd().take(MAX_RESULT_CHARS)
    }

    /** 输入框/按钮清单的预留长度：保证它们不会被正文挤掉 */
    private const val TAIL_RESERVE = 400
}

/**
 * 一次浏览器操作的结果。
 * @param ok 是否成功
 * @param text 成功时是给 AI 看的结果内容；失败时是中文原因（可直接回注决策上下文）
 */
data class BrowseResult(val ok: Boolean, val text: String)