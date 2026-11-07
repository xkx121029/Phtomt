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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.phoneagent.ui.components.ConfigDialCard
import com.phoneagent.ui.components.DialOption
import com.phoneagent.ui.components.GlassHeaderScaffold
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SkillCard
import com.phoneagent.ui.components.SkillDetailBlock
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.ToolIconBadge
import com.phoneagent.ui.components.ToolStatusTrail
import com.phoneagent.ui.components.ToolTimingLabel
import com.phoneagent.ui.components.approvalDialOptions
import com.phoneagent.ui.components.codingToolIdentity
import com.phoneagent.ui.components.formatDuration
import com.phoneagent.ui.components.formatSize
import com.phoneagent.ui.components.modelDialOptions
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.components.toolRunState
import com.phoneagent.ui.components.toolStatusStyle
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.workbench.rememberCodingConversationSummaryText
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.appBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

/**
 * Coding 页（Superagent 融合 Phase 5）：三形态之一的 vibecoding。
 *
 * 会话（对话记录）与 agent 记忆都按工作区隔离，存在 `<工作区>/.happyac/` 里：
 * 一个工作区可以有多个会话，切工作区就切了一整套记忆；Coding 页用的是自己的 agent
 * 连接（[CodingAgentBridge.uiClient]），操控页委托的 Coding 任务不会再冲掉这里的对话。
 * 审批以内嵌卡片呈现——项目铁律：审批绝不走系统弹窗。
 */
@Composable
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun CodingScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    /** 全屏阅读 AI 回返的正文：气泡在会话流里只能限宽，要通读就得整屏。 */
    onOpenMarkdownFullscreen: (content: String) -> Unit = {},
    /** 工作台壳层已画顶栏时置 false：本页不再重复一条自己的页眉 */
    showHeader: Boolean = true,
) {
    val runtime = koinInject<com.phoneagent.coding.CodingRuntimeManager>()
    val bridge = koinInject<CodingAgentBridge>()
    val appSettings = koinInject<AppSettings>()
    val runtimeState by runtime.state.collectAsState()
    val clientState by bridge.uiClient.state.collectAsState()
    // 模型与审批档位是全局设置：Coding页的选择器直接读写它，和设置页保持同一份真值
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
            .onFailure { snackbar?.show(it.message ?: "Coding Agent 连接失败") }
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
            snackbar?.show("已停止当前 Coding 任务")
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
            snackbar?.show("Coding 进行中，结束后再切换会话")
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
            snackbar?.show("Coding 进行中，结束后再删除会话")
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
            snackbar?.show("已切换到新工作区，进行中的 Coding 任务已停止")
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
    val pickAttachment = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            SharedFiles.import(context, listOf(uri))
                .onFailure { snackbar?.show(it.message ?: "附件导入失败") }
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        val files = sharedAttachments
        if ((text.isEmpty() && files.isEmpty()) || clientState.busy) return
        scope.launch {
            runCatching { bridge.ensureUiConnected() }
                .onFailure {
                    snackbar?.show(it.message ?: "Coding 运行时未就绪")
                    return@launch
                }
            bridge.uiClient.sendPrompt(withAttachmentHint(text, files))
            SharedFiles.consume()
        }
    }

    // 系统分享的文件落盘完成后：切到临时目录、把附件挂进输入区，
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
                .onFailure { snackbar?.show("切换失败：${it.message ?: "Coding Agent 未连接"}") }
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

    // 转盘候选：模型库名单 + 当前手填/不在库里的模型（保证当前值一定在轮上）
    val modelOptions = remember(settings.catalog, settings.model) {
        modelDialOptions(settings.catalog, settings.model)
    }
    val approvalOptions = remember { approvalDialOptions() }

    Box(modifier.fillMaxSize()) {
        GlassHeaderScaffold(
            modifier = Modifier.fillMaxSize(),
            header = if (showHeader) {
                {
                    AppTopBar(
                        title = rememberCodingConversationSummaryText(bridge),
                        titleStyle = MaterialTheme.typography.titleMedium,
                        titleMaxLines = 2,
                    )
                }
            } else {
                null
            },
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
                // 顶栏：左侧会话层（当前会话 / 会话列表 / 新会话），右侧工作区（目录 / 连接 / 切换）。
                // 两枚胶囊同高、同描边，一行扫完「在哪个会话、在哪个目录」，不再上下分两行起点各异
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SessionCapsule(
                        title = sessionTitle,
                        count = sessionsState.sessions.size,
                        onSessions = { showSessions = true },
                        onNew = { newSession() },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceCapsule(
                        workspaceName = workspace.name,
                        conn = clientState.conn,
                        onOpenFolder = {
                            if (!openWorkspaceDirectory(context)) snackbar?.show("无法打开文件管理器查看该目录")
                        },
                        onSwitch = { showWorkspaces = true },
                    )
                }

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
                    onOpenFullscreen = onOpenMarkdownFullscreen,
                )

                // 模型与授权等级：两张独立小卡片，默认只露当前值，贴在输入框上方；
                // 点开卡片才铺开转轴平行于屏幕的转盘（与 Agent 页共用同一组件与交互）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                ) {
                    ConfigDialCard(
                        title = "模型",
                        icon = AppIcons.SmartToy,
                        options = modelOptions,
                        selectedId = settings.model,
                        onSelect = { name ->
                            switchModel(name, settings.catalog.firstOrNull { it.name == name }?.endpointId)
                        },
                        note = if (pendingModelApply) "本轮后生效" else null,
                        modifier = Modifier.weight(1f),
                    )
                    ConfigDialCard(
                        title = "执行与授权",
                        icon = AppIcons.Guard,
                        options = approvalOptions,
                        selectedId = settings.codingApprovalMode,
                        onSelect = { switchApproval(it) },
                        modifier = Modifier.weight(1f),
                    )
                }

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
                    onPickAttachment = { pickAttachment.launch("image/*") },
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
    }
}

