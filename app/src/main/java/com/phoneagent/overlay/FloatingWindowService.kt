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
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
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
 * 窗口结构（两个独立窗口，职责不重叠）：
 * - **顶栏**：全宽、贴屏幕物理顶边的跑马灯色带，任务期间常驻，实时显示 AI 意向/任务状态；
 *   任务期间系统状态栏会被隐藏，顶栏就承担"状态栏"的角色。
 * - **任务卡片**：300dp 宽、可拖动的小卡片，承载标题/步骤/详情/交互，高度随内容自适应。
 *
 * 视觉：统一用 App 主题色实色（玄青 + 白字），不做半透明/玻璃质感。
 * 尺寸：卡片宽度固定，高度 WRAP_CONTENT——默认只占"头部 + 状态行"两行；
 *       AI 详情默认折叠，用户点开才占位，避免长任务时窗口越撑越大。
 * 交互：等待批准（批准/取消）、歧义澄清（选项按钮）、需要指导（输入框+按钮），
 *       这些操作全部可在悬浮窗内完成，通过 [onInteraction] 静态回调转发到引擎。
 * 拖动：仅卡片头部区域可拖动；轻点头部展开/收起 AI 详情，拖动后靠近屏幕左右边缘会自动贴边。
 */
class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appSettings: AppSettings by inject()
    // 跑马灯厚度（dp）与渐变颜色（ARGB 列表），从设置读取并随设置实时更新
    private var marqueeHeightDp = 26
    private var marqueeColors = listOf(0xFF4FA3FF.toInt(), 0xFF9B5CFF.toInt(), 0xFFFF6B9D.toInt())
    /** 当前执行阶段：跑马灯底色随它变化（观察/思考/执行/完成/出错各一套色调） */
    private var currentPhase = "PENDING"

    private var dot: View? = null
    private var dotPulseAnimator: ValueAnimator? = null
    /** 顶部状态栏跑马灯：它同时是顶栏窗口的根视图（见 [showTopBar]） */
    private var marquee: MarqueeView? = null
    private var stepText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var taskTitle: TextView? = null
    private var phaseChip: TextView? = null   // 阶段徽章（观察/思考/执行…）

    /** 头部「详情/收起」按钮：控制 AI 详情区是否占位 */
    private var expandChip: TextView? = null

    /**
     * AI 详情是否展开。
     *
     * 详情区（发送/返回/审核）最高 112dp，常驻会把窗口撑成一块"屏幕补丁"，
     * 遮挡后面的内容；默认折叠，只保留头部 + 状态行两行。
     * 该状态在任务内保持（用户展开后，后续步骤不会被自动收起），任务结束才复位。
     */
    private var thinkingExpanded = false

    // AI 思考实时面板：发送给 AI 的内容 + 流式返回的内容
    private var thinkingPanel: LinearLayout? = null
    private var thinkingScroll: ScrollView? = null
    private var thinkingSentText: TextView? = null
    private var thinkingReturnText: TextView? = null
    private var reviewText: TextView? = null
    private var lastSentShown = ""

    /** 上次推送 AI 思考通知的时间：流式输出每秒数次，逐条 notify 是跨进程调用，必须节流 */
    private var lastThinkNotifyAt = 0L

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

    /**
     * 顶部状态栏的窗口参数（窗口根视图就是 [marquee]）。
     *
     * 顶栏刻意不放进任务卡片：卡片只有 300dp 宽、还能被拖走，跑马灯挂在里面既贴不到屏幕左右边缘，
     * 也会被用户一拖就带走；拆成独立的全宽窗口，才能真正做到"贴屏幕顶 + 铺满整宽 + 任务期间常驻"。
     */
    private var barParams: WindowManager.LayoutParams? = null

    /** 截图隐藏前顶栏是否可见，用于截图后原样恢复 */
    private var barVisibleBeforeHide = false

    /**
     * 用户是否主动收起了整套面板（点卡片上的「隐藏」）。
     *
     * 收起后屏幕上只留一个悬浮球，任务同时被搁置（挂起由引擎负责）；点悬浮球唤出后原样恢复。
     * 这个标志必须挡住后续所有"把面板显示出来"的路径（尤其是每步都会跑的 [resetPanel]），
     * 否则下一步状态一刷新，面板就又自己冒出来了。
     */
    private var userHidden = false

    /** 悬浮球窗口：面板收起后留在屏幕上唯一可点的入口 */
    private var miniRoot: TextView? = null
    private var miniParams: WindowManager.LayoutParams? = null

    /** 截图隐藏前悬浮球是否可见，用于截图后原样恢复 */
    private var miniVisibleBeforeHide = false

    /**
     * 任务执行期间系统状态栏是否已被隐藏（由 AgentEngine 经 [setStatusBarHidden] 驱动）。
     *
     * 顶栏是独立窗口，它的落点直接由这个状态决定（见 [topBarY]）：隐藏时贴物理屏顶，
     * 未隐藏时退到状态栏下方。卡片（任务面板）的上边界也跟着走。
     */
    // 初值取 companion 里的静态标志：任务可能在悬浮窗启动之前就把状态栏隐藏了（服务晚一步起来时
    // 仍要按「已隐藏」来布局，否则顶栏会被压到状态栏下方）
    private var statusBarHidden = statusBarHiddenFlag

    /**
     * 顶栏窗口（独立全宽跑马灯）的落点 y：状态栏被隐藏时贴物理屏顶，否则退到状态栏下方。
     *
     * 「贴顶」是用户反复提的诉求，但系统状态栏窗口的层级**永远**压在 TYPE_APPLICATION_OVERLAY 之上：
     * 状态栏没被隐藏时把顶栏放在 y=0，只会被状态栏整条盖住（表现就是"上方还是没到屏幕边缘"）。
     * 所以贴顶的前提是状态栏真的隐藏了；没隐藏时退到状态栏下方，至少不遮系统图标。
     */
    private fun topBarY(): Int = if (statusBarHidden) 0 else statusBarHeight()

    /** 顶栏底边：任务卡片可拖动的上边界，避免卡片被顶栏压住 */
    private fun topBarBottom(): Int = topBarY() + dp(marqueeHeightDp)

    /** 状态栏隐藏状态变化：顶栏与卡片一起重新落位 */
    private fun applyStatusBarHidden(hidden: Boolean) {
        if (statusBarHidden == hidden) return
        statusBarHidden = hidden
        barParams?.let { p ->
            p.y = topBarY()
            runCatching { marquee?.let { b -> windowManager?.updateViewLayout(b, p) } }
            marquee?.post { correctTopBarY(0) }
        }
        // 卡片上边界随顶栏下移：原本贴在顶栏底边的卡片不能被顶栏盖住
        val p = params ?: return
        val minY = topBarBottom()
        if (p.y < minY) {
            p.y = minY
            runCatching { root?.let { r -> windowManager?.updateViewLayout(r, p) } }
        }
    }

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
                applyMarqueeSettings()
            }
        }
    }

    /**
     * 把「跑马灯厚度 / 颜色」设置应用到顶栏窗口。
     *
     * 尺寸变化必须走 updateViewLayout：顶栏是独立窗口，改视图自身的 LayoutParams 不会让窗口重新测量；
     * 厚度变了还要顺带把卡片推到新的顶栏下沿之下，否则卡片会被变厚的顶栏压住。
     */
    private fun applyMarqueeSettings() {
        val m = marquee ?: return
        m.setColors(FloatingUi.phaseGradient(marqueeColors, currentPhase))
        m.requestLayout()
        val p = barParams ?: return
        val newHeight = dp(marqueeHeightDp)
        if (p.height != newHeight) {
            p.height = newHeight
            runCatching { windowManager?.updateViewLayout(m, p) }
            val cardParams = params
            if (cardParams != null && cardParams.y < topBarBottom()) {
                cardParams.y = topBarBottom()
                runCatching { root?.let { r -> windowManager?.updateViewLayout(r, cardParams) } }
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
        val winW = dp(FloatingUi.WIDTH)
        // 宽度固定，高度随内容自适应
        params = WindowManager.LayoutParams(
            winW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 初始位置：水平居中，纵向落在顶栏下沿之下（顶栏是独立窗口，卡片不再承担色带）
            x = (screenW - winW) / 2
            y = topBarBottom() + dp(FloatingUi.PAD)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // 关键：API 30+ 默认 fitInsetsTypes = systemBars()，会把窗口内容整体推到状态栏下方，
                // 这是与 FLAG_LAYOUT_NO_LIMITS 无关的另一套机制（inset 适配）。
                // 清空后窗口坐标系才真正从物理屏顶开始，负 y / 顶栏贴顶才有意义。
                fitInsetsTypes = 0
            }
        }
        root = layout
        // 真实投影：让卡片浮起在屏幕之上，elevation 阴影随圆角轮廓（M3 柔和浮起）
        layout.elevation = dp(FloatingUi.ELEVATION).toFloat()
        layout.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(FloatingUi.RADIUS_CARD.toInt()).toFloat())
            }
        }
        try {
            windowManager?.addView(layout, params)
        } catch (_: Exception) {}
        showTopBar()
        showSheetWindow()
    }

    /**
     * 创建顶部状态栏窗口（独立全宽跑马灯），任务期间常驻。
     *
     * 三个关键点：
     * 1. **窗口宽度 MATCH_PARENT**：色带铺满整宽，这才是"顶栏"而不是卡片上的一条装饰；
     * 2. **FLAG_NOT_TOUCHABLE**：它只负责显示，绝不吃掉任何触摸——顶栏横跨整屏，可触摸就完了；
     * 3. **fitInsetsTypes = 0**：清掉系统栏 inset 适配，配合 [topBarY] 的 y 才能真正贴到物理屏顶。
     */
    private fun showTopBar() {
        if (marquee != null) return
        val wm = windowManager ?: return
        val bar = MarqueeView(this).apply {
            setColors(FloatingUi.phaseGradient(marqueeColors, currentPhase))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(marqueeHeightDp),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = topBarY()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        try {
            wm.addView(bar, lp)
            marquee = bar
            barParams = lp
            // 各 ROM 对 overlay 窗口的 y 处理不一致（有的会把窗口下移一个状态栏），
            // 加窗后按实测屏幕坐标做一次校正——这是唯一跨 ROM 可靠的贴顶手段
            bar.post { correctTopBarY(0) }
        } catch (_: Exception) {
            // 无悬浮窗权限：顶栏建不出来，任务卡片照常工作
        }
    }

    /**
     * 按实测屏幕坐标校正顶栏 y。
     *
     * `getLocationOnScreen` 给出的是窗口**真实**落点：与期望值（[topBarY]）有差就反向补偿。
     * 校正次数封顶，避免个别 ROM 上取到的坐标永远对不上时陷入"改一次、量一次"的死循环。
     */
    private fun correctTopBarY(attempt: Int) {
        val bar = marquee ?: return
        val p = barParams ?: return
        if (attempt >= 3) return
        val loc = IntArray(2)
        runCatching { bar.getLocationOnScreen(loc) }
        val delta = loc[1] - topBarY()
        if (kotlin.math.abs(delta) <= 1) return
        p.y -= delta
        runCatching { windowManager?.updateViewLayout(bar, p) }
        bar.post { correctTopBarY(attempt + 1) }
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
            visibility = View.GONE
        }
        // 交互面板在此挂载（它已在 buildPanel 中构建完成，但未加入顶部窗口）。
        //
        // 左右留白与底边留白放在**面板自己的外边距**上，而不是容器的内边距上：
        // 容器是窗口的根视图，它的内边距在面板隐藏时仍会把窗口撑出一段高度，
        // 零内容却依然可触摸 —— 那一条透明窗口会持续吃掉屏幕底部的操作。
        // 面板隐藏时容器高度为 0，窗口才真正不占地方。
        interactPanel?.let {
            container.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(FloatingUi.PAD_XL)
                    marginEnd = dp(FloatingUi.PAD_XL)
                    bottomMargin = dp(FloatingUi.PAD)
                },
            )
        }
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
                // 面板本身也要收起：容器是窗口根视图，即使它自己 GONE，窗口根仍会被测量，
                // 面板留在 VISIBLE 会继续把窗口撑出同样高度 —— 一层看不见却可触摸的窗口
                // 会持续吃掉这块屏幕区域的操作。
                interactPanel?.visibility = View.GONE
            }
            .start()
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        // 卡片底色：应用主题色实色（玄青），不用半透明/玻璃质感。
        // 浮窗是压在别的 App 上的一小块，透出的底图会让文字随时失去对比度；
        // 实色 + 白色文字反而是最克制、最"看得清"的方案。
        panel.background = FloatingUi.capsule(
            dp(FloatingUi.RADIUS_CARD.toInt()).toFloat(),
            FloatingUi.BRAND,
        )
        panel.setPadding(FloatingUi.PAD_L, FloatingUi.PAD_S, FloatingUi.PAD_L, FloatingUi.PAD_S)

        // 顶部跑马灯已拆成独立的全宽顶栏窗口（见 showTopBar），卡片里不再有色带：
        // 卡片只有 300dp 宽，色带挂在里面永远贴不到屏幕两侧与物理顶边

        // 头部：状态点 + 任务标题 + 详情开关 + 关闭（仅头部可拖动；轻点头部也可切换详情）
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
            setTextColor(FloatingUi.ON_BRAND)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(FloatingUi.PAD, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 关闭按钮：圆形胶囊，按压有反馈
        val close = TextView(this).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(FloatingUi.ON_BRAND_SECONDARY)
            background = FloatingUi.capsule(FloatingUi.RADIUS_CHIP, FloatingUi.ON_BRAND_STATE_WEAK)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            setOnClickListener {
                onInteraction?.invoke("close", "")
                stopSelf(); removeWindow()
            }
        }
        // 详情开关：折叠态只显示三行，AI 的发送/返回/审核内容按需展开
        expandChip = TextView(this).apply {
            text = "详情"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(FloatingUi.ON_BRAND_SECONDARY)
            background = FloatingUi.capsule(FloatingUi.RADIUS_CHIP, FloatingUi.ON_BRAND_STATE_WEAK)
            setPadding(FloatingUi.PAD, 0, FloatingUi.PAD, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26),
            ).apply { marginEnd = FloatingUi.PAD_S }
            setOnClickListener { toggleThinking() }
        }
        header?.addView(dot)
        header?.addView(taskTitle)
        header?.addView(expandChip)
        // 隐藏按钮：收起整套面板、只留一个悬浮球，同时把任务搁置（引擎在下一轮开始前挂起）；
        // 用户点悬浮球即可原样唤出并继续执行
        val hideChip = TextView(this).apply {
            text = "隐藏"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(FloatingUi.ON_BRAND_SECONDARY)
            background = FloatingUi.capsule(FloatingUi.RADIUS_CHIP, FloatingUi.ON_BRAND_STATE_WEAK)
            setPadding(FloatingUi.PAD, 0, FloatingUi.PAD, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26),
            ).apply { marginEnd = FloatingUi.PAD_S }
            setOnClickListener {
                hideForUser()
                onInteraction?.invoke("hide", "")
            }
        }
        header?.addView(hideChip)
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
            // 主题色底上用实心阶段色胶囊 + 白字：半透明的淡色底在深色卡片上读不出层次
            setTextColor(FloatingUi.ON_BRAND)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = FloatingUi.capsule(
                FloatingUi.RADIUS_CHIP / 2,
                FloatingUi.phaseColor("PENDING"),
            )
            setPadding(FloatingUi.PAD, dp(3), FloatingUi.PAD, dp(3))
        }
        stepText = TextView(this).apply {
            text = "等待任务..."
            textSize = 12f
            setTextColor(FloatingUi.ON_BRAND_SECONDARY)
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

        // AI 详情面板：圆角内嵌卡片，按 发送/返回/审核 分栏
        // 默认 GONE（折叠）：只在用户点「详情」或点头部时占位，避免窗口常驻撑高
        thinkingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = FloatingUi.capsule(
                FloatingUi.RADIUS_PANEL,
                FloatingUi.PANEL,
            )
            setPadding(FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L, FloatingUi.PAD_L)
        }
        // 内容可滚动，限制高度避免悬浮窗过大（折叠时整块不占位）
        thinkingScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(FloatingUi.THINKING_H))
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            setPadding(0, FloatingUi.PAD, 0, 0)
            clipToPadding = false
            clipToOutline = false
        }
        val thinkCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sentLabel = TextView(this).apply {
            text = "发送给 AI"
            textSize = 10f
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
            text = "AI 返回"
            textSize = 10f
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
            text = "审核结论"
            textSize = 10f
            setTextColor(FloatingUi.BRAND)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        thinkCol.addView(reviewLabel)
        reviewText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(FloatingUi.BRAND)
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
            background = FloatingUi.capsule(999f, FloatingUi.ACCENT_WARM)
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
        //
        // 这里**不能**用「高度 0 + weight 1」那种"占满剩余空间"的写法：选项卡窗口是
        // WRAP_CONTENT，LinearLayout 在 AT_MOST 下会把「沿高度方向的剩余空间」全分给权重子视图，
        // 而剩余空间就是整块屏幕 —— 面板于是被撑到整屏高，整个窗口变成一张盖住全屏的
        // 透明可触摸层：屏幕上千点什么都落到这层上（用户侧表现就是"整个手机都点不动"，
        // 而顶部跑马灯是另一个窗口，照旧在滚）。
        // 改成固定限高：短文案不留大片空白，超长文案在卡片内部滚动。
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140))
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
                FloatingUi.PANEL_SUNKEN,
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
            background = FloatingUi.capsule(FloatingUi.RADIUS_PANEL, FloatingUi.PANEL)
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
            background = FloatingUi.capsule(999f, FloatingUi.BRAND)
            setPadding(FloatingUi.PAD_XL, 0, FloatingUi.PAD_XL, 0)
            setOnClickListener { stopSelf(); removeWindow() }
        }
        donePanel?.addView(doneClose)
        panel.addView(donePanel)

        return panel
    }

    // ==================== 拖动（仅头部）：点击展开 / 拖动移位 / 松手归位 ====================
    private var startX = 0
    private var startY = 0
    private var startTouchX = 0f
    private var startTouchY = 0f

    /** 本次手势是否已越过触摸阈值（用于区分"轻点"与"拖动"） */
    private var dragging = false

    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

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
                dragging = false
                vtracker.clear()
                vtracker.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startTouchX
                val dy = event.rawY - startTouchY
                // 越过阈值才算拖动：否则手指的微小抖动会把"轻点"变成位移，
                // 窗口跟着抖一下、点击展开也永远触发不了
                if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (!dragging) return true
                p.x = (startX + dx).toInt()
                p.y = (startY + dy).toInt()
                // 拖动期间悬浮窗可能被销毁（removeWindow），root 为空时跳过更新，避免 NPE
                root?.let { r -> windowManager?.updateViewLayout(r, p) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vtracker.computeCurrentVelocity(1000)
                val vx = vtracker.xVelocity
                val vy = vtracker.yVelocity
                velocityTracker?.recycle()
                velocityTracker = null
                if (!dragging) {
                    // 轻点头部：切换 AI 详情展开/收起
                    toggleThinking()
                } else {
                    settlePosition(vx, vy)
                }
            }
        }
        return true
    }

    /**
     * 松手后的归位：先做「边缘吸附 / 越界回收」，都不需要时才让惯性滑行。
     *
     * 窗口用 FLAG_LAYOUT_NO_LIMITS，本身可以被拖到屏幕外——不收拾就会出现
     * "窗口拖丢了、任务还在跑却看不到状态"的情况；靠近左右边缘时吸附贴边，
     * 也让悬浮窗更像系统组件而不是随手丢在屏幕中间的补丁。
     */
    private fun settlePosition(vx: Float, vy: Float) {
        val p = params ?: return
        val r = root ?: return
        val point = android.graphics.Point()
        runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
        val screenW = if (point.x > 0) point.x else dp(360)
        val screenH = if (point.y > 0) point.y else dp(640)
        val winW = dp(FloatingUi.WIDTH)
        val winH = if (r.height > 0) r.height else dp(120)
        val gap = dp(FloatingUi.EDGE_GAP)
        val snapZone = dp(FloatingUi.SNAP_ZONE)
        val targetX = when {
            p.x <= snapZone -> gap
            p.x + winW >= screenW - snapZone -> (screenW - winW - gap).coerceAtLeast(gap)
            else -> null
        }
        // 纵向：顶部最多贴到顶栏下沿（卡片不该被顶栏压住），底部至少留一角可抓
        val minY = topBarBottom()
        val maxY = (screenH - winH - dp(24)).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)
        if (targetX != null || targetY != p.y) {
            animateTo(targetX ?: p.x, targetY)
            return
        }
        if (kotlin.math.abs(vx) + kotlin.math.abs(vy) > 900f) {
            startFling(p.x, p.y, vx, vy)
        }
    }

    /** 位置动画：吸附/回收用，起快收缓，避免窗口"跳"过去 */
    private fun animateTo(targetX: Int, targetY: Int) {
        val p = params ?: return
        val r = root ?: return
        val sx = p.x
        val sy = p.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                p.x = sx + ((targetX - sx) * t).toInt()
                p.y = sy + ((targetY - sy) * t).toInt()
                runCatching { windowManager?.updateViewLayout(r, p) }
            }
            start()
        }
    }

    /** 惯性滑行：用 OverScroller 沿松手速度衰减滑动，平滑停止 */
    private fun startFling(startX: Int, startY: Int, vx: Float, vy: Float) {
        val point = android.graphics.Point()
        runCatching { windowManager?.defaultDisplay?.getRealSize(point) }
        val screenW = if (point.x > 0) point.x else dp(360)
        val screenH = if (point.y > 0) point.y else dp(640)
        val winW = dp(FloatingUi.WIDTH)
        val rootH = root?.height ?: 0
        val winH = if (rootH > 0) rootH else dp(120)
        val gap = dp(FloatingUi.EDGE_GAP)
        flingScroller?.forceFinished(true)
        flingScroller = OverScroller(this).apply {
            fling(
                startX, startY,
                vx.toInt(), vy.toInt(),
                gap, (screenW - winW - gap).coerceAtLeast(gap),   // x：始终保留完整窗口在屏内
                topBarBottom(),                                    // y：顶部不越过顶栏
                (screenH - winH - dp(24)).coerceAtLeast(topBarBottom()),
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
            // 阶段徽章：实心阶段色胶囊 + 白字（卡片是实色主题色，徽章也必须实色才压得住）
            val ph = FloatingUi.phaseColor(phase)
            phaseChip?.text = phaseLabel(phase)
            phaseChip?.setTextColor(FloatingUi.ON_BRAND)
            phaseChip?.background = FloatingUi.capsule(FloatingUi.RADIUS_CHIP / 2, ph)
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
     * 实时更新 AI 详情面板：显示发送给 AI 的内容与流式返回的内容，并同步更新通知。
     *
     * 折叠状态下**只累积内容、不显示面板** —— 详情区有 112dp 高，若每来一段流式文本
     * 就自动弹出来，窗口会在任务执行中反复变高变大，正是"遮挡屏幕"的来源。
     * 用户点开「详情」后内容即刻可见，因为文本一直在后台累积。
     *
     * @param sent 发送给 AI 的文本（首次传入；后续传 null 保持已显示内容）
     * @param delta 流式返回的增量文本（null 表示不更新返回区）
     */
    fun updateThinking(sent: String? = null, delta: String? = null) {
        handler.post {
            val hasSent = !sent.isNullOrBlank()
            val hasDelta = !delta.isNullOrBlank()
            if (!hasSent && !hasDelta) return@post
            if (hasSent && sent != lastSentShown) {
                lastSentShown = sent
                thinkingSentText?.text = sent!!.take(600) + if (sent!!.length > 600) "…" else ""
            }
            if (hasDelta) {
                val cur = thinkingReturnText?.text?.toString().orEmpty()
                // 限制展示长度，避免内容无限增长（完整内容由 AI 客户端保留用于解析）
                thinkingReturnText?.text = (cur + delta).take(3000)
                // 只有展开时才需要滚动到底：折叠状态下滚动位置没人看，做了也是白做
                if (thinkingExpanded) {
                    thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
                }
                notifyThinkingThrottled()
            }
        }
    }

    /** 同步 AI 思考到通知栏（节流）：流式增量每秒数次，逐条 notify 是跨进程调用，代价高 */
    private fun notifyThinkingThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastThinkNotifyAt < NOTIFY_THROTTLE_MS) return
        lastThinkNotifyAt = now
        val task = taskTitle?.text?.toString() ?: "Happy Agent"
        updateNotification("AI 思考中", task, 0, thinkingReturnText?.text?.toString().orEmpty())
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
     * 恢复常规面板视图：隐藏完成面板，重新显示步骤/进度/头部。
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
        // 详情区按用户的展开状态恢复：折叠时保持 GONE，否则窗口会在每步更新后突然变高
        thinkingPanel?.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
        thinkingSentText?.text = ""
        thinkingReturnText?.text = ""
        reviewText?.text = ""
        lastSentShown = ""
        lastThinkNotifyAt = 0L
        header?.visibility = View.VISIBLE
    }

    /** 切换 AI 详情区的展开/收起（点头部或点「详情」按钮） */
    private fun toggleThinking() {
        thinkingExpanded = !thinkingExpanded
        thinkingPanel?.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
        expandChip?.text = if (thinkingExpanded) "收起" else "详情"
        if (thinkingExpanded) {
            thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 复位为折叠态（新任务/任务结束时调用，避免上一个任务的展开状态带过来） */
    private fun collapseThinking() {
        thinkingExpanded = false
        thinkingPanel?.visibility = View.GONE
        expandChip?.text = "详情"
    }

    /**
     * 面板入场动画：透明度 + 自下而上 12dp 位移（DecelerateInterpolator，起快收缓）。
     * 刻意不做缩放：缩放与位移动画叠加时视觉重心会漂移，且入场方向应统一为自下而上。
     */
    private fun showPanelWithAnim(view: View?) {
        view ?: return
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
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
            // 所有交互（批准 / 保存模板 / 澄清 / 指导）都渲染在同一个 interactPanel 里，
            // 而 interactPanel 挂在底部选项卡窗口上，顶部窗口只保留状态与跑马灯。
            // 所以这里必须做三件事，缺一样用户就操作不了：
            //   1) 面板置 VISIBLE —— 它的初值/重置值都是 GONE，只让它滑出容器是看不见的；
            //   2) 按类型重建按钮；
            //   3) showSheet() 让选项卡容器滑出来 —— 容器默认 GONE，
            //      之前 approve / savetemplate 走的是"顶部渲染"分支（面板早已不挂在顶部窗口），
            //      面板永远不可见，于是需要批准时用户点什么都没反应、任务一直挂着。
            interactButtons?.removeAllViews()
            hintInput?.visibility = View.GONE
            hintBtnRow?.removeAllViews()
            interactTitle?.text = title ?: "需要确认"
            interactContent?.text = content ?: ""
            // 不叠加缩放动画：入场方向统一为「自下而上」，滑动由容器承担
            interactPanel?.visibility = View.VISIBLE

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
            showSheet()
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
                setTextColor(FloatingUi.ON_BRAND)
                background = FloatingUi.capsule(999f, FloatingUi.BRAND)
            } else {
                setTextColor(FloatingUi.BRAND)
                background = FloatingUi.capsule(999f, FloatingUi.PANEL_STATE)
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
            // 完成时收起 AI 详情与底部选项卡（若还开着）；详情同时复位为折叠，
            // 下一个任务从最小的三行窗口开始
            collapseThinking()
            hideSheet()
            // 显示打勾面板（淡入 + 自下而上位移动画）
            doneText?.text = message
            showPanelWithAnim(donePanel)
            successMark?.start()
            updateNotification("任务完成：$message", "Happy Agent")
        }
    }

    private fun removeWindow() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
        // 顶栏是独立窗口，需一并移除（否则任务结束后全宽色带会常驻在屏幕顶部）
        marquee?.let { runCatching { windowManager?.removeView(it) } }
        marquee = null
        barParams = null
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

        /** AI 思考写通知的最小间隔：流式增量每秒数次，逐条 notify 是跨进程调用 */
        private const val NOTIFY_THROTTLE_MS = 700L

        /** 悬浮窗交互动作回调（由 MainViewModel 注册，转发到 AgentEngine） */
        @Volatile
        var onInteraction: ((action: String, payload: String) -> Unit)? = null

        @Volatile
        var instance: FloatingWindowService? = null
            private set

        /**
         * 任务执行期间系统状态栏是否已隐藏（由 AgentEngine 维护）。
         * 放在 companion 上：服务可能晚于任务启动，静态标志保证实例起来时能读到正确状态。
         */
        @Volatile
        private var statusBarHiddenFlag = false

        /**
         * 通知浮窗：任务期间的系统状态栏已隐藏 / 已恢复。
         * 跑马灯色带本身不动（它一直铺到物理顶），只是把文字安全区在「避让状态栏」与「贴顶」之间切换。
         */
        fun setStatusBarHidden(hidden: Boolean) {
            statusBarHiddenFlag = hidden
            val svc = instance ?: return
            svc.handler.post { svc.applyStatusBarHidden(hidden) }
        }

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
                // 顶栏是独立窗口，截图时同样要藏起来（它横跨整屏顶部，一定会被截进画面）；
                // 同样先记下本来的可见状态，截完按原样恢复（任务完成后顶栏本来是隐藏的）
                if (!visible) {
                    svc.barVisibleBeforeHide = svc.marquee?.visibility == View.VISIBLE
                    svc.marquee?.visibility = View.GONE
                } else if (svc.barVisibleBeforeHide) {
                    svc.marquee?.visibility = View.VISIBLE
                    svc.barVisibleBeforeHide = false
                }
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
