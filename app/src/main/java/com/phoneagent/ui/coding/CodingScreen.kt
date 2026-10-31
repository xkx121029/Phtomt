package com.phoneagent.ui.coding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.coding.CodingRuntimeState
import com.phoneagent.coding.SharedFiles
import com.phoneagent.coding.agent.ChatMessage
import com.phoneagent.coding.agent.CodingWorkspaces
import com.phoneagent.coding.agent.ConnState
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.engine.coding.CodingAgentBridge
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.ComposerActionButton
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.components.formatSize
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.approvalModeLabel
import com.phoneagent.ui.settings.endpointHost
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

/**
 * 编码页（Superagent 融合 Phase 5）：三形态之一的 vibecoding。
 *
 * 会话（对话记录）与 agent 记忆都按工作区隔离，存在 `<工作区>/.happyac/` 里：
 * 一个工作区可以有多个会话，切工作区就切了一整套记忆；编码页用的是自己的 agent
 * 连接（[CodingAgentBridge.uiClient]），操控页委托的编码任务不会再冲掉这里的对话。
 * 审批以内嵌卡片呈现——项目铁律：审批绝不走系统弹窗。
 */
@Composable
fun CodingScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val runtime = koinInject<com.phoneagent.coding.CodingRuntimeManager>()
    val bridge = koinInject<CodingAgentBridge>()
    val appSettings = koinInject<AppSettings>()
    val runtimeState by runtime.state.collectAsState()
    val clientState by bridge.uiClient.state.collectAsState()
    // 模型与审批档位是全局设置：编码页的选择器直接读写它，和设置页保持同一份真值
    val settings by vm.settingsFlow.collectAsState()

    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    // 工作区与会话从磁盘读初值；「上一次选的是哪个工作区」要能跨 Tab 切换还原。
    // 但 Entry 里的 File/路径不能进 Bundle，
    // rememberSaveable 只能存 id（存整个 Entry 会在首次组合时抛 IllegalArgumentException 直接闪退），
    // 真实 Entry 由工作区列表按 id 还原。
    var workspaceId by rememberSaveable { mutableStateOf(CodingWorkspaces.active(context).id) }
    var workspaces by remember { mutableStateOf(CodingWorkspaces.list(context)) }
    val workspace = remember(workspaces, workspaceId) {
        workspaces.firstOrNull { it.id == workspaceId } ?: CodingWorkspaces.active(context)
    }
    var sessionsState by remember { mutableStateOf(CodingWorkspaces.state(context, workspace)) }
    var activeSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showWorkspaces by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showModelPanel by remember { mutableStateOf(false) }
    /** 运行中换了模型：先落盘，等这一轮结束（busy 翻回 false）再重启宿主应用 */
    var pendingModelApply by remember { mutableStateOf(false) }

    // 输入区草稿抬到页面层：系统分享文件进来时要能把草稿与附件一起预置好。
    // 附件本身不放这里——它是 [SharedFiles.pending] 这一个真值源，切 Tab 再回来还在。
    var draft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { runtime.refresh() }

    /** 把当前会话的消息写回工作区（切会话/切工作区/离开页面/一轮结束都会调用） */
    fun saveCurrent() {
        val id = activeSessionId ?: return
        val msgs = bridge.uiClient.snapshotMessages()
        if (msgs.isEmpty()) return
        CodingWorkspaces.saveMessages(context, workspace, id, msgs)
        sessionsState = CodingWorkspaces.state(context, workspace)
    }

    fun startSession(entry: CodingWorkspaces.Entry, preferred: String?) {
        val st = CodingWorkspaces.state(context, entry)
        val target = preferred?.takeIf { id -> st.sessions.any { it.id == id } }
            ?: st.activeSessionId?.takeIf { id -> st.sessions.any { it.id == id } }
        val (id, msgs) = if (target != null) {
            target to CodingWorkspaces.loadMessages(entry, target)
        } else {
            val fresh = CodingWorkspaces.newSession()
            CodingWorkspaces.saveState(
                context,
                entry,
                st.copy(activeSessionId = fresh.id, sessions = listOf(fresh) + st.sessions),
            )
            fresh.id to emptyList()
        }
        activeSessionId = id
        sessionsState = CodingWorkspaces.state(context, entry)
        CodingWorkspaces.setActiveSession(context, entry, id)
        bridge.uiClient.activateSession(id, msgs)
    }

    /** 进入页面/切工作区：载入该工作区的活动会话，并保证 agent 连接可用 */
    LaunchedEffect(workspace.id) {
        startSession(workspace, null)
        runCatching { bridge.ensureUiConnected() }
            .onFailure { snackbar?.show(it.message ?: "编码 Agent 连接失败") }
    }

    // 一轮结束后落盘：流式输出过程中不动文件，避免每个 token 都写一次。
    // 条数与索引一致时跳过——切回一个老会话不该把它的"最近活动时间"刷新成现在。
    LaunchedEffect(workspace.id, activeSessionId, clientState.busy, clientState.messages.size) {
        val id = activeSessionId ?: return@LaunchedEffect
        val stored = sessionsState.sessions.firstOrNull { it.id == id }?.count ?: 0
        if (!clientState.busy && clientState.messages.isNotEmpty() && clientState.messages.size != stored) {
            saveCurrent()
        }
    }

    // 离开页面时保存一次；切会话时 client 已装成新会话，用会话 id 比对避免写串文件
    val disposeSessionId = activeSessionId
    DisposableEffect(workspace.id, disposeSessionId) {
        onDispose {
            if (disposeSessionId != null && bridge.uiClient.currentSessionId == disposeSessionId) {
                val msgs = bridge.uiClient.snapshotMessages()
                if (msgs.isNotEmpty()) {
                    CodingWorkspaces.saveMessages(context, workspace, disposeSessionId, msgs)
                }
            }
        }
    }

    fun newSession() {
        if (bridge.uiClient.state.value.busy) {
            bridge.uiClient.cancel()
            snackbar?.show("已停止当前编码任务")
        }
        saveCurrent()
        val st = CodingWorkspaces.state(context, workspace)
        val fresh = CodingWorkspaces.newSession()
        CodingWorkspaces.saveState(
            context,
            workspace,
            st.copy(activeSessionId = fresh.id, sessions = listOf(fresh) + st.sessions),
        )
        activeSessionId = fresh.id
        sessionsState = CodingWorkspaces.state(context, workspace)
        bridge.uiClient.activateSession(fresh.id, emptyList())
    }

    fun openSession(session: CodingWorkspaces.SessionMeta) {
        if (session.id == activeSessionId) {
            showSessions = false
            return
        }
        if (bridge.uiClient.state.value.busy) {
            snackbar?.show("编码进行中，结束后再切换会话")
            return
        }
        saveCurrent()
        val msgs = CodingWorkspaces.loadMessages(workspace, session.id)
        CodingWorkspaces.setActiveSession(context, workspace, session.id)
        activeSessionId = session.id
        sessionsState = CodingWorkspaces.state(context, workspace)
        bridge.uiClient.activateSession(session.id, msgs)
        showSessions = false
    }

    fun deleteSession(session: CodingWorkspaces.SessionMeta) {
        if (bridge.uiClient.state.value.busy && session.id == activeSessionId) {
            snackbar?.show("编码进行中，结束后再删除会话")
            return
        }
        CodingWorkspaces.deleteSession(context, workspace, session.id)
        if (session.id == activeSessionId) {
            activeSessionId = null
            startSession(workspace, null)
        } else {
            sessionsState = CodingWorkspaces.state(context, workspace)
        }
    }

    fun switchWorkspace(entry: CodingWorkspaces.Entry) {
        showWorkspaces = false
        if (entry.id == workspace.id) return
        saveCurrent()
        val activated = CodingWorkspaces.activate(context, entry.id)
        if (activated == null) {
            snackbar?.show("该工作区不可读写，换一个目录试试")
            return
        }
        if (bridge.uiClient.state.value.busy) {
            bridge.uiClient.cancel()
            snackbar?.show("已切换到新工作区，进行中的编码任务已停止")
        }
        bridge.restartForWorkspaceChange()
        workspaceId = activated.id
        workspaces = CodingWorkspaces.list(context)
    }

    val pickDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = CodingWorkspaces.resolveTreePath(uri)
        if (path == null) {
            snackbar?.show("只能选择主存储空间里的目录（暂不支持 SD 卡 / U 盘）")
            return@rememberLauncherForActivityResult
        }
        if (!CodingWorkspaces.hasAllFilesAccess() && !isAppOwnedPath(context, path)) {
            openAllFilesAccessSettings(context)
            snackbar?.show("请先授予「所有文件访问」权限，再重新选择该目录")
            return@rememberLauncherForActivityResult
        }
        val entry = CodingWorkspaces.addExternal(context, path, File(path).name)
        if (entry == null) {
            snackbar?.show("该目录不可读写")
        } else {
            workspaces = CodingWorkspaces.list(context)
            switchWorkspace(entry)
        }
    }

    // 系统分享进来的文件：附件区唯一真值源（见 SharedFiles）
    val sharedAttachments by SharedFiles.pending.collectAsState()

    fun send(raw: String) {
        val text = raw.trim()
        val files = sharedAttachments
        if ((text.isEmpty() && files.isEmpty()) || clientState.busy) return
        scope.launch {
            runCatching { bridge.ensureUiConnected() }
                .onFailure {
                    snackbar?.show(it.message ?: "编码运行时未就绪")
                    return@launch
                }
            bridge.uiClient.sendPrompt(withAttachmentHint(text, files))
            SharedFiles.consume()
        }
    }

    // 系统分享的文件落盘完成后：切到临时编码区、把附件挂进输入区，
    // 等工作区切完由 LaunchedEffect(workspace.id) 起好会话。不自动发消息——
    // 解读什么、怎么解读，让用户看一眼附件再决定（草稿里先放一句默认意图，可直接改）。
    LaunchedEffect(sharedAttachments.isNotEmpty()) {
        if (sharedAttachments.isEmpty()) return@LaunchedEffect
        val target = sharedAttachments.first().workspace
        if (target.id != workspace.id) switchWorkspace(target)
        if (draft.isBlank()) {
            draft = if (sharedAttachments.size > 1) "解读一下这些文件" else "解读一下这个文件"
        }
    }

    /**
     * 换主模型：模型名与端点（地址/Key）一起落到全局设置。
     * 宿主进程的模型是启动时注入的环境变量，必须重启宿主才真正换掉；
     * 运行中先记账，等 busy 翻回 false 再应用，不打断当前这一轮。
     */
    fun switchModel(name: String, endpointId: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val ep = endpointId?.let { id -> settings.endpoints.firstOrNull { it.id == id } }
        val next = settings.copy(
            model = trimmed,
            apiBaseUrl = ep?.baseUrl ?: settings.apiBaseUrl,
            apiKey = ep?.apiKey ?: settings.apiKey,
        )
        showModelPanel = false
        if (clientState.busy) {
            pendingModelApply = true
            scope.launch { appSettings.update(next) }
            snackbar?.show("已设为 $trimmed，本轮结束后自动切换")
            return
        }
        pendingModelApply = false
        scope.launch {
            appSettings.update(next)
            runCatching { bridge.applyConfigChange() }
                .onSuccess { snackbar?.show("已切换到 $trimmed") }
                .onFailure { snackbar?.show("切换失败：${it.message ?: "编码 Agent 未连接"}") }
        }
    }

    /** 换审批档位：宿主侧热更新，立即生效；落盘保证下次启动还是这一档 */
    fun switchApproval(mode: String) {
        if (mode == settings.codingApprovalMode) return
        scope.launch {
            appSettings.update(settings.copy(codingApprovalMode = mode))
            bridge.uiClient.setApprovalMode(mode) { ok, error ->
                // 没连上（比如首次进页面还没起宿主）时不该报错卡住：
                // 设置已落盘，下一次连上就按新档位跑。
                if (!ok && clientState.conn == ConnState.CONNECTED) {
                    snackbar?.show("审批档位没切上：${error ?: "未知错误"}")
                }
            }
        }
    }

    // 运行中换过模型：这一轮结束（busy=false）后自动重启宿主应用新配置
    LaunchedEffect(clientState.busy) {
        if (!clientState.busy && pendingModelApply) {
            pendingModelApply = false
            runCatching { bridge.applyConfigChange() }
                .onFailure { snackbar?.show("新模型连接失败：${it.message ?: "超时"}") }
        }
    }

    val activeSession = sessionsState.sessions.firstOrNull { it.id == activeSessionId }
    val sessionTitle = activeSession?.title ?: "新会话"

    Box(modifier.fillMaxSize()) {
        GlassHeaderScaffold(
            modifier = Modifier.fillMaxSize(),
            header = { AppTopBar(title = "编码", subtitle = "vibecoding · 设备端编码 Agent") },
        ) { contentPad ->
            // 宽屏下正文列已由骨架（GlassHeaderScaffold）收口并居中，这里不再重复约束
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(top = contentPad.calculateTopPadding())
                    .padding(horizontal = AppSpacing.Md)
                    .padding(LocalNavClearance.current),
            ) {
                WorkspaceHeader(
                    workspaceName = workspace.name,
                    workspacePath = workspace.path,
                    conn = clientState.conn,
                    onOpenFolder = {
                        if (!openWorkspaceDirectory(context)) snackbar?.show("无法打开文件管理器查看该目录")
                    },
                    onSwitch = { showWorkspaces = true },
                )

                Spacer(Modifier.height(AppSpacing.Xs))

                SessionHeader(
                    title = sessionTitle,
                    count = sessionsState.sessions.size,
                    onSessions = { showSessions = true },
                    onNew = { newSession() },
                )

                if (clientState.error != null) {
                    Spacer(Modifier.height(AppSpacing.Xs))
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

                ModelPermissionChip(
                    model = settings.model,
                    approval = approvalModeLabel(settings.codingApprovalMode),
                    pendingApply = pendingModelApply,
                    onClick = { showModelPanel = true },
                )

                if (clientState.busy) {
                    RunStatusLine(
                        phase = clientState.phase,
                        iteration = clientState.iteration,
                        runStartedAt = clientState.runStartedAt,
                        activeSubagents = clientState.messages.count { it.role == "subagent" && it.toolOk == null },
                    )
                }

                CodingComposer(
                    values = ComposerValues(
                        busy = clientState.busy,
                        enabled = runtimeState is CodingRuntimeState.Installed,
                        draft = draft,
                        attachments = sharedAttachments,
                    ),
                    onDraftChange = { draft = it },
                    onRemoveAttachment = { SharedFiles.remove(it) },
                    onStop = { bridge.uiClient.cancel() },
                    onSend = { send(it) },
                )
                Spacer(Modifier.height(AppSpacing.Sm))
            }
        }

        if (showWorkspaces) {
            WorkspacePanel(
                workspaces = workspaces,
                activeId = workspace.id,
                onSelect = { switchWorkspace(it) },
                onCreate = { name ->
                    val created = CodingWorkspaces.createPrivate(context, name)
                    if (created == null) {
                        snackbar?.show("工作区创建失败")
                    } else {
                        workspaces = CodingWorkspaces.list(context)
                        switchWorkspace(created)
                    }
                },
                onPickExternal = { showWorkspaces = false; pickDirectory.launch(null) },
                onDelete = { entry ->
                    val wasActive = entry.id == workspace.id
                    if (CodingWorkspaces.remove(context, entry.id)) {
                        workspaces = CodingWorkspaces.list(context)
                        if (wasActive) {
                            // 被移除的是当前工作区：登记表已切到另一个，界面指针与 agent 进程要一起跟上
                            bridge.uiClient.cancel()
                            bridge.restartForWorkspaceChange()
                            workspaceId = CodingWorkspaces.active(context).id
                        }
                        snackbar?.show("已移除「${entry.name}」，目录文件保留")
                    } else {
                        snackbar?.show("无法移除该工作区")
                    }
                },
                onDismiss = { showWorkspaces = false },
            )
        }

        if (showSessions) {
            SessionPanel(
                workspaceName = workspace.name,
                sessions = sessionsState.sessions,
                activeId = activeSessionId,
                onSelect = { openSession(it) },
                onNew = { showSessions = false; newSession() },
                onDelete = { deleteSession(it) },
                onDismiss = { showSessions = false },
            )
        }

        if (showModelPanel) {
            ModelPermissionPanel(
                model = settings.model,
                models = settings.catalog,
                endpointLabelOf = { m ->
                    settings.endpoints.firstOrNull { it.id == m.endpointId }
                        ?.let { endpointHost(it.baseUrl) }
                        ?: "手动添加"
                },
                approvalMode = settings.codingApprovalMode,
                busy = clientState.busy,
                onSelectApproval = { switchApproval(it) },
                onPickModel = { switchModel(it.name, it.endpointId) },
                onPickManual = { switchModel(it, null) },
                onDismiss = { showModelPanel = false },
            )
        }
    }
}

