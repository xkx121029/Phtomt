package com.phoneagent.floating

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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ServiceCompat
import com.phoneagent.R
import com.phoneagent.ui.MainActivity

/**
 * 悬浮窗服务：任务执行时在屏幕上显示实时进度面板 + 过程中提问/确认内容。
 * - 顶部状态点 + 任务名 + 关闭按钮
 * - 彩色跑马灯实时滚动 AI 思考/状态
 * - 提问/确认区域（动态显示，有内容时展开）
 * - 步骤进度条
 * 面板可拖动。
 */
class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var dot: View? = null
    private var marquee: MarqueeView? = null
    private var stepText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var taskTitle: TextView? = null

    // 提问/确认区域
    private var queryPanel: LinearLayout? = null
    private var queryLabel: TextView? = null
    private var queryText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
    }

    private fun startForegroundCompat() {
        val channelId = "floating_window"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
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
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Happy Agent 正在执行").setSmallIcon(R.drawable.ic_stat_agent).build()
        }
        ServiceCompat.startForeground(
            this,
            1001,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
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

    private fun showWindow() {
        if (root != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layout = buildPanel()
        params = WindowManager.LayoutParams(
            dp(300), dp(136),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(160)
            y = dp(180)
        }
        root = layout
        try {
            windowManager?.addView(layout, params)
        } catch (_: Exception) {}
    }

    /** 更新面板高度（有提问时展开，无提问时收缩） */
    private fun updatePanelHeight(hasQuery: Boolean) {
        val targetHeight = if (hasQuery) dp(180) else dp(136)
        if (params?.height != targetHeight) {
            params?.height = targetHeight
            root?.let { runCatching { windowManager?.updateViewLayout(it, params) } }
        }
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        // 圆角深色背景
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xE6000000.toInt())
            setStroke(dp(1), 0x55555555.toInt())
        }
        panel.background = bg

        // 头部：状态点 + 标题 + 关闭
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply {
            setBackgroundResource(0)
            val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0x4f, 0xa3, 0xff)) }
            background = d
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        taskTitle = TextView(this).apply {
            text = "Happy Agent"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 18f
            setTextColor(Color.rgb(0xff, 0x6b, 0x9d))
            setOnClickListener { stopSelf(); removeWindow() }
        }
        header.addView(dot)
        header.addView(taskTitle)
        header.addView(close)
        panel.addView(header)

        // 跑马灯
        marquee = MarqueeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30))
        }
        panel.addView(marquee)

        // 提问/确认区域（默认隐藏）
        queryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val divider = View(this@FloatingWindowService).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(Color.rgb(0x55, 0x55, 0x55))
            }
            addView(divider)
        }
        queryLabel = TextView(this).apply {
            text = "需要确认"
            textSize = 10f
            setTextColor(Color.rgb(0xff, 0xd0, 0x6b))
            setPadding(0, dp(4), 0, 0)
        }
        queryPanel?.addView(queryLabel)
        queryText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.rgb(0xff, 0xff, 0xff))
            setPadding(0, dp(2), 0, dp(4))
            maxLines = 3
        }
        queryPanel?.addView(queryText)
        panel.addView(queryPanel)

        // 步骤 + 进度条
        stepText = TextView(this).apply {
            text = "等待任务..."
            textSize = 11f
            setTextColor(Color.rgb(0xaa, 0xbb, 0xcc))
        }
        panel.addView(stepText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
        }
        panel.addView(progressBar)

        // 拖动
        panel.setOnTouchListener { _, event ->
            onTouchDrag(event)
            true
        }
        return panel
    }

    private var startX = 0
    private var startY = 0
    private var startTouchX = 0f
    private var startTouchY = 0f

    private fun onTouchDrag(event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = p.x; startY = p.y
                startTouchX = event.rawX; startTouchY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = (startX + (event.rawX - startTouchX)).toInt()
                p.y = (startY + (event.rawY - startTouchY)).toInt()
                windowManager?.updateViewLayout(root, p)
            }
        }
        return true
    }

    // ==================== 对外更新 ====================

    /**
     * 更新悬浮窗进度状态。
     * @param status 状态文本（如"正在执行"）
     * @param task 任务名称
     * @param reasoning AI 推理/思考内容
     * @param step 当前步骤
     * @param total 总步骤
     * @param phase 阶段（OBSERVING/THINKING/ACTING/DONE/ERROR）
     * @param queryLabelText 提问标签（如"需要澄清"、"需要确认"、"需要指导"），非空时展开提问区域
     * @param queryContent 提问/确认内容
     */
    fun updateStatus(
        status: String,
        task: String,
        reasoning: String,
        step: Int,
        total: Int,
        phase: String,
        queryLabelText: String? = null,
        queryContent: String? = null,
    ) {
        handler.post {
            marquee?.setText(reasoning.ifBlank { status }, marqueeColor(phase))
            taskTitle?.text = task
            stepText?.text = "第 $step 步进度 · $status"
            progressBar?.progress = if (total > 0) (step * 100 / total).coerceIn(0, 100) else step.coerceAtMost(100)
            dot?.setBackgroundColor(dotColor(phase))

            // 提问区域
            val hasQuery = !queryLabelText.isNullOrBlank() && !queryContent.isNullOrBlank()
            queryPanel?.visibility = if (hasQuery) View.VISIBLE else View.GONE
            if (hasQuery) {
                queryLabel?.text = queryLabelText
                queryText?.text = queryContent
            }
            updatePanelHeight(hasQuery && queryContent?.length ?: 0 > 80)
        }
    }

    private fun marqueeColor(phase: String): Int = when (phase) {
        "OBSERVING" -> Color.rgb(0x4f, 0xa3, 0xff)
        "THINKING" -> Color.rgb(0x9b, 0x5c, 0xff)
        "ACTING" -> Color.rgb(0xff, 0x6b, 0x9d)
        "DONE" -> Color.rgb(0x3d, 0xd9, 0x8f)
        "ERROR" -> Color.rgb(0xff, 0x5f, 0x5f)
        else -> Color.rgb(0xff, 0xd0, 0x6b)
    }

    private fun dotColor(phase: String): Int = when (phase) {
        "THINKING" -> Color.rgb(0x9b, 0x5c, 0xff)
        "ACTING" -> Color.rgb(0xff, 0x6b, 0x9d)
        "DONE" -> Color.rgb(0x3d, 0xd9, 0x8f)
        "ERROR" -> Color.rgb(0xff, 0x5f, 0x5f)
        else -> Color.rgb(0x4f, 0xa3, 0xff)
    }

    private fun removeWindow() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
    }

    override fun onDestroy() {
        removeWindow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.phoneagent.floating.STOP"

        @Volatile
        var instance: FloatingWindowService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingWindowService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingWindowService::class.java).setAction(ACTION_STOP))
        }

        fun update(
            status: String,
            task: String,
            reasoning: String,
            step: Int,
            total: Int,
            phase: String,
            queryLabel: String? = null,
            queryContent: String? = null,
        ) {
            instance?.updateStatus(status, task, reasoning, step, total, phase, queryLabel, queryContent)
        }
    }
}