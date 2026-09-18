package com.phoneagent.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.ui.MainViewModel
import kotlinx.coroutines.launch

// ========== 分层导航：设置主页 → 各分类详情页 ==========

enum class SettingsPage { HOME, AI_MODELS, AGENT, AD_SKIP, VISUAL, LONG_RUN }

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settingsFlow.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    val st = remember { SettingsState(settings) }
    var page by remember { mutableStateOf(SettingsPage.HOME) }

    // 监听标定面板展开状态，启动/停止全屏预览
    LaunchedEffect(st.calibrationExpanded, st.edgeLightingEnabled) {
        if (st.calibrationExpanded && st.edgeLightingEnabled && activity != null) {
            EdgeLightingService.startPreview(
                context = activity,
                top = st.edgeInsetTop,
                bottom = st.edgeInsetBottom,
                left = st.edgeInsetLeft,
                right = st.edgeInsetRight,
                radius = st.cornerRadius,
                width = st.edgeLightingWidth,
            )
        } else if (activity != null) {
            EdgeLightingService.stopPreview(activity)
        }
    }

    // 实时更新标定参数到预览服务
    LaunchedEffect(st.edgeInsetTop, st.edgeInsetBottom, st.edgeInsetLeft, st.edgeInsetRight, st.cornerRadius, st.edgeLightingWidth) {
        if (st.calibrationExpanded && st.edgeLightingEnabled && activity != null) {
            EdgeLightingService.updateCalibration(
                context = activity,
                top = st.edgeInsetTop,
                bottom = st.edgeInsetBottom,
                left = st.edgeInsetLeft,
                right = st.edgeInsetRight,
                radius = st.cornerRadius,
                width = st.edgeLightingWidth,
            )
        }
    }

    // 设置持久化后同步到本地表单
    LaunchedEffect(settings) { st.applyFrom(settings) }

    val scope = rememberCoroutineScope()
    fun saveNonAiSettings() {
        scope.launch { vm.saveSettings(st.toSettings()) }
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "settings-page",
        modifier = modifier,
    ) { p ->
        when (p) {
            SettingsPage.HOME -> SettingsHome(st, onOpen = { page = it })
            SettingsPage.AI_MODELS -> SettingsAiModels(vm = vm, st = st, onBack = { page = SettingsPage.HOME })
            SettingsPage.AGENT -> SettingsAgent(st, save = ::saveNonAiSettings, onBack = { page = SettingsPage.HOME })
            SettingsPage.AD_SKIP -> SettingsAdSkip(st, save = ::saveNonAiSettings, onBack = { page = SettingsPage.HOME })
            SettingsPage.VISUAL -> SettingsVisual(st, save = ::saveNonAiSettings, onBack = { page = SettingsPage.HOME })
            SettingsPage.LONG_RUN -> SettingsLongRun(vm = vm, onBack = { page = SettingsPage.HOME })
        }
    }
}

// ========== 本地编辑状态 ==========

