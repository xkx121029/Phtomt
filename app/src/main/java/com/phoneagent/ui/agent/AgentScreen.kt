package com.phoneagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.engine.PlanPhase
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.TopFadeScrim
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agent 页：固定的顶栏 + 输入区夹着一条可滚动的任务流。
 *
 * 数据全部来自引擎已有状态（traces / executionHistory / AgentState / PlanPhase / planStream），
 * 由 [AgentTimelineMapper] 纯函数合成列表项——**不渲染 AgentEngine.conversation**，
 * 那里 user 角色存的是发给模型的完整决策 prompt。
 */
@Composable
fun AgentScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
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

    var draft by rememberSaveable { mutableStateOf("") }
    var submittedTask by rememberSaveable { mutableStateOf("") }
    var previewVisible by rememberSaveable { mutableStateOf(false) }
    var expandedRuns by remember { mutableStateOf(emptySet<Long>()) }

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
        queue, needsUser, a11yEnabled, expandedRuns, fold, doc,
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
            expandedRuns = expandedRuns,
            fold = fold,
            doc = doc,
            decisionStream = decisionText,
        )
    }

    val mode = when {
        needsUser -> ComposerMode.GUIDE_AGENT
        planPhase is PlanPhase.Clarifying -> ComposerMode.ANSWER_CLARIFY
        planPhase is PlanPhase.Planning || planPhase is PlanPhase.AwaitingApproval -> ComposerMode.LOCKED
        agent.isRunning -> ComposerMode.QUEUE_FOLLOW_UP
        else -> ComposerMode.NEW_TASK
    }
    val lockHint = when (planPhase) {
        is PlanPhase.Planning -> "AI 正在规划，请稍候…"
        is PlanPhase.AwaitingApproval -> "请先在上方批准或取消计划"
        else -> "请稍候…"
    }

    // 任务流为空（无任何执行痕迹）时展示起步空态
    val showEmpty = !agent.isRunning && !needsUser && traces.isEmpty() &&
        queue.isEmpty() && planPhase is PlanPhase.Idle && doc == null

    val latestRunKey = traces.maxOfOrNull { it.taskId }?.let { "r$it" }
    val latestSteps = items.filterIsInstance<AgentTimelineItem.StepCall>()
        .filter { it.runKey == latestRunKey }
    val plannedSteps = (planPhase as? PlanPhase.Approved)?.plan?.steps?.size ?: 0
    val totalSteps = maxOf(plannedSteps, agent.stepCount, latestSteps.size)
    val railVisible = !showEmpty && totalSteps >= 2
    val confidence = latestSteps.lastOrNull()?.confidence
        ?: (planPhase as? PlanPhase.Approved)?.plan?.confidence
    val showStrip = agent.isRunning || needsUser || agent.phase == AgentState.Phase.ERROR

    val stepIndexMap = remember(items) {
        items.mapIndexedNotNull { index, item ->
            (item as? AgentTimelineItem.StepCall)?.let { it.step to index }
        }.toMap()
    }
    // 运行中拖动会与自动跟随打架，故只在停止时接管手势
    val onSeek: ((Int) -> Unit)? = if (agent.isRunning || stepIndexMap.isEmpty()) {
        null
    } else {
        { step -> stepIndexMap[step]?.let { index -> scope.launch { listState.scrollToItem(index) } } }
    }

    // 自动跟随：只在用户已在底部、且没有主动上滑时贴到最新
    val follow by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(items.size, planText.length, decisionText.length, follow) {
        if (follow && items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceBase),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AgentHeaderBar(
                running = agent.isRunning,
                runningTask = agent.task,
                queue = queue,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AppSpacing.Lg,
                        end = if (railVisible) 22.dp else AppSpacing.Lg,
                        top = AppSpacing.Sm,
                        bottom = AppSpacing.Lg,
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
                    itemsIndexed(items = items, key = { _, item -> item.key }) { index, item ->
                        Box(modifier = Modifier.animateListItem(index = index)) {
                            AgentTimelineItemView(
                                item = item,
                                vm = vm,
                                expandedRuns = expandedRuns,
                                onToggleRun = { runKey ->
                                    val taskId = runKey.removePrefix("r").toLongOrNull()
                                    if (taskId != null) {
                                        expandedRuns = if (taskId in expandedRuns) {
                                            expandedRuns - taskId
                                        } else {
                                            expandedRuns + taskId
                                        }
                                    }
                                },
                                onFocusComposer = { focusRequester.requestFocus() },
                            )
                        }
                        Box(modifier = Modifier.padding(bottom = AgentItemSpacing))
                    }
                }

                TopFadeScrim(modifier = Modifier.align(Alignment.TopCenter))

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

            AnimatedVisibility(
                visible = showStrip,
                enter = fadeIn(tween(DurationNormal, easing = EaseOut)),
                exit = fadeOut(tween(DurationNormal, easing = EaseOut)),
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

            AgentComposer(
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
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        when (mode) {
                            ComposerMode.NEW_TASK -> {
                                submittedTask = text
                                vm.startPlanning(text)
                            }

                            ComposerMode.QUEUE_FOLLOW_UP -> vm.startAgent(text)
                            ComposerMode.ANSWER_CLARIFY ->
                                vm.answerClarification(ClarificationOption(id = "manual", label = text))

                            ComposerMode.GUIDE_AGENT -> vm.provideUserHint(text)
                            ComposerMode.LOCKED -> Unit
                        }
                        draft = ""
                    }
                },
                onStop = { vm.stopAgent() },
                onDismissUser = { vm.dismissUser() },
                focusRequester = focusRequester,
            )
        }

        // 运行画面是 500ms 轮询取帧，必须挂在与列表同层的浮层上，不能进列表项
        if (previewVisible) {
            AgentPreviewPanel(onDismiss = { previewVisible = false })
        }
    }
}

