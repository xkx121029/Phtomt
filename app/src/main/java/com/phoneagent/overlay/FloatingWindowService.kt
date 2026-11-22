// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.phoneagent.BuildConfig
import com.phoneagent.R
import com.phoneagent.core.window.OverlayWindowSpec
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.TaskOutcome
import com.phoneagent.domain.model.TaskStatus
import com.phoneagent.engine.runtime.TaskNotice
import com.phoneagent.engine.runtime.TaskNoticeCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.phoneagent.ui.MainActivity

/**
 * 悬浮窗服务：任务执行时在屏幕上显示实时进度面板，支持用户在窗内直接交互。
 *
 * 窗口结构（两个独立窗口，职责不重叠）：
 * - **底部跑马灯**：浮在屏幕底边之上（留 [marqueeBottomGap] 净空）的圆角胶囊，任务期间常驻，
 *   实时显示 AI 意向/任务状态；长宽随文字自适应，文字超出一屏才滚动。
 * - **任务卡片**：300dp 宽、可拖动的小卡片，承载标题/步骤/详情/交互，高度随内容自适应。
 *
 * 视觉：任务卡片用玄青 85% 不透明度的半透明底（[FloatingUi.BRAND_GLASS]）+ 发丝描边；
 *       跑马灯仍是不透明实色（它只显示文字，半透明会让文字随时失去对比度）。
 *
 *       卡片**刻意不开** `blurBehindRadius`（曾开过，是 bug 的来源）：它糊的是"窗口背后的
 *       整块屏幕"，而不是卡片那 300dp 见方——框架文档写得很明确：*Blur behind blurs the
 *       whole screen behind the window*，只有 `Window#setBackgroundBlurRadius` 才是"只糊
 *       窗口自身范围"。真机上表现为任务一跑整屏发灰，卡片只是这块灰里颜色更深的一小块。
 *       而悬浮窗是用 WindowManager 直接加 View 的，没有 `Window` 对象，
 *       拿不到 `setBackgroundBlurRadius`——"只糊卡片"没有公开 API 可走，索性不糊。
 * 尺寸：卡片宽度固定，高度 WRAP_CONTENT——默认只占"头部 + 状态行"两行；
 *       AI 详情默认折叠，用户点开才占位，避免长任务时窗口越撑越大。
 * 交互：等待批准（批准/取消）、歧义澄清（选项按钮）、需要指导（输入框+按钮），
 *       这些操作全部可在悬浮窗内完成，通过 [onInteraction] 静态回调转发到引擎。
 * 拖动：仅卡片头部区域可拖动；轻点头部展开/收起 AI 详情，拖动后靠近屏幕左右边缘会自动贴边。
 */
class FloatingWindowService : Service() {

    internal var windowManager: WindowManager? = null
    internal var root: LinearLayout? = null
    internal var params: WindowManager.LayoutParams? = null
    /** 上一次收到的屏幕方向：只有方向真正翻面时才做窗口重排（深浅色切换不该触发） */
    internal var lastOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val appSettings: AppSettings by inject()
    // 跑马灯内边距（dp，决定面板厚度）与底色配置，从设置读取并随设置实时更新
    internal var marqueeHeightDp = 8
    /** 跟随状态变色：底色由当前阶段决定，不用用户自定义配色 */
    internal var marqueeAutoColor = true
    internal var marqueeColors = listOf(0xFF4FA3FF.toInt(), 0xFF9B5CFF.toInt(), 0xFFFF6B9D.toInt())
    /** 当前执行阶段：跟随状态变色时用它决定跑马灯底色 */
    internal var currentPhase = "PENDING"

    internal var dot: View? = null
    internal var dotPulseAnimator: ValueAnimator? = null
    /** 底部胶囊距底边的最小净空（dp）：约 0.5cm，手势条更高时以手势条为准 */
    internal val marqueeBottomGap = 19

