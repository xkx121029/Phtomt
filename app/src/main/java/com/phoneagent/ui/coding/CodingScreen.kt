package com.phoneagent.ui.coding

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.coding.CodingRuntimeState
import com.phoneagent.coding.agent.ChatMessage
import com.phoneagent.coding.agent.CodingAgentConfig
import com.phoneagent.coding.agent.ConnState
import com.phoneagent.coding.agent.Workspace
import com.phoneagent.engine.coding.CodingAgentBridge
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.motionSettings
import org.koin.compose.koinInject

/** 与 CodingAgentBridge 注入 agent-core 的桥接 token 一致（桥内为私有常量，此处同值） */
private const val BRIDGE_TOKEN = "phoneagent-bridge"

/**
 * 编码页（Superagent 融合 Phase 5）：三形态之一的 vibecoding。
 *
 * 顶部运行时状态条消费 [CodingRuntimeManager] 状态机（内置就绪/缺失，只读展示），
 * 对话流消费 [CodingAgentBridge].client.state（与引擎路由委托的编码会话同源，
 * 引擎发起的编码任务在这里实时可见）。审批以内嵌卡片呈现——项目铁律：审批绝不走系统弹窗。
 * 运行时未就绪时输入框禁用，先排查安装包是否完整。
 */
@Composable
fun CodingScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val runtime = koinInject<com.phoneagent.coding.CodingRuntimeManager>()
    val bridge = koinInject<CodingAgentBridge>()
    val runtimeState by runtime.state.collectAsState()
    val clientState by bridge.client.state.collectAsState()

    val context = LocalContext.current
    val snackbar = LocalSnackbar.current

    LaunchedEffect(Unit) { runtime.refresh() }

    // 输入框提交：已连接直接发；运行时就绪但未连接则先连（token/配置与桥同源），连上后补发
    var pendingSend by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(clientState.conn) {
        val text = pendingSend
        if (clientState.conn == ConnState.CONNECTED && text != null) {
            pendingSend = null
            bridge.client.sendPrompt(text)
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || clientState.busy) return
        when {
            clientState.conn == ConnState.CONNECTED -> bridge.client.sendPrompt(text)
            runtimeState is CodingRuntimeState.Installed -> {
                pendingSend = text
                val s = vm.settingsFlow.value
                bridge.client.connectLocal(
                    CodingAgentConfig(
                        provider = "openai",
                        baseUrl = s.apiBaseUrl,
                        apiKey = s.apiKey,
                        model = s.model,
                        approval = s.codingApprovalMode,
                        token = BRIDGE_TOKEN,
                    ),
                )
            }
            else -> snackbar?.show("编码运行时未就绪，请安装完整打包的 APK")
        }
    }

    GlassHeaderScaffold(
        modifier = modifier.fillMaxSize(),
        header = { AppTopBar(title = "编码", subtitle = "vibecoding · 设备端编码 Agent") },
    ) { contentPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(top = contentPad.calculateTopPadding())
                .padding(horizontal = AppSpacing.Md)
                .padding(LocalNavClearance.current),
        ) {
            RuntimeStatusBar(
                runtimeState = runtimeState,
                conn = clientState.conn,
                onNewSession = { bridge.client.newSession() },
            )

            Spacer(Modifier.height(AppSpacing.Sm))

            WorkspaceRow(onOpen = {
                if (!openWorkspaceDirectory(context)) snackbar?.show("无法打开文件管理器查看该目录")
            })

            if (clientState.error != null) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    clientState.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            ConversationFlow(
                messages = clientState.messages,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            CodingComposer(
                busy = clientState.busy,
                enabled = runtimeState is CodingRuntimeState.Installed,
                onStop = { bridge.client.cancel() },
                onSend = { send(it) },
            )
            Spacer(Modifier.height(AppSpacing.Sm))
        }
    }
}

/**
 * 运行时状态条：内置就绪给绿色就绪态，缺失给只读错误态（无下载/安装入口——运行时
 * 随主 APK 打包，缺失属于安装包不完整）；右侧是连接状态点与「新会话」入口。
 * 状态切换走自下而上的进入动效（全站 Motion 约定）。
 */
