package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.engine.PlanPhase
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.GlassSurface
import com.phoneagent.ui.components.LocalBottomNavClearance
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.components.rememberGlassState
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 顶栏与输入区两块玻璃板距屏幕左右（顶栏还含上缘）的外边距。
 * 内层内容的留白要从这个值里减出来（见 [AgentGlassInnerPad]），
 * 这样面板退到屏幕里之后，里面的文字与卡片位置一个像素都没动。
 */
internal val AgentGlassInset = AppSpacing.Md

/** 底部玻璃板内层内容的左右留白：补上外边距，屏幕上仍是原来的 16dp */
internal val AgentGlassInnerPad = AppSpacing.Lg - AgentGlassInset

/**
 * Agent 页：固定的顶栏 + 输入区夹着一条可滚动的任务流。
 *
 * 数据全部来自引擎已有状态（traces / executionHistory / AgentState / PlanPhase / planStream），
 * 由 [AgentTimelineMapper] 纯函数合成列表项——**不渲染 AgentEngine.conversation**，
 * 那里 user 角色存的是发给模型的完整决策 prompt。
 */
@Composable
fun AgentScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    /** 打开全屏记忆页（原底部「记忆」Tab 已并入 Agent 页） */
    onOpenMemory: () -> Unit = {},
) {
    val colors = AppTheme.colors

    val agent by vm.agentState.collectAsState()
    val queue by vm.taskQueue.collectAsState()
    val needsUser by vm.needsUser.collectAsState()
    val a11yEnabled by vm.a11yEnabled.collectAsState()
    val planPhase by vm.planPhase.collectAsState()
    val traces by vm.traces.collectAsState()
    val history by vm.executionHistory.collectAsState()
    val settings by vm.settingsFlow.collectAsState()
    val doc by vm.docResult.collectAsState()
    val memoryEvents by vm.memoryEvents.collectAsState()
    val sayEvents by vm.sayEvents.collectAsState()
    val sessions by vm.taskSessions.collectAsState()

    var draft by rememberSaveable { mutableStateOf("") }
    var submittedTask by rememberSaveable { mutableStateOf("") }
    var previewVisible by rememberSaveable { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    // 侧边栏里选中的任务；-1 = 跟随实时（最新一次执行 + 正在进行的规划）。
    // 新建任务时一律复位到这里，保证「新建任务打开的是新任务」而不是接着看旧的。
    var selectedTaskId by rememberSaveable { mutableStateOf(-1L) }

    val viewingTaskId = selectedTaskId.takeIf { it > 0 }
    val archivedSession = viewingTaskId?.let { id -> sessions.firstOrNull { it.taskId == id } }

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val fold = remember { LiveStatusFold() }

    // planStream 每个 delta 都会变，节流到 150ms 再重算整条列表（否则流式 markdown 会被反复重解析）
    var planText by remember { mutableStateOf("") }
    // 决策流式回显同理节流：逐字更新会让 LazyColumn 每字重建一次
    var decisionText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            planText = vm.planStream.value
            decisionText = vm.decisionStream.value
            delay(150)
        }
    }

    val items = remember(
        submittedTask, agent, planPhase, planText, decisionText, traces, history,
        queue, needsUser, a11yEnabled, fold, doc, memoryEvents, sayEvents, selectedTaskId, archivedSession,
    ) {
        AgentTimelineMapper.build(
            submittedTask = submittedTask,
            state = agent,
            planPhase = planPhase,
            planText = planText,
            traces = traces,
            history = history,
            queue = queue,
            needsUser = needsUser,
            needsUserReason = if (needsUser) agent.message else "",
            a11yEnabled = a11yEnabled,
            fold = fold,
            doc = doc,
            decisionStream = decisionText,
            memoryEvents = memoryEvents,
            sayEvents = sayEvents,
            focusTaskId = viewingTaskId,
            archived = archivedSession,
        )
    }

    val mode = when {
        needsUser -> ComposerMode.GUIDE_AGENT
        planPhase is PlanPhase.Clarifying -> ComposerMode.ANSWER_CLARIFY
        planPhase is PlanPhase.Planning -> ComposerMode.LOCKED
        // 待批准的步骤清单与「批准并开始 / 取消」都搬进输入栏，任务流里不再出卡片
        planPhase is PlanPhase.AwaitingApproval -> ComposerMode.PLAN_APPROVAL
        agent.isRunning -> ComposerMode.QUEUE_FOLLOW_UP
        else -> ComposerMode.NEW_TASK
    }
    val lockHint = when (planPhase) {
        is PlanPhase.Planning -> "AI 正在规划，请稍候…"
        else -> "请稍候…"
    }

    // 当前是否有待用户处理的交互（协助）：有就让底部浮层顶上来。
    // 澄清不走这里——提问与选项已经由输入栏承载，浮层再浮一次就是第三个入口
    val clarifyPhase = planPhase as? PlanPhase.Clarifying
    val assist = when {
        needsUser -> AssistSpec(
            title = "需要你的协助",
            message = agent.message,
            options = emptyList(),
            allowManualHandle = true,
        )
        else -> null
    }
    // 退场动画期间 assist 已经变成 null，缓存最后一次内容，避免面板先空掉再滑走
    var shownAssist by remember { mutableStateOf(assist) }
    LaunchedEffect(assist) { if (assist != null) shownAssist = assist }

    // 任务流为空（无任何执行痕迹）时展示起步空态；回看历史任务时不摆空态
    val showEmpty = viewingTaskId == null && !agent.isRunning && !needsUser && traces.isEmpty() &&
        queue.isEmpty() && planPhase is PlanPhase.Idle && doc == null
    // 规划期的新任务还没有会话记录，单独交给侧边栏置顶展示（纯对话也已产出一句回答，同样算本轮任务）
    val planningTitle = submittedTask.takeIf {
        it.isNotBlank() && (planPhase is PlanPhase.Planning || planPhase is PlanPhase.Clarifying ||
            planPhase is PlanPhase.AwaitingApproval || planPhase is PlanPhase.Reply)
    }

    val latestRunKey = traces.maxOfOrNull { it.taskId }?.let { "r$it" }
    // 工具调用已按"连续若干步"收成工具链，这里摊平回来取最新一次任务的步
    val latestSteps = items.filterIsInstance<AgentTimelineItem.ToolChain>()
        .filter { it.runKey == latestRunKey }
        .flatMap { it.steps }
    val plannedSteps = (planPhase as? PlanPhase.Approved)?.plan?.steps?.size ?: 0
    val totalSteps = maxOf(plannedSteps, agent.stepCount, latestSteps.size)
    // 进度轨只在实时视图出现：历史任务回放时的"当前步"没有意义，摆一条会误导
    val railVisible = viewingTaskId == null && !showEmpty && totalSteps >= 2
    val confidence = latestSteps.lastOrNull()?.confidence
        ?: (planPhase as? PlanPhase.Approved)?.plan?.confidence
    val showStrip = agent.isRunning || needsUser || agent.phase == AgentState.Phase.ERROR

    val stepIndexMap = remember(items) {
        // 一条工具链承载多步：链内任意一步都定位到链所在的那一行
        buildMap {
            items.forEachIndexed { index, item ->
                (item as? AgentTimelineItem.ToolChain)?.steps?.forEach { put(it.step, index) }
            }
        }
    }
    // 运行中拖动会与自动跟随打架，故只在停止时接管手势
    val onSeek: ((Int) -> Unit)? = if (agent.isRunning || stepIndexMap.isEmpty()) {
        null
    } else {
        { step -> stepIndexMap[step]?.let { index -> scope.launch { listState.scrollToItem(index) } } }
    }

    // 自动跟随：只在用户已在底部、且没有主动上滑时贴到最新
    val follow by remember { derivedStateOf { !listState.canScrollForward } }
    // 空态里已带无障碍引导文案，再把任务流的 Notice 摆在同一屏就是重复提示
    val visibleItems = if (showEmpty) {
        items.filterNot { it is AgentTimelineItem.Notice }
    } else {
        items
    }
    LaunchedEffect(visibleItems.size, planText.length, decisionText.length, follow) {
        if (follow && visibleItems.isNotEmpty()) listState.animateScrollToItem(visibleItems.lastIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceBase),
    ) {
        // 顶栏与底部输入区改为浮在任务流之上的毛玻璃层：
        // 任务流真正从它们下方穿过，模糊才有东西可模糊。
        // 取样源与两个玻璃面必须是同一 Box 下的兄弟节点，且取样源在前。
        val glass = rememberGlassState()
        val density = LocalDensity.current
        // 实测高度回填成列表的内边距，首项/末项不会被压在玻璃下面
        var headerHeight by remember { mutableIntStateOf(0) }
        var dockHeight by remember { mutableIntStateOf(0) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(glass),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.Lg,
                    end = if (railVisible) 22.dp else AppSpacing.Lg,
                    top = with(density) { headerHeight.toDp() } + AgentGlassInset + AppSpacing.Sm,
                    // 末项要能滚到输入区之上；输入区本身又浮在悬浮导航栏之上，
                    // 所以导航栏的净空也算进来，任务流才真正铺到屏幕底、从两层面板下穿过
                    bottom = with(density) { dockHeight.toDp() } +
                        AppSpacing.Lg +
                        LocalBottomNavClearance.current,
                ),
            ) {
                if (showEmpty) {
                    item(key = "empty") {
                        AgentEmptyState(
                            a11yEnabled = a11yEnabled,
                            onPickSuggestion = { draft = it },
                        )
                    }
                }
                itemsIndexed(items = visibleItems, key = { _, item -> item.key }) { index, item ->
                    Box(modifier = Modifier.animateListItem(index = index)) {
                        AgentTimelineItemView(
                            item = item,
                            vm = vm,
                            onFocusComposer = { focusRequester.requestFocus() },
                        )
                    }
                    Box(modifier = Modifier.padding(bottom = AgentItemSpacing))
                }
            }

            if (railVisible) {
                AgentStepRail(
                    totalSteps = totalSteps,
                    completed = latestSteps.count { it.verified },
                    current = agent.stepCount.coerceIn(0, totalSteps),
                    onSeek = onSeek,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }

        // 顶部玻璃浮层：四角全圆的浮动卡片。贴着屏幕上缘只圆下面两角时，
        // 剩下两个直角会与状态栏白条拼成一条硬边，读起来像"没画完"；
        // 退到屏幕里一点、四角同半径，才是一块完整的浮起面板。
        GlassSurface(
            hazeState = glass,
            shape = RoundedCornerShape(AppRadii.Hero),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(
                    start = AgentGlassInset,
                    end = AgentGlassInset,
                    top = AgentGlassInset,
                )
                .onSizeChanged { headerHeight = it.height },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AgentHeaderBar(
                    running = agent.isRunning,
                    runningTask = agent.task,
                    queue = queue,
                    taskCount = sessions.size,
                    onOpenTasks = { drawerOpen = true },
                    onOpenMemory = onOpenMemory,
                )

                // 回看历史任务时的提示条：明确当前主区域不是实时任务，并给一键回到当前任务的出口
                if (viewingTaskId != null) {
                    HistoryViewBanner(
                        title = archivedSession?.title.orEmpty(),
                        onBack = { selectedTaskId = -1L },
                        modifier = Modifier.padding(
                            start = AppSpacing.Lg,
                            end = AppSpacing.Lg,
                            top = AppSpacing.Xs,
                        ),
                    )
                }

                // 玻璃的圆角下沿不能贴着内容：留一口气，圆角才看得出来
                Spacer(Modifier.height(AppSpacing.Sm))
            }
        }

        // 底部玻璃浮层：与顶栏同一套形态——四角全圆的浮动卡片（只圆上面两角会在
        // 下沿留出一条硬边，与整页"浮起的面板"读法不一致）。imePadding 放在玻璃外层，
        // 键盘弹出时整块玻璃一起上移，而不是玻璃留在原地、内容从它下面钻出来。
        // 净空padding放在 onSizeChanged 之外，让 dockHeight 只反映玻璃本体高度，
        // 否则列表会按「玻璃 + 净空」再加一次净空，末项被顶得过高
        GlassSurface(
            hazeState = glass,
            shape = RoundedCornerShape(AppRadii.Hero),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(
                    start = AgentGlassInset,
                    end = AgentGlassInset,
                    bottom = LocalBottomNavClearance.current,
                )
                .onSizeChanged { dockHeight = it.height },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = showStrip,
                    // 与条目出现方向一致：由下向上
                    enter = fadeIn(tween(DurationNormal, easing = EaseOut)) +
                        slideInVertically(tween(DurationNormal, easing = EaseOut)) { it / 3 },
                    exit = fadeOut(tween(DurationNormal, easing = EaseOut)) +
                        slideOutVertically(tween(DurationNormal, easing = EaseOut)) { it / 3 },
                ) {
                    AgentRunStatusStrip(
                        state = agent,
                        plannedSteps = plannedSteps,
                        confidence = confidence,
                        needsUser = needsUser,
                        previewVisible = previewVisible,
                        onTogglePreview = { previewVisible = !previewVisible },
                        onFocusComposer = { focusRequester.requestFocus() },
                        onStop = { vm.stopAgent() },
                    )
                }

                // 有协助浮层时输入区让位，避免同屏出现两个输入框。
                // 判断必须看 assist 而不是 shownAssist：后者只是退场动画的内容缓存，
                // 用了它输入区会在浮层退场后一直消失
                if (assist == null) {
                    AgentComposer(
                        vm = vm,
                        draft = draft,
                        onDraftChange = { draft = it },
                        mode = mode,
                        lockHint = lockHint,
                        queueCount = queue.size,
                        onOpenQueue = {
                            if (items.isNotEmpty()) scope.launch { listState.animateScrollToItem(items.lastIndex) }
                        },
                        reviewEnabled = settings.enableReview,
                        onToggleReview = { enabled -> vm.saveSettings(settings.copy(enableReview = enabled)) },
                        options = clarifyPhase?.clarification?.options.orEmpty(),
                        onPickOption = { vm.answerClarification(it) },
                        plan = (planPhase as? PlanPhase.AwaitingApproval)?.plan,
                        onApprovePlan = { vm.approvePlan() },
                        onCancelPlan = { vm.cancelPlanning() },
                        onSend = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                when (mode) {
                                    ComposerMode.NEW_TASK -> {
                                        submittedTask = text
                                        // 新任务一律切回实时视图：主区域打开新任务，旧任务退到侧边栏
                                        selectedTaskId = -1L
                                        vm.startPlanning(text)
                                    }

                                    ComposerMode.QUEUE_FOLLOW_UP -> vm.startAgent(text)
                                    ComposerMode.ANSWER_CLARIFY ->
                                        vm.answerClarification(ClarificationOption(id = "manual", label = text))

                                    ComposerMode.GUIDE_AGENT -> vm.provideUserHint(text)
                                    ComposerMode.LOCKED, ComposerMode.PLAN_APPROVAL -> Unit
                                }
                                draft = ""
                            }
                        },
                        onStop = { vm.stopAgent() },
                        onDismissUser = { vm.dismissUser() },
                        focusRequester = focusRequester,
                    )
                }

                // 协助浮层：从页面下方浮入，承载指导输入；此时输入区让位（否则同屏两个输入框）
                AnimatedVisibility(
                    visible = assist != null,
                    enter = slideInVertically(tween(DurationNormal, easing = EaseOut)) { it } +
                        fadeIn(tween(DurationNormal, easing = EaseOut)),
                    exit = slideOutVertically(tween(DurationFast, easing = EaseOut)) { it } +
                        fadeOut(tween(DurationFast, easing = EaseOut)),
                ) {
                    shownAssist?.let { spec ->
                        AgentAssistSheet(
                            vm = vm,
                            title = spec.title,
                            message = spec.message,
                            options = spec.options,
                            allowManualHandle = spec.allowManualHandle,
                            draft = draft,
                            onDraftChange = { draft = it },
                            onPickOption = { option ->
                                vm.answerClarification(option)
                                draft = ""
                            },
                            onSubmitText = { text ->
                                if (spec.allowManualHandle) {
                                    vm.provideUserHint(text)
                                } else {
                                    vm.answerClarification(ClarificationOption(id = "manual", label = text))
                                }
                                draft = ""
                            },
                            onManualHandled = { vm.dismissUser() },
                            modifier = Modifier.padding(
                                horizontal = AgentGlassInnerPad,
                                vertical = AppSpacing.Md,
                            ),
                        )
                    }
                }
            }
        }

        // 运行画面是 500ms 轮询取帧，必须挂在与列表同层的浮层上，不能进列表项
        if (previewVisible) {
            AgentPreviewPanel(onDismiss = { previewVisible = false })
        }

        // 任务侧边栏常驻组合（关闭时是一层空 Box，不吃触摸），这样打开时才播得进入场动画
        AgentTaskDrawer(
            open = drawerOpen,
            sessions = sessions,
            pendingTitle = planningTitle,
            viewingTaskId = viewingTaskId,
            onSelect = { taskId ->
                selectedTaskId = taskId ?: -1L
                drawerOpen = false
            },
            onNewTask = {
                selectedTaskId = -1L
                drawerOpen = false
                focusRequester.requestFocus()
            },
            onDismiss = { drawerOpen = false },
        )
    }
}

