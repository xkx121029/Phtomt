package com.phoneagent.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.core.ai.Endpoint
import com.phoneagent.core.ai.ModelCatalogCodec
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalIsExpanded
import com.phoneagent.ui.components.contentColumn
import com.phoneagent.ui.theme.ScreenTransitions
import com.phoneagent.ui.theme.motionSettings
import kotlinx.coroutines.launch

// ========== 分层导航：设置主页 → 各分类详情页 ==========

// AD_SKIP 已并入 AGENT 页（内容只有一组开关，不值得独占一级）
// MODEL_CATALOG 是「云端模型」下的二级页：模型库动辄几十上百条，不能平铺在设置页上
enum class SettingsPage { HOME, THEME, AI_MODELS, MODEL_CATALOG, ONDEVICE, AGENT, VISUAL, LONG_RUN, PERMISSIONS, DATA, UPDATE, ABOUT, CODING, TABS }

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    /**
     * 打开二级工具页（调试 / 测试）。
     *
     * 这两个入口原先在首页与 Agent / 技能并排，属于开发工具挤占日常位置；
     * 现在收进「设置 → 关于」的开发者工具分区，由外层把页面切换交给 MainActivity。
     */
    onOpenExtras: (com.phoneagent.ui.ExtrasPage) -> Unit = {},
) {
    val settings by vm.settingsFlow.collectAsState()
    // 同上：改走 LocalActivity（可空），不再把 LocalContext 强转成 Activity
    val activity = androidx.activity.compose.LocalActivity.current

    val st = remember { SettingsState(settings) }
    // 宽屏双栏：左侧分类常驻，右侧直接翻页。
    // 手机上仍是「主页 → 二级页」的分层结构，行为与改动前一致。
    val twoPane = LocalIsExpanded.current
    // 双栏时左栏没有「主页」这一项，默认直接落在第一个分类上——
    // 一进来就是一块空白总览，等于让用户多点一次
    var page by rememberSaveable {
        mutableStateOf(if (twoPane) SettingsPage.AI_MODELS else SettingsPage.HOME)
    }
    // 正在挑选模型的职责（main / vision / reason）；null = 浮层收起
    var pickRole by remember { mutableStateOf<String?>(null) }

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

    // 根节点保持满屏 Box：子页的页内浮层（如拉取模型的勾选浮层）各自挂在自身满屏根节点上，
    // 这里作为统一容器，后续要压全屏的内容也挂得住
    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSettingsTwoPane provides twoPane) {
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    // 分类栏与右侧详情之间一道发丝线：两栏是同层内容，不是浮层关系
                    Box(
                        Modifier
                            .width(SettingsRailWidth)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        SettingsCatalogRail(
                            st = st,
                            vm = vm,
                            selected = page,
                            onSelect = { page = it },
                        )
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        // 详情列同样收口：平板横屏右侧有一千多 dp，输入框被拉成一条长带很难填
                        Box(Modifier.fillMaxHeight().contentColumn()) {
                            SettingsPageContent(
                                page = page,
                                st = st,
                                vm = vm,
                                pickRole = pickRole,
                                transitionMs = pageTransitionMs,
                                onPage = { page = it },
                                onPickRole = { pickRole = it },
                                saveNonAiSettings = ::saveNonAiSettings,
                                onOpenExtras = onOpenExtras,
                            )
                        }
                    }
                }
            } else {
                // 单栏：正文铺满，页面之间做自下而上的转场
                SettingsPageContent(
                    page = page,
                    st = st,
                    vm = vm,
                    pickRole = pickRole,
                    transitionMs = pageTransitionMs,
                    onPage = { page = it },
                    onPickRole = { pickRole = it },
                    saveNonAiSettings = ::saveNonAiSettings,
                    onOpenExtras = onOpenExtras,
                )
            }
        }
    }
}

/** 宽屏双栏时左侧分类栏的宽度：够放「图标 + 标题 + 摘要」，又不至于挤掉右侧正文 */
private val SettingsRailWidth = 264.dp

/**
 * 设置页内容区（转场 + 页面分发），单栏与双栏共用。
 *
 * 转场语言、页面路由只有这一处定义：分成两份写，新增一个设置页时必然只加到一边。
 */
