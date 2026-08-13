package com.phoneagent.ui

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.agent.AgentScreen
import com.phoneagent.ui.components.liquidGlass
import com.phoneagent.ui.debug.DebugScreen
import com.phoneagent.ui.home.HomeScreen
import com.phoneagent.ui.settings.SettingsScreen
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.PhoneAgentTheme
import com.phoneagent.ui.theme.motionSettings
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

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
private fun ActivityContent(vm: MainViewModel) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf(
        TabItem("主页", Icons.Filled.Home),
        TabItem("Agent", Icons.Filled.Bolt),
        TabItem("测试", Icons.Filled.Science),
        TabItem("设置", Icons.Filled.Settings),
        TabItem("调试", Icons.Filled.Terminal),
    )
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val motion = motionSettings()
    val transitionDuration = motion.scaledDuration(DurationSlow)

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

    Scaffold(
        bottomBar = {
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
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)

        AnimatedContent(
            targetState = selected,
            transitionSpec = transitionSpec,
            label = "screen-switch",
        ) { screenIndex ->
            when (screenIndex) {
                0 -> HomeScreen(
                    vm, mod,
                    onRequestScreenshot = {
                        val mpm = activity.getSystemService(MediaProjectionManager::class.java)
                        screenshotLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    onNavigate = { selected = it },
                )
                1 -> AgentScreen(vm, mod)
                2 -> com.phoneagent.ui.test.TestScreen(vm, mod)
                3 -> SettingsScreen(vm, mod)
                4 -> DebugScreen(vm, mod)
            }
        }
    }
}