    /** 胶囊外扩柔光的留白（px）：窗口比胶囊四周各宽/高这么多，光晕才不会被窗口边界裁掉 */
    internal val marqueeGlowPad: Int
        get() = (MarqueeView.GLOW_PAD_DP * resources.displayMetrics.density).toInt()

    /** 底部胶囊窗口两侧留白（dp）：窗口宽度 = 真实屏宽 - 2 * 此值，建窗时定死 */
    internal val marqueeSideMargin = 16


    /**
     * 底部跑马灯（胶囊）：固定宽度窗口，浮在导航栏之上。
     */
    internal var marquee: MarqueeView? = null

    internal var stepText: TextView? = null
    internal var progressBar: ProgressBar? = null
    internal var taskTitle: TextView? = null

    /** 最近一次任务名（来自 updateStatus）：销毁时供状态总线做 Widget 空闲摘要 */
    internal var currentTask: String? = null
    internal var phaseChip: TextView? = null   // 阶段徽章（观察/思考/执行…）
    /** 状态行：阶段徽章 + 步骤文字 + 详情/隐藏。完成态整行让位给打勾面板 */
    internal var statusRow: LinearLayout? = null

    /** 头部「详情/收起」按钮：控制 AI 详情区是否占位 */
    internal var expandChip: TextView? = null

    /**
     * AI 详情是否展开。
     *
     * 详情区（发送/返回/审核）最高 112dp，常驻会把窗口撑成一块"屏幕补丁"，
     * 遮挡后面的内容；默认折叠，只保留头部 + 状态行两行。
     * 该状态在任务内保持（用户展开后，后续步骤不会被自动收起），任务结束才复位。
     */
    internal var thinkingExpanded = false

    // AI 思考实时面板：发送给 AI 的内容 + 流式返回的内容
    internal var thinkingPanel: LinearLayout? = null
    internal var thinkingScroll: ScrollView? = null
    internal var thinkingSentText: TextView? = null
    internal var thinkingReturnText: TextView? = null
    internal var reviewText: TextView? = null
    internal var lastSentShown = ""

    /**
     * 流式返回区的累积缓冲。
     *
     * 原实现每个增量都做 `(cur + delta).takeLast(3000)`：每来一个 token 就要整体复制一次
     * 3000 字的字符串（O(n²) 的总拷贝量）。改为 StringBuilder 追加 + 超限截头，
     * 只有真正写回 TextView 时才 toString 一次。
     */
    internal val thinkingBuffer = StringBuilder(THINKING_DISPLAY_MAX)

    /** 上次推送 AI 思考通知的时间：流式输出每秒数次，逐条 notify 是跨进程调用，必须节流 */
    internal var lastThinkNotifyAt = 0L

    // 头部（可拖动区域）
    internal var header: LinearLayout? = null

    // ==================== 任务完成卡片（独立底部窗口） ====================
    // 原实现是在顶部任务卡里换一块 donePanel；卡片只有 300dp 宽，长结果没地方放，
    // 也没有「复制 / 完成 / 打开」这类下一步动作。现在改为底部独立窗口：
    // 层级 = 拖拽把手 → 状态行（圆点 + 标题）→ 可滚动正文 → 一行三键。
    internal var doneRoot: LinearLayout? = null
    internal var doneCard: LinearLayout? = null
    internal var doneTitle: TextView? = null
    internal var doneStatusDot: TextView? = null
    internal var doneBody: TextView? = null
    internal var doneScroll: MaxHeightScrollView? = null
    internal var doneFade: DoneFadeView? = null
    internal var doneCopy: TextView? = null
    /** 卡片当前展示的完整结果文本（「复制」写剪贴板用） */
    internal var doneMessage = ""
    /** 元信息行（耗时 / 步数 / 执行通道）：引擎没给的信息直接省略该行 */
    internal var doneMeta: TextView? = null
    /** 失败原因行：只在非成功且有原因时出现 */
    internal var doneReason: TextView? = null
    /** 等待交接的终态：胶囊停留期间先存着，交接时再一次性落到卡片上 */
    internal var pendingDoneOutcome: TaskOutcome? = null
    /**
     * 胶囊 → 完成卡片的交接。
     *
     * 顺序是刻意的：胶囊先切终态色停 [DONE_HOLD_MS]，再和任务卡一起淡出，
     * 同一拍卡片淡入。不做位移、不做弹性、不做缩放——这一步是"让位"，不是新一次入场。
     */
    internal val doneHandoff = Runnable {
        if (pendingDoneOutcome == null) return@Runnable
        pendingDoneOutcome = null
        fadeOutAndHide(root, OverlayMotion.ExitMs)
        fadeOutMarquee(OverlayMotion.ExitMs)
        // 首帧耗时从交接开始算：停留的 800ms 是刻意等待，不该混进性能口径
        revealDoneCard(SystemClock.uptimeMillis())
    }
    /** 用户正在按住/滚动/拖拽卡片：期间暂停 8s 自动关闭，松手后重新计时 */
    internal var doneInteracting = false
    /** 下滑关闭：按下时的 rawY 与已累计的下移量（只允许向下） */
    internal var doneDragStartRawY = 0f
    internal var doneDragOffset = 0f
    /** 截图隐藏前完成卡片是否可见，用于截图后原样恢复 */
    internal var doneVisibleBeforeHide = false