@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    st: SettingsState,
    vm: MainViewModel,
    /** 正在挑选模型的职责（main / vision / reason）；非空时模型库页进入「选择」模式 */
    pickRole: String?,
    transitionMs: Int,
    onPage: (SettingsPage) -> Unit,
    onPickRole: (String?) -> Unit,
    saveNonAiSettings: () -> Unit,
    onOpenExtras: (com.phoneagent.ui.ExtrasPage) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        // 转场语言与底部 Tab 完全一致：只做自下而上的升起 + 淡入，不用水平位移。
        // 沿用 MainActivity 里定下的那条全局规矩——全项目只保留自下而上的入场，
        // 不再引入水平位移；层级感交给"内容换了"本身表达。
        transitionSpec = {
            val fade = tween<Float>(transitionMs, easing = ScreenTransitions.Easing)
            val slide = tween<IntOffset>(transitionMs, easing = ScreenTransitions.Easing)
            (fadeIn(fade) + slideInVertically(slide) { full -> full / 12 })
                .togetherWith(fadeOut(fade))
        },
        label = "settings-page",
        // 不再整块让位：页面铺满到屏幕底，净空由各子页在自己的滚动内容里垫出，
        // 这样滚动视口是满高的，内容能从悬浮导航栏下面穿过去
        modifier = Modifier.fillMaxSize(),
    ) { p ->
        val backToHome = { onPage(SettingsPage.HOME) }
        val openCatalog = {
            onPickRole(null)
            onPage(SettingsPage.MODEL_CATALOG)
        }
        // 职责换模型不进浮层：模型库本身已是二级页，直接跳过去选，选完回本页
        val pickRoleInCatalog = { role: String ->
            onPickRole(role)
            onPage(SettingsPage.MODEL_CATALOG)
        }
        when (p) {
            // 双栏时右侧不再重复列一遍入口（左侧分类栏已经是导航），只留就绪度与指引
            SettingsPage.HOME ->
                if (LocalSettingsTwoPane.current) {
                    SettingsOverviewPane(st, vm, onOpen = onPage)
                } else {
                    SettingsHome(st, vm, onOpen = onPage)
                }
            SettingsPage.THEME -> SettingsTheme(st, save = saveNonAiSettings, onBack = backToHome)
            SettingsPage.AI_MODELS -> SettingsAiModels(
                vm = vm, st = st,
                onBack = backToHome,
                onOpenCatalog = openCatalog,
                onPickRole = pickRoleInCatalog,
            )
            SettingsPage.MODEL_CATALOG -> SettingsModelCatalog(
                vm = vm,
                st = st,
                pickRole = pickRole,
                onBack = {
                    onPickRole(null)
                    onPage(SettingsPage.AI_MODELS)
                },
                onPicked = { endpointId, name ->
                    pickRole?.let { applyRoleModel(st, it, endpointId, name) }
                    onPickRole(null)
                    onPage(SettingsPage.AI_MODELS)
                },
            )
            SettingsPage.ONDEVICE -> SettingsOnDevice(st, save = saveNonAiSettings, onBack = backToHome)

            SettingsPage.AGENT -> SettingsAgent(st, save = saveNonAiSettings, onBack = backToHome)
            SettingsPage.VISUAL -> SettingsVisual(st, save = saveNonAiSettings, onBack = backToHome)
            SettingsPage.LONG_RUN -> SettingsLongRun(vm = vm, onBack = backToHome)
            SettingsPage.PERMISSIONS -> SettingsPermissions(vm = vm, st = st, save = saveNonAiSettings, onBack = backToHome)
            SettingsPage.DATA -> SettingsData(vm = vm, onBack = backToHome)
            SettingsPage.UPDATE -> SettingsUpdate(
                vm = vm, st = st,
                save = saveNonAiSettings,
                onBack = backToHome,
            )
            SettingsPage.ABOUT -> SettingsAbout(
                onBack = backToHome,
                onOpenDebug = { onOpenExtras(com.phoneagent.ui.ExtrasPage.Debug) },
                onOpenTest = { onOpenExtras(com.phoneagent.ui.ExtrasPage.Test) },
            )
            SettingsPage.CODING -> SettingsCoding(
                st = st,
                save = saveNonAiSettings,
                onBack = backToHome,
            )
            SettingsPage.TABS -> SettingsTabs(
                st = st,
                save = saveNonAiSettings,
                onBack = backToHome,
            )
        }
    }
}

// ========== 本地编辑状态 ==========

/** 设置页本地编辑状态：所有可编辑配置项 + 标定面板展开状态 */
class SettingsState(initial: AppSettings.Settings) {
    /**
     * 最近一次落库的设置快照。
     *
     * [toSettings] 以它为基底做 copy：表单只镜像了「可编辑项」，Settings 里还有其他
     * 字段（未来新增或只由别处写入的）。若逐字段重建对象，这些字段会在任一处保存时
     * 被悄悄打回默认值——这种丢失很难在测试里发现，所以基底必须跟着 applyFrom 更新。
     */
    private var base = initial

