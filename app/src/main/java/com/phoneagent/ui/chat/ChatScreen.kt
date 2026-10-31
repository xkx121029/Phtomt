package com.phoneagent.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.ComposerActionButton
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalIsExpanded
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/** 对话页本地气泡：role = user | assistant */
private data class ChatBubble(val role: String, val text: String)

/** 气泡列表的进程级保存：拍平成 role/text 对，恢复时再组装 */
private val ChatBubblesSaver = listSaver<List<ChatBubble>, List<String>>(
    save = { list -> list.map { listOf(it.role, it.text) } },
    restore = { flat -> flat.map { ChatBubble(it[0], it[1]) } },
)

/**
 * 普通对话页（Superagent 融合 Phase 5）：三形态之一的「纯对话」。
 *
 * 数据来源：引擎对话区（[MainViewModel].conversation）——chat 直答把回答写进这里，
 * 页面直接消费展示。没有感知循环、没有计划卡：对话气泡 + 底部输入框，比 Agent 页轻一档。
 * 提交走 [MainViewModel].submitChatTurn（引擎的对话入口）：**只作答、不执行**——
 * 对话页里发一句话不会开启任务执行模式（不设 isRunning、不开悬浮窗 / 跑马灯、
 * 不亮边缘光效）。需要实操手机的请求由直答如实说明，用户到 Agent 页下达。
 *
 * 用户气泡留在页面本地（带进程级保存）：引擎对话区的 user 角色存的是发给模型的
 * 决策 prompt，不适合直接展示，与 Agent 页的理由相同。
 */
@Composable
fun ChatScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val conversation by vm.conversation.collectAsState()
    val conversationStart by vm.conversationStart.collectAsState()

    // 本地气泡：用户提交的提问 + 从引擎对话区消费到的回答
    var bubbles by rememberSaveable(stateSaver = ChatBubblesSaver) { mutableStateOf(listOf<ChatBubble>()) }
    // 已消费的回答数（Int.MIN_VALUE = 尚未定基线）；-1 之类真实值不会撞上
    var consumed by rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    var lastConvStart by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    // 等回答中：chat 直答不进引擎 isRunning 态（那是感知/执行链路的旗标），这里用本地旗标
    var awaitingAnswer by rememberSaveable { mutableStateOf(false) }

    // 新建对话（对话区起点变化）→ 本地气泡清空重新计
    LaunchedEffect(conversationStart) {
        if (lastConvStart != Long.MIN_VALUE && lastConvStart != conversationStart) {
            bubbles = emptyList()
            consumed = Int.MIN_VALUE
        }
        lastConvStart = conversationStart
    }

    // 引擎对话区新增的 assistant 回答（chat 直答写入的那份）追加为本页气泡。
    // 基线策略：首帧 / 对话被清（engine 重置或进程重建）以当前对话长度为基线，
    // 已在保存态里的历史不重复补显，只追加之后新到的。
    val answers = conversation.filter { it.role == "assistant" && it.timestamp >= conversationStart }
    LaunchedEffect(answers.size) {
        when {
            consumed == Int.MIN_VALUE || answers.size < consumed -> consumed = answers.size
            answers.size > consumed -> {
                bubbles = bubbles + answers.subList(consumed, answers.size)
                    .map { ChatBubble(role = "assistant", text = it.content) }
                consumed = answers.size
                awaitingAnswer = false
            }
        }
    }

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val buzz = rememberHapticClick()

    // 新气泡或等待回答中 → 贴底
    LaunchedEffect(bubbles.size, awaitingAnswer) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        buzz()
        bubbles = bubbles + ChatBubble(role = "user", text = text)
        draft = ""
        awaitingAnswer = true
        vm.submitChatTurn(text)
    }

    GlassHeaderScaffold(
        modifier = modifier.fillMaxSize(),
        header = { AppTopBar(title = "对话", subtitle = "和 AI 聊天，回答直接落在这里") },
    ) { contentPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(top = contentPad.calculateTopPadding())
                .padding(horizontal = AppSpacing.Md)
                .padding(LocalNavClearance.current),
        ) {
            if (bubbles.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "有什么想聊的，直接说",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AppSpacing.Md),
                ) {
                    itemsIndexed(bubbles, key = { index, _ -> "bubble-$index" }) { _, bubble ->
                        when (bubble.role) {
                            "user" -> UserBubble(bubble.text)
                            else -> AssistantBubble(bubble.text)
                        }
                    }
                    // 回答生成中：chat 直答一次性落库，先给一条呼吸占位
                    if (awaitingAnswer) {
                        item(key = "thinking") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Loading,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(AppSpacing.Sm))
                                Text(
                                    "正在思考…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 底部输入框：与 Agent 页输入面同一套材质语言（surfaceRaised 底 + 软描边）
            val colors = AppTheme.colors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadii.Card))
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.outlineSoft, RoundedCornerShape(AppRadii.Card))
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
                // 与操控页同一套几何：单行文字在 40dp 高里竖直居中，多行时发送键贴行底
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) {
                                    Text(
                                        "说点什么…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                ComposerActionButton(
                    icon = AppIcons.Send,
                    description = "发送",
                    onClick = { submit() },
                    enabled = draft.isNotBlank(),
                )
            }
            Spacer(Modifier.height(AppSpacing.Sm))
        }
    }
}

/** 宽屏下单条气泡的最大宽度：正文列再宽，气泡也不该拉成一整条 */
private val ChatBubbleMaxWidth = 420.dp

/** 用户气泡：右对齐的主色胶囊面 */
@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                // 窄屏沿用 300dp（占满可用宽度的手感），宽屏放宽到固定上限，
                // 否则平板上一句话会拉成横贯整屏的一条色带
                .widthIn(max = if (LocalIsExpanded.current) ChatBubbleMaxWidth else 300.dp)
                .clip(RoundedCornerShape(AppRadii.Card))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** AI 气泡：左对齐的浮起面，正文走全站统一的 Markdown 渲染 */
@Composable
private fun AssistantBubble(text: String) {
    Box(
        modifier = Modifier
            // 宽屏下气泡不跟着正文列一起拉满：留一段留白，读起来仍是"一条消息"。
            // 顺序必须是先 widthIn 再 fillMaxWidth——反过来 min 宽已被撑满，收不回来
            .widthIn(max = if (LocalIsExpanded.current) 720.dp else Dp.Infinity)
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(AppTheme.colors.surfaceRaised)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        // 正文另有 640dp 的阅读上限：再宽每行会到 70+ 字，视线回行极易串行
        MarkdownPreview(
            content = text,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
        )
    }
}