/** 设置页本地编辑状态：所有可编辑配置项 + 标定面板展开状态 */
class SettingsState(initial: AppSettings.Settings) {
    var baseUrl by mutableStateOf(initial.apiBaseUrl)
    var apiKey by mutableStateOf(initial.apiKey)
    var model by mutableStateOf(initial.model)
    var hasVision by mutableStateOf(initial.hasVision)
    var maxSteps by mutableIntStateOf(initial.maxSteps)
    var attachScreenshot by mutableStateOf(initial.attachScreenshot)
    var promptLanguage by mutableStateOf(initial.promptLanguage)
    var visionBaseUrl by mutableStateOf(initial.visionBaseUrl)
    var visionModel by mutableStateOf(initial.visionModel)
    var visionApiKey by mutableStateOf(initial.visionApiKey)
    var reasonBaseUrl by mutableStateOf(initial.reasonBaseUrl)
    var reasonModel by mutableStateOf(initial.reasonModel)
    var reasonApiKey by mutableStateOf(initial.reasonApiKey)
    var mainThinking by mutableStateOf(initial.mainThinking)
    var enableReview by mutableStateOf(initial.enableReview)
    var visionEnabled by mutableStateOf(initial.visionEnabled)
    var visionMode by mutableStateOf(initial.visionMode)
    var enableExternalVision by mutableStateOf(initial.enableExternalVision)
    var smartVisionRoute by mutableStateOf(initial.smartVisionRoute)
    var enableChain by mutableStateOf(initial.enableChain)
    var chainOrder by mutableStateOf(initial.chainOrder)
    var edgeInsetTop by mutableIntStateOf(initial.edgeInsetTop)
    var edgeInsetBottom by mutableIntStateOf(initial.edgeInsetBottom)
    var edgeInsetLeft by mutableIntStateOf(initial.edgeInsetLeft)
    var edgeInsetRight by mutableIntStateOf(initial.edgeInsetRight)
    var cornerRadius by mutableIntStateOf(initial.cornerRadius)
    var edgeLightingWidth by mutableIntStateOf(initial.edgeLightingWidth)
    var edgeLightingEnabled by mutableStateOf(initial.edgeLightingEnabled)
    var autoSkipAds by mutableStateOf(initial.autoSkipAds)
    var floatingWindowEnabled by mutableStateOf(initial.floatingWindowEnabled)
    var executionChannel by mutableStateOf(initial.executionChannel)
    var marqueeHeight by mutableIntStateOf(initial.marqueeHeight)
    var marqueeColors by mutableStateOf(initial.marqueeColors)
    var calibrationExpanded by mutableStateOf(false)

    fun applyFrom(s: AppSettings.Settings) {
        baseUrl = s.apiBaseUrl
        apiKey = s.apiKey
        model = s.model
        hasVision = s.hasVision
        maxSteps = s.maxSteps
        attachScreenshot = s.attachScreenshot
        promptLanguage = s.promptLanguage
        visionBaseUrl = s.visionBaseUrl
        visionModel = s.visionModel
        visionApiKey = s.visionApiKey
        reasonBaseUrl = s.reasonBaseUrl
        reasonModel = s.reasonModel
        reasonApiKey = s.reasonApiKey
        mainThinking = s.mainThinking
        enableReview = s.enableReview
        visionEnabled = s.visionEnabled
        visionMode = s.visionMode
        enableChain = s.enableChain
        chainOrder = s.chainOrder
        edgeInsetTop = s.edgeInsetTop
        edgeInsetBottom = s.edgeInsetBottom
        edgeInsetLeft = s.edgeInsetLeft
        edgeInsetRight = s.edgeInsetRight
        cornerRadius = s.cornerRadius
        edgeLightingWidth = s.edgeLightingWidth
        edgeLightingEnabled = s.edgeLightingEnabled
        autoSkipAds = s.autoSkipAds
        floatingWindowEnabled = s.floatingWindowEnabled
        executionChannel = s.executionChannel
        marqueeHeight = s.marqueeHeight
        marqueeColors = s.marqueeColors
    }

    fun toSettings() = AppSettings.Settings(
        apiBaseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        hasVision = hasVision,
        maxSteps = maxSteps,
        attachScreenshot = attachScreenshot,
        promptLanguage = promptLanguage,
        visionBaseUrl = visionBaseUrl,
        visionModel = visionModel,
        visionApiKey = visionApiKey,
        reasonBaseUrl = reasonBaseUrl,
        reasonModel = reasonModel,
        reasonApiKey = reasonApiKey,
        mainThinking = mainThinking,
        enableReview = enableReview,
        visionEnabled = visionEnabled,
        visionMode = visionMode,
        enableExternalVision = enableExternalVision,
        smartVisionRoute = smartVisionRoute,
        enableChain = enableChain,
        chainOrder = chainOrder,
        edgeInsetTop = edgeInsetTop,
        edgeInsetBottom = edgeInsetBottom,
        edgeInsetLeft = edgeInsetLeft,
        edgeInsetRight = edgeInsetRight,
        cornerRadius = cornerRadius,
        edgeLightingWidth = edgeLightingWidth,
        edgeLightingEnabled = edgeLightingEnabled,
        autoSkipAds = autoSkipAds,
        floatingWindowEnabled = floatingWindowEnabled,
        executionChannel = executionChannel,
        marqueeHeight = marqueeHeight,
        marqueeColors = marqueeColors,
    )
}