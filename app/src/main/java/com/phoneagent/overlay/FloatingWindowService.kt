package com.phoneagent.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ServiceCompat
import com.phoneagent.R
import com.phoneagent.data.prefs.AppSettings
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
 * 视觉：白色液态玻璃（半透明白 + 折射高光 + 边缘色散 + 圆角 + 细边框）。
 * 尺寸：宽度固定，高度随内容自适应（WRAP_CONTENT），随步骤/提问内容实时变化。
 * 交互：等待批准（批准/取消）、歧义澄清（选项按钮）、需要指导（输入框+按钮）、
 *       这些操作全部可在悬浮窗内完成，通过 [onInteraction] 静态回调转发到引擎。
 * 拖动：仅头部区域可拖动，带液态物理反馈（拿起放大 + 速度倾斜 + 松手弹性回弹 + 惯性滑行）。
 */
class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var glassBg: LiquidGlassDrawable? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appSettings: AppSettings by inject()
    // 跑马灯厚度（dp）与渐变颜色（ARGB 列表），从设置读取并随设置实时更新
    private var marqueeHeightDp = 26
    private var marqueeColors = listOf(0xFF4FA3FF.toInt(), 0xFF9B5CFF.toInt(), 0xFFFF6B9D.toInt())

    private var dot: View? = null
    private var dotPulseAnimator: ValueAnimator? = null
    private var marquee: MarqueeView? = null
    private var stepText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var taskTitle: TextView? = null
    private var phaseChip: TextView? = null   // 阶段徽章（观察/思考/执行…）

    // AI 思考实时面板：发送给 AI 的内容 + 流式返回的内容
    private var thinkingPanel: LinearLayout? = null
    private var thinkingScroll: ScrollView? = null
    private var thinkingSentText: TextView? = null
    private var thinkingReturnText: TextView? = null
    private var reviewText: TextView? = null
    private var lastSentShown = ""

    // 头部（可拖动区域）
    private var header: LinearLayout? = null

    // 完成动效：打勾视图 + 完成文字
    private var successMark: SuccessMarkView? = null
    private var doneText: TextView? = null
    private var donePanel: LinearLayout? = null

    // 交互区域：批准/澄清/指导
    private var interactPanel: LinearLayout? = null
    private var interactTitle: TextView? = null
    private var interactContent: TextView? = null
    private var interactButtons: LinearLayout? = null
    private var hintInput: EditText? = null
    private var hintBtnRow: LinearLayout? = null

    /**
     * 底部选项卡：AI 需要答疑（澄清歧义）或协助（动作未生效/敏感页保护）时，
     * 从屏幕底边独立拉起一个面板承载选项与输入，而不是挤在顶部悬浮窗里。
     */
    private var sheetRoot: LinearLayout? = null
    private var sheetParams: WindowManager.LayoutParams? = null

    /** 截图隐藏前底部选项卡是否可见，用于截图后原样恢复 */
    private var sheetVisibleBeforeHide = false

    private val handler = Handler(Looper.getMainLooper())
    private var notificationManager: NotificationManager? = null
    private val channelId = "floating_window"

    // ==================== 拖动物理效果 ====================
    private var velocityTracker: VelocityTracker? = null
    private var flingScroller: OverScroller? = null
    private var isFlinging = false
    private val frameCallback = Choreographer.FrameCallback { onFlingFrame() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
        // 监听跑马灯设置：厚度/颜色修改后即时生效
        scope.launch {
            appSettings.settings.collect { s ->
                marqueeHeightDp = s.marqueeHeight
                marqueeColors = s.marqueeColors.map { it.toInt() }
                marquee?.let { m ->
                    m.layoutParams = m.layoutParams.apply { height = dp(marqueeHeightDp) + statusBarHeight() }
                    m.setColors(marqueeColors)
                    m.setTopInset(statusBarHeight())
                    m.requestLayout()
                }
            }
        }
    }

    private fun startForegroundCompat() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(channelId, "悬浮窗进度", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Happy Agent 正在执行")
                .setContentText("实时跟踪任务进度")
                .setContentIntent(pi)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Happy Agent 正在执行").setSmallIcon(R.drawable.ic_stat_agent).build()
        }
        ServiceCompat.startForeground(
            this,
            NOTIFY_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /** 实时更新前台通知，显示任务状态（可选附加 AI 思考内容） */
    private fun updateNotification(status: String, task: String, step: Int = 0, thinking: String? = null) {
        val nm = notificationManager ?: return
        val base = if (step > 0) "第 $step 步 · $status" else status
        val text = if (!thinking.isNullOrBlank()) "$base\nAI：${thinking.replace("\n", " ").take(140)}" else base
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Happy Agent · $task")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Happy Agent · $task").setContentText(text).setSmallIcon(R.drawable.ic_stat_agent).build()
        }
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.getBooleanExtra("stop", false) == true) {
            removeWindow()
            stopSelf()
            return START_NOT_STICKY
        }
        showWindow()
        return START_STICKY
    }

    /**
     * 状态栏高度（像素）。
     *
     * 该值直接决定悬浮窗的初始 y（`y = -statusBarHeight()`），也就是跑马灯色带能不能贴到屏幕物理顶边。
     * 因此不能用「查系统资源名」这一种方式：`getIdentifier("status_bar_height", ...)` 在相当一部分
     * ROM / 高版本系统上取不到，返回 0 —— 于是 y 变成 0，色带就落在状态栏下方。
     *
     * 取值顺序：WindowInsets（API 30+，最可靠）→ 系统资源名 → 经验兜底值。
     */
    private fun statusBarHeight(): Int {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // 同时算上刘海/挖孔：有 cutout 的机型上，屏幕物理顶到可视内容之间的实际距离
            // 比状态栏更高，只取 statusBars 会偏小，色带就差那么一截贴不到顶
            val inset = runCatching {
                windowManager?.currentWindowMetrics?.windowInsets
                    ?.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.displayCutout(),
                    )
                    ?.top
            }.getOrNull()
            if (inset != null && inset > 0) return inset
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            val h = resources.getDimensionPixelSize(id)
            if (h > 0) return h
        }
        // 兜底：宁可多覆盖一点，也不能返回 0 —— 返回 0 会让色带整条掉到状态栏下方
        return dp(24)
    }

    private fun showWindow() {
        if (root != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layout = buildPanel()
        // 获取当前屏幕尺寸，根据横竖屏调整初始位置，避免旋转后悬浮窗位置出屏
        val point = android.graphics.Point()
        runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
        val screenW = if (point.x > 0) point.x else dp(360)
        val screenH = if (point.y > 0) point.y else dp(640)
        // 宽度固定，高度随内容自适应
        params = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 初始位置：水平居中、贴屏幕上边缘，跑马灯覆盖状态栏区域。
            x = (screenW - dp(300)) / 2
            // y 取负状态栏高度：窗口顶在物理屏顶之上，跑马灯色带从屏幕物理顶开始，
            // 覆盖状态栏区域（状态栏透明/半透明时色带透出，图标浮于其上）
            y = -statusBarHeight()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // 关键：API 30+ 默认 fitInsetsTypes = systemBars()，会把窗口内容整体推到状态栏下方，
                // 这是与 FLAG_LAYOUT_NO_LIMITS 无关的另一套机制（inset 适配），
                // 所以只靠负 y 未必贴得到顶。清空后窗口坐标系才真正从物理屏顶开始。
                fitInsetsTypes = 0
            }
        }
        root = layout
        // 真实投影：让玻璃浮起在屏幕之上，elevation 阴影随圆角轮廓（M3 柔和浮起）
        layout.elevation = dp(18).toFloat()
        layout.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(FloatingUi.RADIUS_CARD.toInt()).toFloat())
            }
        }
        try {
            windowManager?.addView(layout, params)
        } catch (_: Exception) {}
        showSheetWindow()
    }

    /**
     * 创建底部选项卡窗口（常驻、初始不可见）。
     *
     * 与顶部窗口相反，这里**不清空 fitInsetsTypes**：默认避开系统栏，
     * gravity=BOTTOM 时面板正好落在导航栏上方，不会被导航栏压住。
     * 窗口可触摸、可聚焦（需要点选项与输入文字），但 GONE 时不会干扰任何操作。
     */
    private fun showSheetWindow() {
        if (sheetRoot != null) return
        val wm = windowManager ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            // 左右留白，让面板呈卡片状而不是铺满整屏
            setPadding(dp(FloatingUi.PAD_XL), 0, dp(FloatingUi.PAD_XL), dp(FloatingUi.PAD))
            visibility = View.GONE
        }
        // 交互面板在此挂载（它已在 buildPanel 中构建完成，但未加入顶部窗口）
        interactPanel?.let { container.addView(it) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // 输入框获得焦点时让窗口随软键盘上移，避免面板被键盘盖住
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            wm.addView(container, lp)
            sheetRoot = container
            sheetParams = lp
        } catch (_: Exception) {
            // 无悬浮窗权限时静默失败，任务照常执行
        }
    }

    /** 从底边滑入选项卡 */
    private fun showSheet() {
        val container = sheetRoot ?: return
        if (container.visibility == View.VISIBLE) return
        container.visibility = View.VISIBLE
        container.post {
            val h = container.height.toFloat()
            container.translationY = h
            container.animate()
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f))
                .start()
        }
    }

    /** 滑出并隐藏选项卡 */
    private fun hideSheet() {
        val container = sheetRoot ?: return
        if (container.visibility != View.VISIBLE) return
        container.animate()
            .translationY(container.height.toFloat())
            .setDuration(200)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                container.visibility = View.GONE
                container.translationY = 0f
            }
            .start()
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        // 液态玻璃背景：M3 柔和玻璃（半透明白 + 顶部高光 + 柔和光斑 + 内侧描边 + 细边框）。
        // 说明：不用系统 blurBehind（部分设备会将整个屏幕背景模糊）——用较高透明度的半透明白
        // 配合克制的光晕/色散/描边模拟玻璃，兼顾质感与不干扰后台。
        val bg = LiquidGlassDrawable(
            cornerRadius = dp(FloatingUi.RADIUS_CARD.toInt()).toFloat(),
            baseColor = FloatingUi.BASE,
            strokeColor = FloatingUi.BASE_STROKE,
        )
        panel.background = bg
        glassBg = bg
        panel.setPadding(FloatingUi.PAD_L, 0, FloatingUi.PAD_L, FloatingUi.PAD_S)

        // 跑马灯（第一行，紧贴窗口/屏幕顶部边缘，作为顶部状态色带）
        marquee = MarqueeView(this).apply {
            // 高度 = 状态栏覆盖 + 用户可见厚度：窗口顶在物理屏顶之上，色带必然覆盖状态栏到顶
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(marqueeHeightDp) + statusBarHeight())
            // 渐变颜色可在设置中调节（修改后实时生效）
            setColors(marqueeColors)
            // 文字要避开状态栏：色带从屏幕顶铺下来，但文字画在状态栏下方
            setTopInset(statusBarHeight())
        }
        panel.addView(marquee)

        // 头部：状态点 + 任务标题 + 关闭（仅头部可拖动）
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(FloatingUi.PAD_S, FloatingUi.PAD, FloatingUi.PAD_S, FloatingUi.PAD_S)
            setOnTouchListener { _, event ->
                onTouchDrag(event)
                true
            }
        }
        // 状态呼吸点：阶段色 + 呼吸脉冲，让任务状态“有生命”
        dot = View(this).apply {
            setBackgroundResource(0)
            val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(FloatingUi.phaseColor("PENDING")) }
            background = d
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
        }
        // 任务标题
        taskTitle = TextView(this).apply {
            text = "Happy Agent"
            textSize = 15f
            setTextColor(FloatingUi.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(FloatingUi.PAD, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 关闭按钮：圆形胶囊，按压有反馈
        val close = TextView(this).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(FloatingUi.TEXT_SECONDARY)
            background = FloatingUi.capsule(FloatingUi.RADIUS_CHIP, 0x0F000000.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            setOnClickListener {
                onInteraction?.invoke("close", "")
                stopSelf(); removeWindow()
            }
        }
        header?.addView(dot)
        header?.addView(taskTitle)
        header?.addView(close)
        panel.addView(header)

        // 状态行：阶段徽章 + 步骤/状态文字
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(FloatingUi.PAD, 0, FloatingUi.PAD, FloatingUi.PAD_S)
        }
        phaseChip = TextView(this).apply {
            text = "待命"
            textSize = 10f
            setTextColor(FloatingUi.ACCENT_BLUE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = FloatingUi.capsule(
                FloatingUi.RADIUS_CHIP / 2,
                (FloatingUi.ACCENT_BLUE and 0x00FFFFFF) or 0x14000000,
            )
            setPadding(FloatingUi.PAD, dp(3), FloatingUi.PAD, dp(3))
        }
        stepText = TextView(this).apply {
            text = "等待任务..."
            textSize = 12f
            setTextColor(FloatingUi.TEXT_SECONDARY)
            setPadding(FloatingUi.PAD, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(phaseChip)
        statusRow.addView(stepText)
        panel.addView(statusRow)
        // 进度条：用户反馈无用，始终保持隐藏（不占悬浮窗空间）
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
        }
        panel.addView(progressBar)

        // AI 思考面板：圆角内嵌卡片，按 发送/返回/审核 分栏（默认隐藏，AI 开始思考时显示）
        thinkingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = FloatingUi.capsule(
                FloatingUi.RADIUS_PANEL,
                FloatingUi.PANEL,
            )
            setPadding(FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L)
        }
        // 面板标题行：AI 徽章 + 动画指示点
        val thinkHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val aiBadge = TextView(this).apply {
            text = "AI"
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = FloatingUi.capsule(999f, 0xFF7C4DFF.toInt())
            setPadding(FloatingUi.PAD, dp(3), FloatingUi.PAD, dp(3))
        }
        val thinkingLabel = TextView(this).apply {
            text = "  思考中"
            textSize = 12f
            setTextColor(FloatingUi.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        thinkHeaderRow.addView(aiBadge)
        thinkHeaderRow.addView(thinkingLabel)
        thinkingPanel?.addView(thinkHeaderRow)
        // 内容可滚动，限制高度避免悬浮窗过大
        thinkingScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            setPadding(0, FloatingUi.PAD, 0, 0)
            clipToPadding = false
            clipToOutline = false
        }
        val thinkCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sentLabel = TextView(this).apply {
            text = "SENT"
            textSize = 9f
            setTextColor(FloatingUi.TEXT_TERTIARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        thinkCol.addView(sentLabel)
        thinkingSentText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(FloatingUi.TEXT_SECONDARY)
            setPadding(0, dp(3), 0, dp(2))
        }
        thinkCol.addView(thinkingSentText)
        val retLabel = TextView(this).apply {
            text = "RESPONSE"
            textSize = 9f
            setTextColor(FloatingUi.TEXT_TERTIARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        thinkCol.addView(retLabel)
        thinkingReturnText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(FloatingUi.TEXT_PRIMARY)
            setPadding(0, dp(3), 0, dp(2))
        }
        thinkCol.addView(thinkingReturnText)
        val reviewLabel = TextView(this).apply {
            text = "REVIEW"
            textSize = 9f
            setTextColor(FloatingUi.ACCENT_PURPLE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        thinkCol.addView(reviewLabel)
        reviewText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(FloatingUi.ACCENT_PURPLE)
            setPadding(0, dp(3), 0, dp(4))
        }
        thinkCol.addView(reviewText)
        thinkingScroll?.addView(thinkCol)
        thinkingPanel?.addView(thinkingScroll)
        panel.addView(thinkingPanel)

        // 交互区域（批准/澄清/指导，默认隐藏）：圆角内嵌卡
        interactPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = FloatingUi.capsule(
                FloatingUi.RADIUS_PANEL,
                FloatingUi.PANEL,
            )
            setPadding(FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L)
        }
        // 帮助徽章 + 标题
        val interactHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val helpBadge = TextView(this).apply {
            text = "助手"
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = FloatingUi.capsule(999f, 0xFFF59E0B.toInt())
            setPadding(FloatingUi.PAD, dp(3), FloatingUi.PAD, dp(3))
        }
        interactTitle = TextView(this).apply {
            text = "需要确认"
            textSize = 13f
            setTextColor(FloatingUi.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(FloatingUi.PAD, 0, 0, 0)
        }
        interactHeader.addView(helpBadge)
        interactHeader.addView(interactTitle)
        interactPanel?.addView(interactHeader)
        // 内容可滚动（长文本）
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(0), 1f)
            isVerticalScrollBarEnabled = false
        }
        interactContent = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(FloatingUi.TEXT_PRIMARY)
            setPadding(0, dp(8), 0, dp(6))
        }
        contentScroll.addView(interactContent)
        interactPanel?.addView(contentScroll)
        // 选项按钮容器
        interactButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, FloatingUi.PAD, 0, 0)
        }
        interactPanel?.addView(interactButtons)
        // 指导输入框
        hintInput = EditText(this).apply {
            textSize = 12f
            setTextColor(FloatingUi.TEXT_PRIMARY)
            setHintTextColor(FloatingUi.TEXT_TERTIARY)
            setHint("告诉 AI 该怎么做（或留空）")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 3
            setBackgroundResource(0)
            background = FloatingUi.capsule(
                FloatingUi.RADIUS_INPUT,
                0xFFF2F3F7.toInt(),
            )
            setPadding(FloatingUi.PAD_L, dp(10), FloatingUi.PAD_L, dp(10))
        }
        interactPanel?.addView(hintInput)
        hintBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        interactPanel?.addView(hintBtnRow)
        // 交互面板不再挂进顶部窗口：它由底部选项卡承载（见 showSheetWindow），
        // 顶部只保留任务状态与跑马灯，两边职责不重叠

        // 完成面板：打勾动效 + 完成文字（默认隐藏，任务完成后显示）
        donePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = FloatingUi.capsule(FloatingUi.RADIUS_PANEL, 0x0A00A877.toInt())
            setPadding(0, dp(16), 0, dp(16))
        }
        successMark = SuccessMarkView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        donePanel?.addView(successMark)
        doneText = TextView(this).apply {
            text = "任务完成"
            textSize = 14f
            setTextColor(FloatingUi.phaseColor("DONE"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        donePanel?.addView(doneText)
        // 完成后删除按钮：胶囊实心主色
        val doneClose = Button(this).apply {
            text = "移除悬浮窗"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                topMargin = dp(12)
            }
            layoutParams = lp
            background = FloatingUi.capsule(999f, FloatingUi.ACCENT_BLUE)
            setPadding(FloatingUi.PAD_XL, 0, FloatingUi.PAD_XL, 0)
            setOnClickListener { stopSelf(); removeWindow() }
        }
        donePanel?.addView(doneClose)
        panel.addView(donePanel)

        return panel
    }

    // ==================== 拖动（仅头部）：液态物理效果 ====================
    private var startX = 0
    private var startY = 0
    private var startTouchX = 0f
    private var startTouchY = 0f

    private fun onTouchDrag(event: MotionEvent): Boolean {
        val p = params ?: return false
        val vtracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        vtracker.addMovement(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 取消进行中的惯性滑动
                isFlinging = false
                flingScroller?.forceFinished(true)
                startX = p.x; startY = p.y
                startTouchX = event.rawX; startTouchY = event.rawY
                vtracker.clear()
                vtracker.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startTouchX
                val dy = event.rawY - startTouchY
                p.x = (startX + dx).toInt()
                p.y = (startY + dy).toInt()
                // 拖动期间悬浮窗可能被销毁（removeWindow），root 为空时跳过更新，避免 NPE
                root?.let { r ->
                    // 只更新窗口位置，不再对窗口施加缩放/旋转变形，
                    // 避免旋转+缩放导致视觉中心偏移而在拖动中“乱晃”
                    windowManager?.updateViewLayout(r, p)
                    // 动态光斑：玻璃反光跟随手指位置，随手势流动，模拟真实玻璃质感
                    if (r.width > 0 && r.height > 0) {
                        val gx = ((event.rawX - p.x) / r.width).coerceIn(0f, 1f)
                        val gy = ((event.rawY - p.y) / r.height).coerceIn(0f, 1f)
                        glassBg?.setGlint(gx, gy)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vtracker.computeCurrentVelocity(1000)
                val vx = vtracker.xVelocity
                val vy = vtracker.yVelocity
                velocityTracker?.recycle()
                velocityTracker = null
                // 恢复初始状态（不再有缩放/倾斜变形，直接归位即可）
                glassBg?.setGlint(null, null)
                root?.apply {
                    scaleX = 1f
                    scaleY = 1f
                    rotation = 0f
                }
                // 惯性滑行：松手速度足够时沿当前方向自然滑行
                if (kotlin.math.abs(vx) + kotlin.math.abs(vy) > 900f) {
                    startFling(p.x, p.y, vx, vy)
                }
            }
        }
        return true
    }

    /** 松手弹性回弹：光斑已由 setGlint(null) 复位，这里补一个轻微缩放过冲模拟液态回弹 */
    private fun startSettle() {
        root?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(180)
            ?.setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
            ?.start()
    }

    /** 惯性滑行：用 OverScroller 沿松手速度衰减滑动，平滑停止 */
    private fun startFling(startX: Int, startY: Int, vx: Float, vy: Float) {
        val point = android.graphics.Point()
        runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
        val screenW = if (point.x > 0) point.x else dp(360)
        val screenH = if (point.y > 0) point.y else dp(640)
        val winW = dp(300)
        flingScroller?.forceFinished(true)
        flingScroller = OverScroller(this).apply {
            fling(
                startX, startY,
                vx.toInt(), vy.toInt(),
                -winW + 40, screenW - 40,      // x：允许大部分滑出屏幕但保留一角便于抓回
                0, screenH - 120,               // y：不允许飞出顶部，底部保留可抓取区域
            )
        }
        isFlinging = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFlingFrame() {
        if (!isFlinging) return
        val scroller = flingScroller ?: return
        val p = params ?: return
        val r = root ?: return
        if (scroller.computeScrollOffset()) {
            if (p.x != scroller.currX || p.y != scroller.currY) {
                p.x = scroller.currX
                p.y = scroller.currY
                windowManager?.updateViewLayout(r, p)
            }
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            isFlinging = false
        }
    }

    // ==================== 对外更新 ====================

    /**
     * 更新悬浮窗进度状态。
     * @param status 状态文本
     * @param task 任务名称
     * @param reasoning AI 推理/思考内容
     * @param step 当前步骤
     * @param total 总步骤
     * @param phase 阶段（OBSERVING/THINKING/ACTING/DONE/ERROR）
     */
    fun updateStatus(
        status: String,
        task: String,
        reasoning: String,
        step: Int,
        total: Int,
        phase: String,
    ) {
        handler.post {
            // 新任务开始时自动恢复常规面板（清除上一个任务的完成态残留）
            resetPanel()
            marquee?.setText(reasoning.ifBlank { status }, marqueeColor(phase))
            taskTitle?.text = task
            stepText?.text = "第 $step 步 · $status"
            // 阶段徽章：文字 + 阶段色
            val ph = FloatingUi.phaseColor(phase)
            phaseChip?.text = phaseLabel(phase)
            phaseChip?.setTextColor(ph)
            phaseChip?.background = FloatingUi.capsule(
                FloatingUi.RADIUS_CHIP / 2,
                (ph and 0x00FFFFFF) or 0x17000000,
            )
            // 进度条已隐藏（用户反馈无用），仅显示步骤文字
            dot?.setBackgroundColor(dotColor(phase))
            startDotPulse()
            // 实时更新通知
            updateNotification(status, task, step)
        }
    }

    /** 阶段枚举 → 友好中文标签 */
    private fun phaseLabel(phase: String): String = when (phase) {
        "OBSERVING" -> "观察中"
        "THINKING" -> "思考中"
        "ACTING" -> "执行中"
        "DONE" -> "已完成"
        "ERROR" -> "出错"
        else -> "待命"
    }

    /** 清空思考显示区：流式请求中断自动重试前调用，避免重放内容与旧文本重复累积 */
    fun resetThinking() {
        handler.post {
            thinkingReturnText?.text = ""
            thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 实时更新 AI 思考面板：显示发送给 AI 的内容与流式返回的内容，并同步更新通知。
     * @param sent 发送给 AI 的文本（首次传入；后续传 null 保持已显示内容）
     * @param delta 流式返回的增量文本（null 表示不更新返回区）
     */
    fun updateThinking(sent: String? = null, delta: String? = null) {
        handler.post {
            val hasSent = !sent.isNullOrBlank()
            val hasDelta = !delta.isNullOrBlank()
            if (!hasSent && !hasDelta) return@post
            // 有内容即显示思考面板（首次）
            thinkingPanel?.visibility = View.VISIBLE
            if (hasSent && sent != lastSentShown) {
                lastSentShown = sent
                thinkingSentText?.text = sent!!.take(600) + if (sent!!.length > 600) "…" else ""
            }
            if (hasDelta) {
                val cur = thinkingReturnText?.text?.toString().orEmpty()
                // 限制展示长度，避免悬浮窗内容无限增长（完整内容由 AI 客户端保留用于解析）
                thinkingReturnText?.text = (cur + delta).take(3000)
                thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
            }
            // 同步更新通知，展示最新 AI 思考
            val task = taskTitle?.text?.toString() ?: "Happy Agent"
            updateNotification("AI 思考中", task, 0, thinkingReturnText?.text?.toString().orEmpty())
        }
    }

    /**
     * 状态点呼吸脉冲：透明度在 0.45~1 间往复，让任务执行状态更“有生命”。
     * 遵循“常驻/循环动效用线性往复”的规范，仅在任务运行期间启用。
     */
    private fun startDotPulse() {
        if (dotPulseAnimator?.isRunning == true) return
        dotPulseAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { dot?.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopDotPulse() {
        dotPulseAnimator?.cancel()
        dotPulseAnimator = null
        dot?.alpha = 1f
    }

    /** 审核结果行实时更新：在思考面板内展示审核者的 pass/拒绝原因/采用的修正 */
    private fun updateReview(text: String?) {
        handler.post {
            reviewText?.text = text ?: ""
            updateNotification("AI 思考中", taskTitle?.text?.toString() ?: "Happy Agent", 0, thinkingReturnText?.text?.toString().orEmpty())
        }
    }

    /**
     * 恢复常规面板视图：隐藏完成面板，重新显示步骤/进度/头部/交互区。
     * 用于新任务开始时清除上一个任务完成态的残留。
     */
    private fun resetPanel() {
        donePanel?.visibility = View.GONE
        successMark?.reset()
        dot?.visibility = View.VISIBLE
        marquee?.visibility = View.VISIBLE
        stepText?.visibility = View.VISIBLE
        progressBar?.visibility = View.GONE
        interactPanel?.visibility = View.GONE
        thinkingPanel?.visibility = View.GONE
        thinkingSentText?.text = ""
        thinkingReturnText?.text = ""
        reviewText?.text = ""
        lastSentShown = ""
        header?.visibility = View.VISIBLE
    }

    /**
     * 面板淡入动画：透明度 + 轻微缩放（DecelerateInterpolator，起快收缓）。
     * 用于完成面板、交互面板的显示，避免生硬的瞬时切换。
     */
    private fun showPanelWithAnim(view: View?) {
        view ?: return
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * 显示交互面板（批准/澄清/指导），供用户在悬浮窗内直接操作。
     * @param type approve=批准计划 / clarify=歧义澄清 / guide=需要指导 / null=隐藏
     * @param title 交互标题
     * @param content 交互内容
     * @param options 澄清选项文本列表（type=clarify 时有效）
     */
    fun showInteraction(type: String?, title: String?, content: String?, options: List<String>? = null) {
        handler.post {
            if (type == null) {
                hideSheet()
                hideKeyboard()
                return@post
            }
            // 答疑/协助（澄清、guide）走底部选项卡滑入；顶部不再渲染这些交互按钮
            val useSheet = type == "clarify" || type == "guide"
            if (useSheet) {
                // 类型变更时清空上次残留的选项/按钮/输入，再按当前类型重建
                interactButtons?.removeAllViews()
                hintInput?.visibility = View.GONE
                hintBtnRow?.removeAllViews()
                interactTitle?.text = title ?: "需要确认"
                interactContent?.text = content ?: ""
                when (type) {
                    "clarify" -> {
                        options?.forEach { opt ->
                            addBtn(interactButtons, opt, false) { onInteraction?.invoke("clarify", opt) }
                        }
                        addBtn(interactButtons, "✏️ 我想自己说", false) { showHintInput() }
                    }
                    "guide" -> showHintInput()
                }
                showSheet()
                return@post
            }
            // 其余类型（approve / savetemplate / done）保留顶部渲染
            interactPanel?.visibility = View.GONE
            showPanelWithAnim(interactPanel)
            interactTitle?.text = title ?: "需要确认"
            interactContent?.text = content ?: ""

            // 清空选项与按钮
            interactButtons?.removeAllViews()
            hintInput?.visibility = View.GONE
            hintBtnRow?.removeAllViews()

            when (type) {
                "approve" -> {
                    // 批准/取消
                    addBtn(interactButtons, "批准并开始", true) { onInteraction?.invoke("approve", "") }
                    addBtn(interactButtons, "取消", false) { onInteraction?.invoke("cancel", "") }
                }
                "savetemplate" -> {
                    // 任务完成：是否把执行步骤保存为模板（用户主动确认才入库）
                    addBtn(interactButtons, "保存为模板", true) { onInteraction?.invoke("save_template", "yes") }
                    addBtn(interactButtons, "不保存", false) { onInteraction?.invoke("save_template", "no") }
                }
                "clarify" -> {
                    // 选项按钮
                    options?.forEach { opt ->
                        addBtn(interactButtons, opt, false) { onInteraction?.invoke("clarify", opt) }
                    }
                    addBtn(interactButtons, "✏️ 我想自己说", false) {
                        showHintInput()
                    }
                }
                "guide" -> {
                    showHintInput()
                }
            }
        }
    }

    private fun showHintInput() {
        // 输入前切换窗口为可聚焦输入模式，保证软键盘能弹出
        setInputMode(true)
        hintInput?.requestFocus()
        hintInput?.visibility = View.VISIBLE
        hintBtnRow?.removeAllViews()
        addBtn(hintBtnRow, "已手动处理", false) { onInteraction?.invoke("dismiss", "") }
        addBtn(hintBtnRow, "指导 AI", true) {
            val text = hintInput?.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                // 留空点击「指导 AI」等价「已手动处理」，避免空串被 provideUserHint 吞掉导致静默挂起
                onInteraction?.invoke("dismiss", "")
            } else {
                onInteraction?.invoke("hint", text)
            }
            hintInput?.setText("")
        }
        // 延迟弹出软键盘，等待窗口布局完成后再唤起输入法
        hintInput?.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(hintInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /** 切换悬浮窗输入模式：输入时窗口移除 NOT_FOCUSABLE 并加 FLAG_ALT_FOCUSABLE_IM 以弹出软键盘，
     *  输入结束恢复 NOT_FOCUSABLE，保证窗口始终可拖动且不抢占系统焦点 */
    private fun setInputMode(enabled: Boolean) {
        // 输入框现在在底部选项卡里，焦点模式切换的是选项卡窗口；
        // 顶部窗口始终保持 NOT_FOCUSABLE（不抢输入焦点，也不影响拖动）
        val p = sheetParams ?: return
        val rootView = sheetRoot ?: return
        p.flags = if (enabled) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        runCatching { windowManager?.updateViewLayout(rootView, p) }
    }

    private fun addBtn(container: LinearLayout?, label: String, primary: Boolean, onClick: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40),
            ).apply { topMargin = FloatingUi.PAD }
            layoutParams = params
            if (primary) {
                setTextColor(0xFFFFFFFF.toInt())
                background = FloatingUi.capsule(999f, FloatingUi.ACCENT_BLUE)
            } else {
                setTextColor(FloatingUi.ACCENT_BLUE)
                background = FloatingUi.capsule(
                    999f,
                    0x0A000000.toInt(),
                    (FloatingUi.ACCENT_BLUE and 0x00FFFFFF) or 0x14000000,
                )
            }
            setOnClickListener { onClick() }
        }
        container?.addView(btn)
    }

    private fun marqueeColor(phase: String): Int = FloatingUi.phaseColor(phase)

    private fun dotColor(phase: String): Int = FloatingUi.phaseColor(phase)

    private fun hideKeyboard() {
        // 隐藏软键盘并恢复窗口原有的不可聚焦模式（可拖动、不抢占系统焦点）
        setInputMode(false)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val focused = hintInput
        if (focused != null) imm?.hideSoftInputFromWindow(focused.windowToken, 0)
    }

    /**
     * 任务完成：清空面板所有内容，仅显示打勾动效 + 完成文字，并更新通知。
     * @param message 完成提示文字
     */
    fun showDone(message: String) {
        handler.post {
            stopDotPulse()
            // 隐藏常规内容（保留 header，使 × 关闭按钮始终可用）
            dot?.visibility = View.GONE
            marquee?.visibility = View.GONE
            stepText?.visibility = View.GONE
            progressBar?.visibility = View.GONE
            // 完成时收起底部选项卡（若还开着）
            hideSheet()
            // 显示打勾面板（淡入 + 轻微缩放）
            doneText?.text = message
            showPanelWithAnim(donePanel)
            successMark?.start()
            updateNotification("任务完成：$message", "Happy Agent")
        }
    }

    private fun removeWindow() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
        // 底部选项卡是独立窗口，需一并移除
        sheetRoot?.let { runCatching { windowManager?.removeView(it) } }
        sheetRoot = null
        sheetParams = null
    }

    override fun onDestroy() {
        // 清理拖动物理动画与回调
        isFlinging = false
        flingScroller?.forceFinished(true)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        removeWindow()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.phoneagent.floating.STOP"
        private const val NOTIFY_ID = 1001

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
            // 直接 stopService：后台调用不抛异常，onDestroy 中会 removeWindow 清理悬浮窗
            runCatching { context.stopService(Intent(context, FloatingWindowService::class.java)) }
        }

        /** 任务完成后：悬浮窗清空内容显示打勾动效 */
        fun showDone(message: String) {
            instance?.showDone(message)
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
         * 返回是否有悬浮窗服务实例在运行（无实例时调用方无需等待重绘）。
         */
        fun setVisible(visible: Boolean): Boolean {
            val svc = instance ?: return false
            svc.handler.post {
                svc.root?.visibility = if (visible) View.VISIBLE else View.GONE
                // 底部选项卡是独立窗口，截图时同样要藏起来，否则会被截进画面；
                // 截图前先记下它本来是否可见，截完按原样恢复
                if (!visible) {
                    svc.sheetVisibleBeforeHide = svc.sheetRoot?.visibility == View.VISIBLE
                    svc.sheetRoot?.visibility = View.GONE
                } else if (svc.sheetVisibleBeforeHide) {
                    svc.sheetRoot?.visibility = View.VISIBLE
                    svc.sheetVisibleBeforeHide = false
                }
            }
            return true
        }
    }
}