/**
 * 工作区入口：名称 + 连接状态 + 「打开目录」「切换」合在同一个容器里，整体贴齐页面右上角。
 *
 * 之前名称与切换分散在左右两端、路径另起一行，看着是三个独立控件；
 * 现在收成一个胶囊：用户扫一眼就知道"当前在哪个工作区、点哪里换"。
 */
@Composable
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod")
private fun WorkspaceCapsule(
    workspaceName: String,
    conn: ConnState,
    onOpenFolder: () -> Unit,
    onSwitch: () -> Unit,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val shape = RoundedCornerShape(AppRadii.Chip)
    Row(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .clip(shape)
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, shape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableScale(
            onPress = buzz,
            onClick = onOpenFolder,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(AppRadii.Chip)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.FolderOpen,
                    contentDescription = "打开工作区目录",
                    tint = colors.onSurfaceRaised,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.Xs))
        Text(
            workspaceName,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        ConnDot(conn)
        Spacer(Modifier.width(AppSpacing.Sm))
        Box(
            Modifier
                .width(1.dp)
                .height(16.dp)
                .background(colors.outlineSoft),
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        PressableScale(
            onPress = buzz,
            onClick = onSwitch,
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadii.Chip))
                .background(colors.brandContainer)
                .padding(horizontal = AppSpacing.Sm, vertical = 6.dp),
        ) {
            Text(
                "切换",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onBrandContainer,
            )
        }
        Spacer(Modifier.width(2.dp))
    }
}

/**
 * 会话层胶囊：当前会话标题 + 会话数 + 新会话，与工作区胶囊并排、同高同描边。
 *
 * 原先会话层是一整行横向铺开的头部，和工作区各占一行；两者都在回答
 * 「我现在在哪」，拆成两行反而要上下对两次。收成左一枚胶囊后，
 * 标题过长只省略自己这一枚，不挤占右侧工作区。
 */
@Composable
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod")
private fun SessionCapsule(
    title: String,
    count: Int,
    onSessions: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val shape = RoundedCornerShape(AppRadii.Chip)
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, shape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableScale(
            onPress = buzz,
            onClick = onSessions,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(AppRadii.Chip)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.History,
                    contentDescription = "查看会话列表",
                    tint = colors.onSurfaceRaised,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.Xs))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        Text(
            "$count 会话",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceRaised,
            maxLines = 1,
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Box(
            Modifier
                .width(1.dp)
                .height(16.dp)
                .background(colors.outlineSoft),
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        PressableScale(
            onPress = buzz,
            onClick = onNew,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(AppRadii.Chip))
                .background(colors.brandContainer),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.Add,
                    contentDescription = "新建会话",
                    tint = colors.onBrandContainer,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
    }
}

