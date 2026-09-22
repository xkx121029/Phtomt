package com.phoneagent.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.ui.agent.AgentScreen
import com.phoneagent.ui.components.AppSnackbar
import com.phoneagent.ui.components.SnackbarState
import com.phoneagent.ui.components.rememberHapticPress
import com.phoneagent.ui.debug.DebugScreen
import com.phoneagent.ui.home.HomeScreen
import com.phoneagent.ui.memory.MemoryGraphScreen
import com.phoneagent.ui.settings.SettingsScreen
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.PhoneAgentTheme
import com.phoneagent.ui.theme.SpringConfigs
import com.phoneagent.ui.theme.motionSettings
import org.koin.androidx.compose.koinViewModel
import com.phoneagent.ui.icons.AppIcons

class MainActivity : ComponentActivity() {

    /**
     * 外部打开二级页的信号：AI 执行 browse_* 时，引擎从后台把 App 切到「浏览器」页。
     * 已在栈上时走 [onNewIntent]（启动 Intent 带 SINGLE_TOP|CLEAR_TOP），这里自增即触发切页。
     */
    private val pageSignal = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PhoneAgentTheme {
                val vm: MainViewModel = koinViewModel()
                ActivityContent(vm, pageSignal)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pageSignal.value++
    }
}

/** 全屏二级页（从主页进入，非底部 Tab） */
sealed class ExtrasPage {
    object Test : ExtrasPage()
    object Debug : ExtrasPage()
    /** 技能与能力管理：Skill / MCP / 无线 ADB / 提示词（HPA 迭代 A7） */
    object Skill : ExtrasPage()
    /** 记忆：原底部 Tab 已并入 Agent 页，改由顶栏入口打开 */
    object Memory : ExtrasPage()
    /** 内置浏览器：AI 上网/操作网页的落点，用户也可直接当浏览窗口用 */
    object Browser : ExtrasPage()
}

/** ExtrasPage 状态保存：跨进程重建后恢复当前二级页 */
private val ExtrasPageSaver = listSaver<ExtrasPage?, Any?>(
    save = { page ->
        when (page) {
            null -> listOf("__null__")
            is ExtrasPage.Test -> listOf("test")
            is ExtrasPage.Debug -> listOf("debug")
            is ExtrasPage.Skill -> listOf("skill")
            is ExtrasPage.Memory -> listOf("memory")
            is ExtrasPage.Browser -> listOf("browser")
        }
    },
    restore = { list ->
        when (list.firstOrNull()) {
            "__null__" -> null
            "test" -> ExtrasPage.Test
            "debug" -> ExtrasPage.Debug
            "skill" -> ExtrasPage.Skill
            "memory" -> ExtrasPage.Memory
            "browser" -> ExtrasPage.Browser
            else -> null
        }
    },
)

private data class TabItem(val label: String, val icon: ImageVector)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityContent(vm: MainViewModel, pageSignal: kotlinx.coroutines.flow.MutableStateFlow<Int>) {
    var selected by rememberSaveable { mutableStateOf(0) }
    var extrasPage by rememberSaveable(stateSaver = ExtrasPageSaver) { mutableStateOf<ExtrasPage?>(null) }
    val snackbarState = remember { SnackbarState() }
    // 记忆已并入 Agent 页（顶栏入口 + 任务流内嵌卡片），不再占底部 Tab
    val tabs = listOf(
        TabItem("Agent", AppIcons.Bolt),
        TabItem("主页", AppIcons.Home),
        TabItem("设置", AppIcons.Settings),
    )
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val motion = motionSettings()
    val transitionDuration = motion.scaledDuration(DurationSlow)

    // 监听 Agent 状态和设置，控制跑马光效
    val agentState by vm.agentState.collectAsState()
    val settings by vm.settingsFlow.collectAsState()
    val overlayGranted by vm.overlayGranted.collectAsState()

    LaunchedEffect(agentState.isRunning, settings.edgeLightingEnabled, overlayGranted) {
        val shouldShow = agentState.isRunning && settings.edgeLightingEnabled && overlayGranted
        if (shouldShow) {
            EdgeLightingService.start(
                context = activity,
                top = settings.edgeInsetTop,
                bottom = settings.edgeInsetBottom,
                left = settings.edgeInsetLeft,
                right = settings.edgeInsetRight,
                radius = settings.cornerRadius,
                width = settings.edgeLightingWidth,
                speed = 0.3f,
            )
        } else {
            EdgeLightingService.stop(activity)
        }
    }

