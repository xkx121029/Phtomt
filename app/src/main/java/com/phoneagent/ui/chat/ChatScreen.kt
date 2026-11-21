package com.phoneagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.phoneagent.ui.components.ComposerField
import com.phoneagent.ui.components.ComposerPanel
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalIsExpanded
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/** 对话页本地气泡：role = user | assistant */
internal data class ChatBubble(val role: String, val text: String)

/**
 * 对话页的会话状态（气泡 + 同步游标），由 [com.phoneagent.ui.MainViewModel] 持有。
 *
 * 气泡数量随会话线性增长，之前整份序列化进 rememberSaveable，等于把整段对话塞进
 * Bundle；长会话会顶到 Bundle 的 1MB 上限（TransactionTooLargeException）。
 * 放到 ViewModel 后：跨 Activity 重建（旋转、深浅色切换）存活，且不占 Bundle 一个字节。
 * 代价是进程被系统回收后页面本地气泡从头开始——引擎对话区里的回答是落盘的，
 * 由新回答按基线重新起算，与「进程重建后不重复补显历史」的既有策略一致。
 */
internal class ChatSessionState {
    /** 已渲染的气泡（用户提问 + 引擎回答） */
    var bubbles by mutableStateOf<List<ChatBubble>>(emptyList())

    /** 已消费的回答数（Int.MIN_VALUE = 尚未定基线）；-1 之类真实值不会撞上 */
    var consumed by mutableIntStateOf(Int.MIN_VALUE)

    /** 上一帧的对话起点，用于识别「新建对话」 */
    var lastConvStart by mutableLongStateOf(Long.MIN_VALUE)

    /** 等回答中：chat 直答不进引擎 isRunning 态（那是感知/执行链路的旗标），这里用本地旗标 */
    var awaitingAnswer by mutableStateOf(false)
}

/**
 * 普通对话页（Superagent 融合 Phase 5）：三形态之一的「纯对话」。
 *
 * 数据来源：引擎对话区（[MainViewModel].conversation）——chat 直答把回答写进这里，
 * 页面直接消费展示。没有感知循环、没有计划卡：对话气泡 + 底部输入框，比 Agent 页轻一档。
 * 提交走 [MainViewModel].submitChatTurn（引擎的对话入口）：**只作答、不执行**——
 * 对话页里发一句话不会开启任务执行模式（不设 isRunning、不开悬浮窗 / 跑马灯、
 * 不亮边缘光效）。需要实操手机的请求由直答如实说明，用户到 Agent 页下达。
 *
 * 用户气泡不来自引擎，由 [ChatSessionState] 单独记账（ViewModel 持有，不进 Bundle）：
 * 引擎对话区的 user 角色存的是发给模型的决策 prompt，不适合直接展示，与 Agent 页的理由相同。
 */
@Composable
fun ChatScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val conversation by vm.conversation.collectAsState()
    val conversationStart by vm.conversationStart.collectAsState()

    // 本地气泡：用户提交的提问 + 从引擎对话区消费到的回答。
    // 寄存在 ViewModel 里而不是 rememberSaveable：见 ChatSessionState 的注释。
    val session = vm.chatSession
    val bubbles = session.bubbles

    // 新建对话（对话区起点变化）→ 本地气泡清空重新计
    LaunchedEffect(conversationStart) {
        if (session.lastConvStart != Long.MIN_VALUE && session.lastConvStart != conversationStart) {
            session.bubbles = emptyList()
            session.consumed = Int.MIN_VALUE
        }
        session.lastConvStart = conversationStart
    }

    // 引擎对话区新增的 assistant 回答（chat 直答写入的那份）追加为本页气泡。
    // 基线策略：首帧 / 对话被清（engine 重置或进程重建）以当前对话长度为基线，
    // 已在保存态里的历史不重复补显，只追加之后新到的。
    val answers = conversation.filter { it.role == "assistant" && it.timestamp >= conversationStart }
    LaunchedEffect(answers.size) {
        when {
            session.consumed == Int.MIN_VALUE || answers.size < session.consumed ->
                session.consumed = answers.size

            answers.size > session.consumed -> {
                session.bubbles = session.bubbles + answers.subList(session.consumed, answers.size)
                    .map { ChatBubble(role = "assistant", text = it.content) }
                session.consumed = answers.size
                session.awaitingAnswer = false
            }
        }
    }

    var draft by rememberSaveable { mutableStateOf("") }
    // 滚动位置由 rememberLazyListState 自带的 saver 走 Bundle（只有两个 Int，和气泡数量无关）
    val listState = rememberLazyListState()
    val buzz = rememberHapticClick()

    // 新气泡或等待回答中 → 贴底
    LaunchedEffect(bubbles.size, session.awaitingAnswer) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        buzz()
        session.bubbles = session.bubbles + ChatBubble(role = "user", text = text)
        draft = ""
        session.awaitingAnswer = true
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
                    if (session.awaitingAnswer) {
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

            // 底部输入框：与 Agent / Coding 页同一个面板组件，容器、胶囊、描边、行高只定义一次
            ComposerPanel(
                field = { fieldModifier ->
                    ComposerField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "说点什么…",
                        enabled = true,
                        modifier = fieldModifier,
                        maxLines = 4,
                    )
                },
                actions = {
                    ComposerActionButton(
                        icon = AppIcons.Send,
                        description = "发送",
                        onClick = { submit() },
                        enabled = draft.isNotBlank(),
                    )
                },
            )
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
                .clip(RoundedCornerShape(AppTheme.metrics.cardRadius))
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
            .clip(RoundedCornerShape(AppTheme.metrics.cardRadius))
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