/** 连接状态点：已连接绿、连接中主色、未连接灰；胶囊里只留圆点，文字交给无障碍描述 */
@Composable
private fun ConnDot(conn: ConnState) {
    val colors = AppTheme.colors
    val (color, label) = when (conn) {
        ConnState.CONNECTED -> colors.success to "已连接"
        ConnState.CONNECTING -> MaterialTheme.colorScheme.primary to "连接中"
        ConnState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to "未连接"
    }
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = "连接状态：$label" },
    )
}

/** Coding 会话流：user/assistant 气泡 + 工具调用卡 + 审批卡 */
@Composable
private fun ConversationFlow(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    onOpenFullscreen: (content: String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    if (messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "描述要写的代码或脚本，Coding Agent 会自己动手",
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
                "assistant" -> CodingAssistantBubble(msg.text) { onOpenFullscreen(msg.text) }
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
private fun CodingAssistantBubble(text: String, onExpand: () -> Unit) {
    if (text.isBlank()) return
    val buzz = rememberHapticClick()
    // 短回复在气泡里就看得完，不再多摆一个按钮；长回复才给全屏入口，
    // 否则每条消息右上角都挂一个图标，会话流会被这排小按钮切成碎片
    val longEnough = text.length > 400
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(AppTheme.colors.surfaceRaised)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (longEnough) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PressableScale(onPress = buzz, onClick = { buzz(); onExpand() }) {
                        Icon(
                            AppIcons.Expand,
                            contentDescription = "全屏阅读",
                            tint = AppTheme.colors.onSurfaceRaised,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.Xs))
            }
            MarkdownPreview(content = text, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 卡片副标题只取首行：参数摘要往往多行，塞进头部会挤掉标题 */
private fun firstLine(text: String): String = text.lineSequence().firstOrNull()?.trim().orEmpty()

/**
 * 技能卡（工具调用）：中文工具名 + 首行摘要 + 状态，点卡片展开完整执行内容。
 * 与 Agent 页工具链卡片同一外壳（[SkillCard]），两页不再各长一套。
 */
@Composable
private fun ToolCard(msg: ChatMessage) {
    var expanded by rememberSaveable(msg.key) { mutableStateOf(false) }
    val status = toolStatusStyle(toolRunState(msg.toolOk))
    // 耗时只在结束后显示：运行中的数字每帧都在跳，读起来是噪声。
    val timing = msg.durationMs?.takeIf { msg.toolOk != null }?.let { formatDuration(it) }
    // 工具身份自带"是否命令类"标记，页面不再自己列 run_command/bash/shell
    val identity = codingToolIdentity(msg.toolName)

    SkillCard(
        title = identity.label,
        subtitle = firstLine(msg.text),
        expandable = true,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        leading = { ToolIconBadge(identity.icon, status.tint) },
        trailing = { ToolStatusTrail(status, detail = timing) },
    ) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)) {
            SkillDetailBlock(
                title = "完整执行内容",
                body = msg.text,
                monospace = identity.monospace,
            )
            // 回填给 AI 的那段输出：以前展开只有参数，看完还得猜这条命令到底跑出了什么
            msg.outputPreview?.takeIf { it.isNotBlank() }?.let { out ->
                Spacer(Modifier.height(AppSpacing.Sm))
                SkillDetailBlock(
                    title = "执行结果（回传给 AI）",
                    body = out,
                    monospace = identity.monospace,
                )
            }
            if (timing != null) {
                Spacer(Modifier.height(AppSpacing.Xs))
                ToolTimingLabel(timing)
            }
        }
    }
}

/** 子任务卡：并行子 agent 的独立进度（人设 · 任务名 + 当前动作 + 成败 / 耗时） */
@Composable
private fun SubagentCard(msg: ChatMessage) {
    var expanded by rememberSaveable(msg.key) { mutableStateOf(false) }
    val status = toolStatusStyle(
        state = toolRunState(msg.toolOk),
        runningLabel = "进行中",
    )
    val timing = msg.durationMs?.takeIf { msg.toolOk != null }?.let { formatDuration(it) }
    // 人设在前、任务名在后：一眼看出「谁在干哪一摊」，而不是一堆同名的 task-1
    val who = listOfNotNull(msg.subagentPersona, msg.subagentLabel).joinToString(" · ")
    val title = if (who.isBlank()) "子任务" else "子任务 · $who"

    SkillCard(
        title = title,
        subtitle = firstLine(msg.text),
        expandable = true,
        expanded = expanded,
        onToggleExpand = { expanded = !expanded },
        leading = { ToolIconBadge(AppIcons.Guard, status.tint) },
        trailing = { ToolStatusTrail(status, detail = timing) },
    ) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)) {
            SkillDetailBlock(title = "任务说明", body = msg.text)
            if (timing != null) {
                Spacer(Modifier.height(AppSpacing.Xs))
                ToolTimingLabel(timing)
            }
        }
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
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
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
                if (activeSubagents > 0) append(" · $activeSubagents 个子任务并行")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 审批技能卡（铁律）：Coding agent 请求执行敏感工具时的内嵌审批——允许 / 拒绝就在卡片上，
 * 决议后置灰落定，绝不弹系统对话框。
 *
 * 与工具卡共用 [SkillCard] 外壳（不可展开，正文常显），两页所有卡片只长一个样子。
 */