@Composable
private fun RuntimeStatusBar(
    runtimeState: CodingRuntimeState,
    conn: ConnState,
    onNewSession: () -> Unit,
) {
    val buzz = rememberHapticClick()
    val motion = motionSettings()
    val duration = motion.scaledDuration(DurationSlow)
    val colors = AppTheme.colors

    val shape = RoundedCornerShape(AppRadii.Card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "编码运行时",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            ConnDot(conn)
            Spacer(Modifier.width(AppSpacing.Sm))
            OutlinedButton(onClick = { buzz(); onNewSession() }, contentPadding = PaddingValues(horizontal = AppSpacing.Md)) {
                Text("新会话", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(AppSpacing.Sm))
        AnimatedContent(
            targetState = runtimeState,
            transitionSpec = {
                if (motion.reduceMotion) {
                    fadeIn(tween(duration, easing = EaseOut)) togetherWith fadeOut(tween(duration, easing = EaseOut))
                } else {
                    (fadeIn(tween(duration, easing = EaseOut)) +
                        slideInVertically(tween(duration, easing = EaseOut)) { it / 12 })
                        .togetherWith(fadeOut(tween(duration, easing = EaseOut)))
                }
            },
            label = "runtime-state",
            modifier = Modifier.fillMaxWidth(),
        ) { st ->
            when (st) {
                is CodingRuntimeState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.success),
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Text(
                        "内置就绪 v${st.versionName} · Node 宿主",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.success,
                    )
                }

                is CodingRuntimeState.NotReady -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Text(
                        st.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 连接状态点：已连接绿、连接中主色、未连接灰 */
@Composable
private fun ConnDot(conn: ConnState) {
    val colors = AppTheme.colors
    val (color, label) = when (conn) {
        ConnState.CONNECTED -> colors.success to "已连接"
        ConnState.CONNECTING -> MaterialTheme.colorScheme.primary to "连接中"
        ConnState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to "未连接"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 工作区入口：展示路径，点击用系统文件管理器查看该目录 */
@Composable
private fun WorkspaceRow(onOpen: () -> Unit) {
    val context = LocalContext.current
    val buzz = rememberHapticClick()
    val path = remember { Workspace.resolve(context).absolutePath }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .clickable { buzz(); onOpen() }
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text("工作区", style = MaterialTheme.typography.labelMedium)
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 编码会话流：user/assistant 气泡 + 工具调用卡 + 审批卡 */
@Composable
private fun ConversationFlow(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    if (messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "描述要写的代码或脚本，编码 Agent 会自己动手",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        contentPadding = PaddingValues(vertical = AppSpacing.Md),
    ) {
        items(messages, key = { it.key }) { msg ->
            when (msg.role) {
                "user" -> CodingUserBubble(msg.text)
                "assistant" -> CodingAssistantBubble(msg.text)
                "tool" -> ToolCard(msg)
                "approval" -> ApprovalCard(msg)
            }
        }
    }
}

@Composable
private fun CodingUserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
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

@Composable
private fun CodingAssistantBubble(text: String) {
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(AppTheme.colors.surfaceRaised)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        MarkdownPreview(content = text, modifier = Modifier.fillMaxWidth())
    }
}

/** 工具调用卡：工具名 + 参数摘要 + 执行态（运行中 / 成功 / 失败） */
@Composable
private fun ToolCard(msg: ChatMessage) {
    val colors = AppTheme.colors
    val (icon, tint, label) = when (msg.toolOk) {
        null -> Triple(AppIcons.Loading, MaterialTheme.colorScheme.primary, "运行中…")
        true -> Triple(AppIcons.CheckCircle, colors.success, "完成")
        false -> Triple(AppIcons.Cancel, MaterialTheme.colorScheme.error, "失败")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Chip))
            .background(AppTheme.colors.surfaceRaised)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                msg.toolName ?: "工具",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (msg.text.isNotBlank()) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.Sm))
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * 审批卡（铁律）：编码 agent 请求执行敏感工具时的内嵌审批——允许 / 拒绝就在卡片上，
 * 决议后置灰落定，绝不弹系统对话框。
 */
@Composable
private fun ApprovalCard(msg: ChatMessage) {
    val buzz = rememberHapticClick()
    val client = koinInject<CodingAgentBridge>().client
    val callId = msg.approvalCallId
    val resolved = msg.approvalResolved
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(colors.surfaceRaised)
            .padding(AppSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.Guard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Text(
                "编码 Agent 请求执行工具",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            msg.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        if (resolved == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                Button(
                    onClick = {
                        buzz()
                        if (callId != null) client.respondApproval(callId, true)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("允许") }
                OutlinedButton(
                    onClick = {
                        buzz()
                        if (callId != null) client.respondApproval(callId, false)
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text("拒绝") }
            }
        } else {
            Text(
                if (resolved) "已允许" else "已拒绝",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 底部输入区：busy 显示「编码中…」与停止按钮；运行时未安装时整体禁用并提示 */
@Composable
private fun CodingComposer(
    busy: Boolean,
    enabled: Boolean,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val buzz = rememberHapticClick()
    val colors = AppTheme.colors
    val canSend = enabled && !busy && draft.isNotBlank()

    Column(Modifier.fillMaxWidth()) {
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = AppSpacing.Xs),
            ) {
                Icon(
                    AppIcons.Loading,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    "编码中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { buzz(); onStop() },
                    contentPadding = PaddingValues(horizontal = AppSpacing.Md),
                ) {
                    Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("停止", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadii.Card))
                .background(colors.surfaceRaised)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = enabled && !busy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions.Default,
                maxLines = 4,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                when {
                                    !enabled -> "编码运行时未就绪，无法编码"
                                    busy -> "编码中，稍候…"
                                    else -> "描述要写的代码或脚本…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(onClick = { buzz(); onSend(draft); draft = "" }, enabled = canSend) {
                Icon(
                    AppIcons.Send,
                    contentDescription = "发送",
                    tint = if (canSend) MaterialTheme.colorScheme.primary else Color.Gray,
                )
            }
        }
    }
}

/**
 * 用系统文件管理器打开工作区目录。
 *
 * 工作区在应用外部私有目录（免存储权限，文件管理器可见），优先用
 * DocumentsUI 的 ACTION_VIEW + EXTRA_INITIAL_URI 精确定位到该目录，
 * 机型不支持时回落 ACTION_OPEN_DOCUMENT_TREE（至少能把用户带到文件根）。
 * 两种落法都失败返回 false，由调用方给页内提示（项目不使用 Toast）。
 */
internal fun openWorkspaceDirectory(context: Context): Boolean {
    val dir = Workspace.resolve(context)
    val rel = dir.absolutePath.removePrefix("/storage/emulated/0/")
    val uri = runCatching {
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    }.getOrNull() ?: return false

    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            },
        )
    }.isSuccess
    if (opened) return true
    return runCatching {
        context.startActivity(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            },
        )
    }.isSuccess
}
