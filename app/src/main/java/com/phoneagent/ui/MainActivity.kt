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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.phoneagent.edge.EdgeLightingService
import com.phoneagent.ui.agent.AgentScreen
import com.phoneagent.ui.components.AppSnackbar
import com.phoneagent.ui.components.SnackbarState
import com.phoneagent.ui.components.liquidGlass
import com.phoneagent.ui.debug.DebugScreen
import com.phoneagent.ui.home.HomeScreen
import com.phoneagent.ui.memory.MemoryGraphScreen
import com.phoneagent.ui.settings.SettingsScreen
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.PhoneAgentTheme
import com.phoneagent.ui.theme.motionSettings
import com.phoneagent.ui.workspace.FileEditorScreen
import com.phoneagent.ui.workspace.FileListScreen
import com.phoneagent.ui.workspace.WorkAreaScreen
import org.koin.androidx.compose.koinViewModel

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

/** 全屏二级页（从主页/工作区进入，非底部 Tab） */
sealed class ExtrasPage {
    object Test : ExtrasPage()
    object Debug : ExtrasPage()
    /** 文件列表页：独立展示工作区全部文件 */
    object FileList : ExtrasPage()
    /** 文档预览编辑页：文件全文 + 底部 AI 聊天框 */
    data class FileEditor(val fileName: String) : ExtrasPage()
}

/** ExtrasPage 状态保存：跨进程重建后恢复当前二级页 */
private val ExtrasPageSaver = listSaver<ExtrasPage?, Any?>(
    save = { page ->
        when (page) {
            null -> listOf("__null__")
            is ExtrasPage.Test -> listOf("test")
            is ExtrasPage.Debug -> listOf("debug")
            is ExtrasPage.FileList -> listOf("filelist")
            is ExtrasPage.FileEditor -> listOf("file", page.fileName)
        }
    },
    restore = { list ->
        when (list.firstOrNull()) {
            "__null__" -> null
            "test" -> ExtrasPage.Test
            "debug" -> ExtrasPage.Debug
            "filelist" -> ExtrasPage.FileList
            "file" -> ExtrasPage.FileEditor(list.getOrNull(1)?.toString() ?: "")
            else -> null
        }
    },
)

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
private fun ActivityContent(vm: MainViewModel) {
    var selected by rememberSaveable { mutableStateOf(0) }
    var extrasPage by rememberSaveable(stateSaver = ExtrasPageSaver) { mutableStateOf<ExtrasPage?>(null) }
    val snackbarState = remember { SnackbarState() }
    val tabs = listOf(
        TabItem("主页", Icons.Filled.Home),
        TabItem("Agent", Icons.Filled.Bolt),
        TabItem("工作区", Icons.Filled.Folder),
        TabItem("记忆", Icons.Filled.Memory),
        TabItem("设置", Icons.Filled.Settings),
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
    // 1) 二级页（测试/调试/文件列表/文档编辑）→ 关闭二级页回到当前 Tab；
    // 2) 非主页 Tab → 回到主页 Tab；
    // 3) 主页 → 保持系统默认行为（退出应用）
    BackHandler(enabled = extrasPage != null) {
        if (extrasPage is ExtrasPage.FileEditor) vm.workCloseEditor()
        extrasPage = null
    }
    BackHandler(enabled = extrasPage == null && selected != 0) {
        selected = 0
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // 全屏二级页时不显示底部导航
            if (extrasPage == null) {
                // 液态玻璃导航栏 - 色散折射 + 半透明层
                NavigationBar(
                    modifier = Modifier.liquidGlass(
                        alpha = 0.75f,
                        cornerRadius = 0.dp,
                    ),
                    containerColor = Color.Transparent,
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
                        onOpenEditor = { name ->
                            vm.workOpenEditor(name)
                            extrasPage = ExtrasPage.FileEditor(name)
                        },
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
                        0 -> HomeScreen(
                            vm, contentMod,
                            onRequestScreenshot = {
                                val mpm = activity.getSystemService(MediaProjectionManager::class.java)
                                screenshotLauncher.launch(mpm.createScreenCaptureIntent())
                            },
                            onNavigate = { selected = it },
                            onOpenExtras = { extrasPage = it },
                        )
                        1 -> AgentScreen(vm, contentMod)
                        2 -> WorkAreaScreen(
                            vm, contentMod,
                            onOpenFileList = { extrasPage = ExtrasPage.FileList },
                            onOpenEditor = { name ->
                                vm.workOpenEditor(name)
                                extrasPage = ExtrasPage.FileEditor(name)
                            },
                        )
                        3 -> MemoryGraphScreen(vm, contentMod)
                        4 -> SettingsScreen(vm, contentMod)
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
    onOpenEditor: (String) -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                when (page) {
                    is ExtrasPage.Test -> "测试"
                    is ExtrasPage.Debug -> "调试"
                    is ExtrasPage.FileList -> "全部文件"
                    is ExtrasPage.FileEditor -> page.fileName
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        when (page) {
            ExtrasPage.Test -> com.phoneagent.ui.test.TestScreen(
                vm,
                Modifier.weight(1f).padding(horizontal = 0.dp),
            )
            ExtrasPage.Debug -> DebugScreen(
                vm,
                Modifier.weight(1f).padding(horizontal = 0.dp),
            )
            ExtrasPage.FileList -> FileListScreen(
                vm,
                Modifier.weight(1f),
                onOpenEditor = onOpenEditor,
            )
            is ExtrasPage.FileEditor -> FileEditorScreen(
                vm,
                page.fileName,
                Modifier.weight(1f),
            )
        }
        // 底部大圆角返回主页按钮
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                if (page is ExtrasPage.FileEditor) vm.workCloseEditor()
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
            Icon(Icons.Filled.Home, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                "返回主页",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}