package com.phoneagent.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.phoneagent.feature.browser.BrowserBridge
import com.phoneagent.feature.browser.createBrowserWebView
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing

/**
 * 内置浏览器页（全屏二级页）。
 *
 * 它同时承担两件事：
 * 1. 用户在主页点「浏览器」进来时，是一个干净的浏览窗口；
 * 2. 用户**自己**开着这一页时，AI 的 browse_* 就直接用这一份 WebView（[BrowserBridge] 里可见路径优先级更高），
 *    于是每步截图里就是真实网页，AI 能"亲眼看到"页面。
 *
 * AI 没开这一页时，网页在后台静默宿主里打开，界面根本不会切过来（见 `HeadlessWebHost`）。
 *
 * 网页读写全部交给 [BrowserBridge] 通过 DOM 脚本完成，本页只负责：承载 WebView、
 * 把加载进度/标题回传给桥、以及在最上方给出一条"当前在哪一页"的地址条。
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var hasPage by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        AddressStrip(title = title, url = url)
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
                    // 这里只接页面进度/标题/起始地址给本页的地址条，另外挂上桥。
                    createBrowserWebView(
                        context = ctx,
                        onStarted = {
                            url = it
                            hasPage = true
                        },
                        onProgress = { progress = it / 100f },
                        onTitle = { title = it },
                    ).apply {
                        BrowserBridge.attach(this)
                        // 引擎预置的网址优先；Activity 重建时退回上一次的网页，避免网页凭空消失
                        val pending = BrowserBridge.takePendingUrl()
                        if (pending.isNotBlank()) {
                            url = pending
                            loadUrl(pending)
                        } else if (BrowserBridge.lastUrl().isNotBlank()) {
                            loadUrl(BrowserBridge.lastUrl())
                        }
                    }
                },
                onRelease = { wv ->
                    BrowserBridge.detach(wv)
                    wv.destroy()
                },
            )
            if (!hasPage) {
                EmptyHint(Modifier.align(Alignment.Center))
            }
        }
    }
}

/** 地址条：当前网页标题 + 网址，让用户（和截图）一眼知道现在在哪一页 */
@Composable
private fun AddressStrip(title: String, url: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(AppRadii.Item),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Globe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = title.ifBlank { "内置浏览器" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (url.isNotBlank()) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(AppSpacing.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(AppRadii.Card),
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(AppRadii.Card),
                )
                .clip(RoundedCornerShape(AppRadii.Card))
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
                text = "AI 上网默认在后台静默进行（界面不会自动切到这里）；你打开这一页时，它的网页操作会直接用这个窗口显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}