/**
 * 回看历史任务的提示条：说清当前看的是哪一次、给一个回到实时的出口。
 * 不做"只读"字样——历史视图本身就不带任何可点操作，多说一句反而像在甩锅。
 */
@Composable
private fun HistoryViewBanner(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Inline))
            .background(colors.brandContainer)
            .padding(start = AppSpacing.Md, end = AppSpacing.Xs, top = AppSpacing.Xs, bottom = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.History,
            contentDescription = null,
            tint = colors.onBrandContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "正在回看历史任务",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBrandContainer,
            )
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onBrandContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PressableScale(onClick = onBack) {
            Text(
                text = "返回当前任务",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onBrandContainer,
                modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            )
        }
    }
}

/** 底部协助浮层的场景规格（当前只有「协助」一种；骨架仍与答疑共用） */
private data class AssistSpec(
    val title: String,
    val message: String,
    val options: List<ClarificationOption>,
    val allowManualHandle: Boolean,
)

/** 单个任务流列表项。抽成独立函数避免 LazyColumn 的 item 块过长 */
@Composable
private fun AgentTimelineItemView(
    item: AgentTimelineItem,
    vm: MainViewModel,
    onFocusComposer: () -> Unit,
) {
    when (item) {
        is AgentTimelineItem.UserTask -> UserTaskItem(item)
        is AgentTimelineItem.PlanStreaming -> PlanStreamingItem(item, vm)
        is AgentTimelineItem.PlanApproved -> PlanApprovedItem(item)
        is AgentTimelineItem.PlanFailed -> PlanFailedItem(item.message, vm)
        is AgentTimelineItem.AssistantNote -> AssistantNoteItem(item, vm)
        is AgentTimelineItem.Say -> SayItem(item, vm)
        is AgentTimelineItem.ToolChain -> ToolChainItem(item, vm)
        is AgentTimelineItem.LiveStatus -> LiveStatusItem(item, vm)
        is AgentTimelineItem.NeedsUser -> NeedsUserItem(item, onFocusComposer = onFocusComposer)
        is AgentTimelineItem.Done -> DoneItem(item)
        is AgentTimelineItem.Failed -> FailedItem(item.message, vm)
        is AgentTimelineItem.Notice -> NoticeItem(item)
        is AgentTimelineItem.DocPreview -> DocPreviewItem(item, onDismiss = { vm.dismissDoc() })
        is AgentTimelineItem.MemoryAdded -> MemoryCardItem(item, onUndo = { vm.undoMemory(it) })
    }
}