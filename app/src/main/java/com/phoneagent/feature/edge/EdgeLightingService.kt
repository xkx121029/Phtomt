package com.phoneagent.feature.edge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.view.WindowManager
import com.phoneagent.overlay.OverlayDisplaySettings
import com.phoneagent.overlay.OverlayMotion

/**
 * 跑马光效服务：在 Agent 执行任务时显示全屏边缘光效 Overlay。
 *
 * 使用 TYPE_APPLICATION_OVERLAY 实现系统级覆盖层。
 * 通过 FLAG_NOT_TOUCHABLE / FLAG_NOT_FOCUSABLE / 窗口 alpha 0.4 确保不影响用户操作。
 */
class EdgeLightingService : Service() {

    private var windowManager: WindowManager? = null
    private var lightingView: EdgeLightingView? = null
    private var params: WindowManager.LayoutParams? = null
    /** 是否正处于任务运行光效模式（区别于设置页标定预览） */
    private var isRunning = false
    /** 设置页是否正在做标定预览（预览期间切换开关也要能即时收起/恢复） */
    private var previewing = false
    /** 用户设置：绿色主题边缘光效是否开启；关闭时不建窗口、不跑帧动画 */
    private var lightingEnabled = true

    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OverlayDisplaySettings.KEY_EDGE_LIGHTING_ENABLED) {
                lightingEnabled = OverlayDisplaySettings.edgeLightingEnabled(this)
                if (lightingEnabled) {
                    // 立刻恢复：任务在跑或设置页在预览时，重新建窗并起动画
                    if (isRunning || previewing) showOverlay()
                } else {
                    // 真正移除窗口：不留透明窗口、不再逐帧重绘、不占触摸区域
                    removeOverlay()
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lightingEnabled = OverlayDisplaySettings.edgeLightingEnabled(this)
        OverlayDisplaySettings.registerListener(this, settingsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 被系统重建时 intent 为 null：上一场光效的生命周期已随进程丢失，
        // 不能走 else 分支无条件 showOverlay() 让光效「死而复生」——直接自杀（onDestroy 会清理 overlay）
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                isRunning = false
                previewing = false
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PREVIEW_START -> {
                previewing = true
                showOverlay()
                applyCalibrationFrom(intent)
                return START_STICKY
            }
            ACTION_PREVIEW_STOP -> {
                previewing = false
                if (isRunning) {
                    // 任务运行中的光效不应被设置页收起标定误关
                    return START_STICKY
                }
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CALIBRATION -> {
                applyCalibrationFrom(intent)
                return START_STICKY
            }
            else -> {
                isRunning = true
                showOverlay()
                applyCalibrationFrom(intent)
            }
        }
        return START_STICKY
    }

    /** 从 Intent 读取标定参数并应用（三个入口共用同一套键名与默认值） */
    private fun applyCalibrationFrom(intent: Intent) {
        updateCalibration(
            top = intent.getIntExtra("inset_top", 0),
            bottom = intent.getIntExtra("inset_bottom", 0),
            left = intent.getIntExtra("inset_left", 0),
            right = intent.getIntExtra("inset_right", 0),
            radius = intent.getIntExtra("corner_radius", 0),
            width = intent.getIntExtra("edge_width", 20),
            speed = intent.getFloatExtra("speed", 0.3f),
        )
    }

    private fun showOverlay() {
        // 用户关掉了边缘光效：一个窗口都不建（既不空转透明窗口，也不跑帧动画）
        if (!lightingEnabled) return
        if (lightingView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        lightingView = EdgeLightingView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // 全屏、不拦截触摸、不抢焦点。这里**必须清空 fitInsets**（clearInsets = true）：
        // 保留系统默认时窗口内容区从状态栏下沿开始，光带即使画到自身 y=0，
        // 物理上仍落在状态栏下方——这就是「顶部光带总是低一条」的根因。
        // 同时显式给出真实屏幕尺寸：MATCH_PARENT 在部分设备不含系统栏，原点对不齐。
        // 不透明度按「三层组合不超过 0.8」口径统一由 OverlayWindowSpec 给出（含完整踩坑说明）。
        val spec = com.phoneagent.core.window.OverlayWindowSpec
        params = spec.passiveFullScreen(
            clearInsets = true,
            screenSize = spec.realScreenSize(this),
        )

        try {
            windowManager?.addView(lightingView, params)
            // 光效覆盖层淡入，避免生硬闪现；时长与曲线走浮层动效令牌
            lightingView?.alpha = 0f
            lightingView?.animate()
                ?.alpha(1f)
                ?.setDuration(OverlayMotion.EmphasisMs.toLong())
                ?.setInterpolator(OverlayMotion.EaseOut)
                ?.start()
            lightingView?.start()
        } catch (_: WindowManager.BadTokenException) {
            // 没有权限时静默失败
        } catch (_: Exception) {
        }
    }

    private fun updateCalibration(
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        radius: Int,
        width: Int = 20,
        speed: Float = 0.3f,
    ) {
        lightingView?.apply {
            setCalibration(top, bottom, left, right, radius, width)
            setSpeed(speed)
        }
    }

    private fun removeOverlay() {
        lightingView?.stop()
        lightingView?.let { runCatching { windowManager?.removeView(it) } }
        lightingView = null
    }

    override fun onDestroy() {
        OverlayDisplaySettings.unregisterListener(this, settingsListener)
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.phoneagent.edge.STOP"
        const val ACTION_PREVIEW_START = "com.phoneagent.edge.PREVIEW_START"
        const val ACTION_PREVIEW_STOP = "com.phoneagent.edge.PREVIEW_STOP"
        const val ACTION_UPDATE_CALIBRATION = "com.phoneagent.edge.UPDATE_CALIBRATION"

        @Volatile
        var instance: EdgeLightingService? = null
            private set

        fun start(
            context: Context,
            top: Int = 0,
            bottom: Int = 0,
            left: Int = 0,
            right: Int = 0,
            radius: Int = 0,
            width: Int = 20,
            speed: Float = 0.3f,
        ) {
            val intent = Intent(context, EdgeLightingService::class.java).apply {
                putExtra("inset_top", top)
                putExtra("inset_bottom", bottom)
                putExtra("inset_left", left)
                putExtra("inset_right", right)
                putExtra("corner_radius", radius)
                putExtra("edge_width", width)
                putExtra("speed", speed)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, EdgeLightingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun startPreview(
            context: Context,
            top: Int = 0,
            bottom: Int = 0,
            left: Int = 0,
            right: Int = 0,
            radius: Int = 0,
            width: Int = 20,
            speed: Float = 0.3f,
        ) {
            val intent = Intent(context, EdgeLightingService::class.java).apply {
                action = ACTION_PREVIEW_START
                putExtra("inset_top", top)
                putExtra("inset_bottom", bottom)
                putExtra("inset_left", left)
                putExtra("inset_right", right)
                putExtra("corner_radius", radius)
                putExtra("edge_width", width)
                putExtra("speed", speed)
            }
            context.startService(intent)
        }

        fun stopPreview(context: Context) {
            val intent = Intent(context, EdgeLightingService::class.java).apply {
                action = ACTION_PREVIEW_STOP
            }
            context.startService(intent)
        }

        fun updateCalibration(
            context: Context,
            top: Int,
            bottom: Int,
            left: Int,
            right: Int,
            radius: Int,
            width: Int = 20,
            speed: Float = 0.3f,
        ) {
            val intent = Intent(context, EdgeLightingService::class.java).apply {
                action = ACTION_UPDATE_CALIBRATION
                putExtra("inset_top", top)
                putExtra("inset_bottom", bottom)
                putExtra("inset_left", left)
                putExtra("inset_right", right)
                putExtra("corner_radius", radius)
                putExtra("edge_width", width)
                putExtra("speed", speed)
            }
            context.startService(intent)
        }
    }
}