    /**
     * 引擎已请求停止服务，但完成卡片还没走完自己的生命周期。
     *
     * 非成功结局（已中断/失败）里，引擎收尾会立刻收窗，而卡片通常还停在
     * 「胶囊停留 → 交接」这一段，导致用户读不到失败原因。此时把停止时机
     * 交给卡片自己的关闭/超时（[closeDoneCard] 末尾即 `stopSelf`）。
     */
    internal var stopPending = false
    internal val doneAutoClose = Runnable { closeDoneCard() }

    /**
     * 仅 debug 包：用 adb 广播直接拉起完成卡片，供 UI 走查与首帧耗时量化。
     *
     * `adb shell am broadcast -a com.phoneagent.DEBUG_DONE --es message "..." -p com.phoneagent`
     * 真实任务链路不会触发它，release 包也不会注册。
     */
    internal var debugDoneReceiver: BroadcastReceiver? = null

    // 交互区域：批准/澄清/指导
    internal var interactPanel: LinearLayout? = null
    internal var interactTitle: TextView? = null
    internal var interactContent: TextView? = null
    /** 选项行与出口行的容器（按类型重建，见 [showInteraction]） */
    internal var interactRows: LinearLayout? = null
    internal var hintInput: EditText? = null
    /** 输入区的出口行容器（「已手动处理 / 指导 AI」） */
    internal var interactActions: LinearLayout? = null

    /**
     * 答疑面板是否被用户收起。
     *
     * 收起是"让出屏幕"而不是关掉问题：AI 仍在等这个答案，所以收起后必须留一个入口
     * （[collapsedPill]）能点回来，否则任务会永久挂住。
     * 收起期间容器里只剩那枚小胶囊，窗口高度随之压到胶囊高 ——
     * 屏幕其余位置的触摸照常落到底下的 App 上。
     */
    internal var interactCollapsed = false

    /** 收起后留在右下角的入口胶囊：「需要你回答 · 点开」 */
    internal var collapsedPill: TextView? = null

    /**
     * 当前面板配色（浅色暖纸白 / 深色近黑），跟随系统深浅色。
     *
     * 悬浮窗的视图是一次性建出来的（[buildPanel] 只跑一次），系统切换深浅色时不能重建整扇窗口
     * ——那会丢掉正在跑的任务状态。所以用「登记式重上色」：建视图时顺手登记一段
     * 「拿到配色后怎么涂」的代码，翻面时把所有登记项重跑一遍（见 [themed]）。
     */
    internal var palette = FloatingUi.Palette.Light
    internal val themeAppliers = mutableListOf<(FloatingUi.Palette) -> Unit>()

    /** 最近一次答疑的入参：翻面时要用同样的入参重绘选项行/出口行（它们是每次交互现建的） */
    internal var lastInteractType: String? = null
    internal var lastInteractTitle: String? = null
    internal var lastInteractContent: String? = null
    internal var lastInteractOptions: List<String>? = null