/** 单个任务流列表项。抽成独立函数避免 LazyColumn 的 item 块过长 */
@Composable
private fun AgentTimelineItemView(
    item: AgentTimelineItem,
    vm: MainViewModel,
    expandedRuns: Set<Long>,
    onToggleRun: (String) -> Unit,
    onFocusComposer: () -> Unit,
) {
    when (item) {
        is AgentTimelineItem.UserTask -> UserTaskItem(item)
        is AgentTimelineItem.PlanStreaming -> PlanStreamingItem(item, vm)
        is AgentTimelineItem.PlanClarify -> PlanClarifyItem(item, vm) { vm.answerClarification(it) }
        is AgentTimelineItem.PlanApproval -> PlanApprovalItem(
            item = item,
            vm = vm,
            onApprove = { vm.approvePlan() },
            onCancel = { vm.cancelPlanning() },
        )

        is AgentTimelineItem.PlanApproved -> PlanApprovedItem(item)
        is AgentTimelineItem.PlanFailed -> PlanFailedItem(item.message, vm)
        is AgentTimelineItem.AssistantNote -> AssistantNoteItem(item, vm)
        is AgentTimelineItem.StepCall -> StepCallItem(item)
        is AgentTimelineItem.LiveStatus -> LiveStatusItem(item)
        is AgentTimelineItem.NeedsUser -> NeedsUserItem(item, onFocusComposer = onFocusComposer)
        is AgentTimelineItem.Done -> DoneItem(item)
        is AgentTimelineItem.Failed -> FailedItem(item.message, vm)
        is AgentTimelineItem.RunDigest -> RunDigestItem(
            item = item,
            expanded = (item.runKey.removePrefix("r").toLongOrNull() ?: -1L) in expandedRuns,
            onToggle = { onToggleRun(item.runKey) },
        )

        is AgentTimelineItem.Notice -> NoticeItem(item)
        is AgentTimelineItem.DocPreview -> DocPreviewItem(item, onDismiss = { vm.dismissDoc() })
    }
}