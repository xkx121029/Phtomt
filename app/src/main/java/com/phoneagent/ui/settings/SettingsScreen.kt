package com.phoneagent.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.core.ai.Endpoint
import com.phoneagent.core.ai.ModelCatalogCodec
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.theme.ScreenTransitions
import com.phoneagent.ui.theme.motionSettings
import kotlinx.coroutines.launch

// ========== 分层导航：设置主页 → 各分类详情页 ==========

// AD_SKIP 已并入 AGENT 页（内容只有一组开关，不值得独占一级）
enum class SettingsPage { HOME, AI_MODELS, AGENT, VISUAL, LONG_RUN, PERMISSIONS, DATA, ABOUT }

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

    // 转场走 Motion.kt 的时长/缓动，并尊重系统「减少动画」设置
    val pageTransitionMs = motionSettings().scaledDuration(ScreenTransitions.Duration)

    AnimatedContent(
        targetState = page,
        // 用层级转场（淡入 + 轻微位移）替代纯 fade：
        // 进二级页时内容从右侧推入、返回时退回，方向感与"进/出层级"一致
        transitionSpec = {
            val fade = tween<Float>(pageTransitionMs, easing = ScreenTransitions.Easing)
            val slide = tween<IntOffset>(pageTransitionMs, easing = ScreenTransitions.Easing)
            if (targetState == SettingsPage.HOME) {
                (fadeIn(fade) + slideInHorizontally(slide) { -it / 12 })
                    .togetherWith(fadeOut(fade))
            } else {
                (fadeIn(fade) + slideInHorizontally(slide) { it / 12 })
                    .togetherWith(fadeOut(fade))
            }
        },
        label = "settings-page",
        // 不再整块让位：页面铺满到屏幕底，净空由各子页在自己的滚动内容里垫出，
        // 这样滚动视口是满高的，内容能从悬浮导航栏下面穿过去
        modifier = modifier,
    ) { p ->
        when (p) {
            SettingsPage.HOME -> SettingsHome(st, vm, onOpen = { page = it })
            SettingsPage.AI_MODELS -> SettingsAiModels(vm = vm, st = st, onBack = { page = SettingsPage.HOME })
            SettingsPage.AGENT -> SettingsAgent(st, save = ::saveNonAiSettings, onBack = { page = SettingsPage.HOME })
            SettingsPage.VISUAL -> SettingsVisual(st, save = ::saveNonAiSettings, onBack = { page = SettingsPage.HOME })
            SettingsPage.LONG_RUN -> SettingsLongRun(vm = vm, onBack = { page = SettingsPage.HOME })
            SettingsPage.PERMISSIONS -> SettingsPermissions(vm = vm, onBack = { page = SettingsPage.HOME })
            SettingsPage.DATA -> SettingsData(vm = vm, onBack = { page = SettingsPage.HOME })
            SettingsPage.ABOUT -> SettingsAbout(onBack = { page = SettingsPage.HOME })
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
    /** 自定义系统提示词：留空=用内置提示词；非空=整体替换内置系统提示词 */
    var systemPrompt by mutableStateOf(initial.systemPrompt)
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

    var floatingWindowEnabled by mutableStateOf(initial.floatingWindowEnabled)
    var executionChannel by mutableStateOf(initial.executionChannel)
    var actionMode by mutableStateOf(initial.actionMode)
    var marqueeHeight by mutableIntStateOf(initial.marqueeHeight)
    var marqueeAutoColor by mutableStateOf(initial.marqueeAutoColor)
    var marqueeColors by mutableStateOf(initial.marqueeColors)
    var cursorOverlayEnabled by mutableStateOf(initial.cursorOverlayEnabled)
    var cursorClickSync by mutableStateOf(initial.cursorClickSync)
    // 模型库：端点 + 模型条目（列表用 SnapshotStateList，编辑后 Compose 才能感知）
    val endpoints = mutableStateListOf<Endpoint>().apply { addAll(initial.endpoints) }
    val catalog = mutableStateListOf<CatalogModel>().apply { addAll(initial.catalog) }
    var skipVisionDescWhenMainSees by mutableStateOf(initial.skipVisionDescWhenMainSees)
    var calibrationExpanded by mutableStateOf(false)

    fun applyFrom(s: AppSettings.Settings) {
        baseUrl = s.apiBaseUrl
        apiKey = s.apiKey
        model = s.model
        hasVision = s.hasVision
        maxSteps = s.maxSteps
        attachScreenshot = s.attachScreenshot
        promptLanguage = s.promptLanguage
        systemPrompt = s.systemPrompt
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
        // 这两项 toSettings() 里有、applyFrom 原先漏了，导致保存后开关编辑态不回填
        enableExternalVision = s.enableExternalVision
        smartVisionRoute = s.smartVisionRoute
        enableChain = s.enableChain
        chainOrder = s.chainOrder
        edgeInsetTop = s.edgeInsetTop
        edgeInsetBottom = s.edgeInsetBottom
        edgeInsetLeft = s.edgeInsetLeft
        edgeInsetRight = s.edgeInsetRight
        cornerRadius = s.cornerRadius
        edgeLightingWidth = s.edgeLightingWidth
        edgeLightingEnabled = s.edgeLightingEnabled
        floatingWindowEnabled = s.floatingWindowEnabled
        executionChannel = s.executionChannel
        actionMode = s.actionMode
        marqueeHeight = s.marqueeHeight
        marqueeAutoColor = s.marqueeAutoColor
        marqueeColors = s.marqueeColors
        cursorOverlayEnabled = s.cursorOverlayEnabled
        cursorClickSync = s.cursorClickSync
        // 列表不能整体替换，否则 Compose 感知不到元素级变化
        endpoints.clear()
        endpoints.addAll(s.endpoints)
        catalog.clear()
        catalog.addAll(s.catalog)
        skipVisionDescWhenMainSees = s.skipVisionDescWhenMainSees
    }

    /**
     * 端点落库前归一化：id 由地址推导、地址为空的行丢弃、同址去重
     * —— 编辑期允许"地址还没填"的临时行，但它不该被写进设置。
     */
    private fun normalizedEndpoints(): List<Endpoint> = endpoints
        .mapNotNull { ep ->
            val id = ModelCatalogCodec.endpointId(ep.baseUrl)
            if (id.isBlank()) null
            else Endpoint(id = id, baseUrl = ep.baseUrl.trim(), apiKey = ep.apiKey.trim())
        }
        .distinctBy { it.id }

    /** 模型条目只留下指向现存端点的；端点地址改写后条目已随行迁移，这里再兜一次保证无孤儿 */
    private fun normalizedCatalog(): List<CatalogModel> {
        val ids = normalizedEndpoints().map { it.id }.toSet()
        return catalog
            .map { it.copy(endpointId = ModelCatalogCodec.endpointId(it.endpointId), name = it.name.trim()) }
            .filter { it.endpointId in ids && it.name.isNotBlank() }
            .distinctBy { it.endpointId to it.name }
    }

    fun toSettings() = AppSettings.Settings(
        apiBaseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        hasVision = hasVision,
        maxSteps = maxSteps,
        attachScreenshot = attachScreenshot,
        promptLanguage = promptLanguage,
        systemPrompt = systemPrompt,
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
        floatingWindowEnabled = floatingWindowEnabled,
        executionChannel = executionChannel,
        actionMode = actionMode,
        marqueeHeight = marqueeHeight,
        marqueeAutoColor = marqueeAutoColor,
        marqueeColors = marqueeColors,
        cursorOverlayEnabled = cursorOverlayEnabled,
        cursorClickSync = cursorClickSync,
        endpoints = normalizedEndpoints(),
        catalog = normalizedCatalog(),
        skipVisionDescWhenMainSees = skipVisionDescWhenMainSees,
    )
}