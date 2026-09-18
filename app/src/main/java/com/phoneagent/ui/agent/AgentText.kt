package com.phoneagent.ui.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.phoneagent.domain.rules.EngineRules
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.MarkdownPreview
import kotlinx.coroutines.delay

@Composable
internal fun rememberTranslated(text: String, vm: MainViewModel): String {
    var translated by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        if (text.isBlank() || EngineRules.isMostlyChinese(text)) {
            translated = null
        } else {
            delay(600) // 流式输出时防止频繁触发，等待停顿后翻译
            translated = vm.translate(text)
        }
    }
    return when {
        text.isBlank() -> text
        EngineRules.isMostlyChinese(text) -> text
        else -> translated ?: text
    }
}

/**
 * 消息正文：先按需翻译，再交给 [MarkdownPreview] 渲染。
 *
 * 用本地确定性解析器而不是第三方库，是因为它会把**未闭合的 ``` 代码块 flush 输出**，
 * 天然适配规划流的半截 markdown（流式过程中每 150ms 就会重算一次）。
 */
@Composable
internal fun AgentMessageText(
    text: String,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val rendered = rememberTranslated(text, vm)
    if (rendered.isBlank()) return
    MarkdownPreview(content = rendered, modifier = modifier)
}