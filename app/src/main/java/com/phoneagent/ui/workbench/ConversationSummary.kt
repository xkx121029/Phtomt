package com.phoneagent.ui.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.phoneagent.engine.AgentEngine
import com.phoneagent.engine.coding.CodingAgentBridge
import com.phoneagent.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 一轮对话结束后才请求摘要，流式输出期间不调用模型。 */
class ConversationSummary(private val summarize: suspend (String) -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var submitted = ""
    private val _text = MutableStateFlow("开始一段新的对话")
    val text = _text.asStateFlow()

    fun reset() {
        job?.cancel()
        submitted = ""
        _text.value = "开始一段新的对话"
    }

    fun submit(transcript: String, fallback: String) {
        if (transcript.isBlank() || transcript == submitted) return
        submitted = transcript
        _text.value = fallback.take(48)
        job?.cancel()
        job = scope.launch {
            val summary = summarize(transcript)
            if (summary.isNotBlank()) _text.value = summary
        }
    }
}

/** 摘要走引擎已有的模型连接，不新增第二套客户端，也不经任务执行链路。 */
@Composable
fun rememberConversationSummary(): ConversationSummary {
    val engine = koinInject<AgentEngine>()
    return remember(engine) { ConversationSummary(engine::summarizeConversation) }
}

/** 经典 Agent 模式与工作台共用同一份摘要逻辑。 */
@Composable
fun rememberCodingConversationSummaryText(bridge: CodingAgentBridge): String {
    val summary = rememberConversationSummary()
    val text by summary.text.collectAsState()
    val state by bridge.uiClient.state.collectAsState()
    LaunchedEffect(state.busy, state.messages.lastOrNull()) {
        if (!state.busy) {
            val transcript = state.messages.map { (if (it.role == "user") "用户：" else "助手：") + it.text }
                .joinToString("\n").takeLast(7000)
            if (transcript.isBlank()) summary.reset()
            else summary.submit(transcript, state.messages.lastOrNull { it.role == "user" }?.text ?: "继续 Coding 对话")
        }
    }
    return text
}

@Composable
fun rememberConversationSummaryText(vm: MainViewModel): String {
    val summary = rememberConversationSummary()
    val text by summary.text.collectAsState()
    val state by vm.agentState.collectAsState()
    val turns by vm.chatTurns.collectAsState()
    val busy = state.isRunning || turns.lastOrNull()?.answer?.isBlank() == true
    LaunchedEffect(busy, turns.lastOrNull()) {
        if (!busy) {
            val transcript = buildList {
                if (state.task.isNotBlank()) add("用户：" + state.task)
                if (state.message.isNotBlank()) add("助手：" + state.message)
                turns.forEach {
                    add("用户：" + it.question)
                    if (it.answer.isNotBlank()) add("助手：" + it.answer)
                }
            }.joinToString("\n").takeLast(7000)
            if (transcript.isBlank()) summary.reset()
            else summary.submit(transcript, turns.lastOrNull()?.question ?: state.task)
        }
    }
    return text
}