@Composable
private fun ApprovalCard(msg: ChatMessage) {
    val buzz = rememberHapticClick()
    val client = koinInject<CodingAgentBridge>().uiClient
    val callId = msg.approvalCallId
    val resolved = msg.approvalResolved
    val colors = AppTheme.colors
    val toolLabel = msg.text.ifBlank { "敏感操作" }

    SkillCard(
        title = "请求执行敏感操作",
        subtitle = "工具：$toolLabel",
        leading = { ToolIconBadge(AppIcons.Guard, MaterialTheme.colorScheme.primary) },
        trailing = {
            val (tint, text) = when (resolved) {
                null -> colors.brand to "等待确认"
                true -> colors.success to "已允许"
                false -> colors.error to "已拒绝"
            }
            StatusPill(text = text, color = tint)
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)) {
            Text(
                text = "Coding Agent 请求执行「$toolLabel」，确认后才会真正执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(AppSpacing.Md))
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
                    if (resolved) "已允许本次执行" else "已拒绝本次执行",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceRaised,
                )
            }
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
 * 底部输入区：busy 显示「正在 Coding…」与停止按钮；运行时未安装时整体禁用并提示。
 *
 * 草稿与附件由页面层持有：系统分享文件进来时要把附件（以及一句默认意图）
 * 直接预置到输入区里，状态留在这层就做不到了。
 */
@Composable
private fun CodingComposer(
    values: ComposerValues,
    onDraftChange: (String) -> Unit,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (SharedFiles.Imported) -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    val buzz = rememberHapticClick()
    Column(Modifier.fillMaxWidth()) {
        if (values.busy) {
            Spacer(Modifier.height(AppSpacing.Xs))
            BusyLine(onStop = { buzz(); onStop() })
        }
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
            onAttach = onPickAttachment,
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
    onAttach: () -> Unit,
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
        ComposerActionButton(
            icon = AppIcons.Add,
            description = "添加附件",
            onClick = onAttach,
            enabled = values.enabled && !values.busy,
        )
        Spacer(Modifier.width(AppSpacing.Xs))
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
    !values.enabled -> "Coding 运行时未就绪，暂时无法使用"
    values.busy -> "正在 Coding，稍候…"
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
            "正在 Coding…",
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

/** 附件行：文件名 + 大小，右侧可摘掉。附件本身已经在临时目录里，这里只是提示。 */
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
 * 把附件写进消息正文再发给 Coding agent。
 *
 * agent 只有工作区相对路径这一种寻址方式，它看不到"用户刚刚分享了什么"；
 * 附件已经在临时目录根目录里，所以这里直接给出文件名，让它用 read_file 打开。
 * 附件清单也写进用户气泡，回看历史时能知道当时带了哪些文件。
 */
private fun withAttachmentHint(text: String, attachments: List<SharedFiles.Imported>): String {
    if (attachments.isEmpty()) return text
    val hint = buildString {
        append("（附件在工作区根目录，用 read_file 读取）\n")
        attachments.forEach { item ->
            append("- ").append(item.relativePath)
            // 容器格式（docx/xlsx/pptx/zip）导入时已抽出纯文本，让 agent 直接读摘录而不是二进制
            item.excerptPath?.let { append("（文本摘录：").append(it).append("）") }
            append('\n')
        }
        append(if (text.isBlank()) "请读取上面的文件并解读。" else text)
        append("\n（若是 PDF / 图片 / 音视频这类二进制文件，读不了就直说，不要编造内容）")
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

/** 打开「所有文件访问」授权页：让Coding agent 能直接用路径读写用户选的目录 */
internal fun openAllFilesAccessSettings(context: Context) {
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