    /**
     * 底部选项卡：AI 需要答疑（澄清歧义）或协助（动作未生效/敏感页保护）时，
     * 从屏幕底边独立拉起一个面板承载选项与输入，而不是挤在顶部悬浮窗里。
     */
    internal var sheetRoot: LinearLayout? = null
    internal var sheetParams: WindowManager.LayoutParams? = null

    /** 截图隐藏前底部选项卡是否可见，用于截图后原样恢复 */
    internal var sheetVisibleBeforeHide = false

    /**
     * 底部跑马灯的窗口参数（窗口根视图就是 [marquee]）。
     *
     * 跑马灯刻意不放进任务卡片：卡片只有 300dp 宽、还能被用户拖走，挂在里面会跟着一起被拖走；
     * 拆成独立窗口浮在屏幕底边之上，才能做到"任务期间常驻 + 长宽随内容自适应"。
     */
    internal var barParams: WindowManager.LayoutParams? = null

    /** 截图隐藏前跑马灯是否可见，用于截图后原样恢复 */
    internal var barVisibleBeforeHide = false

    /**
     * 逐步任务文字的显示位置（跑马灯 / 任务卡状态行 / 两处），用户可在设置里改。
     *
     * 改动即时生效：监听偏好写入后立即按最近一次状态就地重绘，不等下一个任务。
     */
    internal var stepPlacement = OverlayDisplaySettings.DEFAULT_STEP_TEXT_PLACEMENT

    /** 最近一次状态更新的原始入参：切换显示位置时就地重绘，不用等下一次 updateStatus */
    internal var lastStatusText = "等待任务..."
    internal var lastReasoningText = ""
    internal var lastStep = 0
    internal var lastTotal = 0

    /** 跑马灯最近一次"想不想显示"的请求；实际可见性还要再过一道 [stepPlacement] 闸门 */
    internal var marqueeRequestedVisible = true

