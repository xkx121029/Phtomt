package com.phoneagent.ui.agent

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut

/**
 * 输入区状态机。
 *
 * 关键约束来自引擎真实行为（见方案 2.5）：
 * - 运行中不禁用输入，走「排队下一条」而不是并发执行；
 * - Planning 阶段必须锁定——此时引擎的规划 job 尚空闲，
 *   若下发新任务会与规划并发跑，所以这个阶段一律 LOCKED；
 * - AwaitingApproval 不锁定输入框（没有输入框），而是整块换成计划面板：
 *   步骤清单与「批准并开始 / 取消」都落在输入栏里，任务流里不再另出一张卡片。
 */
internal enum class ComposerMode { NEW_TASK, QUEUE_FOLLOW_UP, GUIDE_AGENT, ANSWER_CLARIFY, LOCKED, PLAN_APPROVAL }

/** 细行开关：比 M3 默认 Switch 紧凑，匹配 28dp 行高 */
@Composable
private fun MiniSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val trackShape = RoundedCornerShape(AppRadii.Chip)
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 19.dp else 3.dp,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "mini-switch-knob",
    )
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(trackShape)
            .background(if (checked) colors.brand else colors.surfaceSunken)
            .border(1.dp, if (checked) colors.brand else colors.outlineSoft, trackShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                buzz()
                onToggle(!checked)
            },
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = 3.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(if (checked) colors.onBrand else colors.outlineStrong),
        )
    }
}

/** 等待执行队列入口：只在有排队任务时出现，点开查看明细 */
@Composable
private fun QueueStrip(count: Int, onOpen: () -> Unit) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    PressableScale(
        onPress = buzz,
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.Sm)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .background(colors.brandContainer)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "还有 $count 条任务排队等待执行",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.onBrandContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.brand,
            )
        }
    }
}

/**
 * 输入面内主操作的尺寸。
 * 与 [AgentActionButton] 的高度（20sp 行高 + 上下各 10dp）对齐，
 * 一行的两个按钮底边齐平，不会一个高一个矮。
 */
private val ComposerActionSize = 40.dp

/**
 * 输入面内的主操作：方块图标按钮。
 *
 * 为什么不是通栏文字按钮：主操作属于"输入框的一部分"——按下的对象是刚写完的这句话，
 * 不是页面底部的一个独立表单按钮。收进输入面之后，底部浮层从"输入框 + 一行按钮"
 * 压回单段高度，动作与文本在同一个容器里，视线不用来回跳。
 * 禁用态用下沉底色而不是降透明度：纸白底上一团半透明的主色会发浑，换底色更干净。
 */
@Composable
private fun ComposerActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val shape = RoundedCornerShape(AppRadii.Tile)
    PressableScale(
        onPress = buzz,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(ComposerActionSize)
            .clip(shape)
            .background(if (enabled) colors.brand else colors.surfaceSunken),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) colors.onBrand else colors.outlineStrong,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 澄清态的候选答案：铺在输入面正上方的一排胶囊，点一下即答。
 *
 * 规划期 AI 反问时，"问题 + 选项"整个落在输入栏里，任务流与浮层都不再重复——
 * 使用者看到的就是"该我答了"，而不是"列表里多了一条记录"。
 * 横向可滚：选项文字长短不一，宁可滚也不要让输入框被顶高。
 */
