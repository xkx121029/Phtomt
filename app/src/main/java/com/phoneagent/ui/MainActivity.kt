package com.phoneagent.ui

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.ui.agent.AgentScreen
import com.phoneagent.ui.components.AppSnackbar
import com.phoneagent.ui.components.SnackbarState
import com.phoneagent.ui.debug.DebugScreen
import com.phoneagent.ui.home.HomeScreen
import com.phoneagent.ui.memory.MemoryGraphScreen
import com.phoneagent.ui.settings.SettingsScreen
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.PhoneAgentTheme
import com.phoneagent.ui.theme.motionSettings
import org.koin.androidx.compose.koinViewModel
import com.phoneagent.ui.icons.AppIcons

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PhoneAgentTheme {
                val vm: MainViewModel = koinViewModel()
                ActivityContent(vm)
            }
        }
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
        }
    },
    restore = { list ->
        when (list.firstOrNull()) {
            "__null__" -> null
            "test" -> ExtrasPage.Test
            "debug" -> ExtrasPage.Debug
            "skill" -> ExtrasPage.Skill
            "memory" -> ExtrasPage.Memory
            else -> null
        }
    },
)

private data class TabItem(val label: String, val icon: ImageVector)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityContent(vm: MainViewModel) {
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

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // 全屏二级页时不显示底部导航
            if (extrasPage == null && !keyboardUp) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = {
                                // 选中图标轻微放大（弹簧回弹），未选中恢复正常
                                val iconScale by animateFloatAsState(
                                    targetValue = if (selected == index) 1.14f else 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.6f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                    label = "nav-icon-scale",
                                )
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.scale(iconScale),
                                )
                            },
                            label = { Text(tab.label) },
                            // Material 3 Expressive：选中态使用主色胶囊指示器 + 主色文字
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
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
                AppSnackbar(
                    state = snackbarState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp),
                )
            }
        }
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