    /** 显示设置监听：只关心文字位置这一个键（边缘光效由 EdgeLightingService 自己监听） */
    internal val displaySettingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OverlayDisplaySettings.KEY_STEP_TEXT_PLACEMENT) {
                stepPlacement = OverlayDisplaySettings.stepTextPlacement(this)
                handler.post { applyStepTextPlacement() }
            }
        }

    // ==================== 降级提示细条（引擎 → 浮层） ====================
    // 引擎已经产出成品中文（TaskNoticeCenter.notice），浮层只负责展示：
    // 不新增常驻栏，做成底部一次性细条，显示几秒后自己向上淡出。
    // 位置在底部跑马灯胶囊之上（见 [noticeBottomOffset]），宽度与胶囊同宽、建窗时定死。
    internal var noticeRoot: LinearLayout? = null
    internal var noticeParams: WindowManager.LayoutParams? = null
    internal var noticeText: TextView? = null
    internal var noticeMethod: TextView? = null
    internal var noticeBadge: TextView? = null
    /** 当前细条语义（true = 需要用户动手）：主题翻面重上色时按它决定白底还是实色 */
    internal var noticeRequiresAction = false
    /** 细条是否处于"已展示/计时中"状态；退场动画期间置 false，避免重复收场 */
    internal var noticeShowing = false
    /** 截图隐藏期间为 true：此间到达的提示只更新内容，不把窗口弹出来打断抓屏 */
    internal var captureHidden = false
    internal val noticeAutoHide = Runnable { hideNotice() }

    /** 截图隐藏前提示细条是否可见，用于截图后原样恢复 */
    internal var noticeVisibleBeforeHide = false

    /**
     * 用户是否主动收起了整套面板（点卡片上的「隐藏」）。
     *
     * 收起后屏幕上只留一个悬浮球，任务同时被搁置（挂起由引擎负责）；点悬浮球唤出后原样恢复。
     * 这个标志必须挡住后续所有"把面板显示出来"的路径（尤其是每步都会跑的 [resetPanel]），
     * 否则下一步状态一刷新，面板就又自己冒出来了。
     */
    internal var userHidden = false

    /** 悬浮球窗口：面板收起后留在屏幕上唯一可点的入口 */
    internal var miniRoot: TextView? = null
    internal var miniParams: WindowManager.LayoutParams? = null

    /** 截图隐藏前悬浮球是否可见，用于截图后原样恢复 */
    internal var miniVisibleBeforeHide = false


    internal val handler = Handler(Looper.getMainLooper())
    internal var notificationManager: NotificationManager? = null
    internal val channelId = "floating_window"

    // ==================== 拖动物理效果 ====================
    internal var velocityTracker: VelocityTracker? = null
    internal var flingScroller: OverScroller? = null
    internal var isFlinging = false
    internal val frameCallback = Choreographer.FrameCallback { onFlingFrame() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 起始配色取当下的系统深浅色；之后由 onConfigurationChanged 跟随翻面
        palette = FloatingUi.Palette.of(FloatingUi.isNightMode(this))
        startForegroundCompat()
        registerDebugDoneReceiver()
        // 逐步任务文字的显示位置：启动时读一次，运行中由偏好监听即时改写
        stepPlacement = OverlayDisplaySettings.stepTextPlacement(this)
        OverlayDisplaySettings.registerListener(this, displaySettingsListener)
        // 监听跑马灯设置：内边距/配色方式修改后即时生效
        scope.launch {
            appSettings.settings.collect { s ->
                marqueeHeightDp = s.marqueeHeight
                marqueeAutoColor = s.marqueeAutoColor
                marqueeColors = s.marqueeColors.map { it.toInt() }
                applyMarqueeSettings()
            }
        }
        // 引擎降级提示：只读 notice，展示由浮层决定（text 原样、method 作方式标签）
        scope.launch {
            TaskNoticeCenter.notice.collect { notice -> showNotice(notice) }
        }
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.getBooleanExtra("stop", false) == true) {
            removeWindow()
            stopSelf()
            return START_NOT_STICKY
        }
        showWindow()
        // START_STICKY 被系统重建（intent 为 null）时：上一任务的真实状态已随进程丢失，
        // 卡片不能继续摆出"任务进行中"的样子误导用户 —— 复位为空闲文案，等引擎重新推送
        if (intent == null) {
            resetToIdle()
        }
        return START_STICKY
    }

    /** 任务卡片复位为空闲文案（sticky 重建路径）：标题/阶段/步骤/思考区/跑马灯/通知全部回到待命态 */
    internal fun resetToIdle() {
        handler.post {
            taskTitle?.text = "Happy Agent"
            phaseChip?.text = "待命"
            phaseChip?.background = FloatingUi.capsule(this@FloatingWindowService, 
                FloatingUi.RADIUS_CHIP / 2,
                FloatingUi.phaseColor("PENDING"),
            )
            dot?.setBackgroundColor(dotColor("PENDING"))
            stopDotPulse()
            thinkingSentText?.text = ""
            clearThinkingReturn()
            lastSentShown = ""
            lastStatusText = "等待任务..."
            lastReasoningText = ""
            lastStep = 0
            lastTotal = 0
            currentPhase = "PENDING"
            applyMarqueeSettings()
            applyStepTextPlacement()
            updateNotification("等待任务...", "Happy Agent")
        }
    }








    // ==================== 拖动（仅头部）：点击展开 / 拖动移位 / 松手归位 ====================
    internal var startX = 0
    internal var startY = 0
    internal var startTouchX = 0f
    internal var startTouchY = 0f

    /** 本次手势是否已越过触摸阈值（用于区分"轻点"与"拖动"） */
    internal var dragging = false

    internal val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }




    /**
     * 登记一个「跟随主题重上色」的视图：登记时立刻按当前配色涂一遍，
     * 之后系统深浅色翻面时会被再跑一遍（见 [onConfigurationChanged]）。
     */
    internal fun themed(apply: (FloatingUi.Palette) -> Unit) {
        themeAppliers += apply
        apply(palette)
    }

    /**
     * 系统配置变化：深浅色翻面时把所有登记过的静态视图重涂一遍，再重绘答疑里的动态行；
     * 屏幕方向翻面时把所有悬浮窗口按新几何重排（见 [relayoutOverlays]）。
     *
     * 只处理登记过的那一层（内层卡片与卡片上的文字/主色）；玄青任务卡与跑马灯是品牌/阶段表面，
     * 深浅两套下都成立，不参与切换。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转重排：post 到主线程队列再跑。此刻窗口布局还没走完，直接读 view.height 是旧值，
        // currentWindowMetrics / statusBarHeight 也要等系统下发新几何之后读才准
        if (newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation
            handler.post { relayoutOverlays() }
        }
        val dark = FloatingUi.isNightMode(this)
        if (dark == palette.isDark) return
        palette = FloatingUi.Palette.of(dark)
        themeAppliers.forEach { it(palette) }
        rerenderInteractRows()
    }













    override fun onDestroy() {
        OverlayDisplaySettings.unregisterListener(this, displaySettingsListener)
        debugDoneReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugDoneReceiver = null
        // 清理拖动物理动画与回调
        isFlinging = false
        flingScroller?.forceFinished(true)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        removeWindow()
        scope.cancel()
        if (instance === this) {
            instance = null
            // 悬浮窗退场即复位状态总线：桌面 Widget 跟着回到空闲态（保留最后任务名做摘要）
            AgentStatusStore.idle(currentTask)
        }
        super.onDestroy()
    }

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.phoneagent.floating.STOP"
        internal const val NOTIFY_ID = 1001

        /** AI 思考写通知的最小间隔：流式增量每秒数次，逐条 notify 是跨进程调用 */
        internal const val NOTIFY_THROTTLE_MS = 700L

        /** 流式返回区最多展示的字符数（保留尾部最新内容） */
        internal const val THINKING_DISPLAY_MAX = 3000

        /** 关闭按钮两段式确认：第一段显示的待确认文案 */
        internal const val CLOSE_CONFIRM_LABEL = "再点确认停止"

        /** 关闭按钮待确认态自动复位的时长（毫秒） */
        internal const val CLOSE_CONFIRM_RESET_MS = 2500L

        /** 悬浮球直径（dp）：小到不挡操作，大到手指点得到 */
        internal const val MINI_BALL_SIZE = 44

        /** 底部胶囊取不到高度时（首帧）的经验值（dp）：提示细条据此让位，不会压在胶囊上 */
        internal const val NOTICE_MARQUEE_FALLBACK_HEIGHT_DP = 36

        /** 完成卡片正文最大高度（dp）：超过则在卡片内部滚动，卡片本身不长出屏幕 */
        internal const val DONE_BODY_MAX_HEIGHT = 220

        /** 完成卡片下滑关闭阈值（dp）：与 Eta 的 96dp 对齐 */
        internal const val DONE_SWIPE_CLOSE_DP = 96

        /** 完成卡片静止自动关闭时长（毫秒）：按压/滚动/拖拽期间暂停计时 */
        internal const val DONE_AUTO_CLOSE_MS = 8000L

        /**
         * 收尾时胶囊停留在终态色的时长（毫秒）。
         *
         * 700–900ms 是"看得见但不拖沓"的窗口：低于 500ms 会被卡片入场直接盖掉，
         * 高于 1s 会让用户以为卡住了。**实测截图若临时加长，事后必须调回这个值。**
         */
        internal const val DONE_HOLD_MS = 800L

        /** 「复制」按钮显示"已复制"的保持时长（毫秒）：状态保持，不属于动效 */
        internal const val COPY_FEEDBACK_MS = 1400L

        /** 调试期量化埋点 tag（仅 debug 包输出，release 不打印） */
        /** debug 预览完成卡片的广播 action（见 [registerDebugDoneReceiver]） */
        internal const val DEBUG_DONE_ACTION = "com.phoneagent.DEBUG_DONE"

        /** 悬浮窗交互动作回调（由 MainViewModel 注册，转发到 AgentEngine） */
        @Volatile
        var onInteraction: ((action: String, payload: String) -> Unit)? = null

        @Volatile
        var instance: FloatingWindowService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingWindowService::class.java))
        }

        fun stop(context: Context) {
            // 引擎收尾（AgentEngine.safeResetRuntime）在任务终态时立刻要求收窗。若完成卡片
            // 刚交接上屏或还在等交接，直接 stopService 会在用户读到失败原因前把窗口摘掉
            // （实测非成功结局存活不足 1.1s）。这里只把"停止"延后，不拒绝：
            // 卡片关闭/超时后由 closeDoneCard 里的 stopSelf 收尾。
            val svc = instance
            if (svc == null) {
                runCatching { context.stopService(Intent(context, FloatingWindowService::class.java)) }
                return
            }
            // 引擎的收尾跑在 Dispatchers.Default 上，而卡片状态只在主线程读写：
            // 把停止决策投回主线程，避免跨线程读 doneRoot / 写 stopPending
            svc.handler.post {
                if (svc.deferStopForDoneCard()) return@post
                // 直接 stopService：后台调用不抛异常，onDestroy 中会 removeWindow 清理悬浮窗
                runCatching { context.stopService(Intent(context, FloatingWindowService::class.java)) }
            }
        }

        /** 任务收尾：终态由引擎显式给出（成功/失败/中断/未达成），总线与卡片共用同一份结果 */
        fun showDone(outcome: TaskOutcome) {
            // 完成态先落状态总线（Widget 用），再走悬浮窗的收尾交接；
            // 悬浮窗关闭时（instance 为空）总线也要拿到完成事件
            AgentStatusStore.update(
                AgentStatusStore.Snapshot(
                    task = instance?.currentTask ?: "任务",
                    status = "${outcome.status.label}：${outcome.summary}",
                    reasoning = outcome.summary,
                    // 成功的终态是 DONE；失败/中断/未达成都不能让 Widget 显示"完成"
                    phase = if (outcome.status == TaskStatus.SUCCESS) "DONE" else "ERROR",
                    done = true,
                ),
            )
            instance?.showDone(outcome)
        }

        /** 显示交互面板（批准/澄清/指导） */
        fun interaction(type: String?, title: String?, content: String?, options: List<String>? = null) {
            instance?.showInteraction(type, title, content, options)
        }

        fun update(
            status: String,
            task: String,
            reasoning: String,
            step: Int,
            total: Int,
            phase: String,
        ) {
            // 状态总线随每次引擎推送同步（Widget 数据源）——无论悬浮窗是否开着，引擎都会走到这里
            AgentStatusStore.update(
                AgentStatusStore.Snapshot(
                    task = task,
                    status = status,
                    reasoning = reasoning,
                    step = step,
                    phase = phase,
                ),
            )
            instance?.updateStatus(status, task, reasoning, step, total, phase)
        }

        /** 实时更新 AI 思考（发送/返回内容）到悬浮窗并同步通知 */
        fun updateThinking(sent: String? = null, delta: String? = null) {
            instance?.updateThinking(sent, delta)
        }

        /** 清空思考显示区（流式重试前调用，避免重放内容重复累积） */
        fun resetThinking() {
            instance?.resetThinking()
        }

        /** 审核结果推送到悬浮窗思考面板展示 */
        fun updateReviewText(text: String?) {
            instance?.updateReview(text)
        }

        /**
         * 截图时隐藏悬浮窗 / 截图后恢复，避免悬浮窗出现在 AI 读屏画面中。
         *
         * 返回「本次调用确实隐藏了内容」：调用方据此决定是否需要恢复，避免对「没藏东西」的
         * 调用做无谓的恢复动作。实现在 [setOverlayVisible]。
         */
        fun setVisible(visible: Boolean): Boolean = setOverlayVisible(visible)

    }
}
