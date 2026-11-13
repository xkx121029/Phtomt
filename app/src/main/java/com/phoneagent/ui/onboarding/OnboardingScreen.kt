package com.phoneagent.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.home.BrandTile
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.SettingsState
import com.phoneagent.ui.settings.commitModelConfig
import com.phoneagent.ui.settings.modelReady
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.appBorder
import com.phoneagent.ui.theme.appBorderStroke
import com.phoneagent.ui.theme.contentSpringSpec
import com.phoneagent.ui.theme.motionSettings
import com.phoneagent.ui.theme.staggerDelayMs

/** 介绍页数：三页讲完「是什么 / 看得见 / 会记住」 */
private const val INTRO_PAGE_COUNT = 3

/** 准备：安装服务组件（无障碍、内置 Shizuku、Termux 的运行载体） */
internal const val PAGE_PREPARE = INTRO_PAGE_COUNT

/** 权限：必做 / 推荐 / 可选三组 */
internal const val PAGE_PERMISSIONS = PAGE_PREPARE + 1

/** 连接模型：预置服务商 + Key + 就地测连接 */
internal const val PAGE_MODEL = PAGE_PERMISSIONS + 1

/** 第一个任务：给几个能立刻跑的例子 */
internal const val PAGE_FIRST_TASK = PAGE_MODEL + 1

/** 向导总步数（进度点数量） */
internal const val ONBOARDING_STEPS = PAGE_FIRST_TASK + 1

/**
 * 首次启动的完整向导。
 *
 * 一条线性流程，做完就能直接跑第一个任务：
 * **介绍 ×3 → 装服务组件 → 开权限（必做/推荐/可选）→ 连模型 → 试一个任务**。
 *
 * 与旧实现的三处差别：
 * 1. 服务组件从「进主界面后另弹一层」提前到这里。无障碍能力由独立组件提供，
 *    组件没装时系统无障碍列表里根本没有可开启条目，先装再开才是通的。
 * 2. 权限按「必做 / 推荐 / 可选」分组，不再 6 项平铺——用户一眼知道先点哪个；
 *    授权全可选，完成按钮始终可用。
 * 3. 模型配置搬进向导，与「设置 → 快速开始」共用同一份 [SettingsState] 与落库路径，
 *    向导结束时模型已可用，不再出现「引导完了但还不能用」。
 *
 * 步骤号用 [rememberSaveable] 保存：旋转屏幕/进程重建都停在原页，不再回到第一页。
 * 动效遵守全项目规矩：只做自下而上的浮入 + 淡入，无水平位移、无缩放弹出。
 */
@Composable
fun OnboardingFlow(
    vm: MainViewModel,
    onFinish: () -> Unit,
    onStartTask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    // 模型表单：与设置页 / 快速开始共用同一个编辑态与落库路径，不另造一套存储
    val settings by vm.settingsFlow.collectAsState()
    val st = remember { SettingsState(settings) }
    LaunchedEffect(settings) { st.applyFrom(settings) }

    /** 前进一页；已是最后一页则结束引导 */
    fun goNext() {
        if (step < PAGE_FIRST_TASK) step++ else onFinish()
    }

    /**
     * 兜底落盘：用户在模型页填了 Key 却没点「下一步」就按了「跳过」时，
     * 已填的内容不该丢。没填齐就什么都不做（不把半套配置写进设置）。
     *
     * 走 [MainViewModel.saveSettings]（viewModelScope）而不是页面协程：
     * 这一步之后引导组合马上销毁，页面作用域的协程会被取消，写入可能半途而废。
     */
    fun persistModelIfReady() {
        if (!st.modelReady()) return
        commitModelConfig(st)
        vm.saveSettings(st.toSettings())
    }

    // 系统返回 = 上一页；第一页返回直接结束引导（与旧行为一致）
    BackHandler(enabled = true) {
        if (step > 0) step-- else {
            persistModelIfReady()
            onFinish()
        }
    }

    OnboardingScaffold(
        step = step,
        onSkip = {
            persistModelIfReady()
            onFinish()
        },
        modifier = modifier,
    ) { index ->
        when {
            index < INTRO_PAGE_COUNT -> IntroPage(index = index, onNext = ::goNext)
            index == PAGE_PREPARE -> OnboardingPreparePage(onNext = ::goNext)
            index == PAGE_PERMISSIONS -> OnboardingPermissionPage(vm = vm, onNext = ::goNext)
            index == PAGE_MODEL -> OnboardingModelPage(vm = vm, st = st, onNext = ::goNext)
            else -> OnboardingFirstTaskPage(
                vm = vm,
                st = st,
                onFinish = onFinish,
                onStartTask = onStartTask,
                onBackToModel = { step = PAGE_MODEL },
            )
        }
    }
}

/** 向导外壳：顶部进度 + 跳过，中部当前页，换页只做自下而上浮入 */
@Composable
private fun OnboardingScaffold(
    step: Int,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (Int) -> Unit,
) {
    val motion = motionSettings()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶部：进度点 + 跳过。跳过 = 结束整个引导，不再"跳到下一阶段"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpacing.Lg, end = AppSpacing.Sm, top = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepDots(
                count = ONBOARDING_STEPS,
                current = step,
                modifier = Modifier.padding(start = AppSpacing.Xs),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSkip) {
                Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (
                    fadeIn(tween(DurationSlow, easing = EaseOut)) +
                        slideInVertically(
                            animationSpec = contentSpringSpec(),
                            initialOffsetY = { if (motion.reduceMotion) 0 else it / 4 },
                        )
                    ) togetherWith fadeOut(tween(DurationFast))
            },
            label = "onboarding-step",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { index ->
            page(index)
        }
    }
}