    // Screen transition spec — symmetric paths with ease-out.
    // Principle: "If something disappears one way, we expect it to emerge
    // from where it came."
    val transitionSpec: AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
        if (motion.reduceMotion) {
            // Reduced motion: simple fade only
            fadeIn(tween(durationMillis = transitionDuration, easing = EaseOut)) togetherWith
                fadeOut(tween(durationMillis = transitionDuration, easing = EaseOut))
        } else {
            val direction = if (targetState > initialState) 1 else -1
            slideInHorizontally(
                initialOffsetX = { fullWidth -> direction * fullWidth / 4 },
                animationSpec = tween(durationMillis = transitionDuration, easing = EaseOut),
            ) + fadeIn(
                animationSpec = tween(durationMillis = transitionDuration, easing = EaseOut),
            ) togetherWith
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> direction * -fullWidth / 4 },
                    animationSpec = tween(durationMillis = transitionDuration, easing = EaseOut),
                ) + fadeOut(
                    animationSpec = tween(durationMillis = transitionDuration, easing = EaseOut),
                )
        }
    }

    // Screenshot authorization
    val screenshotLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vm.startScreenshotService(activity, result.resultCode, result.data)
    }

    LaunchedEffect(Unit) {
        vm.refreshStatus(activity)
        vm.refreshA11yState()
    }

    // AI 上网：引擎从后台拉起本页并要求切到「浏览器」二级页（BrowserBridge.EXTRA_BROWSE）。
    // 首次组合（onCreate）与已在栈上被复用（onNewIntent）都走这里，保证两条路径的落点一致。
    val browseSignal by pageSignal.collectAsState()
    LaunchedEffect(browseSignal) {
        val intent = activity.intent
        if (intent?.getBooleanExtra(com.phoneagent.feature.browser.BrowserBridge.EXTRA_BROWSE, false) == true) {
            intent.removeExtra(com.phoneagent.feature.browser.BrowserBridge.EXTRA_BROWSE)
            extrasPage = ExtrasPage.Browser
        }
    }

    // 全局返回手势返回上一层：
    // 1) 二级页（测试/调试/技能与能力）→ 关闭二级页回到当前 Tab；
    // 2) 非 Agent Tab → 回到 Agent（主界面）；
    // 3) Agent → 保持系统默认行为（退出应用）
    BackHandler(enabled = extrasPage != null) {
        extrasPage = null
    }
    BackHandler(enabled = extrasPage == null && selected != 0) {
        selected = 0
    }

    // 键盘是否弹出：必须在内容层读一次，不能写在 Scaffold 的 bottomBar 里。
    // bottomBar 是 SubcomposeLayout 的子组合，在它内部读 insets 不保证随键盘弹出而重组，
    // 一旦读到旧值（false），导航栏就不让位：Scaffold 的底部内边距仍带着导航栏高度，
    // 输入区再整段避让 IME，两者相加 → 输入区浮在键盘上方一整条导航栏的高度，中间空出一块。
    //
    // 判定用 IME 实际占位高度，而不是 WindowInsets.isImeVisible：
    // 前者与输入区的 imePadding() 同源，只要输入区被抬起，导航栏在同一帧必定让位；
    // 可见位在部分机型/ROM 上不可靠（键盘已弹出却仍为 false）。
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // 系统手势导航条高度：悬浮导航栏要靠它让开底部系统区域。
    // 与 keyboardUp 同理在内容层读一次，避免在 bottomBar 的子组合里读到旧值。
    val systemNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 底部悬浮导航栏的净空 = 条本体 + 与屏幕的呼吸间距 + 系统手势区。
    // 键盘弹出或进全屏二级页时导航栏不显示，净空归零，页面按原样铺满。
    val navBarVisible = extrasPage == null && !keyboardUp
    val navClearance = if (navBarVisible) NavBarHeight + AppSpacing.Md + systemNavInset else 0.dp

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        // 不再用 bottomBar 整段预留底部：导航栏改为浮在内容之上，
        // 否则条下方会留下一条看不见的矩形预留带，看着像把内容截断了。
        // 各页自己按 LocalBottomNavClearance 垫出净空。
    ) { padding ->
        // 统一白色状态栏边条：所有页面顶部先铺一条与状态栏等高的白色/浅色背景，
        // 内容整体下移，避免与系统状态栏图标重叠（edge-to-edge 下状态栏区域透明）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .background(MaterialTheme.colorScheme.surface),
            )
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBottomNavClearance provides navClearance) {
                    val currentExtras = extrasPage
                    if (currentExtras != null) {
                    // 全屏二级页：返回栏 + 对应页面
                    ExtrasPageContent(
                        page = currentExtras,
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        onBack = { extrasPage = null },
                    )
                    return@Box
                }

                AnimatedContent(
                    targetState = selected,
                    transitionSpec = transitionSpec,
                    label = "screen-switch",
                ) { screenIndex ->
                    val contentMod = Modifier.fillMaxSize()
                    when (screenIndex) {
                        0 -> AgentScreen(
                            vm, contentMod,
                            onOpenMemory = { extrasPage = ExtrasPage.Memory },
                        )
                        1 -> HomeScreen(
                            vm, contentMod,
                            onRequestScreenshot = {
                                val mpm = activity.getSystemService(MediaProjectionManager::class.java)
                                screenshotLauncher.launch(mpm.createScreenCaptureIntent())
                            },
                            onNavigate = { selected = it },
                            onOpenExtras = { extrasPage = it },
                        )
                        2 -> SettingsScreen(vm, contentMod)
                    }
                }

                // 全局 Snackbar 覆盖层
                // 内容区已被 Scaffold 让出底部悬浮导航栏的高度，这里只留一点呼吸间距
                AppSnackbar(
                    state = snackbarState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = AppSpacing.Md),
                )
            }
        }
    }
}