@Composable
private fun ClarifyOptionChips(
    options: List<ClarificationOption>,
    onPick: (ClarificationOption) -> Unit,
) {
    val colors = AppTheme.colors
    val buzz = rememberHapticClick()
    val shape = RoundedCornerShape(AppRadii.Chip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = AppSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            PressableScale(
                onPress = buzz,
                onClick = { onPick(option) },
                modifier = Modifier
                    .clip(shape)
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.outlineSoft, shape)
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            ) {
                Column {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (option.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceRaised,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 待批准的计划面板：整块占据输入栏的位置。
 *
 * 为什么放在输入栏而不是任务流里：批准是"该我拍板了"的动作，和输入框一样属于
 * 底部操作区；摆进任务流会跟历史消息混在一起，用户得往上翻才找得到按钮。
 * 步骤清单限高内滚——步骤多时也不能把底部面板顶到半屏高。
 */
@Composable
private fun PlanApprovalPanel(
    plan: TaskPlan,
    vm: MainViewModel,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppRadii.Card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineSoft, shape)
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "执行计划",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = "共 ${plan.steps.size} 步", color = colors.brand)
            Spacer(Modifier.width(AppSpacing.Xs))
            StatusPill(
                text = "把握 ${(plan.confidence * 100).toInt()}%",
                color = confidenceColor(plan.confidence),
            )
        }
        if (plan.estimatedTimeSeconds > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "预计耗时约 ${plan.estimatedTimeSeconds} 秒",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
            )
        }
        Spacer(Modifier.height(AppSpacing.Sm))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PlanListMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            plan.steps.forEachIndexed { index, step ->
                val desc = rememberTranslated(step.description, vm)
                val intent = rememberTranslated(step.intent, vm)
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.brand,
                        modifier = Modifier.width(16.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (intent.isNotBlank()) {
                            Text(
                                text = intent,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceRaised,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
            AgentActionButton(
                text = "取消",
                tone = AgentButtonTone.NEUTRAL,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            AgentActionButton(
                text = "批准并开始",
                icon = AppIcons.Play,
                onClick = onApprove,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 计划步骤清单在输入栏里的最大高度：超出内滚，底部面板不会被顶高 */
private val PlanListMaxHeight = 132.dp

/**
 * 固定底部输入区：输入框与动作按钮同处一个容器，圆角与内边距随聚焦形变，
 * 按钮随 [mode] 切换。不使用系统弹窗，队列入口为内嵌细条。
 */
@Composable
internal fun AgentComposer(
    vm: MainViewModel,
    draft: String,
    onDraftChange: (String) -> Unit,
    mode: ComposerMode,
    lockHint: String,
    queueCount: Int,
    onOpenQueue: () -> Unit,
    reviewEnabled: Boolean,
    onToggleReview: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDismissUser: () -> Unit,
    /** 澄清态的可选答案：非空时输入面顶部先铺一排选项胶囊，点一下即答 */
    options: List<ClarificationOption> = emptyList(),
    onPickOption: (ClarificationOption) -> Unit = {},
    /** 待批准的计划：非空且 mode 为 [ComposerMode.PLAN_APPROVAL] 时，整块输入面换成计划面板 */
    plan: TaskPlan? = null,
    onApprovePlan: () -> Unit = {},
    onCancelPlan: () -> Unit = {},
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val locked = mode == ComposerMode.LOCKED
    var focused by remember { mutableStateOf(false) }

    val fieldShape = RoundedCornerShape(if (focused && !locked) AppRadii.Hero else AppRadii.Card)
    val fieldPad by animateDpAsState(
        targetValue = if (focused && !locked) AppSpacing.Lg else AppSpacing.Md,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "composer-pad",
    )
    val fieldElevation by animateDpAsState(
        targetValue = if (focused && !locked) 6.dp else 0.dp,
        animationSpec = tween(DurationFast, easing = EaseOut),
        label = "composer-elevation",
    )

    val placeholder = when (mode) {
        ComposerMode.LOCKED -> lockHint.ifBlank { "请稍候…" }
        ComposerMode.PLAN_APPROVAL -> "请先批准或取消计划"
        ComposerMode.ANSWER_CLARIFY -> "或者自己说一个答案"
        ComposerMode.QUEUE_FOLLOW_UP -> "追加下一条任务（当前任务结束后执行）"
        ComposerMode.GUIDE_AGENT -> "直接告诉我该怎么做"
        ComposerMode.NEW_TASK -> "描述任务，AI 将逐步接管手机"
    }

    // 自身不铺底：这一层被 AgentScreen 的底部毛玻璃板包着，
    // 底色交给玻璃，输入区才有"浮在任务流之上"的层次。
    // 玻璃板已退到屏幕里一段，这里把那段距离补回来，输入框在屏幕上仍是原来的位置
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AgentGlassInnerPad, vertical = AppSpacing.Md),
    ) {
        if (queueCount > 0) {
            QueueStrip(count = queueCount, onOpen = onOpenQueue)
        }

        if (mode == ComposerMode.NEW_TASK) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.Sm),
            ) {
                Text(
                    text = "审核 AI",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = "独立复核每个动作是否真有页面证据",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceRaised,
                    modifier = Modifier.weight(1f),
                )
                MiniSwitch(checked = reviewEnabled, onToggle = onToggleReview)
            }
        }

        // 澄清态：输入面顶部先铺候选答案，点一下即答；下面的输入框留给选项覆盖不到的情况
        if (mode == ComposerMode.ANSWER_CLARIFY && options.isNotEmpty()) {
            ClarifyOptionChips(options = options, onPick = onPickOption)
        }

        // 待批准：整块输入面换成计划面板——步骤清单与批准键就在输入栏的位置上
        if (mode == ComposerMode.PLAN_APPROVAL && plan != null) {
            PlanApprovalPanel(
                plan = plan,
                vm = vm,
                onApprove = onApprovePlan,
                onCancel = onCancelPlan,
            )
            return@Column
        }

        // 输入面：一个容器同时装下输入框与动作按钮。
        // 右侧按钮贴着行底：文本涨到多行时，按钮留在右下角不动，不会跟着文字上下漂
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(fieldElevation, fieldShape)
                .clip(fieldShape)
                .background(colors.surfaceRaised)
                .border(
                    width = 1.dp,
                    color = if (focused && !locked) colors.brand.copy(alpha = 0.45f) else colors.outlineSoft,
                    shape = fieldShape,
                )
                .padding(
                    start = fieldPad,
                    end = AppSpacing.Sm,
                    top = AppSpacing.Sm,
                    bottom = AppSpacing.Sm,
                )
                .alpha(if (locked) 0.7f else 1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ComposerActionSize),
                contentAlignment = Alignment.Center,
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceRaised,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { if (!locked) onDraftChange(it) },
                    enabled = !locked,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(colors.brand),
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                )
            }

            // 次要动作跟主操作挤在同一行：停止 / 已手动处理是"运行中才会出现"的旁路出口，
            // 不值得为它单独占掉一整行的高度
            when (mode) {
                ComposerMode.GUIDE_AGENT -> AgentActionButton(
                    text = "已手动处理",
                    tone = AgentButtonTone.NEUTRAL,
                    onClick = onDismissUser,
                )

                ComposerMode.QUEUE_FOLLOW_UP -> AgentActionButton(
                    text = "停止",
                    icon = AppIcons.Stop,
                    tone = AgentButtonTone.DANGER,
                    onClick = onStop,
                )

                else -> Unit
            }

            when (mode) {
                ComposerMode.LOCKED -> ComposerActionButton(
                    icon = AppIcons.Send,
                    description = lockHint.ifBlank { "请稍候…" },
                    enabled = false,
                    onClick = {},
                )

                ComposerMode.NEW_TASK -> ComposerActionButton(
                    icon = AppIcons.Send,
                    description = "发送",
                    enabled = draft.isNotBlank(),
                    onClick = onSend,
                )

                ComposerMode.ANSWER_CLARIFY -> ComposerActionButton(
                    icon = AppIcons.Send,
                    description = "提交回答",
                    enabled = draft.isNotBlank(),
                    onClick = onSend,
                )

                ComposerMode.GUIDE_AGENT -> ComposerActionButton(
                    icon = AppIcons.Send,
                    description = "指导 AI",
                    enabled = draft.isNotBlank(),
                    onClick = onSend,
                )

                // 排队用「加号」：它的含义是"把这条追加到队列末尾"，不是"发出去"
                ComposerMode.QUEUE_FOLLOW_UP -> ComposerActionButton(
                    icon = AppIcons.Add,
                    description = "排队执行下一条",
                    enabled = draft.isNotBlank(),
                    onClick = onSend,
                )

                // 计划面板不走这一行（上面已经整块替换掉了），这里只为穷尽分支
                ComposerMode.PLAN_APPROVAL -> Unit
            }
        }
    }
}