    var baseUrl by mutableStateOf(initial.apiBaseUrl)
    var apiKey by mutableStateOf(initial.apiKey)
    var model by mutableStateOf(initial.model)
    /** 主模型识图三态：AUTO/ON/OFF，判定口径见 com.phoneagent.core.ai.VisionRouting */
    var mainVisionMode by mutableStateOf(initial.mainVisionMode)
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
    // 语音输入：OpenAI 兼容 /audio/transcriptions；留空回退系统识别
    var asrBaseUrl by mutableStateOf(initial.asrBaseUrl)
    var asrModel by mutableStateOf(initial.asrModel)
    var asrApiKey by mutableStateOf(initial.asrApiKey)
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
    /** 页面记忆：自动记忆页面热点与路径，屏幕标记 + AI 参考 */
    var pageMemoryEnabled by mutableStateOf(initial.pageMemoryEnabled)
    /** 副屏模式：任务在模拟辅助显示上运行，不抢用户前台 */
    var virtualDisplayEnabled by mutableStateOf(initial.virtualDisplayEnabled)
    /** 主题：无框线模式。开启后卡片 / 面板 / 输入面的装饰性描边一律不画 */
    var borderless by mutableStateOf(initial.borderless)
    /** 主题：配色方案（ThemePalette.id） */
    var palette by mutableStateOf(initial.palette)
    /** 主题：磨砂浓淡微调（-25~25，0 = 色板默认），负更透正更实 */
    var glassTintOffset by mutableIntStateOf(initial.glassTintOffset)
    // 模型库：端点 + 模型条目（列表用 SnapshotStateList，编辑后 Compose 才能感知）
    val endpoints = mutableStateListOf<Endpoint>().apply { addAll(initial.endpoints) }
    val catalog = mutableStateListOf<CatalogModel>().apply { addAll(initial.catalog) }
    var skipVisionDescWhenMainSees by mutableStateOf(initial.skipVisionDescWhenMainSees)
    // 自动更新：服务地址 / 接收频道 / 启动时自动检查
    var updateBaseUrl by mutableStateOf(initial.updateBaseUrl)
    var updateChannel by mutableStateOf(initial.updateChannel)
    var autoCheckUpdate by mutableStateOf(initial.autoCheckUpdate)
    // 对外控制接口：Tasker 等广播下发任务的开关与口令
    var externalControlEnabled by mutableStateOf(initial.externalControlEnabled)
    var externalControlToken by mutableStateOf(initial.externalControlToken)
    var calibrationExpanded by mutableStateOf(false)
    // 编码 agent 审批档位（ask/risky/bypass，Superagent 融合 Phase 6）
    var codingApprovalMode by mutableStateOf(initial.codingApprovalMode)
    // 底部 Tab 顺序与可见性（Tab id 列表，Superagent 融合 Phase 6）
    var tabOrder by mutableStateOf(initial.tabOrder)
    var tabVisible by mutableStateOf(initial.tabVisible)

    fun applyFrom(s: AppSettings.Settings) {
        base = s
        baseUrl = s.apiBaseUrl
        apiKey = s.apiKey
        model = s.model
        mainVisionMode = s.mainVisionMode
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
        asrBaseUrl = s.asrBaseUrl
        asrModel = s.asrModel
        asrApiKey = s.asrApiKey
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
        pageMemoryEnabled = s.pageMemoryEnabled
        virtualDisplayEnabled = s.virtualDisplayEnabled
        borderless = s.borderless
        palette = s.palette
        glassTintOffset = s.glassTintOffset
        // 列表不能整体替换，否则 Compose 感知不到元素级变化
        endpoints.clear()
        endpoints.addAll(s.endpoints)
        catalog.clear()
        catalog.addAll(s.catalog)
        skipVisionDescWhenMainSees = s.skipVisionDescWhenMainSees
        updateBaseUrl = s.updateBaseUrl
        updateChannel = s.updateChannel
        autoCheckUpdate = s.autoCheckUpdate
        externalControlEnabled = s.externalControlEnabled
        externalControlToken = s.externalControlToken
        // 审批档位与 Tab 配置同回填：漏了的话任一设置页保存一次就会把它们打回默认值
        codingApprovalMode = s.codingApprovalMode
        tabOrder = s.tabOrder
        tabVisible = s.tabVisible
    }

    /**
     * 端点落库前归一化：id 由地址推导、地址为空的行丢弃、同址去重
     * —— 编辑期允许"地址还没填"的临时行，但它不该被写进设置。
     */
    private fun normalizedEndpoints(): List<Endpoint> = endpoints
        .mapNotNull { ep ->
            val id = ModelCatalogCodec.endpointId(ep.baseUrl)
            if (id.isBlank()) null
            else Endpoint(id = id, baseUrl = ep.baseUrl.trim(), apiKey = ep.apiKey.trim(), protocol = ep.protocol)
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

    fun toSettings() = base.copy(
        apiBaseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        mainVisionMode = mainVisionMode,
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
        asrBaseUrl = asrBaseUrl,
        asrModel = asrModel,
        asrApiKey = asrApiKey,
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
        pageMemoryEnabled = pageMemoryEnabled,
        virtualDisplayEnabled = virtualDisplayEnabled,
        borderless = borderless,
        palette = palette,
        glassTintOffset = glassTintOffset,
        endpoints = normalizedEndpoints(),
        catalog = normalizedCatalog(),
        skipVisionDescWhenMainSees = skipVisionDescWhenMainSees,
        updateBaseUrl = updateBaseUrl,
        updateChannel = updateChannel,
        autoCheckUpdate = autoCheckUpdate,
        externalControlEnabled = externalControlEnabled,
        externalControlToken = externalControlToken.trim(),
        codingApprovalMode = codingApprovalMode,
        tabOrder = tabOrder,
        tabVisible = tabVisible,
    )
}