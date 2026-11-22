package com.phoneagent.ui.coding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.phoneagent.coding.api.CodingPreviewRenderer
import com.phoneagent.coding.api.CodingPreviewRenderers
import com.phoneagent.ui.agent.MarkdownReaderScreen
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.PhoneAgentTheme

/**
 * [CodingPreviewRenderer] 的宿主实现：编码预览页的整屏 Markdown 阅读器。
 *
 * 主题、图标与阅读器都在宿主这一侧，编码切片只认接口（`coding/api/CodingPreviewRenderer`）。
 */
private object ThemedCodingPreview : CodingPreviewRenderer {

    @Composable
    override fun MarkdownFullscreen(title: String, content: String, onClose: () -> Unit) {
        PhoneAgentTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                MarkdownReaderScreen(
                    title = title,
                    content = content,
                    headline = "工作区 Markdown",
                    icon = AppIcons.Description,
                    backLabel = "返回编辑",
                    backIcon = AppIcons.ArrowBack,
                    onClose = onClose,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 启动时把实现装进编码切片的端口；[com.phoneagent.PhoneAgentApp] 调用一次。 */
fun installCodingPreviewRenderer() {
    CodingPreviewRenderers.install(ThemedCodingPreview)
}