/** 工作区行：名称 + 真实路径 + 连接状态点，右侧「切换」与「在文件管理器打开」 */
@Composable
private fun WorkspaceHeader(
    workspaceName: String,
    workspacePath: String,
    conn: ConnState,
    onOpenFolder: () -> Unit,
    onSwitch: () -> Unit,
) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { buzz(); onOpenFolder() }, modifier = Modifier.size(32.dp)) {
            Icon(
                AppIcons.FolderOpen,
                contentDescription = "打开工作区目录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(AppSpacing.Xs))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    workspaceName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                ConnDot(conn)
            }
            Text(
                workspacePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { buzz(); onSwitch() }) {
            Text("切换", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 会话行：当前会话标题 + 会话数入口 + 新会话 */
@Composable
private fun SessionHeader(
    title: String,
    count: Int,
    onSessions: () -> Unit,
    onNew: () -> Unit,
) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .clickable { buzz(); onSessions() }
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count 个会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
        TextButton(onClick = { buzz(); onNew() }, contentPadding = PaddingValues(horizontal = AppSpacing.Sm)) {
            Text("新会话", style = MaterialTheme.typography.labelMedium)
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
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                "subagent" -> SubagentCard(msg)
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

/** 工具调用卡：工具名 + 参数摘要 + 执行态（运行中 / 成功 / 失败）+ 耗时 */
@Composable
private fun ToolCard(msg: ChatMessage) {
    val colors = AppTheme.colors
    val (icon, tint, label) = when (msg.toolOk) {
        null -> Triple(AppIcons.Loading, MaterialTheme.colorScheme.primary, "运行中…")
        true -> Triple(AppIcons.CheckCircle, colors.success, "完成")
        false -> Triple(AppIcons.Cancel, MaterialTheme.colorScheme.error, "失败")
    }
    // 耗时只在结束后显示：运行中的数字每帧都在跳，读起来是噪声。
    val timing = msg.durationMs?.takeIf { msg.toolOk != null }?.let { formatDuration(it) }
    RunCard(
        name = msg.toolName ?: "工具",
        detail = msg.text,
        icon = AppIcons.Terminal,
        statusIcon = icon,
        tint = tint,
        statusLabel = if (timing != null) "$label · $timing" else label,
    )
}

/** 子 agent 卡：并行子任务的独立进度（人设 · 任务名 + 当前动作 + 成败 / 耗时） */
@Composable
private fun SubagentCard(msg: ChatMessage) {
    val colors = AppTheme.colors
    val (icon, tint, label) = when (msg.toolOk) {
        null -> Triple(AppIcons.Loading, MaterialTheme.colorScheme.primary, "进行中")
        true -> Triple(AppIcons.CheckCircle, colors.success, "完成")
        false -> Triple(AppIcons.Cancel, MaterialTheme.colorScheme.error, "失败")
    }
    val timing = msg.durationMs?.takeIf { msg.toolOk != null }?.let { formatDuration(it) }
    // 人设在前、任务名在后：一眼看出「谁在干哪一摊」，而不是一堆同名的 task-1。
    val title = listOfNotNull("子 agent", msg.subagentPersona, msg.subagentLabel ?: msg.toolName).joinToString(" · ")
    RunCard(
        name = title,
        detail = msg.text,
        icon = AppIcons.Guard,
        statusIcon = icon,
        tint = tint,
        statusLabel = if (timing != null) "$label · $timing" else label,
    )
}

/** 工具卡与子 agent 卡共用的行布局：避免两份几乎一样的排版分叉。 */
@Composable
private fun RunCard(
    name: String,
    detail: String,
    icon: ImageVector,
    statusIcon: ImageVector,
    tint: Color,
    statusLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Chip))
            .background(AppTheme.colors.surfaceRaised)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.Sm))
        Icon(statusIcon, contentDescription = statusLabel, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * 运行状态行：无人值守时用户只想知道"活着吗、跑到哪、多久了"。
 * 计时器本地跑（200ms 一跳），不依赖 host 的 15s 心跳——心跳只用来兜底证明进程还在。
 */
@Composable
private fun RunStatusLine(
    phase: String,
    iteration: Int,
    runStartedAt: Long?,
    activeSubagents: Int,
) {
    var elapsedMs by remember(runStartedAt) { mutableStateOf(runStartedAt?.let { System.currentTimeMillis() - it } ?: 0L) }
    LaunchedEffect(runStartedAt) {
        while (runStartedAt != null) {
            elapsedMs = System.currentTimeMillis() - runStartedAt
            delay(200)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            phase.ifEmpty { "处理中" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Text(
            buildString {
                append(formatDuration(elapsedMs))
                if (iteration > 0) append(" · 第 $iteration 轮")
                if (activeSubagents > 0) append(" · $activeSubagents 个子 agent 并行")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 耗时文案：毫秒级、秒级、分钟级各用不同精度，别显示 "0.0s"。 */
private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

/**
 * 审批卡（铁律）：编码 agent 请求执行敏感工具时的内嵌审批——允许 / 拒绝就在卡片上，
 * 决议后置灰落定，绝不弹系统对话框。
 */
@Composable
private fun ApprovalCard(msg: ChatMessage) {
    val buzz = rememberHapticClick()
    val client = koinInject<CodingAgentBridge>().uiClient
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

/**
 * 模型与权限入口（胶囊）：当前模型 + 审批档位，点开即切换。
 * 放在输入框上方——模型和档位决定的是「这一轮怎么跑」，离发消息越近越顺手。
 */
@Composable
private fun ModelPermissionChip(
    model: String,
    approval: String,
    pendingApply: Boolean,
    onClick: () -> Unit,
) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadii.Chip))
                .background(AppTheme.colors.surfaceRaised)
                .clickable { buzz(); onClick() }
                .padding(horizontal = AppSpacing.Sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                model.ifBlank { "未选模型" },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "·",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                AppIcons.Guard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                approval,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pendingApply) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "本轮后生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                AppIcons.ChevronDown,
                contentDescription = "切换模型与权限",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 输入区要显示的值（行为另以回调传入）：值放一起，避免一长串同名参数 */
private data class ComposerValues(
    val busy: Boolean,
    val enabled: Boolean,
    val draft: String,
    val attachments: List<SharedFiles.Imported>,
)

/**
 * 底部输入区：busy 显示「编码中…」与停止按钮；运行时未安装时整体禁用并提示。
 *
 * 草稿与附件由页面层持有：系统分享文件进来时要把附件（以及一句默认意图）
 * 直接预置到输入区里，状态留在这层就做不到了。
 */
@Composable
private fun CodingComposer(
    values: ComposerValues,
    onDraftChange: (String) -> Unit,
    onRemoveAttachment: (SharedFiles.Imported) -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    val buzz = rememberHapticClick()
    Column(Modifier.fillMaxWidth()) {
        if (values.busy) BusyLine(onStop = { buzz(); onStop() })
        if (values.attachments.isNotEmpty()) {
            AttachmentRow(
                attachments = values.attachments,
                enabled = !values.busy,
                onRemove = onRemoveAttachment,
            )
            Spacer(Modifier.height(AppSpacing.Xs))
        }
        ComposerInputRow(
            values = values,
            canSend = values.enabled && !values.busy &&
                (values.draft.isNotBlank() || values.attachments.isNotEmpty()),
            onDraftChange = onDraftChange,
            onSend = { buzz(); onSend(values.draft); onDraftChange("") },
        )
    }
}

/** 输入框 + 发送键：与操控页/对话页同一套尺寸与间距 */
@Composable
private fun ComposerInputRow(
    values: ComposerValues,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(AppTheme.colors.surfaceRaised)
            // 与操控页/对话页统一：上下各 4dp + 40dp 动作键 = 单行 48dp，
            // 单行文字在 40dp 里竖直居中，多行时发送键贴行底
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = values.draft,
                onValueChange = onDraftChange,
                enabled = values.enabled && !values.busy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions.Default,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (values.draft.isEmpty()) {
                            Text(
                                composerHint(values),
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
            onClick = onSend,
            enabled = canSend,
        )
    }
}

/** 输入区占位文案：禁用 / 运行中 / 空闲三种状态各说一件事 */
private fun composerHint(values: ComposerValues): String = when {
    !values.enabled -> "编码运行时未就绪，无法编码"
    values.busy -> "编码中，稍候…"
    else -> "描述要写的代码或脚本…"
}

/** 运行中的提示行：一行说明 + 停止键 */
@Composable
private fun BusyLine(onStop: () -> Unit) {
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
            onClick = onStop,
            contentPadding = PaddingValues(horizontal = AppSpacing.Md),
        ) {
            Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("停止", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 附件行：文件名 + 大小，右侧可摘掉。附件本身已经在临时编码区里，这里只是提示。 */
@Composable
private fun AttachmentRow(
    attachments: List<SharedFiles.Imported>,
    enabled: Boolean,
    onRemove: (SharedFiles.Imported) -> Unit,
) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
    ) {
        attachments.forEach { item ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadii.Chip))
                    .background(AppTheme.colors.surfaceRaised)
                    .padding(start = AppSpacing.Sm, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${item.displayName} · ${formatSize(item.bytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                IconButton(
                    onClick = { buzz(); onRemove(item) },
                    enabled = enabled,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = "移除附件",
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 把附件写进消息正文再发给编码 agent。
 *
 * agent 只有工作区相对路径这一种寻址方式，它看不到"用户刚刚分享了什么"；
 * 附件已经在临时编码区根目录里，所以这里直接给出文件名，让它用 read_file 打开。
 * 附件清单也写进用户气泡，回看历史时能知道当时带了哪些文件。
 */
private fun withAttachmentHint(text: String, attachments: List<SharedFiles.Imported>): String {
    if (attachments.isEmpty()) return text
    val hint = buildString {
        append("（附件在工作区根目录）\n")
        attachments.forEach { append("- ").append(it.relativePath).append('\n') }
        append(if (text.isBlank()) "请读取上面的文件并解读。" else text)
    }
    return hint.trim()
}

/** 应用自有目录（私有目录 / Android/data 下）无需「所有文件访问」就能被 node 读写 */
private fun isAppOwnedPath(context: Context, path: String): Boolean {
    val roots = listOfNotNull(
        context.filesDir.absolutePath,
        context.cacheDir.absolutePath,
        runCatching { context.getExternalFilesDir(null)?.absolutePath }.getOrNull(),
    )
    return roots.any { path == it || path.startsWith("$it/") }
}

/** 打开「所有文件访问」授权页：让编码 agent 能直接用路径读写用户选的目录 */
private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * 用系统文件管理器打开工作区目录。
 *
 * 只对共享存储里的路径有效（应用私有目录文件管理器进不去），
 * 优先 DocumentsUI 的 ACTION_VIEW + EXTRA_INITIAL_URI 精确定位，
 * 机型不支持时回落 ACTION_OPEN_DOCUMENT_TREE。两种都不行返回 false，由调用方给页内提示。
 */
internal fun openWorkspaceDirectory(context: Context): Boolean {
    val path = com.phoneagent.coding.agent.Workspace.resolve(context).absolutePath
    if (Environment.getExternalStorageDirectory().absolutePath.let { !path.startsWith(it) }) {
        return false
    }
    val rel = path.removePrefix(Environment.getExternalStorageDirectory().absolutePath + "/")
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
