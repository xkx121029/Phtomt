package com.phoneagent.ui.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val TICK_MS = 40L
private const val BASE_CPS = 30          // 常态 30 字/秒
private const val CATCH_UP_CPS = 120     // 积压时 120 字/秒
private const val CATCH_UP_THRESHOLD = 80

/**
 * 打字机：把"分块到达的流式文本"摊成连续吐字。
 *
 * 三条规则：
 * 1. 新文本**不以已显示内容为前缀**（超长截断丢了头部）→ 立即整段切换，不重打；
 * 2. 新文本以已显示内容为前缀 → 已显示的字符一个不动，只按节奏追加后续字符；
 * 3. 无新增时不空转、不出字（AI 停顿 2 秒，界面就安静 2 秒，符合"可以间断"）。
 *
 * 节奏：常态 [BASE_CPS] 字符/秒；积压超过 [CATCH_UP_THRESHOLD] 字符时升到
 * [CATCH_UP_CPS]，避免长文本被永久拖在后面。
 *
 * 状态必须留在**调用它的 item 组件内部**：提到 AgentScreen 的 items 计算里
 * 会导致每 tick 重建整条 LazyColumn。
 */
@Composable
internal fun rememberTypedText(target: String, key: Any = Unit): String {
    val latest by rememberUpdatedState(target)
    var shown by remember(key) { mutableStateOf("") }
    LaunchedEffect(key) {
        while (true) {
            val wanted = latest
            when {
                // 丢头（截断）或换任务：直接切换，不回退打字
                !wanted.startsWith(shown) -> shown = wanted
                wanted.length > shown.length -> {
                    val backlog = wanted.length - shown.length
                    val cps = if (backlog > CATCH_UP_THRESHOLD) CATCH_UP_CPS else BASE_CPS
                    val step = (cps * TICK_MS / 1000).toInt().coerceAtLeast(1)
                    shown = wanted.substring(0, (shown.length + step).coerceAtMost(wanted.length))
                }
            }
            delay(TICK_MS)
        }
    }
    return shown
}