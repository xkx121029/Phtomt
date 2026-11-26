package com.phoneagent.coding.api

import androidx.compose.runtime.Composable

/**
 * 整屏 Markdown 阅读器的宿主端口。
 *
 * 编码切片自己不带主题、图标与阅读器（这些是宿主 UI 的东西）。预览页反过来 import
 * `ui.theme` / `ui.agent` 就等于把切片钉死在宿主上——将来把 Coding 摘出去时 ui 包不在，
 * 编译直接断。所以这里只声明「要一块整屏 Markdown 画面」，实现由宿主在启动时注入。
 */
interface CodingPreviewRenderer {
    /**
     * 整屏展示一份 Markdown（[title] 是文件名），[onClose] 回到编辑页。
     */
    @Composable
    fun MarkdownFullscreen(title: String, content: String, onClose: () -> Unit)
}

/**
 * 端口注册表：宿主在 `Application.onCreate` 里 [install]，预览页只读。
 *
 * 只允许装一次；没装时预览页退回 WebView（见 [com.phoneagent.coding.PreviewActivity]），
 * 不会黑屏也不会崩。
 */
object CodingPreviewRenderers {

    @Volatile
    private var current: CodingPreviewRenderer? = null

    fun install(renderer: CodingPreviewRenderer) {
        current = renderer
    }

    val installed: CodingPreviewRenderer? get() = current
}
