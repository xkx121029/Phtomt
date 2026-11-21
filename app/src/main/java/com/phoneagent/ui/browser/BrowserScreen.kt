package com.phoneagent.ui.browser

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.phoneagent.feature.browser.BrowserBridge
import com.phoneagent.feature.browser.createBrowserWebView
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.appBorder
import java.net.URLEncoder

/**
 * 内置浏览器页（全屏二级页）。
 *
 * 它同时承担两件事：
 * 1. 用户在主页点「浏览器」进来时，是一个**能自己用**的浏览窗口：地址栏可输入、
 *    能返回上一页、顶部显示当前网页标题；
 * 2. 用户**自己**开着这一页时，AI 的 browse_* 就直接用这一份 WebView（[BrowserBridge] 里可见路径优先级更高），
 *    于是每步截图里就是真实网页，AI 能"亲眼看到"页面。
 *
 * AI 没开这一页时，网页在后台静默宿主里打开，界面根本不会切过来（见 `HeadlessWebHost`）。
 *
 * 网页读写全部交给 [BrowserBridge] 通过 DOM 脚本完成，本页只负责：承载 WebView、
 * 把加载进度/标题回传给桥、以及最上方那条"当前在哪一页 + 我要去哪儿"的地址栏。
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var hasPage by remember { mutableStateOf(false) }
    /** 地址栏里正在编辑的一行（与已加载的 [url] 分开：编辑中不该被页面回调覆盖） */
    var address by remember { mutableStateOf("") }
    /** WebView 实例：返回键与"前往"都要直接驱动它，单靠回调拿不到 */
    var view by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    fun go(raw: String) {
        val target = navigableUrl(raw) ?: return
        address = target
        view?.loadUrl(target)
    }

    Column(modifier = modifier.fillMaxSize()) {
        AddressStrip(
            address = address,
            title = title,
            url = url,
            hasPage = hasPage,
            canGoBack = canGoBack,
            onAddressChange = { address = it },
            onGo = { go(address) },
            onBack = { view?.takeIf { it.canGoBack() }?.goBack() },
        )
        if (progress in 0.01f..0.99f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // WebView 的设置与两个客户端统一在 createBrowserWebView 里（静默宿主共用同一份），
                    // 这里只接页面进度/标题/起始地址给本页的地址栏，另外挂上桥。
                    createBrowserWebView(
                        context = ctx,
                        onStarted = {
                            url = it
                            address = it
                            hasPage = true
                            canGoBack = false
                        },
                        onProgress = { progress = it / 100f },
                        onTitle = {
                            title = it
                            // 「能否返回」只有在页面换过之后才准，这里顺带刷新
                            canGoBack = view?.canGoBack() == true
                        },
                    ).apply {
                        BrowserBridge.attach(this)
                        // 引擎预置的网址优先；Activity 重建时退回上一次的网页，避免网页凭空消失
                        val pending = BrowserBridge.takePendingUrl()
                        if (pending.isNotBlank()) {
                            url = pending
                            address = pending
                            loadUrl(pending)
                        } else if (BrowserBridge.lastUrl().isNotBlank()) {
                            loadUrl(BrowserBridge.lastUrl())
                        }
                        view = this
                    }
                },
                onRelease = { wv ->
                    BrowserBridge.detach(wv)
                    wv.destroy()
                    view = null
                },
            )
            if (!hasPage) {
                BrowserEmptyHint(Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * 地址栏里的一行输入 → 可加载的 URL。
 *
 * 三条规则覆盖用户真实会打的三种东西：完整网址、省略了协议的域名、想搜的词。
 * 只认 http/https：`javascript:` 之类不会被当成网址执行，而是走搜索——地址栏是用户
 * 手打的入口，不能成为注入口。
 *
 * 返回 null 表示输入为空，调用方不该发请求。
 */
internal fun navigableUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("http://", true) || text.startsWith("https://", true)) return text
    val looksLikeHost = !text.contains(' ') && text.contains('.') && !text.startsWith('.') && !text.endsWith('.')
    if (looksLikeHost) return "https://$text"
    return "https://www.bing.com/search?q=" + URLEncoder.encode(text, "UTF-8")
}

/** 地址栏：可输入的网址 + 当前标题 + 返回；空态也照样可输入，不再是一条读作禁用的灰条 */
@Composable
private fun AddressStrip(
    address: String,
    title: String,
    url: String,
    hasPage: Boolean,
    canGoBack: Boolean,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppTheme.metrics.itemRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
    ) {
        // 返回键常驻但不可用时置灰：用户一眼知道"这里能退回去"，而不是等有网页了才冒出来
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = "后退",
                tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else colors.onSurfaceRaised,
                modifier = Modifier.size(20.dp),
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .appBorder(1.dp, colors.outlineSoft, shape)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (hasPage) AppIcons.Globe else AppIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Column(Modifier.weight(1f)) {
                BasicTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    decorationBox = { inner ->
                        if (address.isBlank()) {
                            Text(
                                // 空态直接说清能干什么：UI-05 里这条灰条被读成过禁用控件
                                text = "输入网址或搜索",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasPage) {
                    Text(
                        text = title.ifBlank { url },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = "前往",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (address.isBlank()) colors.onSurfaceRaised else colors.brand,
            modifier = Modifier
                .clip(RoundedCornerShape(AppTheme.metrics.chipRadius))
                .background(if (address.isBlank()) Color.Transparent else colors.brandContainer)
                .clickable(enabled = address.isNotBlank(), onClick = onGo)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        )
    }
}

@Composable
private fun BrowserEmptyHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(AppSpacing.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(AppTheme.metrics.cardRadius),
    ) {
        Column(
            modifier = Modifier
                .appBorder(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(AppTheme.metrics.cardRadius),
                )
                .clip(RoundedCornerShape(AppTheme.metrics.cardRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(AppSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            Text(
                text = "还没有打开网页",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "在上面的地址栏输入网址、或者想搜的内容，点「前往」就能打开。" +
                    "也可以在「助手」页直接说「打开 xxx」，我来替你去浏览。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "AI 自己上网时默认在后台进行，界面不会自动切到这里；你开着这一页时，它的网页操作会直接用这个窗口显示。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}