/**
 * 底部导航：大圆角长方形悬浮条。
 *
 * 与全站卡片语言同源——surfaceContainerHigh 底 + 1dp 细边框 + 大圆角 + 投影，
 * 左右留白、底部让开系统导航区后悬浮，不再通栏贴底。
 * 选中态沿用 Material 3 Expressive 的主色胶囊指示器与图标弹簧放大。
 */
@Composable
private fun FloatingNavBar(
    tabs: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppRadii.Hero)
    Surface(
        modifier = modifier
            .shadow(elevation = 10.dp, shape = shape, clip = false)
            .clip(shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = shape,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = AppSpacing.Sm)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                FloatingNavItem(
                    tab = tab,
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 悬浮导航栏的单个条目：主色胶囊指示器 + 图标 + 文字，按下即回弹（无涟漪） */
@Composable
private fun FloatingNavItem(
    tab: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hapticPress = rememberHapticPress()
    // 与 PressableScale 同一套手感：反馈发生在 pointer-down，而不是抬手时
    LaunchedEffect(pressed) { if (pressed) hapticPress() }

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = SpringConfigs.ButtonDampingRatio,
            stiffness = SpringConfigs.ButtonStiffness,
        ),
        label = "nav-item-press",
    )
    // 选中图标轻微放大（弹簧回弹），未选中恢复正常
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.14f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "nav-icon-scale",
    )
    // 选中态：主色胶囊指示器 + 主色文字
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(DurationNormal, easing = EaseOut),
        label = "nav-indicator",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(DurationNormal, easing = EaseOut),
        label = "nav-icon-color",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(DurationNormal, easing = EaseOut),
        label = "nav-label-color",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .scale(pressScale)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 30.dp)
                .background(color = indicatorColor, shape = RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = iconColor,
                modifier = Modifier
                    .size(22.dp)
                    .scale(iconScale),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 1,
        )
    }
}

/** 全屏二级页内容：顶部返回栏 + 对应页面 */
@Composable
private fun ExtrasPageContent(
    page: ExtrasPage,
    vm: MainViewModel,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // 各二级页都自带页头标题（测试页「AI 测试工作台」、调试/技能/记忆页 AppTopBar），
        // 这里再渲染一行页名会与页面自身标题重复（调试页同屏出现两个「调试」），故移除。
        when (page) {
            ExtrasPage.Test -> com.phoneagent.ui.test.TestScreen(
                vm,
                Modifier.weight(1f).padding(horizontal = 0.dp),
            )
            ExtrasPage.Debug -> DebugScreen(
                vm,
                Modifier.weight(1f).padding(horizontal = 0.dp),
            )
            ExtrasPage.Skill -> com.phoneagent.ui.skill.SkillManagerScreen(
                vm,
                Modifier.weight(1f).padding(horizontal = 0.dp),
            )
            ExtrasPage.Memory -> MemoryGraphScreen(
                vm,
                Modifier.weight(1f).padding(horizontal = 0.dp),
            )
            ExtrasPage.Browser -> com.phoneagent.ui.browser.BrowserScreen(
                Modifier.weight(1f).padding(horizontal = 0.dp),
            )
        }
        // 底部大圆角返回主页按钮
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                onBack()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = RoundedCornerShape(AppRadii.Hero),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .height(56.dp),
        ) {
            Icon(AppIcons.Home, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                "返回主页",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}