/** 一页介绍：品牌瓦片（仅首屏）+ 标题 + 正文 */
@Composable
private fun IntroPage(index: Int, onNext: () -> Unit) {
    val info = IntroSteps[index]
    OnboardingPage(
        content = {
            if (index == 0) {
                StaggerIn(0) { BrandTile(size = 72.dp) }
                Spacer(Modifier.height(AppSpacing.Lg))
            } else {
                StepIconTile(info.icon)
            }
            StaggerIn(1) {
                Text(
                    info.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(AppSpacing.Md))
            StaggerIn(2) {
                Text(
                    info.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        footer = {
            PrimaryOnboardingButton(
                label = if (index == INTRO_PAGE_COUNT - 1) "开始配置" else "继续",
                onClick = onNext,
            )
        },
    )
}

/** 引导页文案与配图 */
private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val IntroSteps = listOf(
    OnboardingStep(
        AppIcons.AutoAwesome,
        "你好，我是 Happy Agent",
        "一句话说出需求，我来替你操作手机：自己看屏幕、自己点按输入，一步步把事情做完。",
    ),
    OnboardingStep(
        AppIcons.TouchApp,
        "每个动作都看得见",
        "执行时屏幕上会标出点击位置，进度实时播报；需要确认时先来问你，不乱动你的手机。",
    ),
    OnboardingStep(
        AppIcons.Remember,
        "越用越懂你",
        "自动记住常用页面与操作路径，学会的技能可以复用；网页操作与文档产出直接交付结果。",
    ),
)

/**
 * 各准备页共用的页首：图标瓦片 + 标题 + 说明。
 *
 * 三步（组件 / 权限 / 模型）版式完全一致，抽出这一份避免三处各调一遍字阶与间距，
 * 也保证后续改版式只改一处。
 */
@Composable
internal fun OnboardingPageHeader(
    icon: ImageVector,
    title: String,
    body: String,
) {
    StepIconTile(icon)
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = AppSpacing.Lg),
    )
    Spacer(Modifier.height(AppSpacing.Md))
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * 引导页骨架：上方是「内容少时垂直居中、内容多时可滚动」的区域，底部是固定操作区。
 *
 * 固定底部（而不是让按钮跟着内容滚走）是为了让每一步的出口始终在同一位置；
 * 内容高度不够时靠 [BoxWithConstraints] 拿到可用高度并作为最小高度，实现垂直居中。
 */
@Composable
internal fun OnboardingPage(
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                content()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = AppSpacing.Lg, bottom = AppSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            footer()
        }
    }
}

/** 主行动按钮：全宽 52dp 胶囊，向导每一步的主出口都用它 */
@Composable
internal fun PrimaryOnboardingButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 步骤配图瓦片：品牌色低饱和底 + 主题图标，与列表条目的图标瓦片同一套语言 */
@Composable
internal fun StepIconTile(icon: ImageVector) {
    val colors = AppTheme.colors
    StaggerIn(0) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(AppRadii.Hero))
                .background(colors.brand.copy(alpha = 0.14f))
                .appBorder(1.dp, colors.brand.copy(alpha = 0.25f), RoundedCornerShape(AppRadii.Hero)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 进度点：当前步收成品牌色胶囊，其余是静默圆点，颜色与宽度用同一根曲线过渡 */
@Composable
private fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 18.dp else 6.dp,
                animationSpec = contentSpringSpec(),
                label = "dot-width",
            )
            Surface(
                shape = CircleShape,
                color = if (active) {
                    AppTheme.colors.brand
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                modifier = Modifier.size(width = width, height = 6.dp),
            ) {}
        }
    }
}

/** 就绪统计胶囊：全部点亮时换成成功色 + 对勾 */
@Composable
internal fun ReadyBadge(granted: Int, total: Int, allGranted: Boolean) {
    val color = if (allGranted) Success else MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        border = appBorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            Icon(
                if (allGranted) AppIcons.CheckCircle else AppIcons.Guard,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Text(
                if (allGranted) "全部就绪" else "已就绪 $granted / $total",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

/**
 * 分段入场：元素自下而上浮入 + 淡入，相邻元素错开 [staggerDelayMs] 形成"级联"。
 * 只在首次进入组合时播一次；减少动画时直接呈现、不做位移。
 */
@Composable
internal fun StaggerIn(
    index: Int,
    content: @Composable () -> Unit,
) {
    val motion = motionSettings()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val offsetY = if (motion.reduceMotion) 0 else 1
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(
            tween(DurationSlow, delayMillis = staggerDelayMs(index), easing = EaseOut),
        ) + slideInVertically(
            animationSpec = contentSpringSpec(),
            initialOffsetY = { if (motion.reduceMotion) 0 else it / 3 * offsetY },
        ),
    ) {
        content()
    }
}
